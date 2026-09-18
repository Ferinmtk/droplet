"""The mesh port: one TLS listener for WebSocket links, files and pairing.

    wss://<peer>:<port>/mesh                  a trusted peer's link           (client certificate, trusted)
    GET  https://<peer>:<port>/mesh/files/<id> a file offered to that peer    (client certificate, trusted)
    POST https://<peer>:<port>/mesh/pair       pairing                        (no client certificate)
    GET  /mesh/pair/<request>, POST /mesh/pair/<request>/confirm, POST /mesh/pair/<request>/cancel

Who may do what is decided once, right after the TLS handshake (tlsctx.py):
a client with a trusted certificate may use everything; a client with no
certificate may only use /mesh/pair*; any other certificate never gets
past the handshake. Everything else is refused before any body is read.
"""

from __future__ import annotations

import errno
import json
import logging
import socket
import threading
from http import HTTPStatus

from . import PORT_RANGE
from .tlsctx import ServerContexts, peer_fingerprint

log = logging.getLogger("droplet_agent.mesh.server")

MAX_HEAD = 16 * 1024
MAX_BODY = 64 * 1024
MAX_CONNECTIONS = 64
HANDSHAKE_TIMEOUT = 10
MAX_REQUESTS = 8      # per connection, for the pairing calls


class Request:
    def __init__(self, method: str, path: str, headers: dict, raw_head: bytes):
        self.method, self.path, self.headers, self.raw_head = method, path, headers, raw_head


def parse_head(head: bytes) -> Request | None:
    """A request head, including its closing blank line."""
    try:
        text = head[:-4].decode("iso-8859-1") if head.endswith(b"\r\n\r\n") else head.decode("iso-8859-1")
    except UnicodeDecodeError:
        return None
    lines = text.split("\r\n")
    parts = lines[0].split(" ")
    if len(parts) != 3 or not parts[2].startswith("HTTP/1."):
        return None
    headers = {}
    for line in lines[1:]:
        if not line:
            continue
        k, sep, v = line.partition(":")
        if not sep or not k or k != k.strip():
            return None
        headers[k.strip().lower()] = v.strip()
    path = parts[1].split("?", 1)[0]
    return Request(parts[0], path, headers, head)


def bind(port: int | None) -> tuple[socket.socket, int]:
    """Listen on `port` (0: any free one), or with None the first free one in 1739–1749.
    Dual-stack when IPv6 is there."""
    ports = [port] if port is not None else list(PORT_RANGE)
    last = None
    for p in ports:
        for family, addr in ((socket.AF_INET6, "::"), (socket.AF_INET, "0.0.0.0")):
            try:
                s = socket.socket(family, socket.SOCK_STREAM)
            except OSError as e:
                last = e
                continue
            try:
                s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                if family == socket.AF_INET6:
                    s.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
                s.bind((addr, p))
                s.listen(32)
                return s, s.getsockname()[1]
            except OSError as e:
                s.close()
                last = e
                if e.errno == errno.EADDRINUSE:
                    break   # taken: the other family won't help, try the next port
    raise OSError(f"no free mesh port in {ports[0]}–{ports[-1]}: {last}")


