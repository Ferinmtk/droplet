"""The mesh peer: links to other devices, what arrives over them, and how messages leave.

Routing (docs/mesh.md §5), for each message this device sends, the first that works:

1. direct LAN: an open link, or a new one to an address from mDNS or one that worked before;
2. direct tailnet: the peer's tailnet address, from the hub's roster;
3. through the hub, when it's connected and knows the peer;
4. the hub's mailbox, when the hub knows the peer but the peer is offline;
5. the outbox: kept here, and sent when the peer or the hub appears.

- Live control (`input`, `media`, `cmd`) and `clip` and `ring` use 1–3 only:
  a mouse move, a clipboard or a ring delivered an hour later is wrong.
- Chat (`text`) and files use 1–5. Through the hub they become the hub's own
  chat message and inbox file, which the hub holds for an offline device, so
  3 and 4 are the same call; the result says which it was.

The rest of the agent is reached only through `host` (see `Host`).
"""

from __future__ import annotations

import ipaddress
import json
import logging
import mimetypes
import re
import secrets
import socket
import threading
import time
from pathlib import Path

from . import DEFAULT_PORT, PROTOCOL_VERSION
from .control import ControlServer
from .desktop import Desktop
from .discovery import Directory, txt_records
from .files import Completed, DownloadError, Offer, Offers, check_offer, download, safe_name
from .identity import PEER_ID, der_to_pem, load_or_create
from .outbox import DONE, FAILED, QUEUED, SENDING, Outbox
from .pairing import ACCEPTED, CANCELLED, DENIED, EXPIRED, Incoming, Outgoing, PairError
from .server import Server
from .tlsctx import ServerContexts, client_context
from .trust import TrustList, clean_addresses, clean_port, make_entry
from .wslink import Link, LinkError, client_handshake, server_handshake

log = logging.getLogger("droplet_agent.mesh")

USER_AGENT = "droplet-agent-mesh/1"
LAN_TIMEOUT = 1.5
TAILNET_TIMEOUT = 4
HELLO_TIMEOUT = 15
ACK_TIMEOUT = 10
STALL = 60             # seconds a file transfer may make no progress
IDLE_CLOSE = 300       # an outbound link unused this long is closed
RETRY_EVERY = 15       # the outbox looks for routes this often (and at once when a peer or the hub appears)
MAX_TEXT = 64 * 1024
LIVE = ("input", "media", "cmd")
TAILNET_V4 = ipaddress.ip_network("100.64.0.0/10")
TAILNET_V6 = ipaddress.ip_network("fd7a:115c:a1e0::/48")


def is_tailnet(address: str) -> bool:
    try:
        ip = ipaddress.ip_address(address)
    except ValueError:
        return False
    return ip in (TAILNET_V4 if ip.version == 4 else TAILNET_V6)


class NoRoute(Exception):
    pass


class Host:
    """What the mesh needs from the rest of the agent. Every hub call may raise."""

    def mesh_caps(self) -> list[str]: return []
    def device_name(self) -> str: return socket.gethostname().split(".")[0]
    def hub_device_id(self) -> str | None: return None
    def hub_id(self) -> str | None: return None
    def dispatch_remote(self, msg: dict, source) -> None: pass
    def last_states(self) -> dict: return {}
    def hub_connected(self) -> bool: return False
    def hub_online(self, device_id: str) -> bool: return False
    def hub_send(self, msg: dict) -> bool: return False
    def hub_text(self, device_id: str, body: str) -> None: raise NoRoute("no hub")
    def hub_upload(self, device_id: str, path: Path, name: str, mime: str) -> None: raise NoRoute("no hub")
    def hub_ring(self, device_id: str, stop: bool) -> None: raise NoRoute("no hub")


class PeerSource:
    """Where a message arrived from, for the agent's handlers: a direct link."""

    kind = "peer"

    def __init__(self, node: "MeshNode", fp: str, name: str):
        self.node, self.fp, self.name = node, fp, name

    def reply(self, msg: dict) -> bool:
        link = self.node.direct(self.fp, dial=True)
        return bool(link and link.send(msg))

    def deliver_file(self, name: str, data: bytes, mime: str = "application/octet-stream", to: str | None = None):
        self.node.send_bytes(self.fp, name, data, mime)   # to the peer that asked, whatever `to` says


class Chat:
    """Chat messages sent and received directly, one JSON line each. Ids make repeats harmless."""

    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.Lock()
        self._ids: set[str] = set()
        try:
            with path.open(encoding="utf-8") as f:
                for line in f:
                    try:
                        self._ids.add(json.loads(line)["id"])
                    except (ValueError, KeyError, TypeError):
                        pass
        except OSError:
            pass

    def add(self, entry: dict) -> bool:
        """Store it; False if a message with that id is already there."""
        with self._lock:
            key = f"{entry['dir']}:{entry['id']}"
            if key in self._ids or entry["id"] in self._ids:
                return False
            self._ids.add(key)
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with self.path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(entry) + "\n")
            return True

    def recent(self, n: int = 50) -> list[dict]:
        try:
            lines = self.path.read_text(encoding="utf-8").splitlines()[-n:]
        except OSError:
            return []
        out = []
        for line in lines:
            try:
                out.append(json.loads(line))
            except ValueError:
                pass
        return out


