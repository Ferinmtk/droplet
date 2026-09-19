"""A WebSocket over an already established TLS socket, both ends.

Built on websockets' Sans-I/O layer (ClientProtocol / ServerProtocol), so the
TLS handshake, the fingerprint check and the HTTP routing happen first, on a
socket we control, and the WebSocket framing is still websockets' own.

Frames are JSON text, at most 1 MiB. Either side pings after 20 s of
silence, and gives up after 60 s without hearing anything.
"""

from __future__ import annotations

import json
import logging
import socket
import threading
import time

from websockets.client import ClientProtocol
from websockets.frames import Frame, Opcode
from websockets.http11 import Request, Response
from websockets.protocol import State
from websockets.server import ServerProtocol
from websockets.uri import parse_uri

log = logging.getLogger("droplet_agent.mesh.link")

MAX_FRAME = 1024 * 1024
IDLE_PING = 20
DEAD_AFTER = 60
HANDSHAKE_TIMEOUT = 10


class LinkError(Exception):
    pass


def _flush(sock, proto) -> bool:
    """Send what the protocol has queued. False once it asks for the write side to close."""
    for chunk in proto.data_to_send():
        if chunk:
            sock.sendall(chunk)
        else:
            return False
    return True


def client_handshake(sock, host: str, port: int, user_agent: str, timeout: float = HANDSHAKE_TIMEOUT):
    """Upgrade to a WebSocket at /mesh. Returns (protocol, frames already received)."""
    h = f"[{host}]" if ":" in host and not host.startswith("[") else host
    proto = ClientProtocol(parse_uri(f"wss://{h}:{int(port)}/mesh"), max_size=MAX_FRAME)
    req = proto.connect()
    req.headers["User-Agent"] = user_agent
    proto.send_request(req)
    sock.settimeout(timeout)
    _flush(sock, proto)
    end = time.monotonic() + timeout
    while True:
        if time.monotonic() > end:
            raise LinkError("no WebSocket answer in time")
        data = sock.recv(65536)
        if not data:
            proto.receive_eof()
            raise LinkError("closed during the WebSocket handshake")
        proto.receive_data(data)
        events = proto.events_received()
        if events and isinstance(events[0], Response):
            if proto.handshake_exc is not None or proto.state is not State.OPEN:
                raise LinkError(f"WebSocket refused: {events[0].status_code} {proto.handshake_exc or ''}".strip())
            return proto, [e for e in events[1:] if isinstance(e, Frame)]


def server_handshake(sock, raw_head: bytes, server_header: str):
    """Accept a WebSocket whose request head (and anything after it) is `raw_head`."""
    proto = ServerProtocol(max_size=MAX_FRAME)
    proto.receive_data(raw_head)
    events = proto.events_received()
    if not events or not isinstance(events[0], Request):
        raise LinkError("not a WebSocket request")
    resp = proto.accept(events[0])
    resp.headers["Server"] = server_header
    proto.send_response(resp)
    _flush(sock, proto)
    if resp.status_code != 101 or proto.state is not State.OPEN:
        raise LinkError(f"bad WebSocket request ({resp.status_code})")
    return proto, [e for e in events[1:] if isinstance(e, Frame)]


class Link:
    """One open WebSocket with a peer whose certificate is already checked.

    `on_message(link, dict)` is called on the link's reader thread for each
    JSON object received; `on_close(link)` once, when it ends.
    """

    def __init__(self, sock, proto, *, fp: str, address: str, outbound: bool, on_message, on_close,
                 pending_frames=()):
        self.sock = sock
        self.proto = proto
        self.fp = fp
        self.address = address
        self.outbound = outbound
        self.on_message = on_message
        self.on_close = on_close
        self.hello: dict = {}          # what the peer said about itself
        self.ready = threading.Event() # set once hello/welcome went both ways
        self.kind = "lan"              # "lan" or "tailnet": the route, for reporting
        self.port: int | None = None   # the peer's mesh port, when we dialled it
        self.opened = time.monotonic()
        self.last_used = time.monotonic()
        self._lock = threading.Lock()
        self._closed = threading.Event()
        self._parts: list[bytes] = []
        self._pending = list(pending_frames)
        self._last_rx = time.monotonic()

    @property
    def closed(self) -> bool:
        return self._closed.is_set()

    def start(self):
        threading.Thread(target=self._reader, name=f"mesh-link-{self.fp[:8]}", daemon=True).start()

    def send(self, msg: dict) -> bool:
        if self.closed:
            return False
        data = json.dumps(msg, separators=(",", ":")).encode()
        if len(data) > MAX_FRAME:
            log.warning("not sending a %s message of %d bytes: over the frame limit", msg.get("t"), len(data))
            return False
        try:
            with self._lock:
                self.proto.send_text(data)
                _flush(self.sock, self.proto)
            self.last_used = time.monotonic()
            return True
        except Exception as e:
            log.debug("sending to %s failed: %s", self.address, e)
            self.close()
            return False

    def close(self, code: int = 1000, reason: str = ""):
        if self._closed.is_set():
            return
        try:
            with self._lock:
                if self.proto.state is State.OPEN:
                    self.proto.send_close(code, reason)
                    _flush(self.sock, self.proto)
        except Exception:
            pass
        self._finish()

    def _finish(self):
        if self._closed.is_set():
            return
        self._closed.set()
        for fn in (lambda: self.sock.shutdown(socket.SHUT_RDWR), self.sock.close):
            try:
                fn()
            except OSError:
                pass
        try:
            self.on_close(self)
        except Exception:
            log.exception("closing a link")

    def _frames(self, frames):
        for f in frames:
            if f.opcode is Opcode.TEXT:
                self._parts = [f.data]
            elif f.opcode is Opcode.CONT and self._parts:
                self._parts.append(f.data)
            elif f.opcode is Opcode.BINARY:
                self._parts = []   # not used by the mesh; ignored
                continue
            else:
                continue          # ping/pong/close: the protocol answers those itself
            if f.fin and self._parts:
                raw, self._parts = b"".join(self._parts), []
                try:
                    msg = json.loads(raw)
                except ValueError:
                    continue
                if isinstance(msg, dict):
                    self.last_used = time.monotonic()
                    try:
                        self.on_message(self, msg)
                    except Exception:
                        log.exception("handling %r from %s", msg.get("t"), self.address)

    def _reader(self):
        try:
            self.sock.settimeout(IDLE_PING)
            self._frames(self._pending)
            self._pending = []
            while not self.closed:
                try:
                    data = self.sock.recv(65536)
                except (socket.timeout, TimeoutError):
                    if time.monotonic() - self._last_rx > DEAD_AFTER:
                        log.info("link with %s went quiet; closing it", self.address)
                        break
                    with self._lock:
                        self.proto.send_ping(str(int(time.time())).encode())
                        _flush(self.sock, self.proto)
                    continue
                self._last_rx = time.monotonic()
                with self._lock:
                    if not data:
                        self.proto.receive_eof()
                    else:
                        self.proto.receive_data(data)
                    events = self.proto.events_received()
                    more = _flush(self.sock, self.proto)
                self._frames(events)
                if not data or not more or self.proto.state is State.CLOSED:
                    break
        except Exception as e:
            if not self.closed:
                log.debug("link with %s ended: %s", self.address, e)
        finally:
            self._finish()
