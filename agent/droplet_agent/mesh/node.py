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
import subprocess
import sys
import threading
import time
from pathlib import Path

from . import DEFAULT_PORT, OS_NAME, PROTOCOL_VERSION, perms
from .control import ControlServer
from .desktop import Desktop
from .discovery import Directory, txt_records
from . import transfers as tx
from .files import Cancelled, Completed, DownloadError, Offer, Offers, check_offer, download, safe_name
from .identity import PEER_ID, der_to_pem, load_or_create
from .links import check_url
from .outbox import CANCELLED, DONE, FAILED, FINISHED, QUEUED, SENDING, Outbox
from .pairing import ACCEPTED, CANCELLED, DENIED, EXPIRED, Incoming, Outgoing, PairError
from .server import Server
from .tlsctx import ServerContexts, client_context
from .transfers import Transfers
from .trust import TrustList, check_name, clean_addresses, clean_port, make_browser_entry, make_entry
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
# Accepting a pairing request trusts the other device at once, but it trusts this one only
# when its own owner has confirmed the code and it has polled for the answer: seconds, or
# minutes, later. Until then its TLS refuses this device's certificate. So for a while after
# accepting, a peer that can't be reached directly is tried again every PAIR_RETRY seconds
# instead of every RETRY_EVERY.
PAIR_GRACE = 120
PAIR_RETRY = 2
MAX_TEXT = 64 * 1024
MAX_CHAT = 500          # messages one `chat` answer holds at most
MAX_ANSWER = 768 * 1024  # and bytes (a control answer is one line, read up to control.MAX_LINE)
LIVE = ("input", "media", "cmd")
# what this peer understands besides v1 (docs/mesh.md §9.10), in its hello as "features":
# `cancel` (stop a transfer), `link` (a web link, opened on your own devices), `rename`
FEATURES = ["cancel", "link", "rename"]
OPEN_LINKS = 5          # links opened at most, from one device, in a minute (the rest wait in Messages)
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
    def shows_notifications(self) -> bool: return True
    def device_name(self) -> str: return getattr(self, "_name", None) or socket.gethostname().split(".")[0]
    # a new name for this device (droplet-agent rename): kept by the host; with a hub, the hub
    # names it (and may refuse: a name another device has). Raises ValueError saying why not.
    def set_device_name(self, name: str) -> None: self._name = name
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
    # Pause everything (perms.py): kept by the host, so it survives a restart
    _paused_all = False
    def paused_everything(self) -> bool: return self._paused_all
    def set_paused_everything(self, on: bool) -> None: self._paused_all = bool(on)


class Refused(ValueError):
    """Not sent: this device's own settings say no (`local`), or the peer said it would refuse."""

    def __init__(self, text: str, why: str, cap: str | None, local: bool):
        super().__init__(text)
        self.why, self.cap, self.local = why, cap, local


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

    def recent(self, n: int = 50, fp: str | None = None) -> list[dict]:
        """The last `n` messages, oldest first; only those with peer `fp` if it's given."""
        try:
            lines = self.path.read_text(encoding="utf-8").splitlines()
        except OSError:
            return []
        out = []
        for line in reversed(lines):
            if len(out) >= n:
                break
            try:
                m = json.loads(line)
            except ValueError:
                continue
            if isinstance(m, dict) and (fp is None or m.get("fp") == fp):
                out.append(m)
        out.reverse()
        return out


def default_gateways(route_table: str = "/proc/net/route") -> list[str]:
    """This computer's IPv4 default gateways. On a phone's hotspot, that's the phone."""
    if sys.platform == "darwin" and route_table == "/proc/net/route":
        return _mac_default_gateway()
    out = []
    try:
        with open(route_table) as f:
            next(f, None)
            for line in f:
                parts = line.split()
                # a default route (destination 0) through a gateway (flag RTF_GATEWAY)
                if len(parts) < 4 or parts[1] != "00000000" or not int(parts[3], 16) & 0x2:
                    continue
                if parts[0].startswith(("docker", "br-", "veth", "virbr", "tailscale", "tun", "wg")):
                    continue
                gw = str(ipaddress.IPv4Address(int.from_bytes(bytes.fromhex(parts[2]), "little")))
                if gw not in out:
                    out.append(gw)
    except (OSError, ValueError):
        pass
    return out


def mac_gateway_from_route(text: str) -> list[str]:
    """`route -n get default` → [the gateway], unless the default route is a VPN's or a tailnet's."""
    gw = iface = ""
    for line in text.splitlines():
        key, _, value = line.strip().partition(":")
        if key == "gateway":
            gw = value.strip()
        elif key == "interface":
            iface = value.strip()
    if iface.startswith(("utun", "ipsec", "ppp", "tun", "tap", "bridge")):
        return []
    try:
        return [str(ipaddress.IPv4Address(gw))]
    except ValueError:
        return []


def _mac_default_gateway() -> list[str]:
    try:
        r = subprocess.run(["route", "-n", "get", "default"], capture_output=True, text=True, timeout=5,
                           stdin=subprocess.DEVNULL)
    except (OSError, subprocess.TimeoutExpired):
        return []
    return mac_gateway_from_route(r.stdout) if r.returncode == 0 else []


