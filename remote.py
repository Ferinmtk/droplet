"""Live connections, so devices can control each other in real time.

Every device (a browser tab, or a native helper: the Linux agent, the Windows
and Android apps) can hold a WebSocket to the hub at /ws. Helpers announce
what they can do ("caps"): take mouse and keyboard input, control media,
lock, take screenshots, sync the clipboard, read SMS, browse files. The hub
relays between devices. It never acts on anything itself.

The message format is documented in docs/remote.md.
"""

import json
import secrets
import threading
import time
from urllib.parse import urlsplit

from flask import abort, jsonify, request

CAPS = {"input", "media", "lock", "screenshot", "clipboard", "sms", "files"}
MAX_MESSAGE = 512 * 1024  # a chunk of SMS or a folder listing fits easily
RPC_TIMEOUT = 30

# what the target needs to be able to do for each kind of routed message
NEEDS = {"input": "input", "media": "media"}
CMD_NEEDS = {"lock": "lock", "screenshot": "screenshot"}
RPC_NEEDS = {"sms": "sms", "files": "files", "media": "media"}


class Conn:
    def __init__(self, ws, device: dict, caps, platform: str, app: str):
        self.id = secrets.token_hex(6)
        self.ws = ws
        self.device_id = device["id"]
        self.device_name = device["name"]
        self.caps = {c for c in caps if c in CAPS}
        self.platform = platform[:20]
        self.app = app[:60]
        self.since = time.time()
        self._send_lock = threading.Lock()

    def send(self, msg: dict) -> bool:
        try:
            with self._send_lock:  # simple-websocket isn't safe for concurrent sends
                self.ws.send(json.dumps(msg))
            return True
        except Exception:
            return False


class Hub:
    def __init__(self):
        self._lock = threading.Lock()
        self.conns: dict[str, Conn] = {}
        self.pending: dict[str, tuple[str, float]] = {}   # rpc id → (requester conn id, expiry)
        self.state: dict[str, dict[str, dict]] = {}        # device id → kind → latest data
        self._last_clip = ""

    # --- bookkeeping ---------------------------------------------------------

    def add(self, conn: Conn):
        with self._lock:
            self.conns[conn.id] = conn
        self.presence()

    def remove(self, conn: Conn):
        with self._lock:
            self.conns.pop(conn.id, None)
            still_here = any(c.device_id == conn.device_id and c.caps for c in self.conns.values())
            if not still_here:
                # a helper that left can't vouch for its old media state any more
                self.state.pop(conn.device_id, None)
        self.presence()

    def all(self) -> list[Conn]:
        with self._lock:
            return list(self.conns.values())

    def target(self, device_id: str, cap: str) -> Conn | None:
        """The connection that acts for a device: the newest one with the capability."""
        found = [c for c in self.all() if c.device_id == device_id and cap in c.caps]
        return max(found, key=lambda c: c.since) if found else None

    def caps(self, device_id: str) -> set[str]:
        out: set[str] = set()
        for c in self.all():
            if c.device_id == device_id:
                out |= c.caps
        return out

    def summary(self) -> dict[str, dict]:
        out: dict[str, dict] = {}
        for c in self.all():
            entry = out.setdefault(c.device_id, {"caps": set(), "apps": []})
            entry["caps"] |= c.caps
            if c.caps:
                entry["apps"].append({"platform": c.platform, "app": c.app})
        return {k: {"caps": sorted(v["caps"]), "apps": v["apps"]} for k, v in out.items()}

    def broadcast(self, msg: dict, *, skip_device: str | None = None, need: str | None = None):
        for c in self.all():
            if c.device_id == skip_device or (need and need not in c.caps):
                continue
            c.send(msg)

    def presence(self):
        self.broadcast({"t": "presence", "devices": self.summary()})

    # --- routing -------------------------------------------------------------

    def handle(self, conn: Conn, msg: dict, devices):
        t = msg.get("t")
        if t == "ping":
            conn.send({"t": "pong"})
            return
        sender = {"id": conn.device_id, "name": conn.device_name}

        if t in ("input", "media", "cmd"):
            to = str(msg.get("to") or "")
            if t == "cmd":
                need = CMD_NEEDS.get(str(msg.get("cmd")))
                if need is None:
                    return self.fail(conn, msg, "unknown command")
            else:
                need = NEEDS[t]
            target = self.target(to, need)
            if target is None:
                name = (devices.get(to) or {}).get("name", "That device")
                return self.fail(conn, msg, f"{name} isn't connected for this. Is its droplet app running?")
            out = {k: v for k, v in msg.items() if k != "to"}
            out["from"] = sender
            if not target.send(out):
                self.fail(conn, msg, "Couldn't reach it. Try again.")
            return

        if t == "rpc":
            rid = str(msg.get("id") or "")[:40]
            method = str(msg.get("method") or "")
            need = RPC_NEEDS.get(method.split(".", 1)[0])
            target = self.target(str(msg.get("to") or ""), need) if need else None
            if not rid or target is None:
                conn.send({"t": "rpc-result", "id": rid, "error": "Not available on that device right now."})
                return
            key = f"{conn.id}:{rid}"
            with self._lock:
                now = time.time()
                self.pending = {k: v for k, v in self.pending.items() if v[1] > now}
                self.pending[key] = (conn.id, now + RPC_TIMEOUT)
            target.send({"t": "rpc", "id": key, "method": method,
                         "params": msg.get("params") or {}, "from": sender})
            return

        if t == "rpc-result":
            key = str(msg.get("id") or "")
            with self._lock:
                entry = self.pending.pop(key, None)
                requester = self.conns.get(entry[0]) if entry else None
            if requester is not None:
                out = {k: v for k, v in msg.items() if k in ("result", "error")}
                requester.send({"t": "rpc-result", "id": key.split(":", 1)[1], **out})
            return

        if t == "state" and conn.caps:
            kind = str(msg.get("kind") or "")[:20]
            data = msg.get("data")
            with self._lock:
                self.state.setdefault(conn.device_id, {})[kind] = data
            self.broadcast({"t": "state", "device": conn.device_id, "kind": kind, "data": data})
            return

        if t == "clip" and "clipboard" in conn.caps:
            text = msg.get("text")
            if not isinstance(text, str) or not text or len(text) > 256 * 1024 or text == self._last_clip:
                return  # the echo from a device we just updated
            self._last_clip = text
            self.broadcast({"t": "clip", "text": text, "from": sender},
                           skip_device=conn.device_id, need="clipboard")
            return

        conn.send({"t": "error", "error": f"unknown message type {t!r}"})

    @staticmethod
    def fail(conn: Conn, msg: dict, error: str):
        conn.send({"t": "error", "re": msg.get("t"), "to": msg.get("to"), "error": error})


