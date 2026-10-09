"""A WebSocket over an already established TLS socket, both ends.

Built on websockets' Sans-I/O layer (ClientProtocol / ServerProtocol), so the
TLS handshake, the fingerprint check and the HTTP routing happen first, on a
socket we control, and the WebSocket framing is still websockets' own.

Frames are JSON text, at most 1 MiB. Either side pings after 20 s of
silence, and gives up after 60 s without hearing anything.
"""

from __future__ import annotations

import errno
import json
import logging
import select
import socket
import ssl
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
SEND_TIMEOUT = 20      # a sender waits this long for room in a full queue
OUT_LIMIT = 4 * 1024 * 1024
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


_TRY_AGAIN = (BlockingIOError, InterruptedError, ssl.SSLWantReadError, ssl.SSLWantWriteError)


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
        self._room = threading.Condition(self._lock)   # senders wait here while the queue is full
        self._closed = threading.Event()
        self._parts: list[bytes] = []
        self._pending = list(pending_frames)
        self._last_rx = time.monotonic()
        self._out = bytearray()        # bytes the protocol wants sent, not yet taken by TLS
        self._eof_after = False        # the protocol asked to end the connection once _out is sent
        self._closing = False
        self._thread: threading.Thread | None = None
        self._wake_r, self._wake_w = socket.socketpair()
        self._wake_r.setblocking(False)
        self._wake_w.setblocking(False)

    @property
    def closed(self) -> bool:
        return self._closed.is_set()

    def start(self):
        """One thread owns the TLS socket and does all its reads and writes: OpenSSL can't
        have a read and a write in flight on one connection at once, and a sender blocked
        on a full socket must not keep this side from reading (both ends would stall)."""
        self._thread = threading.Thread(target=self._run, name=f"mesh-link-{self.fp[:8]}", daemon=True)
        self._thread.start()

    def _on_io_thread(self) -> bool:
        return threading.current_thread() is self._thread

    def _queue(self):
        # with the lock held: take what the protocol wants sent
        for chunk in self.proto.data_to_send():
            if chunk:
                self._out += chunk
            else:
                self._eof_after = True

    def _wake(self):
        try:
            self._wake_w.send(b"x")
        except OSError:
            pass

    def send(self, msg: dict) -> bool:
        if self.closed:
            return False
        data = json.dumps(msg, separators=(",", ":")).encode()
        if len(data) > MAX_FRAME:
            log.warning("not sending a %s message of %d bytes: over the frame limit", msg.get("t"), len(data))
            return False
        with self._lock:
            # back-pressure, except on the link's own thread (it's the one that drains the queue)
            deadline = time.monotonic() + SEND_TIMEOUT
            while len(self._out) > OUT_LIMIT and not self.closed and not self._on_io_thread():
                left = deadline - time.monotonic()
                if left <= 0:
                    break
                self._room.wait(left)
            if self.closed or self.proto.state is not State.OPEN or len(self._out) > OUT_LIMIT * 2:
                stuck = not self.closed and self.proto.state is State.OPEN
            else:
                self.proto.send_text(data)
                self._queue()
                stuck = None
        if stuck is not None:
            if stuck:
                log.debug("sending to %s failed: the link stopped draining", self.address)
                self.close()
            return False
        self._wake()
        self.last_used = time.monotonic()
        return True

    def close(self, code: int = 1000, reason: str = ""):
        if self._closed.is_set():
            return
        with self._lock:
            if self.proto.state is State.OPEN:
                try:
                    self.proto.send_close(code, reason)
                    self._queue()
                except Exception:
                    pass
            self._closing = True
        running = self._thread is not None and self._thread.is_alive()
        if running and not self._on_io_thread():
            self._wake()
            self._closed.wait(2)      # the link's thread sends the close frame, then finishes
        elif not running:
            try:                      # never started: send what's queued the plain way
                self.sock.settimeout(2)
                with self._lock:
                    if self._out:
                        self.sock.sendall(bytes(self._out))
                        self._out.clear()
            except Exception:
                pass
        if not running or not self._on_io_thread():
            self._finish()

    def _finish(self):
        if self._closed.is_set():
            return
        self._closed.set()
        for fn in (lambda: self.sock.shutdown(socket.SHUT_RDWR), self.sock.close, self._wake_r.close,
                   self._wake_w.close):
            try:
                fn()
            except OSError:
                pass
        with self._lock:
            self._room.notify_all()
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

    def _drain(self, seconds: float):
        """Send what's queued (a close frame, a pong) for up to `seconds`, best effort."""
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            with self._lock:
                if not self._out:
                    return
                try:
                    n = self.sock.send(self._out[:65536])
                    del self._out[:n]
                    continue
                except (_TRY_AGAIN + (OSError,)):
                    pass
            try:
                select.select([], [self.sock], [], max(0.0, end - time.monotonic()))
            except (OSError, ValueError):
                return

    def _run(self):
        try:
            self.sock.setblocking(False)
            self._frames(self._pending)
            self._pending = []
            while not self.closed:
                with self._lock:
                    want_write = bool(self._out)
                    if not want_write and (self._closing or self._eof_after):
                        break
                pending = self.sock.pending() > 0
                try:
                    r, w, _ = select.select([self.sock, self._wake_r], [self.sock] if want_write else [], [],
                                            0 if pending else IDLE_PING)
                except (OSError, ValueError):
                    break
                if self._wake_r in r:
                    try:
                        while self._wake_r.recv(4096):
                            pass
                    except OSError:
                        pass
                if not r and not w and not pending:
                    if time.monotonic() - self._last_rx > DEAD_AFTER:
                        log.info("link with %s went quiet; closing it", self.address)
                        break
                    with self._lock:
                        self.proto.send_ping(str(int(time.time())).encode())
                        self._queue()
                    continue
                if self.sock in w:
                    with self._lock:
                        try:
                            n = self.sock.send(self._out[:65536])
                            del self._out[:n]
                            self._room.notify_all()
                        except _TRY_AGAIN:
                            pass
                        except OSError as e:
                            if e.errno not in (errno.EAGAIN, errno.EWOULDBLOCK):
                                raise
                if self.sock in r or pending:
                    with self._lock:
                        try:
                            data = self.sock.recv(65536)
                        except _TRY_AGAIN:
                            # a TLS record not complete yet, or one with no data (a session ticket)
                            data = None
                        except OSError as e:
                            if e.errno not in (errno.EAGAIN, errno.EWOULDBLOCK):
                                raise
                            data = None
                        if data is not None:
                            self._last_rx = time.monotonic()
                            if not data:
                                self.proto.receive_eof()
                            else:
                                self.proto.receive_data(data)
                            events = self.proto.events_received()
                            self._queue()
                    if data is None:
                        continue
                    self._frames(events)
                    if not data or self.proto.state is State.CLOSED:
                        self._drain(1)
                        break
        except Exception as e:
            if not self.closed:
                log.debug("link with %s ended: %s", self.address, e)
        finally:
            self._finish()
