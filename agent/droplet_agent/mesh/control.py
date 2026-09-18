"""The local socket the CLI uses to talk to the running agent.

`$XDG_RUNTIME_DIR/droplet-agent/control.sock`: a Unix socket in a directory
only this user can open (700), and each connection's peer is checked to be
this same user (SO_PEERCRED). One JSON request per line, one JSON answer
per line.
"""

from __future__ import annotations

import json
import logging
import os
import socket
import struct
import threading
from pathlib import Path

log = logging.getLogger("droplet_agent.mesh.control")

MAX_LINE = 1024 * 1024


def socket_path() -> Path:
    base = os.environ.get("XDG_RUNTIME_DIR") or f"/run/user/{os.getuid()}"
    if not os.path.isdir(base):
        from ..config import config_dir
        return config_dir() / "control.sock"
    return Path(base) / "droplet-agent" / "control.sock"


class NotRunning(Exception):
    pass


def _peer_uid(conn: socket.socket) -> int | None:
    try:
        creds = conn.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize("3i"))
        return struct.unpack("3i", creds)[1]
    except OSError:
        return None


def _readline(conn: socket.socket) -> bytes | None:
    buf = bytearray()
    while b"\n" not in buf:
        if len(buf) > MAX_LINE:
            return None
        data = conn.recv(65536)
        if not data:
            return bytes(buf) or None
        buf += data
    return bytes(buf[:buf.index(b"\n")])


class ControlServer:
    def __init__(self, handler, path: Path | None = None):
        self.handler = handler     # dict -> dict
        self.path = path or socket_path()
        if len(str(self.path).encode()) > 100:
            raise OSError(f"the control socket path is too long for a Unix socket: {self.path}")
        self.path.parent.mkdir(parents=True, exist_ok=True)
        os.chmod(self.path.parent, 0o700)
        try:
            self.path.unlink()
        except FileNotFoundError:
            pass
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        old = os.umask(0o177)
        try:
            self.sock.bind(str(self.path))
        finally:
            os.umask(old)
        os.chmod(self.path, 0o600)
        self.sock.listen(8)
        self._stop = threading.Event()

    def start(self):
        threading.Thread(target=self._accept, name="control", daemon=True).start()

    def close(self):
        self._stop.set()
        try:
            self.sock.close()
            self.path.unlink()
        except OSError:
            pass

    def _accept(self):
        while not self._stop.is_set():
            try:
                conn, _ = self.sock.accept()
            except OSError:
                return
            threading.Thread(target=self._serve, args=(conn,), name="control-conn", daemon=True).start()

    def _serve(self, conn: socket.socket):
        with conn:
            if _peer_uid(conn) != os.getuid():
                return
            conn.settimeout(600)
            try:
                line = _readline(conn)
                if not line:
                    return
                req = json.loads(line)
                out = self.handler(req) if isinstance(req, dict) else {"error": "bad request"}
            except Exception as e:
                log.exception("control request failed")
                out = {"error": f"the agent failed: {e}"}
            try:
                conn.sendall(json.dumps(out).encode() + b"\n")
            except OSError:
                pass


def call(request: dict, timeout: float = 30, path: Path | None = None) -> dict:
    """Ask the running agent. Raises NotRunning when there's no agent to ask."""
    path = path or socket_path()
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect(str(path))
    except (FileNotFoundError, ConnectionRefusedError) as e:
        s.close()
        raise NotRunning(str(e)) from e
    with s:
        s.sendall(json.dumps(request).encode() + b"\n")
        line = _readline(s)
    if not line:
        raise NotRunning("the agent closed the connection")
    out = json.loads(line)
    return out if isinstance(out, dict) else {"error": "bad answer"}