hub = Hub()


def register(ctx):
    from flask_sock import Sock

    app = ctx.app
    # server-side pings keep idle connections alive through proxies and
    # notice dead ones (a phone that left Wi-Fi) within a minute
    app.config.setdefault("SOCK_SERVER_OPTIONS", {"ping_interval": 25})
    sock = Sock(app)

    def listing_extra():
        # a device with a live connection is online, even when nothing on it
        # polls /api/files (a headless machine's agent, the Windows app alone)
        return {k: {**v, "online": True} for k, v in hub.summary().items()}

    ctx.presence_hooks.append(listing_extra)

    @sock.route("/ws")
    def ws(ws):
        # a WebSocket handshake is a GET, so the cross-site guard for writes
        # doesn't cover it; check Origin here (native helpers send none)
        origin = request.headers.get("Origin")
        if origin is not None and urlsplit(origin).netloc != request.host:
            ws.close(reason=1008, message="cross-site")
            return
        device = ctx.current_device()
        if device is None:
            ws.close(reason=1008, message="name this device first")
            return
        try:
            hello = json.loads(ws.receive(timeout=15) or "{}")
        except (ValueError, TypeError):
            hello = {}
        if hello.get("t") != "hello":
            ws.close(reason=1002, message="expected hello")
            return
        conn = Conn(ws, device, hello.get("caps") or [], str(hello.get("platform") or "web"),
                    str(hello.get("app") or "browser"))
        # welcome first, so the client's first frame is always the welcome;
        # the presence broadcast from add() follows it
        summary = hub.summary()
        if conn.caps:
            summary.setdefault(device["id"], {"caps": [], "apps": []})
            summary[device["id"]] = {
                "caps": sorted(set(summary[device["id"]]["caps"]) | conn.caps),
                "apps": summary[device["id"]]["apps"] + [{"platform": conn.platform, "app": conn.app}],
            }
        conn.send({"t": "welcome", "conn": conn.id, "device": {"id": device["id"], "name": device["name"]},
                   "devices": summary, "state": hub.state})
        hub.add(conn)
        try:
            while True:
                raw = ws.receive()
                if raw is None:
                    break
                if len(raw) > MAX_MESSAGE:
                    conn.send({"t": "error", "error": "message too large"})
                    continue
                try:
                    msg = json.loads(raw)
                except ValueError:
                    continue
                if isinstance(msg, dict):
                    hub.handle(conn, msg, ctx.devices)
        except Exception:
            pass  # the socket closed under us; clean up below
        finally:
            hub.remove(conn)

    @app.route("/api/remote/presence")
    def remote_presence():
        if ctx.current_device() is None:
            abort(400)
        return jsonify({"devices": hub.summary(), "state": hub.state})
