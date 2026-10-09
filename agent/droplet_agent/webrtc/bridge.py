"""A connected browser as a mesh peer.

Once a browser has proved who it is, it's a link like any other in the mesh
node (`BrowserLink`): chat to it and from it goes through the node's chat
and outbox, files it sends land in the downloads folder, `droplet-agent
text`/`send-file` reach it, and it shows in `droplet-agent peers`. A
browser is never dialled: things for it wait in the outbox until it
connects (when the web app is open), and go then.
"""

from __future__ import annotations

import asyncio
import concurrent.futures
import logging
import secrets
import sys
import threading
import time
from pathlib import Path

from . import DEFAULT_PORT
from .protocol import Conn, Host, fits
from . import qr

log = logging.getLogger("droplet_agent.webrtc")

APP_URL = "https://droplet.noxeratech.com/app/"
TOKEN_TTL = 600     # a QR code lets a browser ask to pair for this long


class BrowserLink:
    """What the mesh node sees of a browser's connection: the interface of mesh.wslink.Link it uses."""

    kind = "webrtc"
    outbound = False
    port = None
    keep = True

    def __init__(self, bridge: "Bridge", conn: Conn):
        self.bridge, self.conn = bridge, conn
        self.fp, self.address = conn.fp, conn.address
        self.ready = threading.Event()
        self.ready.set()
        self.closed = False
        self.opened = self.last_used = time.monotonic()
        self.hello = {"name": conn.name, "os": "ios"}

    def send(self, msg: dict) -> bool:
        if self.closed or not fits(msg):
            return False
        self.last_used = time.monotonic()
        self.bridge.listener.call(self.conn.send, msg)
        return True

    @staticmethod
    def fits(msg: dict) -> bool:
        """Whether msg fits in one frame of the channel (a large clipboard may not)."""
        return fits(msg)

    def close(self, code: int = 1000, reason: str = ""):
        if not self.closed:
            self.bridge.listener.call(self.conn.close)

    def send_file(self, job: dict) -> str:
        """Called by the node's delivery thread. "ok", "refused: …", or why it stopped."""
        if self.closed:
            return "the link dropped"
        self.last_used = time.monotonic()
        fut = asyncio.run_coroutine_threadsafe(
            self.conn.send_file(job["id"], job["name"], job["size"], job["mime"], Path(job["path"])),
            self.bridge.listener.loop)
        try:
            ok, why = fut.result()
        except Exception as e:
            return f"stopped: {e}"
        return "ok" if ok else why