class Server:
    """`handler` provides:

    - trusted(fp) -> entry | None
    - on_link(sock, request, entry, address, leftover): take over the socket for a WebSocket
    - serve_file(sock, oid, fp, request, send_head)
    - pair(method, path, body_or_None) -> (status, dict)
    """

    def __init__(self, contexts: ServerContexts, handler, port: int | None = None):
        self.contexts = contexts
        self.handler = handler
        self.sock, self.port = bind(port)
        self._stop = threading.Event()
        self._slots = threading.BoundedSemaphore(MAX_CONNECTIONS)
        self.refused = 0     # TLS handshakes that failed (untrusted certificates among them)

    def start(self):
        threading.Thread(target=self._accept, name="mesh-accept", daemon=True).start()

    def close(self):
        self._stop.set()
        # shutdown wakes the thread blocked in accept(); close alone wouldn't
        for fn in (lambda: self.sock.shutdown(socket.SHUT_RDWR), self.sock.close):
            try:
                fn()
            except OSError:
                pass

    def _accept(self):
        while not self._stop.is_set():
            try:
                conn, addr = self.sock.accept()
            except OSError:
                if self._stop.is_set():
                    return
                continue
            if not self._slots.acquire(blocking=False):
                conn.close()
                continue
            threading.Thread(target=self._serve, args=(conn, addr), name="mesh-conn", daemon=True).start()

    def _serve(self, raw: socket.socket, addr):
        address = addr[0]
        if address.startswith("::ffff:"):
            address = address[7:]
        handed_over = False
        tls = None
        try:
            raw.settimeout(HANDSHAKE_TIMEOUT)
            try:
                tls = self.contexts.current().wrap_socket(raw, server_side=True)
            except (OSError, ValueError) as e:
                self.refused += 1
                log.info("refused a TLS connection from %s: %s", address, getattr(e, "reason", None) or e)
                return
            fp = peer_fingerprint(tls)
            entry = self.handler.trusted(fp) if fp else None
            if fp and entry is None:
                # OpenSSL let it through (a chain it accepted), but its fingerprint isn't trusted
                log.warning("refused %s: certificate %s isn't in the trust list", address, fp)
                self._respond(tls, 403, {"error": "not trusted"}, close=True)
                return
            handed_over = self._requests(tls, address, fp, entry)
        except Exception as e:
            log.debug("connection from %s: %s", address, e)
        finally:
            if not handed_over:
                for s in (tls, raw):
                    if s is not None:
                        try:
                            s.close()
                        except OSError:
                            pass
            self._slots.release()

    def _read_head(self, sock, buf: bytearray) -> bytes | None:
        while b"\r\n\r\n" not in buf:
            if len(buf) > MAX_HEAD:
                return None
            data = sock.recv(8192)
            if not data:
                return None
            buf += data
        end = buf.index(b"\r\n\r\n") + 4
        head = bytes(buf[:end])
        del buf[:end]
        return head

    def _requests(self, tls, address: str, fp: str | None, entry: dict | None) -> bool:
        """Serve requests on this connection. True when the socket was handed to a link."""
        buf = bytearray()
        for _ in range(MAX_REQUESTS):
            head = self._read_head(tls, buf)
            if head is None:
                return False
            req = parse_head(head)
            if req is None:
                self._respond(tls, 400, {"error": "bad request"}, close=True)
                return False
            path = req.path
            if path == "/mesh/pair" or path.startswith("/mesh/pair/"):
                body = None
                if req.method == "POST":
                    try:
                        n = int(req.headers.get("content-length") or "0")
                    except ValueError:
                        n = -1
                    if not 0 <= n <= MAX_BODY or "transfer-encoding" in req.headers:
                        self._respond(tls, 413, {"error": "body too large"}, close=True)
                        return False
                    while len(buf) < n:
                        data = tls.recv(min(65536, n - len(buf)))
                        if not data:
                            return False
                        buf += data
                    raw_body, buf = bytes(buf[:n]), buf[n:]
                    try:
                        body = json.loads(raw_body or b"{}")
                    except ValueError:
                        self._respond(tls, 400, {"error": "bad JSON"}, close=True)
                        return False
                elif req.method != "GET":
                    self._respond(tls, 405, {"error": "method not allowed"}, close=True)
                    return False
                status, out = self.handler.pair(req.method, path, body)
                close = req.headers.get("connection", "").lower() == "close"
                self._respond(tls, status, out, close=close)
                if close:
                    return False
                continue
            if entry is None:
                # an untrusted client (no certificate) gets nothing but pairing
                self._respond(tls, 403, {"error": "pair with this device first"}, close=True)
                return False
            if path == "/mesh" and req.method == "GET" and "websocket" in req.headers.get("upgrade", "").lower():
                self.handler.on_link(tls, req, entry, address, bytes(buf))
                return True
            if path.startswith("/mesh/files/") and req.method in ("GET", "HEAD"):
                def send_head(status, headers):
                    lines = [f"HTTP/1.1 {status} {HTTPStatus(status).phrase}"]
                    lines += [f"{k}: {v}" for k, v in headers.items()]
                    lines += ["Connection: close", "Server: droplet-agent", "", ""]
                    tls.sendall("\r\n".join(lines).encode("latin-1"))
                tls.settimeout(60)
                self.handler.serve_file(tls, path[len("/mesh/files/"):], fp, req, send_head)
                return False
            self._respond(tls, 404, {"error": "not found"}, close=True)
            return False
        return False

    @staticmethod
    def _respond(sock, status: int, body: dict, close: bool):
        data = json.dumps(body).encode()
        head = (f"HTTP/1.1 {status} {HTTPStatus(status).phrase}\r\nContent-Type: application/json\r\n"
                f"Content-Length: {len(data)}\r\nConnection: {'close' if close else 'keep-alive'}\r\n"
                "Server: droplet-agent\r\n\r\n")
        sock.sendall(head.encode("latin-1") + data)
