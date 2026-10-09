"""What goes over a browser's data channel: who it is, pairing, then chat and files.

Every text frame is one JSON object; binary frames carry file data. The
computer speaks first, as soon as the channel opens:

    S → {"t":"server-hello","v":1,"id","name","os":"linux","fp":<mesh fp>,"nonce":nS}

**A paired browser proves who it is** (its key never leaves it: WebCrypto,
non-extractable, in IndexedDB):

    C → {"t":"auth","v":1,"key":<base64 SPKI>,"name","sig":<base64>}
        sig = ECDSA P-256/SHA-256 over
        "droplet-webrtc-auth-v1" LF fpK LF fpS LF fpD LF nS
    S → {"t":"welcome","id","name","os","caps":[]}   or   {"t":"auth-failed","error","paired":bool}

where fpK is the SHA-256 of the browser's key (its identity), fpS this
computer's fingerprint (the DTLS certificate the browser pinned from the QR
code), and fpD the browser's own DTLS certificate's fingerprint, as this
side saw it in the handshake. So the signature is bound to this very DTLS
session: it can't be replayed, or relayed through a connection someone
else made. `sig` is raw r‖s (WebCrypto's form, 64 bytes) or DER.

**An unpaired browser pairs**, as in docs/mesh.md §9.3 (the same commitment,
transcript and 4-digit code, with fpK as the initiator's fingerprint), and
it must hold the one-time token from a QR code this computer is showing:

    C → {"t":"pair","v":1,"key","name","os":"ios","token","commit":hex(SHA-256(nC))}
    S → {"t":"pair-nonce","request","nonce":nR}            or {"t":"pair-failed","error"}
    C → {"t":"pair-confirm","nonce":nC,"sig":<over the §9.3 transcript>}
    S → {"t":"pair-state","state":"waiting"}   … then "accepted" | "denied" | "expired" | "cancelled"
    C → {"t":"pair-cancel"}                    (gives up)

Both screens show the code. The computer trusts the browser when its owner
accepts; the browser keeps the computer when its owner has said the codes
match *and* the computer said accepted. Then it sends `auth` on the same
channel.

**Once authenticated**, as on a mesh link (docs/mesh.md §9.4):
`text` → `ack`/`nack`, `ping` → `pong`, `ring`, `unpair`. Files go over the
channel itself (a browser can't serve HTTPS):

    {"t":"file","id":<32 hex>,"name","size","mime"}
    binary frames: the first 8 bytes of the id (16 hex), then up to 16 KB of data
    {"t":"file-end","id"}
    → {"t":"ack","id"}  or  {"t":"nack","id","error"}

One file at a time in each direction; the sender waits for the channel's
buffer to drain before sending more (backpressure).
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import re
import secrets
import time
from pathlib import Path

from ..mesh.files import safe_name, unique_path
from ..mesh.identity import key_fingerprint, p256_spki, verify_key
from . import PROTOCOL_VERSION

log = logging.getLogger("droplet_agent.webrtc")

CHUNK = 16 * 1024            # data per binary frame (plus the 8-byte id)
HIGH_WATER = 1024 * 1024     # stop sending while this much is buffered
MAX_FRAME = 256 * 1024
MAX_TEXT = 64 * 1024
MAX_FILE = 64 * 1024 ** 3
FILE_ID = re.compile(r"[0-9a-f]{32}")
AUTH_TIMEOUT = 30            # an unauthenticated channel that isn't pairing closes after this
ACK_TIMEOUT = 60


def auth_transcript(fp_key: str, fp_server: str, fp_dtls: str, nonce: str) -> bytes:
    return "\n".join(["droplet-webrtc-auth-v1", fp_key, fp_server, fp_dtls, nonce]).encode("ascii")


class Host:
    """What a connection needs from the rest of the agent (the bridge implements it)."""

    def server_info(self) -> dict: raise NotImplementedError          # {"id", "name", "fp"}
    def browser(self, fp: str) -> dict | None: return None             # a trusted browser's entry
    def token_ok(self, token) -> bool: return False
    def pair_open(self, body: dict) -> tuple[int, dict]: return 500, {}
    def pair_confirm(self, rid: str, body: dict) -> tuple[int, dict]: return 500, {}
    def pair_status(self, rid: str) -> str: return "expired"
    def pair_cancel(self, rid: str) -> None: pass
    def authenticated(self, conn: "Conn") -> None: pass
    def deliver(self, conn: "Conn", msg: dict) -> None: pass
    def received(self, conn: "Conn", fid: str, path: Path) -> None: pass
    def already_received(self, conn: "Conn", fid: str) -> bool: return False
    def downloads(self) -> Path: raise NotImplementedError
    def closed(self, conn: "Conn") -> None: pass


class Conn:
    """One browser's channel. Runs on the listener's asyncio loop.

    `channel` is an aiortc RTCDataChannel (or anything with send(), bufferedAmount and close()).
    """

    def __init__(self, host: Host, channel, *, dtls_fp: str, address: str):
        self.host, self.channel, self.dtls_fp, self.address = host, channel, dtls_fp, address
        self.nonce = secrets.token_hex(32)
        self.fp: str | None = None          # the browser's key fingerprint, once it's authenticated
        self.name = ""
        self.closed = False
        self.pairing: str | None = None     # the open pairing request
        self.created = time.monotonic()
        self._incoming: dict[bytes, dict] = {}
        self._waiting: dict[str, asyncio.Future] = {}
        self._send_lock = asyncio.Lock()
        self.on_progress = None             # (file id, bytes sent) while sending

    # --- sending ------------------------------------------------------------------
    def send(self, msg: dict) -> bool:
        if self.closed:
            return False
        try:
            self.channel.send(json.dumps(msg, separators=(",", ":")))
            return True
        except Exception:
            return False

    def close(self):
        if self.closed:
            return
        self.closed = True
        if self.pairing and self.fp is None:
            self.host.pair_cancel(self.pairing)
        for f in self._incoming.values():
            try:
                f["file"].close()
                f["part"].unlink(missing_ok=True)
            except OSError:
                pass
        self._incoming.clear()
        for fut in self._waiting.values():
            if not fut.done():
                fut.set_result((False, "the connection closed"))
        try:
            self.channel.close()
        except Exception:
            pass
        self.host.closed(self)

    # --- arriving -------------------------------------------------------------------
    def opened(self):
        info = self.host.server_info()
        self.send({"t": "server-hello", "v": PROTOCOL_VERSION, "id": info["id"], "name": info["name"],
                   "os": "linux", "fp": info["fp"], "nonce": self.nonce})
        asyncio.get_event_loop().call_later(AUTH_TIMEOUT, self._auth_deadline)

    def _auth_deadline(self):
        if self.fp is None and self.pairing is None and not self.closed:
            log.info("webrtc: %s didn't say who it is; closing", self.address)
            self.close()

    def message(self, data):
        if self.closed:
            return
        if isinstance(data, (bytes, bytearray)):
            if self.fp is not None:
                self._chunk(bytes(data))
            return
        if not isinstance(data, str) or len(data) > MAX_FRAME:
            return
        try:
            msg = json.loads(data)
        except ValueError:
            return
        if not isinstance(msg, dict):
            return
        t = msg.get("t")
        if t == "ping":
            self.send({"t": "pong"})
        elif t == "auth":
            self._auth(msg)
        elif t == "pair":
            self._pair(msg)
        elif t == "pair-confirm":
            self._pair_confirm(msg)
        elif t == "pair-cancel":
            if self.pairing:
                self.host.pair_cancel(self.pairing)
        elif self.fp is None:
            return    # nothing else before it has said who it is
        elif t == "file":
            self._file(msg)
        elif t == "file-end":
            self._file_end(msg)
        elif t in ("ack", "nack"):
            fut = self._waiting.get(msg.get("id")) if isinstance(msg.get("id"), str) else None
            if fut is not None and not fut.done():
                fut.set_result((t == "ack", str(msg.get("error") or "")[:200]))
            self.host.deliver(self, msg)
        elif t in ("text", "unpair", "ring", "ring-stop"):
            self.host.deliver(self, msg)

    # --- who it is -------------------------------------------------------------------
    def _auth(self, msg: dict):
        if self.fp is not None:
            return
        try:
            spki = p256_spki(msg.get("key"))
            sig = base64.b64decode(msg.get("sig") or "", validate=True)
        except (ValueError, TypeError):
            self.send({"t": "auth-failed", "error": "malformed", "paired": False})
            return
        fp = key_fingerprint(spki)
        entry = self.host.browser(fp)
        if entry is None:
            self.send({"t": "auth-failed", "error": "this device isn't paired with it", "paired": False})
            return
        info = self.host.server_info()
        if not verify_key(spki, sig, auth_transcript(fp, info["fp"], self.dtls_fp, self.nonce)):
            log.warning("webrtc: %s at %s failed the challenge", entry["name"], self.address)
            self.send({"t": "auth-failed", "error": "the proof didn't check out", "paired": True})
            self.close()
            return
        self.fp, self.name = fp, entry["name"]
        self.pairing = None
        self.send({"t": "welcome", "v": PROTOCOL_VERSION, "id": info["id"], "name": info["name"], "os": "linux",
                   "caps": []})
        log.info("webrtc: %s connected from %s", entry["name"], self.address)
        self.host.authenticated(self)

    def _pair(self, msg: dict):
        if self.fp is not None or self.pairing is not None:
            return
        if not self.host.token_ok(msg.get("token")):
            self.send({"t": "pair-failed", "error": "this pairing code has expired: show a new QR code and scan it"})
            return
        status, out = self.host.pair_open(msg)
        if status != 200:
            self.send({"t": "pair-failed", "error": out.get("error") or f"refused ({status})"})
            return
        self.pairing = out["request"]
        self.send({"t": "pair-nonce", "request": out["request"], "nonce": out["nonce"]})

    def _pair_confirm(self, msg: dict):
        if not self.pairing or self.fp is not None:
            return
        status, out = self.host.pair_confirm(self.pairing, msg)
        if status != 200:
            self.pairing = None
            self.send({"t": "pair-failed", "error": out.get("error") or f"refused ({status})"})
            return
        self.send({"t": "pair-state", "state": "waiting"})
        asyncio.ensure_future(self._watch_pairing(self.pairing))

    async def _watch_pairing(self, rid: str):
        last = "waiting"
        while not self.closed and self.pairing == rid:
            await asyncio.sleep(0.5)
            state = self.host.pair_status(rid)
            if state != last:
                last = state
                self.send({"t": "pair-state", "state": state})
            if state != "waiting":
                return

    # --- files arriving -----------------------------------------------------------------
    def _file(self, msg: dict):
        fid, size = msg.get("id"), msg.get("size")
        if not isinstance(fid, str) or not FILE_ID.fullmatch(fid):
            return
        if not isinstance(size, int) or isinstance(size, bool) or not 0 <= size <= MAX_FILE:
            self.send({"t": "nack", "id": fid, "error": "bad size"})
            return
        if self.host.already_received(self, fid):
            self.send({"t": "ack", "id": fid})
            return
        key = bytes.fromhex(fid[:16])
        if key in self._incoming:
            return
        d = self.host.downloads()
        try:
            d.mkdir(parents=True, exist_ok=True)
            free = os.statvfs(d).f_bavail * os.statvfs(d).f_frsize
            if size > free:
                self.send({"t": "nack", "id": fid, "error": "not enough space on the computer"})
                return
            part = d / f".droplet-{self.fp[:16]}-{fid}.part"
            f = open(part, "wb")
        except OSError as e:
            self.send({"t": "nack", "id": fid, "error": f"can't save it: {e.strerror or e}"})
            return
        self._incoming[key] = {"id": fid, "name": safe_name(msg.get("name")), "size": size, "got": 0,
                               "file": f, "part": part}

    def _chunk(self, data: bytes):
        f = self._incoming.get(data[:8])
        if f is None:
            return
        body = data[8:]
        f["got"] += len(body)
        if f["got"] > f["size"]:
            self._drop(f, "more data than it said")
            return
        try:
            f["file"].write(body)
        except OSError as e:
            self._drop(f, f"can't save it: {e.strerror or e}")

    def _drop(self, f: dict, why: str):
        self._incoming.pop(bytes.fromhex(f["id"][:16]), None)
        try:
            f["file"].close()
        except OSError:
            pass
        f["part"].unlink(missing_ok=True)
        self.send({"t": "nack", "id": f["id"], "error": why})

    def _file_end(self, msg: dict):
        fid = msg.get("id")
        if not isinstance(fid, str) or not FILE_ID.fullmatch(fid):
            return
        f = self._incoming.get(bytes.fromhex(fid[:16]))
        if f is None or f["id"] != fid:
            return
        if f["got"] != f["size"]:
            self._drop(f, f"got {f['got']} of {f['size']} bytes")
            return
        self._incoming.pop(bytes.fromhex(fid[:16]), None)
        try:
            f["file"].close()
            path = unique_path(f["part"].parent, f["name"])
            os.replace(f["part"], path)
        except OSError as e:
            f["part"].unlink(missing_ok=True)
            self.send({"t": "nack", "id": fid, "error": f"can't save it: {e.strerror or e}"})
            return
        self.host.received(self, fid, path)
        self.send({"t": "ack", "id": fid})

    # --- files leaving -------------------------------------------------------------------
    async def _drain(self):
        while not self.closed and getattr(self.channel, "bufferedAmount", 0) > HIGH_WATER:
            await asyncio.sleep(0.005)

    async def send_file(self, fid: str, name: str, size: int, mime: str, path: Path) -> tuple[bool, str]:
        """Send a file; (True, "") once the browser acknowledged it, else (False, why).

        why starts with "refused:" when the browser refused it for good."""
        async with self._send_lock:
            if self.closed:
                return False, "the connection closed"
            fut = asyncio.get_event_loop().create_future()
            self._waiting[fid] = fut
            try:
                if not self.send({"t": "file", "id": fid, "name": name, "size": size, "mime": mime}):
                    return False, "the connection closed"
                key = bytes.fromhex(fid[:16])
                sent = 0
                with open(path, "rb") as f:
                    while sent < size:
                        await self._drain()
                        if self.closed or fut.done():
                            break
                        body = f.read(min(CHUNK, size - sent))
                        if not body:
                            return False, "refused: the file got shorter while it was sent"
                        self.channel.send(key + body)
                        sent += len(body)
                        if self.on_progress:
                            self.on_progress(fid, sent)
                if fut.done():
                    ok, why = fut.result()
                    return ok, "" if ok else f"refused: {why}"
                if self.closed:
                    return False, f"the connection closed at {sent} bytes"
                self.send({"t": "file-end", "id": fid})
                try:
                    ok, why = await asyncio.wait_for(asyncio.shield(fut), ACK_TIMEOUT)
                except asyncio.TimeoutError:
                    return False, "no answer"
                if ok:
                    return True, ""
                return False, why if why == "the connection closed" else f"refused: {why}"
            except OSError as e:
                return False, f"refused: can't read it: {e.strerror or e}"
            finally:
                self._waiting.pop(fid, None)