class MeshNode:
    def __init__(self, host: Host, *, config_dir: Path, data_dir: Path, downloads: Path,
                 port: int | None = None, max_rate: int = 0, dry_run: bool = False, announce: bool = True,
                 local_addresses=None, retry_every: float = RETRY_EVERY, control: bool = True,
                 session_env=None):
        self.host = host
        self.config_dir, self.data_dir, self.downloads = config_dir, data_dir, downloads
        self.want_port, self.dry_run, self.announce = port, dry_run, announce
        self.retry_every = retry_every
        self.use_control = control
        self.local_addresses = local_addresses or (lambda: [])   # LAN addresses, the main one first
        self.identity = load_or_create(config_dir)
        self.trust = TrustList(config_dir / "trust.json", self.identity.fp)
        self.contexts = ServerContexts(self.identity, self.trust.pems())
        self.trust.on_change = self._trust_changed
        self.incoming = Incoming(self.identity)
        self.incoming.on_ready = self._pair_request
        self.outgoing: dict[str, Outgoing] = {}
        self.offers = Offers(max_rate)
        self.completed = Completed(data_dir / "received.json")
        self.outbox = Outbox(data_dir / "outbox.json")
        self.chat = Chat(data_dir / "chat.jsonl")
        self.desktop = Desktop(dry_run, session_env)
        self.links: dict[str, list[Link]] = {}
        self.peer_state: dict[str, dict] = {}
        self._lock = threading.RLock()
        self._dial_locks: dict[str, threading.Lock] = {}
        self._acks: dict[str, list] = {}         # text id → [Event, ok, error]
        self._downloading: set[tuple[str, str]] = set()
        self._workers: set[str] = set()
        self._wlock = threading.Lock()
        self._kick = threading.Event()
        self.stop = threading.Event()
        self.server: Server | None = None
        self.directory: Directory | None = None
        self.control: ControlServer | None = None
        self.port = 0

    # --- who we are -------------------------------------------------------------

    @property
    def peer_id(self) -> str:
        return self.identity.peer_id(self.host.hub_device_id())

    @property
    def name(self) -> str:
        return self.host.device_name()

    def _txt(self) -> dict:
        return txt_records(peer_id=self.peer_id, fp=self.identity.fp, name=self.name,
                           caps=self.host.mesh_caps(), hub_id=self.host.hub_id())

    def hello(self, t: str = "hello") -> dict:
        return {"t": t, "id": self.peer_id, "name": self.name, "caps": self.host.mesh_caps(), "os": "linux",
                "v": PROTOCOL_VERSION, "port": self.port}

    def announce_body(self) -> dict:
        """What the hub's roster needs from this device (POST /api/mesh/announce)."""
        lan = [a for a in self.local_addresses() if not is_tailnet(a)]
        return {"fp": self.identity.fp, "cert_pem": self.identity.cert_pem, "port": self.port,
                "lan": clean_addresses(lan), "os": "linux",
                "caps": self.host.mesh_caps(), "v": PROTOCOL_VERSION}

    # --- lifecycle --------------------------------------------------------------

    def start(self):
        self.server = Server(self.contexts, self, self.want_port)
        self.port = self.server.port
        self.server.start()
        log.info("mesh: listening on port %d as %s (fingerprint %s)", self.port, self.peer_id, self.identity.fp)
        if self.use_control:
            try:
                self.control = ControlServer(self.handle_control)
                self.control.start()
            except OSError as e:
                log.warning("mesh: no control socket, so the droplet-agent commands can't reach this agent: %s", e)
        if self.announce:
            self.directory = Directory(self.identity.fp, self.local_addresses, self._seen)
            self.directory.start(self.port, self._txt())
        for target in (self._deliver_loop, self._housekeeping):
            threading.Thread(target=target, name=f"mesh-{target.__name__.strip('_')}", daemon=True).start()
        self._kick.set()

    def close(self):
        self.stop.set()
        self._kick.set()
        for c in (self.control, self.directory, self.server):
            if c is not None:
                try:
                    c.close()
                except Exception:
                    pass
        for links in list(self.links.values()):
            for link in list(links):
                link.close(1001, "going away")

    def refresh_announcement(self):
        if self.directory is not None and self.directory.zc is not None and self._txt() != self.directory._txt:
            self.directory.update(self._txt())

    def _housekeeping(self):
        while not self.stop.wait(30):
            now = time.monotonic()
            for links in list(self.links.values()):
                for link in list(links):
                    if link.outbound and now - link.last_used > IDLE_CLOSE:
                        link.close(1000, "idle")
            try:
                self.refresh_announcement()
            except Exception:
                log.exception("mesh: announcing again")

    # --- trust ------------------------------------------------------------------

    def trusted(self, fp: str | None) -> dict | None:
        return self.trust.get(fp)

    def _trust_changed(self):
        self.contexts.update(self.trust.pems())
        with self._lock:
            gone = [fp for fp in self.links if self.trust.get(fp) is None]
        for fp in gone:
            for link in list(self.links.get(fp, [])):
                link.close(1008, "not trusted any more")
        for j in self.outbox.queued():
            if self.trust.get(j["fp"]) is None:
                self.outbox.update(j["id"], state=FAILED, error="that peer isn't trusted any more")

    def apply_roster(self, data: dict, hub_id: str) -> tuple[int, int]:
        """Trust exactly the hub's roster (and whoever was paired directly)."""
        peers = data.get("peers") if isinstance(data, dict) else None
        if not isinstance(peers, list):
            raise ValueError("the hub's roster wasn't a list of peers")
        entries = []
        for p in peers:
            if not isinstance(p, dict) or p.get("fp") == self.identity.fp:
                continue
            try:
                entries.append(make_entry(peer_id=p.get("id"), name=p.get("name"), cert_pem=p.get("cert_pem"),
                                          source="roster", lan=p.get("lan") or [], port=p.get("port"),
                                          tailnet_ip=p.get("tailnet_ip"), os_name=p.get("os") or "",
                                          caps=p.get("caps") or [], fp=p.get("fp"), hub=hub_id))
            except (ValueError, TypeError) as e:
                log.warning("mesh: skipping a roster entry for %s: %s", p.get("name") or p.get("id"), e)
        added, removed = self.trust.sync_roster(entries, hub_id)
        if added or removed:
            log.info("mesh: the hub's roster: %d peer(s), %d new, %d removed", len(entries), added, removed)
        self._kick.set()
        return added, removed

    # --- links ------------------------------------------------------------------

    def _add_link(self, link: Link):
        with self._lock:
            self.links.setdefault(link.fp, []).append(link)

    def _on_close(self, link: Link):
        with self._lock:
            links = self.links.get(link.fp, [])
            if link in links:
                links.remove(link)
            if not links:
                self.links.pop(link.fp, None)
        if link.ready.is_set():
            log.info("mesh: link with %s (%s) closed", link.hello.get("name") or link.fp[:12], link.address)

    def on_link(self, tls, req, entry: dict, address: str, leftover: bytes):
        """The server hands over a trusted peer's WebSocket upgrade."""
        try:
            proto, frames = server_handshake(tls, req.raw_head + leftover, USER_AGENT)
        except (LinkError, OSError) as e:
            log.debug("mesh: WebSocket from %s failed: %s", address, e)
            tls.close()
            return
        link = Link(tls, proto, fp=entry["fp"], address=address, outbound=False, on_message=self._on_message,
                    on_close=self._on_close, pending_frames=frames)
        link.kind = "tailnet" if is_tailnet(address) else "lan"
        self._add_link(link)
        link.start()

        def no_hello():
            if not link.ready.wait(HELLO_TIMEOUT):
                link.close(1008, "expected hello")
        threading.Thread(target=no_hello, daemon=True).start()

    def _dial(self, entry: dict, address: str, port: int, kind: str) -> Link | None:
        timeout = TAILNET_TIMEOUT if kind == "tailnet" else LAN_TIMEOUT
        fp = entry["fp"]
        try:
            raw = socket.create_connection((address, port), timeout=timeout)
        except OSError as e:
            log.debug("mesh: %s at %s:%d: %s", entry["name"], address, port, e)
            return None
        try:
            raw.settimeout(timeout * 3)
            tls = client_context(self.identity, fp).wrap_socket(raw)
            proto, frames = client_handshake(tls, address, port, USER_AGENT)
        except Exception as e:
            log.info("mesh: couldn't open a link to %s at %s:%d: %s", entry["name"], address, port, e)
            raw.close()
            return None
        link = Link(tls, proto, fp=fp, address=address, outbound=True, on_message=self._on_message,
                    on_close=self._on_close, pending_frames=frames)
        link.kind, link.port = kind, port
        self._add_link(link)
        link.start()
        link.send(self.hello())
        if not link.ready.wait(HELLO_TIMEOUT):
            link.close(1008, "no welcome")
            return None
        self.trust.learn(fp, address=address, port=port, tailnet=kind == "tailnet")
        return link

    def _candidates(self, entry: dict) -> list[tuple[str, int, str]]:
        out = []
        port = entry.get("port") or DEFAULT_PORT
        if self.directory is not None:
            for s in self.directory.by_fp(entry["fp"]):
                out += [(a, s.port, "tailnet" if is_tailnet(a) else "lan") for a in s.addresses]
        out += [(a, port, "tailnet" if is_tailnet(a) else "lan") for a in entry.get("lan") or []]
        if entry.get("tailnet_ip"):
            out.append((entry["tailnet_ip"], port, "tailnet"))
        seen, ordered = set(), []
        for c in sorted(out, key=lambda c: c[2] == "tailnet"):   # LAN first, then the tailnet
            if c[:2] not in seen:
                seen.add(c[:2])
                ordered.append(c)
        return ordered

    def open_link(self, fp: str) -> Link | None:
        with self._lock:
            ready = [link for link in self.links.get(fp, []) if link.ready.is_set() and not link.closed]
        return max(ready, key=lambda link: link.opened) if ready else None

    def direct(self, fp: str, dial: bool = True) -> Link | None:
        """An open link with the peer (routes 1–2), dialling one if need be."""
        link = self.open_link(fp)
        if link or not dial:
            return link
        entry = self.trust.get(fp)
        if entry is None:
            return None
        with self._lock:
            lock = self._dial_locks.setdefault(fp, threading.Lock())
        with lock:
            link = self.open_link(fp)
            if link:
                return link
            for address, port, kind in self._candidates(entry):
                link = self._dial(entry, address, port, kind)
                if link:
                    log.info("mesh: link open with %s over %s (%s:%d)", entry["name"], kind, address, port)
                    return link
        return None

    def broadcast(self, msg: dict) -> bool:
        """Send to every peer with an open link (state, clipboard)."""
        sent = False
        with self._lock:
            fps = list(self.links)
        for fp in fps:
            link = self.open_link(fp)
            if link is not None:
                sent = link.send(msg) or sent
        return sent

    # --- what arrives -------------------------------------------------------------

    def _on_message(self, link: Link, msg: dict):
        entry = self.trust.get(link.fp)
        if entry is None:
            link.close(1008, "not trusted")
            return
        t = msg.get("t")
        if not link.ready.is_set():
            if (t == "hello" and not link.outbound) or (t == "welcome" and link.outbound):
                link.hello = msg
                self.trust.learn(link.fp, port=msg.get("port"), name=msg.get("name"), peer_id=msg.get("id"),
                                 os_name=msg.get("os"), caps=msg.get("caps") if isinstance(msg.get("caps"), list) else None,
                                 address=None if link.outbound else link.address,
                                 tailnet=link.kind == "tailnet")
                if t == "hello":
                    link.send(self.hello("welcome"))
                    log.info("mesh: %s connected from %s", entry["name"], link.address)
                link.ready.set()
                for kind, data in (self.host.last_states() or {}).items():
                    link.send({"t": "state", "kind": kind, "data": data})
                self._kick.set()
            return
        sender = {"id": entry["id"], "name": entry["name"]}
        if t == "ping":
            link.send({"t": "pong"})
        elif t == "text":
            self._recv_text(link, entry, msg)
        elif t in ("ack", "nack"):
            oid = msg.get("id")
            if isinstance(oid, str):
                waiter = self._acks.get(oid)
                if waiter is not None and waiter[3] == link.fp:
                    waiter[1], waiter[2] = t == "ack", str(msg.get("error") or "")[:200]
                    waiter[0].set()
                self.offers.resolve(link.fp, oid, t == "ack", str(msg.get("error") or "")[:200])
        elif t == "offer":
            self._recv_offer(link, entry, msg)
        elif t == "ring":
            self.desktop.ring(entry["name"])
        elif t == "ring-stop":
            self.desktop.stop_ring()
        elif t == "notify":
            key = msg.get("key")
            app = str(msg.get("app") or entry["name"])[:40]
            title = str(msg.get("title") or app)
            self.desktop.notify(f"{title} ({entry['name']})", str(msg.get("text") or ""),
                                key=f"{link.fp}:{key}" if isinstance(key, str) else None, app=app)
        elif t == "notify-removed":
            if isinstance(msg.get("key"), str):
                self.desktop.close_notification(f"{link.fp}:{msg['key']}")
        elif t == "unpair":
            if entry["source"] == "paired":
                log.info("mesh: %s unpaired from this device", entry["name"])
                self.trust.remove(link.fp)
            else:
                log.info("mesh: %s asked to unpair, but your hub vouches for it; remove it on the hub", entry["name"])
        elif t == "state":
            kind = str(msg.get("kind") or "")[:20]
            if kind:
                self.peer_state.setdefault(link.fp, {})[kind] = msg.get("data")
        elif t in ("input", "media", "cmd", "clip", "rpc"):
            out = {k: v for k, v in msg.items() if k not in ("from", "to")}
            out["from"] = sender   # who sent it is the authenticated peer, whatever the message says
            self.host.dispatch_remote(out, PeerSource(self, link.fp, entry["name"]))
        # hello, welcome, pong, rpc-result and anything newer: nothing to do

    def _recv_text(self, link: Link, entry: dict, msg: dict):
        mid, body = msg.get("id"), msg.get("body")
        if not isinstance(mid, str) or not re.fullmatch(r"[0-9A-Za-z_-]{8,64}", mid):
            return
        if not isinstance(body, str) or not body or len(body.encode("utf-8", "surrogatepass")) > MAX_TEXT:
            link.send({"t": "nack", "id": mid, "error": "empty or too long"})
            return
        ts = msg.get("ts") if isinstance(msg.get("ts"), (int, float)) else time.time()
        new = self.chat.add({"id": f"{link.fp[:16]}:{mid}", "dir": "in", "fp": link.fp, "peer": entry["id"],
                             "name": entry["name"], "body": body, "ts": ts})
        link.send({"t": "ack", "id": mid})
        if new:
            log.info("mesh: message from %s: %s", entry["name"], body[:200])
            self.desktop.notify(entry["name"], body, key=f"chat-{link.fp[:16]}")

    def _recv_offer(self, link: Link, entry: dict, msg: dict):
        try:
            oid, name, size, _mime = check_offer(msg)
        except DownloadError as e:
            link.send({"t": "nack", "id": msg.get("id") if isinstance(msg.get("id"), str) else "", "error": str(e)})
            return
        if self.completed.has(link.fp, oid) is not None:
            link.send({"t": "ack", "id": oid})   # already here: the last ack must have been lost
            return
        key = (link.fp, oid)
        with self._lock:
            if key in self._downloading:
                return
            self._downloading.add(key)
        hosts = [(link.address, link.port if link.outbound else clean_port(link.hello.get("port")) or DEFAULT_PORT)]
        hosts += [(a, p) for a, p, _ in self._candidates(entry) if (a, p) not in hosts]
        threading.Thread(target=self._download, args=(entry, oid, name, size, hosts),
                         name="mesh-download", daemon=True).start()

    def _download(self, entry: dict, oid: str, name: str, size: int, hosts):
        fp = entry["fp"]
        try:
            log.info("mesh: receiving %s (%d bytes) from %s", name, size, entry["name"])
            path = download(self.identity, fp, hosts, oid, name, size, self.downloads)
            self.completed.add(fp, oid, str(path))
            log.info("mesh: saved %s from %s", path, entry["name"])
            self.desktop.notify(f"{entry['name']} sent a file", path.name, key=f"file-{oid}")
            reply = {"t": "ack", "id": oid}
        except DownloadError as e:
            log.warning("mesh: receiving %s from %s failed: %s", name, entry["name"], e)
            reply = {"t": "nack", "id": oid, "error": str(e)} if e.permanent else None
        except Exception:
            log.exception("mesh: receiving %s failed", name)
            reply = None
        finally:
            with self._lock:
                self._downloading.discard((fp, oid))
        if reply is not None:
            link = self.direct(fp)
            if link is None or not link.send(reply):
                log.info("mesh: couldn't tell %s about %s; it will offer it again", entry["name"], name)

    # --- sending: live messages (routes 1–3) --------------------------------------

    def _hub_knows(self, entry: dict) -> bool:
        hub_id = self.host.hub_id()
        return bool(hub_id and entry.get("hub") == hub_id and self.host.hub_connected())

    def send_live(self, fp: str, msg: dict) -> str:
        """input, media, cmd: direct, else through the hub. Returns the route; raises NoRoute."""
        entry = self._entry(fp)
        link = self.direct(fp)
        if link is not None and link.send(msg):
            return link.kind
        if self._hub_knows(entry) and self.host.hub_send({**msg, "to": entry["id"]}):
            return "hub"
        raise NoRoute(f"{entry['name']} isn't reachable directly, and not through the hub either")

    def ring(self, fp: str, stop: bool = False) -> str:
        entry = self._entry(fp)
        link = self.direct(fp)
        if link is not None and link.send({"t": "ring-stop" if stop else "ring"}):
            return link.kind
        if self._hub_knows(entry):
            self.host.hub_ring(entry["id"], stop)
            return "hub"
        raise NoRoute(f"{entry['name']} isn't reachable directly, and not through the hub either")

    def clip(self, fp: str, text: str) -> str:
        entry = self._entry(fp)
        if not isinstance(text, str) or not text or len(text.encode("utf-8", "surrogatepass")) > 256 * 1024:
            raise ValueError("clipboard text must be 1 byte to 256 KB")
        link = self.direct(fp)
        if link is not None and link.send({"t": "clip", "text": text}):
            return link.kind
        # the hub has no addressed clipboard message: it goes to all your devices' clipboards
        if self._hub_knows(entry) and self.host.hub_send({"t": "clip", "text": text}):
            return "hub"
        raise NoRoute(f"{entry['name']} isn't reachable directly, and not through the hub either")

    def _entry(self, fp: str) -> dict:
        entry = self.trust.get(fp)
        if entry is None:
            raise NoRoute("that peer isn't trusted")
        return entry

    # --- sending: chat and files (routes 1–5) --------------------------------------

    def send_text(self, fp: str, body: str) -> dict:
        entry = self._entry(fp)
        if not isinstance(body, str) or not body.strip() or len(body.encode("utf-8", "surrogatepass")) > MAX_TEXT:
            raise ValueError("a message must be 1 byte to 64 KB of text")
        job = self.outbox.add_text(fp, entry["name"], body)
        self._kick.set()
        return job

    def send_file(self, fp: str, path: Path, cleanup: bool = False) -> dict:
        entry = self._entry(fp)
        path = Path(path).expanduser().resolve()
        if not path.is_file():
            raise ValueError(f"{path} isn't a file")
        mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        job = self.outbox.add_file(fp, entry["name"], path, safe_name(path.name), mime)
        if cleanup:
            self.outbox.update(job["id"], cleanup=True)
        self._kick.set()
        return job

    def send_bytes(self, fp: str, name: str, data: bytes, mime: str):
        """Send data as a file (a screenshot): written under the data dir, removed once delivered."""
        d = self.data_dir / "sending"
        d.mkdir(parents=True, exist_ok=True)
        p = d / f"{secrets.token_hex(4)}-{safe_name(name)}"
        p.write_bytes(data)
        job = self.outbox.add_file(fp, self._entry(fp)["name"], p, safe_name(name), mime)
        self.outbox.update(job["id"], cleanup=True)
        self._kick.set()

    def _deliver_loop(self):
        while not self.stop.is_set():
            self._kick.wait(self.retry_every)
            self._kick.clear()
            if self.stop.is_set():
                return
            for fp in dict.fromkeys(j["fp"] for j in self.outbox.queued()):
                with self._wlock:
                    if fp in self._workers:
                        continue
                    self._workers.add(fp)
                threading.Thread(target=self._work_peer, args=(fp,), name="mesh-deliver", daemon=True).start()

    def _work_peer(self, fp: str):
        """Deliver one peer's jobs in order, until one has to wait for a route."""
        try:
            while not self.stop.is_set():
                with self._wlock:
                    jobs = self.outbox.for_peer(fp)
                    if not jobs:
                        self._workers.discard(fp)
                        return
                if self._attempt(jobs[0]) == "wait":
                    break
        except Exception:
            log.exception("mesh: delivering to %s", fp[:12])
        with self._wlock:
            self._workers.discard(fp)

    def _finish(self, job: dict, state: str, **fields):
        self.outbox.update(job["id"], state=state, **fields)
        if state in (DONE, FAILED) and job.get("cleanup"):
            Path(job["path"]).unlink(missing_ok=True)
        if state == DONE and job["kind"] == "text":
            entry = self.trust.get(job["fp"]) or {}
            self.chat.add({"id": job["id"], "dir": "out", "fp": job["fp"], "peer": entry.get("id"),
                           "name": entry.get("name"), "body": job["body"], "ts": job["created"],
                           "route": fields.get("route")})
        if state == DONE:
            log.info("mesh: %s to %s delivered (%s)", "message" if job["kind"] == "text" else job["name"],
                     job["peer"], fields.get("route"))
        elif state == FAILED:
            log.warning("mesh: %s to %s failed: %s", job["kind"], job["peer"], fields.get("error"))

    def _file_changed(self, job: dict) -> str | None:
        try:
            st = Path(job["path"]).stat()
        except OSError:
            return f"{job['path']} is gone"
        if st.st_size != job["size"] or st.st_mtime_ns != job["mtime_ns"]:
            return f"{job['path']} changed after it was sent"
        return None

    def _attempt(self, job: dict) -> str:
        entry = self.trust.get(job["fp"])
        if entry is None:
            self._finish(job, FAILED, error="that peer isn't trusted any more")
            return "done"
        if job["kind"] == "file":
            why = self._file_changed(job)
            if why:
                self._finish(job, FAILED, error=why)
                return "done"
        self.outbox.update(job["id"], state=SENDING, attempts=job["attempts"] + 1)
        link = self.direct(job["fp"])
        if link is not None:
            got = self._direct_text(link, job) if job["kind"] == "text" else self._direct_file(link, job)
            if got == "ok":
                self._finish(job, DONE, route=link.kind, error=None)
                return "done"
            if got.startswith("refused:"):
                self._finish(job, FAILED, error=got[len("refused:"):].strip() or "the peer refused it")
                return "done"
            # the peer is there but it didn't finish: try again soon, directly
            self.outbox.update(job["id"], state=QUEUED, error=got)
            threading.Timer(3, self._kick.set).start()
            return "wait"
        if self._hub_knows(entry):
            try:
                if job["kind"] == "text":
                    self.host.hub_text(entry["id"], job["body"])
                else:
                    self.host.hub_upload(entry["id"], Path(job["path"]), job["name"], job["mime"])
                route = "hub" if self.host.hub_online(entry["id"]) else "hub-mailbox"
                self._finish(job, DONE, route=route, error=None)
                return "done"
            except Exception as e:
                log.info("mesh: through the hub failed: %s", e)
                err = f"the hub: {e}"
        else:
            err = "not reachable directly, and no hub knows it right now"
        self.outbox.update(job["id"], state=QUEUED, error=err)
        return "wait"

    def _direct_text(self, link: Link, job: dict) -> str:
        waiter = [threading.Event(), False, "", link.fp]
        self._acks[job["id"]] = waiter
        try:
            if not link.send({"t": "text", "id": job["id"], "body": job["body"], "ts": job["created"]}):
                return "the link dropped"
            if not waiter[0].wait(ACK_TIMEOUT):
                return "no answer"
            return "ok" if waiter[1] else f"refused: {waiter[2]}"
        finally:
            self._acks.pop(job["id"], None)

    def _direct_file(self, link: Link, job: dict) -> str:
        offer = Offer(job["id"], link.fp, job["name"], job["size"], job["mime"],
                      opener=lambda: open(job["path"], "rb"), check=lambda: self._file_changed(job))
        self.offers.add(offer)
        try:
            if not link.send(offer.message()):
                return "the link dropped"
            while not offer.done.wait(1):
                if self.stop.is_set():
                    return "stopping"
                if time.monotonic() - offer.last_activity > STALL:
                    return f"stalled at {offer.sent} bytes"
                if self.trust.get(link.fp) is None:
                    return "refused: not trusted any more"
            ok, error = offer.result or (False, "")
            return "ok" if ok else f"refused: {error}"
        finally:
            self.offers.remove(job["id"])

    def serve_file(self, sock, oid: str, fp: str, req, send_head):
        self.offers.serve(sock, oid, fp, req.headers, req.method, send_head)

    # --- pairing --------------------------------------------------------------------

    def pair(self, method: str, path: str, body):
        if path == "/mesh/pair":
            if method != "POST":
                return 405, {"error": "POST"}
            return self.incoming.open(body, self.peer_id, self.name)
        m = re.fullmatch(r"/mesh/pair/([0-9a-f]{32})(/confirm|/cancel)?", path)
        if not m:
            return 404, {"error": "not found"}
        rid, action = m.group(1), m.group(2)
        if action is None and method == "GET":
            return self.incoming.status(rid)
        if action == "/confirm" and method == "POST":
            return self.incoming.confirm(rid, body)
        if action == "/cancel" and method == "POST":
            return self.incoming.cancel(rid)
        return 405, {"error": "method not allowed"}

    def _pair_request(self, req: dict):
        log.warning("mesh: %s (%s) wants to pair, code %s. Answer with: droplet-agent pair --accept "
                    "(or --deny)", req["name"], req["id"], req["code"])
        self.desktop.notify(f"{req['name']} wants to pair", f"Code {req['code']}. If it matches the code on "
                            f"{req['name']}, run: droplet-agent pair --accept", key=f"pair-{req['request']}")

    def pair_start(self, target: str) -> dict:
        host, port, expect = self._pair_target(target)
        og = Outgoing(self.identity, self.peer_id, self.name, host, port, expect)
        try:
            og.start()
        except PairError as e:
            raise ValueError(str(e)) from e
        except OSError as e:
            raise ValueError(f"couldn't reach {host}:{port}: {e}") from e
        if og.peer["fp"] == self.identity.fp:
            raise ValueError("that's this device")
        with self._lock:
            self.outgoing[og.request] = og
        og.address = host
        return {"request": og.request, "code": og.code, "peer": og.peer, "address": f"{host}:{port}"}

    def _pair_target(self, target: str) -> tuple[str, int, str | None]:
        t = (target or "").strip()
        seen = self.directory.peers() if self.directory is not None else []
        q = t.lower()
        matches = [s for s in seen if s.name.casefold() == t.casefold() or s.id == q
                   or (len(q) >= 8 and s.fp.startswith(q))]
        if len({s.fp for s in matches}) == 1:
            s = matches[0]
            return s.addresses[0], s.port, s.fp
        if len({s.fp for s in matches}) > 1:
            raise ValueError(f"more than one peer on this network is called {t}; use its id")
        m = re.fullmatch(r"\[?([0-9A-Za-z.:\-]+?)\]?(?::(\d{1,5}))?", t)
        if m and (m.group(2) or re.fullmatch(r"[0-9.]+|[0-9a-fA-F:]+:[0-9a-fA-F:]*|[A-Za-z0-9.\-]+\.[A-Za-z]+", m.group(1))):
            port = int(m.group(2)) if m.group(2) else DEFAULT_PORT
            if not 0 < port < 65536:
                raise ValueError("bad port")
            return m.group(1), port, None
        raise ValueError(f"no peer called {t!r} is announcing itself on this network. "
                         "Give its address instead: droplet-agent pair <address>[:port]")

    def pair_confirm(self, rid: str, yes: bool) -> dict:
        og = self.outgoing.get(rid)
        if og is None:
            raise ValueError("no such pairing request")
        if not yes:
            og.cancel()
            og.state = CANCELLED
            return {"state": og.state}
        og.local_ok = True
        threading.Thread(target=self._pair_wait, args=(og,), name="mesh-pair", daemon=True).start()
        return {"state": og.state}

    def _pair_wait(self, og: Outgoing):
        end = time.monotonic() + 300
        while time.monotonic() < end and not self.stop.is_set():
            try:
                state = og.poll()
            except Exception as e:
                log.debug("mesh: polling the pairing request: %s", e)
                state = "waiting"
            if state == ACCEPTED:
                try:
                    entry = make_entry(peer_id=og.peer["id"], name=og.peer["name"], cert_pem=der_to_pem(og.der),
                                       source="paired", os_name=og.peer.get("os") or "", port=og.port,
                                       lan=[] if is_tailnet(og.host) else [og.host],
                                       tailnet_ip=og.host if is_tailnet(og.host) else None)
                    self.trust.add_paired(entry)
                    log.info("mesh: paired with %s (%s)", entry["name"], entry["fp"])
                    og.state = ACCEPTED
                except ValueError as e:
                    log.warning("mesh: pairing failed: %s", e)
                    og.state = EXPIRED
                return
            if state in (DENIED, EXPIRED, CANCELLED):
                og.state = state
                return
            time.sleep(1.5)
        og.state = EXPIRED

    def pair_answer(self, rid: str, accept: bool) -> dict:
        r = self.incoming.answer(rid, accept)
        if r is None:
            raise ValueError("no such pairing request waiting (it may have expired)")
        self.desktop.close_notification(f"pair-{rid}")
        if accept:
            self.trust.add_paired(make_entry(peer_id=r["id"], name=r["name"], cert_pem=der_to_pem(r["der"]),
                                             source="paired", os_name=r["os"]))
            log.info("mesh: paired with %s (%s)", r["name"], r["fp"])
        else:
            log.info("mesh: refused to pair with %s", r["name"])
        return {"state": ACCEPTED if accept else DENIED, "name": r["name"], "fp": r["fp"]}

    def unpair(self, fp: str) -> dict:
        entry = self._entry(fp)
        if entry["source"] != "paired":
            raise ValueError(f"{entry['name']} is trusted because your hub lists it. Remove it on the hub, "
                             "and every device stops trusting it.")
        told = False
        link = self.direct(fp)
        if link is not None:
            told = link.send({"t": "unpair"})
        self.trust.remove(fp)
        log.info("mesh: unpaired %s", entry["name"])
        return {"name": entry["name"], "told": told}

    # --- the control socket -----------------------------------------------------------

    def _seen(self, s):
        if self.trust.get(s.fp) and self.outbox.for_peer(s.fp):
            self._kick.set()

    def resolve(self, query: str) -> dict:
        found = self.trust.find(query)
        if not found:
            raise ValueError(f"no trusted peer called {query!r}. See: droplet-agent peers")
        if len(found) > 1:
            raise ValueError(f"{query!r} matches more than one peer: "
                             + ", ".join(f"{e['name']} ({e['id']})" for e in found))
        return found[0]

    def status(self) -> dict:
        seen = self.directory.peers() if self.directory is not None else []
        peers = []
        for e in self.trust.all():
            link = self.open_link(e["fp"])
            peers.append({k: e[k] for k in ("id", "name", "fp", "source", "lan", "port", "tailnet_ip", "os", "hub")}
                         | {"link": f"{link.kind} {link.address}" if link else None,
                            "on_lan": any(s.fp == e["fp"] for s in seen)})
        trusted = {e["fp"] for e in peers}
        return {
            "id": self.peer_id, "name": self.name, "fp": self.identity.fp, "port": self.port,
            "peers": peers,
            "nearby": [{"id": s.id, "name": s.name, "fp": s.fp, "os": s.os, "addresses": s.addresses,
                        "port": s.port} for s in seen if s.fp not in trusted],
            "incoming": self.incoming.waiting(),
            "outbox": [{k: j.get(k) for k in ("id", "kind", "peer", "state", "error", "name", "attempts")}
                       for j in self.outbox.queued()],
            "refused": self.server.refused if self.server else 0,
        }

    def handle_control(self, req: dict) -> dict:
        cmd = req.get("cmd")
        try:
            if cmd == "status":
                return self.status()
            if cmd == "pair-start":
                return self.pair_start(str(req.get("target") or ""))
            if cmd == "pair-confirm":
                return self.pair_confirm(str(req.get("request")), bool(req.get("yes")))
            if cmd == "pair-status":
                og = self.outgoing.get(str(req.get("request")))
                return {"state": og.state if og else EXPIRED}
            if cmd == "pair-answer":
                return self.pair_answer(str(req.get("request")), bool(req.get("accept")))
            if cmd == "unpair":
                return self.unpair(self.resolve(str(req.get("peer") or ""))["fp"])
            if cmd in ("text", "send-file"):
                entry = self.resolve(str(req.get("peer") or ""))
                job = (self.send_text(entry["fp"], req.get("body")) if cmd == "text"
                       else self.send_file(entry["fp"], Path(str(req.get("path") or ""))))
                return self._job_answer(job["id"], float(req.get("wait") or 0))
            if cmd == "job":
                return self._job_answer(str(req.get("id")), float(req.get("wait") or 0))
            if cmd == "ring":
                return {"route": self.ring(self.resolve(str(req.get("peer") or ""))["fp"], bool(req.get("stop")))}
            if cmd == "clip":
                return {"route": self.clip(self.resolve(str(req.get("peer") or ""))["fp"], req.get("text"))}
            if cmd == "send":
                msg = req.get("msg")
                if not isinstance(msg, dict) or msg.get("t") not in LIVE:
                    raise ValueError(f"only {', '.join(LIVE)} messages can be sent this way")
                return {"route": self.send_live(self.resolve(str(req.get("peer") or ""))["fp"], msg)}
            return {"error": f"unknown command {cmd!r}"}
        except (ValueError, NoRoute) as e:
            return {"error": str(e)}

    def _job_answer(self, jid: str, wait: float) -> dict:
        """The job, once it's delivered, failed, or tried every route once (or `wait` seconds passed)."""
        job = self.outbox.wait(jid, lambda j: j["state"] in (DONE, FAILED)
                               or (j["state"] == QUEUED and j["attempts"] > 0), min(wait, 3600))
        if job is None:
            return {"error": "no such job"}
        return {k: job.get(k) for k in ("id", "kind", "peer", "state", "route", "error", "attempts", "name")}