class MeshNode:
    def __init__(self, host: Host, *, config_dir: Path, data_dir: Path, downloads: Path,
                 port: int | None = None, max_rate: int = 0, dry_run: bool = False, announce: bool = True,
                 local_addresses=None, retry_every: float = RETRY_EVERY, control: bool = True,
                 session_env=None, gateways=None):
        self.host = host
        self.config_dir, self.data_dir, self.downloads = config_dir, data_dir, downloads
        self.want_port, self.dry_run, self.announce = port, dry_run, announce
        self.retry_every = retry_every
        self.use_control = control
        self.local_addresses = local_addresses or (lambda: [])   # LAN addresses, the main one first
        self.gateways = gateways or default_gateways
        self._gateway_misses: set[tuple[str, str]] = set()        # (gateway, fp) that weren't that peer
        self.identity = load_or_create(config_dir)
        self.trust = TrustList(config_dir / "trust.json", self.identity.fp)
        self.contexts = ServerContexts(self.identity, self.trust.pems())
        self.trust.on_change = self._trust_changed
        self.incoming = Incoming(self.identity)
        self.incoming.on_ready = self._pair_request
        self.outgoing: dict[str, Outgoing] = {}
        self.offers = Offers(max_rate)
        self.transfers = Transfers()
        self._dl_cancel: dict[tuple[str, str], str] = {}   # (fp, offer id) → who cancelled it ("here", "sender")
        self._cancelled_in: set[tuple[str, str]] = set()   # offers cancelled here: a re-offer is refused
        self._cancel_jobs: set[str] = set()                 # outbox jobs cancelled while being sent
        self._opened: dict[str, list] = {}                   # fp → when links from it were opened
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
        self._accepted: dict[str, float] = {}    # fp → when this device accepted its pairing request
        # what each linked peer said about how it treats this device (its `perm`): a hint only
        self.remote_perm: dict[str, dict] = {}
        self.refusals: dict[str, dict] = {}       # fp → the last thing it refused, and why
        self._refused_at: dict[tuple, float] = {}  # (fp, type) → when we last told it no
        self.stop = threading.Event()
        self.server: Server | None = None
        self.directory: Directory | None = None
        self.control: ControlServer | None = None
        self.port = 0
        # the iPhone link (droplet_agent/webrtc), when it's on: it adds control commands ("qr")
        # and a "webrtc" section to the status. When it isn't running, webrtc_off says why
        # ({"why": "off" | "missing" | "broken" | "failed", "text"}; webrtc.bridge.start_bridge)
        self.webrtc = None
        self.webrtc_off: dict | None = None
        self.control_ext: dict = {}

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

    def hello(self, t: str = "hello", fp: str | None = None) -> dict:
        """Our hello (or welcome) to the peer `fp`: the caps it may use, and how we treat it."""
        out = {"t": t, "id": self.peer_id, "name": self.name, "caps": self.host.mesh_caps(), "os": OS_NAME,
               "v": PROTOCOL_VERSION, "port": self.port, "features": list(FEATURES)}
        if fp is not None:
            out["caps"] = self.caps_for(fp)
            out["perm"] = self.perm_for(fp)
        return out

    # --- permissions (perms.py) ---------------------------------------------------------

    CAP_NEEDS = {"input": "control", "media": "control", "lock": "control", "screenshot": "control",
                 "clipboard": "clipboard", "notify": "notify"}

    @property
    def paused_all(self) -> bool:
        return bool(self.host.paused_everything())

    def caps_for(self, fp: str) -> list[str]:
        """The caps a peer is told about: only what it may use here."""
        entry = self.trust.get(fp)
        if entry is None or entry.get("paused") or self.paused_all:
            return []
        return [c for c in self.host.mesh_caps() if c not in self.CAP_NEEDS or perms.allowed(entry, self.CAP_NEEDS[c])]

    def perm_for(self, fp: str) -> dict:
        return perms.remote_view(self.trust.get(fp), self.paused_all)

    @staticmethod
    def label(entry: dict | None) -> str:
        """What the owner here calls a peer: its nickname, else its own name. Never sent."""
        entry = entry or {}
        return entry.get("nickname") or entry.get("name") or "that device"

    def features_of(self, fp: str) -> list:
        """What the peer said it understands besides v1, on its open link (none: an older peer)."""
        link = self.open_link(fp)
        f = (getattr(link, "hello", None) or {}).get("features") if link is not None else None
        return [x for x in f if isinstance(x, str)] if isinstance(f, list) else []

    def may_send(self, fp: str, msg: dict, entry: dict | None = None) -> None:
        """Raise Refused unless `msg` may go to the peer: by this device's settings, then by what
        the peer said it would take (a hint, so nothing goes that it would only refuse)."""
        entry = entry or self.trust.get(fp)
        no = perms.check(entry, msg, self.paused_all, "out")
        name = (entry or {}).get("name") or "that device"
        if no:
            raise Refused(perms.local_text(name, no[0], no[1], self.paused_all), no[0], no[1], True)
        # only while a link is open: its hello brought the peer's latest word, and a peer that
        # changed its mind while away says so in the hello of the next link
        remote = self.remote_perm.get(fp) if self.open_link(fp) is not None else None
        why = perms.remote_refuses(remote, perms.capability(msg))
        if why and msg.get("t") not in perms.ALWAYS:
            cap = perms.capability(msg)
            raise Refused(perms.refusal_text(name, why, cap), why, cap, False)

    def set_perms(self, fp: str, *, relation=None, allow=None, paused=None) -> dict:
        entry = self.trust.set_perms(fp, relation=relation, allow=allow, paused=paused)
        log.info("mesh: %s: %s%s", entry["name"], "paused" if entry["paused"] else "sharing",
                 "; " + ", ".join(f"{c} {'on' if v else 'off'}" for c, v in entry["allow"].items())
                 if relation is not None or allow is not None else "")
        self._tell_perms([fp])
        return entry

    def pause_everything(self, on: bool):
        self.host.set_paused_everything(on)
        log.info("mesh: %s", "everything paused" if on else "everything resumed")
        self._tell_perms(list(self.links))

    def _tell_perms(self, fps):
        """Tell linked peers how they're treated now (perm), and send what waited for a resume."""
        for fp in fps:
            link = self.open_link(fp)
            if link is not None:
                link.send({"t": "perm", **self.perm_for(fp), "caps": self.caps_for(fp)})
        self._kick.set()

    def _refuse(self, link, entry: dict, msg: dict, why: str, cap: str):
        """Say no to the sender, so it can show why. Acknowledged messages get an answer for that
        id: a nack for good when the capability is off, `refused` when paused (it waits, and goes
        on resume). The rest get one `refused` every few seconds at most."""
        t = msg.get("t")
        text = perms.refusal_text(self.name, why, cap)
        log.info("mesh: refused %s from %s: %s", t, entry["name"], "paused" if why == "paused" else f"{cap} is off")
        mid = msg.get("id") if isinstance(msg.get("id"), str) and len(msg["id"]) <= 64 else None
        if mid and t in ("text", "offer", "clip", "file", "link"):
            if why == "denied":
                link.send({"t": "nack", "id": mid, "error": text, "cap": cap, "why": why})
            else:
                link.send({"t": "refused", "re": t, "id": mid, "cap": cap, "why": why, "error": text})
            return
        key, now = (link.fp, t), time.monotonic()
        with self._lock:
            if now - self._refused_at.get(key, -1e9) < 5:
                return
            self._refused_at[key] = now
        link.send({"t": "refused", "re": t, "cap": cap, "why": why, "error": text})

    def _remote_perm(self, fp: str, v):
        got = perms.parse_remote(v)
        with self._lock:
            if got is None:
                self.remote_perm.pop(fp, None)      # an older peer: it takes everything, as before
            else:
                self.remote_perm[fp] = got
                if not got["paused"]:
                    old = self.refusals.get(fp)
                    if old and old["why"] == "paused":
                        self.refusals.pop(fp, None)

    def _got_refused(self, link, entry: dict, msg: dict):
        why = msg.get("why") if msg.get("why") in ("paused", "denied") else "denied"
        cap = msg.get("cap") if msg.get("cap") in perms.CAPABILITIES else None
        text = str(msg.get("error") or perms.refusal_text(entry["name"], why, cap))[:200]
        self.refusals[link.fp] = {"re": str(msg.get("re") or "")[:20], "cap": cap, "why": why, "text": text,
                                  "ts": time.time()}
        log.info("mesh: %s refused: %s", entry["name"], text)
        oid = msg.get("id")
        if isinstance(oid, str):
            waiter = self._acks.get(oid)
            if waiter is not None and waiter[3] == link.fp:
                waiter[1], waiter[2] = False, f"{why}: {text}"
                waiter[0].set()
            self.offers.resolve(link.fp, oid, False, f"{why}: {text}")

    def check_hub_message(self, msg: dict) -> str | None:
        """A message that came through the hub: why it's refused, or None. The sender is the hub's
        device `from.id`; one this device trusts gets its own switches, any other device of the
        hub's is your own (the same user's hub)."""
        sender = (msg.get("from") or {}).get("id") if isinstance(msg.get("from"), dict) else None
        entry = None
        if isinstance(sender, str):
            found = [e for e in self.trust.all() if e["id"] == sender and e["source"] != "browser"]
            entry = found[0] if found else None
        if entry is None:
            if self.paused_all and msg.get("t") not in perms.ALWAYS:
                return "paused"
            return None
        no = perms.check(entry, msg, self.paused_all, "in")
        return no[0] if no else None

    def hub_may_share(self, msg: dict) -> bool:
        """Whether a broadcast (clip, state) may go to the hub, which hands it to every device it
        has. Not while everything is paused, and a clipboard not while any device the hub lists
        is paused or has the clipboard off here."""
        if self.paused_all:
            return False
        cap = perms.capability(msg)
        if cap not in ("clipboard",):
            return True
        hub_id = self.host.hub_id()
        for e in self.trust.all():
            if hub_id and e.get("hub") == hub_id and (e.get("paused") or not perms.allowed(e, cap)):
                return False
        return True

    def announce_body(self) -> dict:
        """What the hub's roster needs from this device (POST /api/mesh/announce)."""
        lan = [a for a in self.local_addresses() if not is_tailnet(a)]
        return {"fp": self.identity.fp, "cert_pem": self.identity.cert_pem, "port": self.port,
                "lan": clean_addresses(lan), "os": OS_NAME,
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
        threading.Thread(target=self.probe_gateways, name="mesh-gateway", daemon=True).start()
        self._kick.set()

    def close(self):
        self.stop.set()
        self._kick.set()
        for c in (self.control, self.directory, self.server, self.webrtc):
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
                    if link.outbound and not getattr(link, "keep", False) and now - link.last_used > IDLE_CLOSE:
                        link.close(1000, "idle")
            try:
                self.refresh_announcement()
            except Exception:
                log.exception("mesh: announcing again")
            try:
                self.probe_gateways()
            except Exception:
                log.exception("mesh: looking for a paired device at the gateway")

    def probe_gateways(self) -> Link | None:
        """Link with a paired device that is this network's gateway: a phone serving a hotspot.

        Android doesn't announce on a hotspot it serves, and can't see who joined
        it, so neither side would find the other. The phone is always the
        hotspot's gateway, though. The link stays open (it's how the phone
        knows this computer is there), and only the device whose certificate
        is pinned for that peer gets one. A gateway that turned out not to be
        a peer isn't tried for it again until the network changes.
        """
        gateways = self.gateways()
        self._gateway_misses = {m for m in self._gateway_misses if m[0] in gateways}
        for gw in gateways:
            with self._lock:
                if any(link.address == gw and not link.closed for links in self.links.values() for link in links):
                    continue
            for entry in self.trust.all():
                if entry["source"] == "browser":
                    continue   # a browser is never dialled: it connects when it's open
                fp, port = entry["fp"], entry.get("port") or DEFAULT_PORT
                if self.open_link(fp) is not None or (gw, fp) in self._gateway_misses:
                    continue
                try:
                    socket.create_connection((gw, port), timeout=LAN_TIMEOUT).close()
                except OSError:
                    break   # nothing listening there: no peer at this gateway
                link = self._dial(entry, gw, port, "lan")
                if link is None:
                    self._gateway_misses.add((gw, fp))
                    continue
                link.keep = True
                log.info("mesh: link open with %s at the gateway (%s): its hotspot", entry["name"], gw)
                return link
        return None

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
            # a peer that just paired may not trust this device yet: that's expected, not news
            level = logging.DEBUG if self._just_accepted(fp) else logging.INFO
            log.log(level, "mesh: couldn't open a link to %s at %s:%d: %s", entry["name"], address, port, e)
            raw.close()
            return None
        link = Link(tls, proto, fp=fp, address=address, outbound=True, on_message=self._on_message,
                    on_close=self._on_close, pending_frames=frames)
        link.kind, link.port = kind, port
        self._add_link(link)
        link.start()
        link.send(self.hello(fp=fp))
        if not link.ready.wait(HELLO_TIMEOUT):
            link.close(1008, "no welcome")
            return None
        self.trust.learn(fp, address=address, port=port, tailnet=kind == "tailnet")
        with self._lock:
            self._accepted.pop(fp, None)
        return link

    def _candidates(self, entry: dict) -> list[tuple[str, int, str]]:
        out = []
        port = entry.get("port") or DEFAULT_PORT
        if self.directory is not None:
            for s in self.directory.by_fp(entry["fp"]):
                out += [(a, s.port, "tailnet" if is_tailnet(a) else "lan") for a in s.addresses]
        out += [(a, port, "tailnet" if is_tailnet(a) else "lan") for a in entry.get("lan") or []]
        # a phone serving a hotspot doesn't announce itself on it, but it's the hotspot's gateway
        out += [(gw, port, "lan") for gw in self.gateways() if (gw, entry["fp"]) not in self._gateway_misses]
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
        if entry is None or entry["source"] == "browser":
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
        """Send to every peer with an open link (state, clipboard) that may have it."""
        sent = False
        with self._lock:
            fps = list(self.links)
        for fp in fps:
            link = self.open_link(fp)
            if link is None:
                continue
            try:
                self.may_send(fp, msg)
            except Refused:
                continue
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
                self._remote_perm(link.fp, msg.get("perm"))
                if t == "hello":
                    link.send(self.hello("welcome", link.fp))
                    log.info("mesh: %s connected from %s", entry["name"], link.address)
                link.ready.set()
                for kind, data in (self.host.last_states() or {}).items():
                    st = {"t": "state", "kind": kind, "data": data}
                    if perms.check(entry, st, self.paused_all, "out") is None:
                        link.send(st)
                self._kick.set()
            return
        if t == "perm":
            self._remote_perm(link.fp, msg)
            if isinstance(msg.get("caps"), list):
                self.trust.learn(link.fp, caps=msg["caps"])
            self._kick.set()     # a resume: what waited for it goes now
            return
        if t == "refused":
            self._got_refused(link, entry, msg)
            return
        no = perms.check(entry, msg, self.paused_all, "in")
        if no is not None:
            self._refuse(link, entry, msg, *no)
            return
        sender = {"id": entry["id"], "name": entry["name"]}
        if t == "ping":
            link.send({"t": "pong"})
        elif t == "text":
            self._recv_text(link, entry, msg)
        elif t == "link":
            self._recv_link(link, entry, msg)
        elif t == "cancel":
            self._got_cancel(link, entry, msg)
        elif t == "rename":
            got = self.trust.rename(link.fp, msg.get("name"))
            if got is not None:
                log.info("mesh: %s is called %s now", entry["name"], got["name"])
                if isinstance(link.hello, dict):
                    link.hello["name"] = got["name"]
                conn = getattr(link, "conn", None)
                if conn is not None:
                    conn.name = got["name"]
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
            if not self.host.shows_notifications():
                return
            key = msg.get("key")
            app = str(msg.get("app") or entry["name"])[:40]
            title = str(msg.get("title") or app)
            self.desktop.notify(f"{title} ({entry['name']})", str(msg.get("text") or ""),
                                key=f"{link.fp}:{key}" if isinstance(key, str) else None, app=app)
        elif t == "notify-removed":
            if isinstance(msg.get("key"), str):
                self.desktop.close_notification(f"{link.fp}:{msg['key']}")
        elif t == "unpair":
            if entry["source"] in ("paired", "browser"):
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
            if t == "clip":
                # the iPhone's web app can't watch the clipboard: a clip from it is always
                # someone tapping "Send clipboard" there, so it's applied even with sync off
                out["explicit"] = link.kind == "webrtc"
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
            self.desktop.notify(self.label(entry), body, key=f"chat-{link.fp[:16]}")

    def _recv_link(self, link: Link, entry: dict, msg: dict):
        """A web link. From your own device it opens in the browser here; from someone else's it
        waits in Messages with an Open button. Never anything but http(s)."""
        mid = msg.get("id")
        if not isinstance(mid, str) or not re.fullmatch(r"[0-9A-Za-z_-]{8,64}", mid):
            return
        try:
            url = check_url(msg.get("url"))
        except ValueError as e:
            link.send({"t": "nack", "id": mid, "error": str(e)})
            return
        ts = msg.get("ts") if isinstance(msg.get("ts"), (int, float)) else time.time()
        own = entry.get("relation", "own") == "own"
        opened = own and self._may_open(link.fp)
        new = self.chat.add({"id": f"{link.fp[:16]}:{mid}", "dir": "in", "fp": link.fp, "peer": entry["id"],
                             "name": entry["name"], "body": url, "ts": ts, "kind": "link", "opened": opened})
        link.send({"t": "ack", "id": mid})
        if not new:
            return
        log.info("mesh: link from %s%s: %s", entry["name"], " (opening it)" if opened else "", url[:200])
        if opened and self.desktop.open_url(url):
            self.desktop.notify(f"{self.label(entry)} opened a link", url, key=f"chat-{link.fp[:16]}")
        else:
            self.desktop.notify(f"{self.label(entry)} sent a link", url + "\nOpen it from Messages in Droplet.",
                                key=f"chat-{link.fp[:16]}")

    def _may_open(self, fp: str) -> bool:
        now = time.monotonic()
        with self._lock:
            recent = [t for t in self._opened.get(fp, []) if now - t < 60]
            if len(recent) >= OPEN_LINKS:
                self._opened[fp] = recent
                return False
            self._opened[fp] = recent + [now]
        return True

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
        if key in self._cancelled_in:
            # cancelled here, and offered again by a sender that didn't understand `cancel`
            link.send({"t": "nack", "id": oid, "error": f"{self.name} cancelled it", "why": "cancelled"})
            return
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
        key = (fp, oid)
        self.transfers.start(oid, direction="in", fp=fp, peer=entry["name"], name=name, size=size)
        try:
            log.info("mesh: receiving %s (%d bytes) from %s", name, size, entry["name"])
            path = download(self.identity, fp, hosts, oid, name, size, self.downloads,
                            on_progress=lambda got, _size: self.transfers.progress(oid, got),
                            cancelled=lambda: key in self._dl_cancel)
            self.completed.add(fp, oid, str(path))
            self.transfers.finish(oid, tx.DONE)
            log.info("mesh: saved %s from %s", path, entry["name"])
            self.desktop.notify(f"{self.label(entry)} sent a file", path.name, key=f"file-{oid}")
            reply = {"t": "ack", "id": oid}
        except Cancelled:
            here = self._dl_cancel.get(key) == "here"
            self.transfers.finish(oid, tx.CANCELLED, "cancelled here" if here else f"{self.label(entry)} cancelled it")
            log.info("mesh: receiving %s from %s cancelled %s", name, entry["name"], "here" if here else "by the sender")
            # the sender cancelled: it knows. Cancelled here: tell it (an older sender ignores that,
            # and is refused when it offers it again)
            if here:
                self._cancelled_in.add(key)
            reply = {"t": "cancel", "id": oid} if here else None
        except DownloadError as e:
            self.transfers.finish(oid, tx.FAILED if e.permanent else tx.WAITING, str(e))
            log.warning("mesh: receiving %s from %s failed: %s", name, entry["name"], e)
            reply = {"t": "nack", "id": oid, "error": str(e)} if e.permanent else None
        except Exception as e:
            log.exception("mesh: receiving %s failed", name)
            self.transfers.finish(oid, tx.FAILED, str(e))
            reply = None
        finally:
            with self._lock:
                self._downloading.discard((fp, oid))
                self._dl_cancel.pop(key, None)
        if reply is not None:
            link = self.direct(fp)
            if link is None or not link.send(reply):
                log.info("mesh: couldn't tell %s about %s; it will offer it again", entry["name"], name)

    def _got_cancel(self, link: Link, entry: dict, msg: dict):
        """The peer cancelled a transfer: one it was sending here (stop fetching it, and drop
        what came), or one this device was sending it (it doesn't want it)."""
        oid = msg.get("id")
        if not isinstance(oid, str) or len(oid) > 64:
            return
        key = (link.fp, oid)
        with self._lock:
            if key in self._downloading:
                self._dl_cancel.setdefault(key, "sender")
                return
        job = self.outbox.get(oid)
        if job is not None and job["fp"] == link.fp and job["state"] not in FINISHED:
            self._cancel_out(job, f"{self.label(entry)} cancelled it", tell=False)

    def cancel(self, tid: str) -> dict:
        """Stop a transfer, or a send still waiting in the outbox, by its id (or the start of it,
        6 characters or more). The other device is told. Returns {"id", "dir", "name", "peer"}."""
        tid = str(tid or "").strip().lower()
        if not tid:
            raise ValueError("say which transfer: its id, as droplet-agent transfers shows it")

        def match(i):
            return i == tid or (len(tid) >= 6 and i.startswith(tid))
        with self._lock:
            downloads = [k for k in self._downloading if match(k[1])]
        jobs = [j for j in self.outbox.queued() if match(j["id"])]
        if len(downloads) + len(jobs) > 1:
            raise ValueError(f"{tid!r} matches more than one transfer: give more of its id")
        if downloads:
            key = downloads[0]
            with self._lock:
                self._dl_cancel[key] = "here"
            t = self.transfers.get(key[1]) or {}
            log.info("mesh: cancelling %s from %s", t.get("name") or key[1], t.get("peer") or key[0][:12])
            return {"id": key[1], "dir": "in", "name": t.get("name"), "peer": t.get("peer")}
        if jobs:
            job = jobs[0]
            self._cancel_out(job, "cancelled here", tell=True)
            return {"id": job["id"], "dir": "out", "name": job.get("name"), "peer": job["peer"]}
        # a file arriving from the iPhone, over its data channel
        for t in self.transfers.active():
            if t["dir"] == "in" and match(t["id"]):
                link = self.open_link(t["fp"])
                if link is not None and hasattr(link, "cancel_receive") and link.cancel_receive(t["id"]):
                    log.info("mesh: cancelling %s from %s", t["name"], t["peer"])
                    return {"id": t["id"], "dir": "in", "name": t["name"], "peer": t["peer"]}
        raise ValueError("no transfer with that id is going on (it may have finished already)")

    def _cancel_out(self, job: dict, why: str, tell: bool):
        """Cancel something this device sends: stop serving it, mark it cancelled, tell the peer."""
        jid = job["id"]
        with self._lock:
            self._cancel_jobs.add(jid)
        active = False
        offer = self.offers.get(jid)
        if offer is not None:
            offer.cancel(f"cancelled: {why}")
            active = True
        link = self.open_link(job["fp"])
        if link is not None and hasattr(link, "cancel_send") and link.cancel_send(jid, why):
            active = True      # the iPhone: its data channel stops at once
        if tell and link is not None and (active or job.get("attempts")):
            link.send({"t": "cancel", "id": jid})
        if not active:
            # not on its way right now: it just leaves the outbox (the delivery thread, if it's
            # about to send it, sees it was cancelled)
            self._finish(job, CANCELLED, error=why)
        log.info("mesh: %s to %s cancelled (%s)", job.get("name") or "a message", job["peer"], why)

    # --- sending: live messages (routes 1–3) --------------------------------------

    def _hub_knows(self, entry: dict) -> bool:
        hub_id = self.host.hub_id()
        return bool(hub_id and entry.get("hub") == hub_id and self.host.hub_connected())

    def _hub_has_it_live(self, entry: dict) -> bool:
        """The hub knows the peer and the peer is connected to it now: live messages can go that way."""
        return self._hub_knows(entry) and self.host.hub_online(entry["id"])

    def send_live(self, fp: str, msg: dict) -> str:
        """input, media, cmd: direct, else through the hub. Returns the route; raises NoRoute."""
        entry = self._entry(fp)
        self.may_send(fp, msg, entry)
        link = self.direct(fp)
        if link is not None and link.send(msg):
            return link.kind
        if self._hub_has_it_live(entry) and self.host.hub_send({**msg, "to": entry["id"]}):
            return "hub"
        raise NoRoute(f"{entry['name']} isn't reachable directly, and not through the hub either")

    def ring(self, fp: str, stop: bool = False) -> str:
        entry = self._entry(fp)
        self.may_send(fp, {"t": "ring"}, entry)
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
        msg = {"t": "clip", "text": text}
        self.may_send(fp, msg, entry)
        link = self.direct(fp)
        if link is not None and not getattr(link, "fits", lambda _m: True)(msg):
            raise ValueError(f"that's too much text for {entry['name']}'s web app in one go (256 KB at most)")
        if link is not None and link.send(msg):
            return link.kind
        if entry["source"] == "browser":
            raise NoRoute(f"{entry['name']} isn't connected: open droplet on it, on the same Wi-Fi")
        # the hub has no addressed clipboard message: it goes to all your devices' clipboards,
        # so not while any of them shouldn't have it
        if self._hub_has_it_live(entry) and self.hub_may_share(msg) and self.host.hub_send({"t": "clip", "text": text}):
            return "hub"
        raise NoRoute(f"{entry['name']} isn't reachable directly, and not through the hub either")

    def send_link(self, fp: str, url: str) -> dict:
        """A web link for the peer to open (docs/mesh.md §9.10): it opens on your own device, and
        waits with an Open button on someone else's. Over an open link to a peer that understands
        `link`; otherwise (not reachable, or an older peer) it goes as a chat message, which
        waits in the outbox like any other and shows there with an Open button.

        Returns {"how": "link", "route"} or {"how": "message", "job": <the text job>}."""
        entry = self._entry(fp)
        url = check_url(url)
        try:
            self.may_send(fp, {"t": "link"}, entry)
        except Refused as e:
            if e.why != "paused":
                raise
            # paused, here or there: it waits as a message, and goes on resume
            return {"how": "message", "job": self.send_text(fp, url)}
        link = self.direct(fp) if entry["source"] != "browser" else self.open_link(fp)
        if link is None or "link" not in self.features_of(fp):
            return {"how": "message", "job": self.send_text(fp, url)}
        mid = secrets.token_hex(16)
        ts = time.time()
        waiter = [threading.Event(), False, "", link.fp]
        self._acks[mid] = waiter
        try:
            if not link.send({"t": "link", "id": mid, "url": url, "ts": ts}):
                return {"how": "message", "job": self.send_text(fp, url)}
            if not waiter[0].wait(ACK_TIMEOUT):
                return {"how": "message", "job": self.send_text(fp, url)}
        finally:
            self._acks.pop(mid, None)
        if not waiter[1]:
            why = waiter[2]
            raise Refused(why.split(": ", 1)[-1] if why.startswith(("paused:", "denied:")) else why or "refused",
                          "paused" if why.startswith("paused:") else "denied", "chat", False)
        self.chat.add({"id": mid, "dir": "out", "fp": fp, "peer": entry["id"], "name": entry["name"], "body": url,
                       "ts": ts, "route": link.kind, "kind": "link"})
        log.info("mesh: link to %s delivered (%s)", entry["name"], link.kind)
        return {"how": "link", "route": link.kind}

    def rename(self, name: str) -> dict:
        """Give this device a new name: kept by the host (with a hub, the hub names it, and its
        rules apply), announced over mDNS, and told to every linked peer at once (`rename`).
        Peers not linked now hear it in the next hello."""
        name = check_name(name)
        old = self.name
        if name == old:
            return {"name": name, "told": 0}
        self.host.set_device_name(name)
        try:
            self.refresh_announcement()
        except Exception:
            log.exception("mesh: announcing the new name")
        told = 0
        with self._lock:
            fps = list(self.links)
        for fp in fps:
            link = self.open_link(fp)
            if link is not None and link.send({"t": "rename", "name": name}):
                told += 1
        log.info("mesh: this device is called %s now (was %s); told %d linked device(s)", name, old, told)
        return {"name": name, "told": told}

    def set_nickname(self, fp: str, nickname: str) -> dict:
        e = self.trust.set_nickname(fp, nickname)
        log.info("mesh: %s %s", e["name"], f"is called {e['nickname']} here" if e["nickname"] else "has no nickname")
        return e

    def _may_queue(self, fp: str, msg: dict, entry: dict):
        """Chat and files: refused at once when a switch says no; a pause only makes them wait."""
        try:
            self.may_send(fp, msg, entry)
        except Refused as e:
            if e.why != "paused":
                raise

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
        self._may_queue(fp, {"t": "text"}, entry)
        job = self.outbox.add_text(fp, entry["name"], body)
        self._kick.set()
        return job

    def send_file(self, fp: str, path: Path, cleanup: bool = False) -> dict:
        entry = self._entry(fp)
        path = Path(path).expanduser().resolve()
        if not path.is_file():
            raise ValueError(f"{path} isn't a file")
        mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        self._may_queue(fp, {"t": "offer"}, entry)
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
                    # the ones behind it wait too, for the same reason: say so, so whoever sent
                    # them hears "waiting" rather than nothing
                    head = self.outbox.get(jobs[0]["id"]) or {}
                    for j in self.outbox.for_peer(fp)[1:]:
                        if j["attempts"] == 0 and head.get("error"):
                            self.outbox.update(j["id"], attempts=1, retry=False, error=head["error"])
                    break
        except Exception:
            log.exception("mesh: delivering to %s", fp[:12])
        with self._wlock:
            self._workers.discard(fp)

    def _finish(self, job: dict, state: str, **fields):
        self.outbox.update(job["id"], state=state, **fields)
        if job["kind"] == "file":
            if self.transfers.get(job["id"]) is not None or state == CANCELLED:
                if self.transfers.get(job["id"]) is None:
                    self.transfers.start(job["id"], direction="out", fp=job["fp"], peer=job["peer"],
                                         name=job.get("name") or "", size=job.get("size") or 0)
                self.transfers.finish(job["id"], {DONE: tx.DONE, FAILED: tx.FAILED, CANCELLED: tx.CANCELLED}
                                      .get(state, tx.WAITING), fields.get("error"))
        if state in FINISHED:
            with self._lock:
                self._cancel_jobs.discard(job["id"])
        if state in FINISHED and job.get("cleanup"):
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

    def _wait_job(self, job: dict, **fields):
        """The job goes back to the queue; a transfer that had started says it's waiting."""
        self.outbox.update(job["id"], state=QUEUED, **fields)
        if job["kind"] == "file" and self.transfers.get(job["id"]) is not None:
            self.transfers.finish(job["id"], tx.WAITING, fields.get("error"))

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
        try:
            self.may_send(job["fp"], {"t": "text" if job["kind"] == "text" else "offer"}, entry)
        except Refused as e:
            if e.why == "paused":
                # paused, here or there: it waits, and goes on resume (perm, or Resume here)
                self.outbox.update(job["id"], state=QUEUED, attempts=job["attempts"] + 1, retry=False,
                                   error=f"waiting: {e}")
                return "wait"
            self._finish(job, FAILED, error=str(e))
            return "done"
        if job["id"] in self._cancel_jobs:
            self._finish(job, CANCELLED, error=job.get("error") or "cancelled")
            return "done"
        self.outbox.update(job["id"], state=SENDING, attempts=job["attempts"] + 1)
        link = self.direct(job["fp"])
        if link is None and self._just_accepted(job["fp"]):
            # it may not have heard our yes yet: look again soon rather than in RETRY_EVERY
            timer = threading.Timer(PAIR_RETRY, self._kick.set)
            timer.daemon = True
            timer.start()
        if link is not None:
            if job["kind"] == "file":
                self.transfers.start(job["id"], direction="out", fp=job["fp"], peer=job["peer"], name=job["name"],
                                     size=job["size"], route=link.kind)
            if job["kind"] == "text":
                got = self._direct_text(link, job)
            elif hasattr(link, "send_file"):
                got = link.send_file(job)    # a browser: the file goes over its data channel
            else:
                got = self._direct_file(link, job)
            if got.startswith("cancelled") or job["id"] in self._cancel_jobs:
                why = got.split(": ", 1)[1] if got.startswith("cancelled: ") else "cancelled"
                self._finish(job, CANCELLED, error=why)
                return "done"
            if got == "ok":
                self._finish(job, DONE, route=link.kind, error=None)
                return "done"
            if got.startswith("paused:"):
                # the peer paused sharing with us: wait for its perm saying it resumed
                self._wait_job(job, error=f"waiting: {got[len('paused:'):].strip()}", retry=False)
                with self._lock:
                    rp = self.remote_perm.setdefault(job["fp"], {"paused": True, "allow": {}})
                    rp["paused"] = True
                return "wait"
            if got.startswith("refused:"):
                self._finish(job, FAILED, error=got[len("refused:"):].strip() or "the peer refused it")
                return "done"
            # the peer is there but it didn't finish: try again soon, directly
            self._wait_job(job, error=got, retry=True)
            threading.Timer(3, self._kick.set).start()
            return "wait"
        if self._hub_knows(entry):
            try:
                if job["kind"] == "text":
                    self.host.hub_text(entry["id"], job["body"])
                else:
                    # through the hub there are no byte counts: it shows as going, then sent
                    self.transfers.start(job["id"], direction="out", fp=job["fp"], peer=job["peer"],
                                         name=job["name"], size=job["size"], route="hub")
                    self.host.hub_upload(entry["id"], Path(job["path"]), job["name"], job["mime"])
                route = "hub" if self.host.hub_online(entry["id"]) else "hub-mailbox"
                self._finish(job, DONE, route=route, error=None)
                return "done"
            except Exception as e:
                log.info("mesh: through the hub failed: %s", e)
                err = f"the hub: {e}"
        else:
            err = "not reachable directly, and no hub knows it right now"
        self._wait_job(job, error=err, retry=False)
        return "wait"

    def _direct_text(self, link: Link, job: dict) -> str:
        waiter = [threading.Event(), False, "", link.fp]
        self._acks[job["id"]] = waiter
        try:
            if not link.send({"t": "text", "id": job["id"], "body": job["body"], "ts": job["created"]}):
                return "the link dropped"
            if not waiter[0].wait(ACK_TIMEOUT):
                return "no answer"
            if not waiter[1] and waiter[2].startswith("paused:"):
                return waiter[2]
            return "ok" if waiter[1] else f"refused: {waiter[2]}"
        finally:
            self._acks.pop(job["id"], None)

    def _direct_file(self, link: Link, job: dict) -> str:
        offer = Offer(job["id"], link.fp, job["name"], job["size"], job["mime"],
                      opener=lambda: open(job["path"], "rb"), check=lambda: self._file_changed(job))
        offer.on_progress = lambda n: self.transfers.progress(job["id"], n)
        self.offers.add(offer)
        try:
            if job["id"] in self._cancel_jobs:
                return "cancelled"
            if not link.send(offer.message()):
                return "the link dropped"
            while not offer.done.wait(1):
                if self.stop.is_set():
                    return "stopping"
                idle = time.monotonic() - offer.last_activity
                if idle > STALL or (link.closed and idle > 5):
                    # gone quiet, or the peer went away: offer it again later, and it resumes
                    return f"stopped at {offer.sent} bytes sent"
                if self.trust.get(link.fp) is None:
                    return "refused: not trusted any more"
            ok, error = offer.result or (False, "")
            if not ok and (error.startswith("paused:") or error.startswith("cancelled")):
                return error
            return "ok" if ok else f"refused: {error}"
        finally:
            self.offers.remove(job["id"])

    def serve_file(self, sock, oid: str, fp: str, req, send_head):
        try:
            self.may_send(fp, {"t": "offer"})
        except Refused as e:
            if e.local:
                send_head(403, {"Content-Length": "0"})   # paused (or switched off) since it was offered
                return
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

    def _just_accepted(self, fp: str) -> bool:
        with self._lock:
            at = self._accepted.get(fp)
            if at is not None and time.monotonic() - at > PAIR_GRACE:
                del self._accepted[fp]
                at = None
        return at is not None

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

    def pair_confirm(self, rid: str, yes: bool, relation: str = "own") -> dict:
        """The owner here says the codes match (or not), and whether the other device is theirs
        ("own") or someone else's ("other"), which sets what it may do (perms.py)."""
        og = self.outgoing.get(rid)
        if og is None:
            raise ValueError("no such pairing request")
        if not yes:
            og.cancel()
            og.state = CANCELLED
            return {"state": og.state}
        if relation not in perms.RELATIONS:
            raise ValueError("relation must be \"own\" or \"other\"")
        og.relation = relation
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
                                       tailnet_ip=og.host if is_tailnet(og.host) else None,
                                       relation=getattr(og, "relation", "own"))
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

    def pair_answer(self, rid: str, accept: bool, relation: str = "own") -> dict:
        """The owner's answer to a device asking to pair: and, accepting, whether it's theirs
        ("own") or someone else's ("other")."""
        if accept and relation not in perms.RELATIONS:
            raise ValueError("relation must be \"own\" or \"other\"")
        r = self.incoming.answer(rid, accept)
        if r is None:
            raise ValueError("no such pairing request waiting (it may have expired)")
        self.desktop.close_notification(f"pair-{rid}")
        if accept and r.get("kind") == "browser":
            self.trust.add_browser(make_browser_entry(name=r["name"], key=r["der"], os_name=r["os"] or "ios",
                                                      relation=relation))
            log.info("mesh: paired with %s (%s), a browser, %s", r["name"], r["fp"], relation)
        elif accept:
            self.trust.add_paired(make_entry(peer_id=r["id"], name=r["name"], cert_pem=der_to_pem(r["der"]),
                                             source="paired", os_name=r["os"], relation=relation))
            with self._lock:
                self._accepted[r["fp"]] = time.monotonic()
            log.info("mesh: paired with %s (%s), %s", r["name"], r["fp"],
                     "your own device" if relation == "own" else "someone else's")
        else:
            log.info("mesh: refused to pair with %s", r["name"])
        return {"state": ACCEPTED if accept else DENIED, "name": r["name"], "fp": r["fp"],
                **({"relation": relation} if accept else {})}

    def unpair(self, fp: str) -> dict:
        entry = self._entry(fp)
        if entry["source"] not in ("paired", "browser"):
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
            peers.append({k: e[k] for k in ("id", "name", "fp", "source", "lan", "port", "tailnet_ip", "os", "hub",
                                            "relation", "allow", "paused")}
                         | {"nickname": e.get("nickname") or "", "features": self.features_of(e["fp"]) if link else []}
                         | {"link": f"{link.kind} {link.address}" if link else None,
                            "on_lan": any(s.fp == e["fp"] for s in seen),
                            # what it said about how it treats this device (a hint), and its last no
                            "remote": self.remote_perm.get(e["fp"]) if link else None,
                            "refused": self.refusals.get(e["fp"])})
        trusted = {e["fp"] for e in peers}
        return {
            "id": self.peer_id, "name": self.name, "fp": self.identity.fp, "port": self.port,
            "paused_all": self.paused_all,
            "capabilities": list(perms.CAPABILITIES),
            "peers": peers,
            "nearby": [{"id": s.id, "name": s.name, "fp": s.fp, "os": s.os, "addresses": s.addresses,
                        "port": s.port} for s in seen if s.fp not in trusted],
            "incoming": self.incoming.waiting(),
            "outbox": [{k: j.get(k) for k in ("id", "kind", "peer", "fp", "state", "error", "name", "attempts")}
                       for j in self.outbox.queued()],
            "transfers": self.transfers.list(),
            "refused": self.server.refused if self.server else 0,
            "caps": self.host.mesh_caps(),   # what this device offers right now
            "webrtc": self.webrtc.status() if self.webrtc is not None else None,
            "webrtc_off": self.webrtc_off if self.webrtc is None else None,
        }

    def chat_history(self, fp: str | None = None, n: int = 100) -> list[dict]:
        """Messages to and from peers, oldest first: those delivered or received, then the ones
        still waiting to go (state "queued" or "sending") and the ones that failed."""
        n = max(1, min(int(n), MAX_CHAT))
        out = []
        for m in self.chat.recent(n, fp):
            out.append({k: m.get(k) for k in ("id", "dir", "fp", "peer", "name", "body", "ts", "route")}
                       | {"state": "sent" if m.get("dir") == "out" else "received", "kind": m.get("kind") or "text"}
                       | ({"opened": True} if m.get("opened") else {}))
        jobs = [j for j in self.outbox.queued() + self.outbox.failed()
                if j["kind"] == "text" and (fp is None or j["fp"] == fp)]
        for j in jobs:
            entry = self.trust.get(j["fp"]) or {}
            out.append({"id": j["id"], "dir": "out", "fp": j["fp"], "peer": entry.get("id"),
                        "name": entry.get("name") or j["peer"], "body": j["body"], "ts": j["created"],
                        "route": None, "state": j["state"], "why": j.get("error"), "kind": "text"})
        out.sort(key=lambda m: m["ts"] if isinstance(m["ts"], (int, float)) else 0)
        out = out[-n:]
        # one answer is one line, and the CLI reads at most MAX_LINE of it
        while len(out) > 1 and len(json.dumps(out)) > MAX_ANSWER:
            out.pop(0)
        return out

    def received(self, n: int = 50) -> dict:
        """The files received directly, newest first, and where they're saved."""
        n = max(1, min(int(n), 200))
        files = []
        for x in self.completed.recent(n):
            entry = self.trust.get(x.get("fp")) or {}
            path = Path(str(x.get("path") or ""))
            try:
                size = path.stat().st_size if path.is_file() else None
            except OSError:
                size = None
            files.append({"name": path.name, "path": str(path), "fp": x.get("fp"), "from": entry.get("name"),
                          "ts": x.get("ts"), "size": size, "exists": size is not None})
        return {"folder": str(self.downloads), "files": files}

    def handle_control(self, req: dict) -> dict:
        cmd = req.get("cmd")
        try:
            if cmd == "status":
                return self.status()
            if cmd == "pair-start":
                return self.pair_start(str(req.get("target") or ""))
            if cmd == "pair-confirm":
                return self.pair_confirm(str(req.get("request")), bool(req.get("yes")),
                                         str(req.get("relation") or "own"))
            if cmd == "pair-status":
                og = self.outgoing.get(str(req.get("request")))
                return {"state": og.state if og else EXPIRED}
            if cmd == "pair-answer":
                return self.pair_answer(str(req.get("request")), bool(req.get("accept")),
                                        str(req.get("relation") or "own"))
            if cmd == "perm-set":
                entry = self.resolve(str(req.get("peer") or ""))
                allow = req.get("allow")
                if req.get("capability") is not None:
                    allow = {str(req["capability"]): req.get("on")}
                e = self.set_perms(entry["fp"], relation=req.get("relation"), allow=allow)
                return {k: e[k] for k in ("name", "fp", "relation", "allow", "paused")}
            if cmd in ("pause", "resume"):
                on = cmd == "pause"
                if req.get("all"):
                    self.pause_everything(on)
                    return {"paused_all": self.paused_all}
                entry = self.resolve(str(req.get("peer") or ""))
                e = self.set_perms(entry["fp"], paused=on)
                return {k: e[k] for k in ("name", "fp", "relation", "allow", "paused")} | {"paused_all": self.paused_all}
            if cmd == "unpair":
                return self.unpair(self.resolve(str(req.get("peer") or ""))["fp"])
            if cmd in ("text", "send-file"):
                entry = self.resolve(str(req.get("peer") or ""))
                job = (self.send_text(entry["fp"], req.get("body")) if cmd == "text"
                       else self.send_file(entry["fp"], Path(str(req.get("path") or ""))))
                return self._job_answer(job["id"], float(req.get("wait") or 0))
            if cmd == "job":
                return self._job_answer(str(req.get("id")), float(req.get("wait") or 0))
            if cmd == "transfers":
                return {"transfers": self.transfers.list(),
                        "queued": [{k: j.get(k) for k in ("id", "kind", "peer", "fp", "state", "error", "name", "size")}
                                   for j in self.outbox.queued()]}
            if cmd == "cancel":
                return self.cancel(str(req.get("id") or ""))
            if cmd == "link":
                entry = self.resolve(str(req.get("peer") or ""))
                out = self.send_link(entry["fp"], req.get("url"))
                if out["how"] == "message":
                    job = self._job_answer(out["job"]["id"], float(req.get("wait") or 0))
                    return {"how": "message", **job}
                return out
            if cmd == "rename":
                return self.rename(req.get("name"))
            if cmd == "nickname":
                entry = self.resolve(str(req.get("peer") or ""))
                e = self.set_nickname(entry["fp"], req.get("nickname") or "")
                return {k: e[k] for k in ("name", "fp", "nickname")}
            if cmd == "ring":
                return {"route": self.ring(self.resolve(str(req.get("peer") or ""))["fp"], bool(req.get("stop")))}
            if cmd == "clip":
                return {"route": self.clip(self.resolve(str(req.get("peer") or ""))["fp"], req.get("text"))}
            if cmd == "chat":
                fp = self.resolve(str(req["peer"]))["fp"] if req.get("peer") else None
                return {"messages": self.chat_history(fp, req.get("n") or 100)}
            if cmd == "received":
                return self.received(req.get("n") or 50)
            if cmd == "send":
                msg = req.get("msg")
                if not isinstance(msg, dict) or msg.get("t") not in LIVE:
                    raise ValueError(f"only {', '.join(LIVE)} messages can be sent this way")
                return {"route": self.send_live(self.resolve(str(req.get("peer") or ""))["fp"], msg)}
            if cmd in self.control_ext:
                return self.control_ext[cmd](req)
            if cmd == "qr":
                off = self.webrtc_off or {}
                if off.get("why") == "missing":
                    return {"error": "This computer can't pair an iPhone yet: the iPhone link isn't installed. "
                                     "Run droplet-agent doctor in a terminal: it installs it (a small download)."}
                if off.get("why") in ("failed", "broken"):
                    return {"error": f"{off['text'][:1].upper()}{off['text'][1:]}. droplet-agent doctor may say more."}
                if not off:
                    return {"error": "The iPhone link is starting. Try again in a moment."}
                return {"error": "The iPhone link is switched off on this computer. Turn it on with "
                                 "\"iphone\": {\"enabled\": true} in the config, and restart the agent."}
            return {"error": f"unknown command {cmd!r}"}
        except (ValueError, TypeError, NoRoute) as e:
            return {"error": str(e)}

    def _job_answer(self, jid: str, wait: float) -> dict:
        """The job, once it's delivered, failed, or found no route (or `wait` seconds passed).

        A transfer that stopped part-way and is being retried isn't an answer yet.
        """
        job = self.outbox.wait(jid, lambda j: j["state"] in FINISHED
                               or (j["state"] == QUEUED and j["attempts"] > 0 and not j.get("retry")),
                               min(wait, 3600))
        if job is None:
            return {"error": "no such job"}
        # "error" in an answer means the command failed, so the job's own problem is "why"
        out = {k: job.get(k) for k in ("id", "kind", "peer", "state", "route", "attempts", "name")}
        out["why"] = job.get("error")
        return out