class Bridge(Host):
    def __init__(self, node, *, port: int | None = None, app_url: str = APP_URL, listener_factory=None):
        from .transport import Listener
        self.node = node
        self.app_url = app_url or APP_URL
        self._tokens: dict[str, float] = {}
        self._lock = threading.Lock()
        self._worker = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix="webrtc-deliver")
        self.conns: set[Conn] = set()
        factory = listener_factory or Listener
        self.listener = factory(node.identity.cert_path, node.identity.key_path, self._on_channel,
                                port=port or node.port or DEFAULT_PORT)

    # --- life ---------------------------------------------------------------------
    def start(self):
        self.listener.start()
        self.node.webrtc = self
        self.node.control_ext["qr"] = self.control_qr

    def close(self):
        self.listener.close()
        self._worker.shutdown(wait=False)

    def status(self) -> dict:
        with self._lock:
            now = time.monotonic()
            showing = any(t > now for t in self._tokens.values())
        return {"port": self.listener.port, "fp": self.node.identity.fp,
                "connected": sorted(c.name for c in list(self.conns) if c.fp), "qr_showing": showing}

    # --- the QR code ---------------------------------------------------------------------
    def addresses(self) -> list[str]:
        from ..mesh.node import is_tailnet
        found = list(self.node.local_addresses() or [])
        # LAN first; a tailnet address last, for an iPhone on Tailscale away from home
        return [a for a in found if not is_tailnet(a)][:3] + [a for a in found if is_tailnet(a)][:1]

    def new_token(self) -> str:
        token = qr.b64url(secrets.token_bytes(16))
        now = time.monotonic()
        with self._lock:
            self._tokens = {t: exp for t, exp in self._tokens.items() if exp > now}
            self._tokens[token] = now + TOKEN_TTL
        return token

    def control_qr(self, req: dict) -> dict:
        addresses = self.addresses()
        if not addresses:
            raise ValueError("this computer has no network address an iPhone could reach")
        data = qr.payload(name=self.node.name, peer_id=self.node.peer_id, fp=self.node.identity.fp,
                          addresses=addresses, port=self.listener.port, token=self.new_token())
        return {"link": qr.link(self.app_url, data), "payload": data, "expires_in": TOKEN_TTL,
                "port": self.listener.port, "fp": self.node.identity.fp, "addresses": addresses}

    # --- Host: what a connection asks --------------------------------------------------------
    def server_info(self) -> dict:
        return {"id": self.node.peer_id, "name": self.node.name, "fp": self.node.identity.fp}

    def browser(self, fp: str) -> dict | None:
        e = self.node.trust.get(fp)
        return e if e and e["source"] == "browser" else None

    def token_ok(self, token) -> bool:
        if not isinstance(token, str):
            return False
        with self._lock:
            exp = self._tokens.get(token)
            return exp is not None and exp > time.monotonic()

    def pair_open(self, body: dict):
        body = {k: body.get(k) for k in ("key", "name", "os", "commit")}
        body["os"] = body.get("os") or "ios"
        return self.node.incoming.open(body, self.node.peer_id, self.node.name, browser=True)

    def pair_confirm(self, rid: str, body: dict):
        return self.node.incoming.confirm(rid, {k: body.get(k) for k in ("nonce", "sig")})

    def pair_status(self, rid: str) -> str:
        return self.node.incoming.status(rid)[1].get("state", "expired")

    def pair_cancel(self, rid: str):
        self.node.incoming.cancel(rid)

    def authenticated(self, conn: Conn):
        conn.link = BrowserLink(self, conn)
        self.node._add_link(conn.link)
        self.node._kick.set()      # anything waiting in the outbox for it goes now

    def deliver(self, conn: Conn, msg: dict):
        link = getattr(conn, "link", None)
        if link is not None:
            # in order, and off the loop: the node may write to disk or show a notification
            self._worker.submit(self._dispatch, link, msg)

    def _dispatch(self, link: BrowserLink, msg: dict):
        try:
            self.node._on_message(link, msg)
        except Exception:
            log.exception("webrtc: handling %s", msg.get("t"))

    def received(self, conn: Conn, fid: str, path: Path):
        self.node.completed.add(conn.fp, fid, str(path))
        log.info("webrtc: saved %s from %s", path, conn.name)
        self._worker.submit(self.node.desktop.notify, f"{conn.name} sent a file", path.name, key=f"file-{fid}")

    def already_received(self, conn: Conn, fid: str) -> bool:
        return self.node.completed.has(conn.fp, fid) is not None

    def downloads(self) -> Path:
        return self.node.downloads

    def closed(self, conn: Conn):
        self.conns.discard(conn)
        link = getattr(conn, "link", None)
        if link is not None and not link.closed:
            link.closed = True
            self.node._on_close(link)
            log.info("webrtc: %s disconnected", conn.name)

    # --- the transport -------------------------------------------------------------------------
    def _on_channel(self, session):
        conn = Conn(self, session.channel, dtls_fp=session.remote_fp, address=session.peer_address)
        self.conns.add(conn)
        session.channel.on("message", conn.message)
        session.on_close.append(lambda _s: conn.close())
        conn.opened()


def start_bridge(node, cfg: dict) -> Bridge | None:
    """Start the iPhone link, unless the config turns it off (`iphone.enabled`: false).

    Never raises. When it doesn't start, one line in the log says why, and so does
    `node.webrtc_off` ({"why": "off" | "missing" | "broken" | "failed", "text": ...}), which the
    agent's status carries for `droplet-agent status`, doctor, the tray and the window.
    """
    m = cfg.get("iphone") or {}
    if m.get("enabled") is False:
        node.webrtc_off = {"why": "off", "text": "the iPhone link is switched off in the config "
                                                 "(\"iphone\": {\"enabled\": false})"}
        log.info("iphone: switched off in the config")
        return None
    from .deps import available
    ok, why = available()
    if not ok:
        node.webrtc_off = {"why": "missing", "text": why}
        log.warning("iphone: %s; the agent runs without it (droplet-agent doctor)", why)
        return None
    try:
        b = Bridge(node, port=m.get("port") if isinstance(m.get("port"), int) else None,
                   app_url=m.get("app_url") if isinstance(m.get("app_url"), str) else APP_URL)
        b.start()
    except ImportError as e:
        # installed, but it won't load (a broken or too old aiortc, say)
        from .deps import AIORTC
        node.webrtc_off = {"why": "broken", "text": f"the iPhone link can't load ({e}). Reinstall it with: "
                                                     f"{sys.executable} -m pip install --no-deps --force-reinstall "
                                                     f"'{AIORTC}'"}
        log.warning("iphone: %s", node.webrtc_off["text"])
        return None
    except Exception as e:
        node.webrtc_off = {"why": "failed", "text": f"the iPhone link couldn't start: {e}"}
        log.error("iphone: the iPhone link couldn't start: %s", e)
        return None
    node.webrtc_off = None
    return b
