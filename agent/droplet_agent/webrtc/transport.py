"""The UDP port a browser connects to: ICE-lite, then DTLS, SCTP and one data channel.

No signalling server, so nothing tells this side the browser's SDP. It
doesn't need it:

- **ICE.** The browser writes our answer with `a=ice-lite`, so it does all
  the connectivity checks and we only answer them. Our ICE credentials are
  chosen by the browser: `ice-ufrag` and `ice-pwd` are both
  `droplet+v1/<24 random ice-chars>`. The first STUN Binding request
  carries `USERNAME = <our ufrag>:<its ufrag>`, so we learn our ufrag, and
  the password is the same string, which checks the request's
  MESSAGE-INTEGRITY and signs our answer. (That's no secret, and needn't
  be: ICE only proves the path works. DTLS and the app's challenge are the
  security.) The browser's own credentials are never modified ("munged"),
  which browsers are dropping support for; an ICE-lite agent never sends a
  check, so it never needs them.
- Each connection is keyed by our ufrag, and follows the address the
  browser's checks last nominated (USE-CANDIDATE).
- **DTLS**: we're the server (`a=setup:passive` in the answer), with the
  mesh certificate. The browser pins its fingerprint from the QR code. The
  browser's certificate is accepted whatever it is, and its fingerprint is
  handed up, so the app's challenge can bind to it.
- **SCTP** on port 5000, and one data channel, negotiated out of band:
  id 0, label "droplet". No DCEP round trip.
"""

from __future__ import annotations

import asyncio
import hashlib
import ipaddress
import logging
import re
import socket
import threading
import time

from . import UFRAG_PREFIX

log = logging.getLogger("droplet_agent.webrtc")

SCTP_PORT = 5000
MAX_MESSAGE = 256 * 1024
IDLE = 30            # seconds without a packet before a connection is dropped
SETUP = 20           # seconds from the first check to an open channel
MAX_SESSIONS = 8
UFRAG = re.compile(re.escape(UFRAG_PREFIX) + r"[A-Za-z0-9+/]{22,64}")


def unmap(address: str) -> str:
    """'::ffff:192.0.2.1' (an IPv4 peer on a dual-stack socket) → '192.0.2.1'."""
    try:
        ip = ipaddress.ip_address(address.split("%")[0])
    except ValueError:
        return address
    if ip.version == 6 and ip.ipv4_mapped:
        return str(ip.ipv4_mapped)
    return address


class _Udp(asyncio.DatagramProtocol):
    def __init__(self, listener: "Listener"):
        self.listener = listener

    def datagram_received(self, data, addr):
        try:
            self.listener._datagram(data, addr[:2])
        except Exception:
            log.exception("webrtc: handling a datagram")

    def error_received(self, exc):
        log.debug("webrtc: UDP error: %s", exc)


class Session:
    """One browser's connection. To aiortc's DTLS transport, it is the ICE transport."""

    role = "controlled"      # the ICE-lite side is always controlled
    state = "completed"

    def __init__(self, listener: "Listener", ufrag: str):
        self.listener, self.ufrag = listener, ufrag
        self.queue: asyncio.Queue = asyncio.Queue()
        self.address: tuple | None = None       # where we send: the nominated path
        self.created = self.last = time.monotonic()
        self.closed = False
        self.channel = None
        self.remote_fp: str | None = None        # SHA-256 of the browser's DTLS certificate, lowercase hex
        self.dtls = self.sctp = None
        self.opened = False
        self.on_close = []

    # --- what aiortc's RTCDtlsTransport calls --------------------------------
    async def _recv(self) -> bytes:
        data = await self.queue.get()
        if data is None:
            raise ConnectionError("closed")
        return data

    async def _send(self, data: bytes):
        if self.closed or self.address is None:
            raise ConnectionError("closed")
        self.listener.udp.sendto(data, self.address)

    # --- life ------------------------------------------------------------------
    @property
    def peer_address(self) -> str:
        return unmap(self.address[0]) if self.address else ""

    def checked(self, addr, nominate: bool):
        self.last = time.monotonic()
        if nominate or self.address is None:
            self.address = addr

    def feed(self, data: bytes):
        self.last = time.monotonic()
        self.queue.put_nowait(data)

    async def run(self):
        rtc = self.listener.rtc

        class Dtls(rtc.RTCDtlsTransport):
            session = self

            def _validate_peer_identity(self, remoteParameters):
                from cryptography.hazmat.primitives.serialization import Encoding
                cert = self._ssl.get_peer_certificate(as_cryptography=True)
                self.session.remote_fp = hashlib.sha256(cert.public_bytes(Encoding.DER)).hexdigest()

            def _setup_srtp(self):
                pass   # a data channel only: no media, so no SRTP keys

        try:
            self.dtls = Dtls(self, [self.listener.certificate])
            self.dtls._set_role("server")
            self.sctp = rtc.RTCSctpTransport(self.dtls, SCTP_PORT)
            channel = rtc.RTCDataChannel(self.sctp, rtc.RTCDataChannelParameters(
                label="droplet", negotiated=True, id=0, ordered=True))
            self.channel = channel

            @channel.on("open")
            def _open():
                self.opened = True
                log.info("webrtc: data channel open with %s", self.peer_address)
                self.listener._opened(self)

            @channel.on("close")
            def _closed():
                asyncio.ensure_future(self.close())

            # the browser's fingerprint is checked by the app's challenge, not here
            await self.dtls.start(rtc.RTCDtlsParameters(fingerprints=[rtc.RTCDtlsFingerprint("sha-256", "00")]))
            if self.dtls.state != "connected":
                log.info("webrtc: DTLS with %s failed", self.peer_address)
                await self.close()
                return
            await self.sctp.start(rtc.RTCSctpCapabilities(maxMessageSize=MAX_MESSAGE), SCTP_PORT)
        except Exception:
            log.exception("webrtc: setting up a connection")
            await self.close()

    async def close(self):
        if self.closed:
            return
        self.closed = True
        self.queue.put_nowait(None)
        self.listener._forget(self)
        for t in (self.sctp, self.dtls):
            if t is not None:
                try:
                    await t.stop()
                except Exception:
                    pass
        for cb in list(self.on_close):
            try:
                cb(self)
            except Exception:
                log.exception("webrtc: on close")
        log.debug("webrtc: connection %s closed", self.ufrag[-6:])


class Listener:
    """The UDP port, in its own thread with its own asyncio loop."""

    def __init__(self, cert_path, key_path, on_channel, *, port: int, port_range: int = 10, host: str = "::"):
        from . import deps
        self.rtc = deps.load()
        from cryptography import x509
        from cryptography.hazmat.primitives import serialization
        key = serialization.load_pem_private_key(open(key_path, "rb").read(), password=None)
        cert = x509.load_pem_x509_certificate(open(cert_path, "rb").read())
        self.certificate = self.rtc.RTCCertificate(key, cert)
        self.fingerprint = hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()
        self.on_channel = on_channel
        self.want_port, self.port_range, self.host = port, port_range, host
        self.port = 0
        self.sessions: dict[str, Session] = {}
        self.by_addr: dict[tuple, Session] = {}
        self.loop: asyncio.AbstractEventLoop | None = None
        self.udp = None
        self._thread = None
        self._started = threading.Event()
        self._error: Exception | None = None

    # --- life ----------------------------------------------------------------------
    def _bind(self) -> socket.socket:
        last = None
        for port in range(self.want_port, self.want_port + self.port_range + 1) if self.want_port else [0]:
            try:
                if self.host in ("::", "") and socket.has_ipv6:
                    s = socket.socket(socket.AF_INET6, socket.SOCK_DGRAM)
                    s.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
                    s.bind(("::", port))
                else:
                    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    s.bind((self.host, port))
                return s
            except OSError as e:
                last = e
                try:
                    s.close()
                except Exception:
                    pass
        raise OSError(f"no free UDP port from {self.want_port}: {last}")

    def start(self):
        sock = self._bind()
        self.port = sock.getsockname()[1]

        def run():
            self.loop = asyncio.new_event_loop()
            asyncio.set_event_loop(self.loop)
            try:
                self.udp, _ = self.loop.run_until_complete(
                    self.loop.create_datagram_endpoint(lambda: _Udp(self), sock=sock))
            except Exception as e:
                self._error = e
                self._started.set()
                return
            self.loop.create_task(self._sweep())
            self._started.set()
            self.loop.run_forever()
            self.loop.close()

        self._thread = threading.Thread(target=run, name="webrtc", daemon=True)
        self._thread.start()
        self._started.wait(10)
        if self._error:
            raise self._error
        log.info("webrtc: listening on UDP port %d (fingerprint %s)", self.port, self.fingerprint)

    def close(self):
        if self.loop is None:
            return

        async def shut():
            for s in list(self.sessions.values()):
                await s.close()
            if self.udp is not None:
                self.udp.close()
            me = asyncio.current_task()
            for t in asyncio.all_tasks():
                if t is not me:
                    t.cancel()
        try:
            asyncio.run_coroutine_threadsafe(shut(), self.loop).result(5)
        except Exception:
            pass
        self.loop.call_soon_threadsafe(self.loop.stop)
        if self._thread:
            self._thread.join(5)

    def call(self, fn, *args):
        """Run fn(*args) on the loop, from another thread."""
        self.loop.call_soon_threadsafe(fn, *args)

    def run(self, coro, timeout=None):
        """Run a coroutine on the loop from another thread, and wait for it."""
        return asyncio.run_coroutine_threadsafe(coro, self.loop).result(timeout)

    async def _sweep(self):
        while True:
            await asyncio.sleep(2)
            now = time.monotonic()
            for s in list(self.sessions.values()):
                if now - s.last > IDLE or (not s.opened and now - s.created > SETUP):
                    log.debug("webrtc: dropping a quiet connection from %s", s.peer_address)
                    await s.close()

    # --- packets -------------------------------------------------------------------
    def _datagram(self, data: bytes, addr: tuple):
        if not data:
            return
        b = data[0]
        if b < 4:
            self._stun(data, addr)
        elif 20 <= b <= 63:
            s = self.by_addr.get(addr)
            if s is not None and not s.closed:
                s.feed(data)

    def _stun(self, data: bytes, addr: tuple):
        from aioice import stun
        try:
            msg = stun.parse_message(data)
        except ValueError:
            return
        if msg.message_method != stun.Method.BINDING:
            return
        if msg.message_class == stun.Class.INDICATION:
            s = self.by_addr.get(addr)
            if s is not None:
                s.last = time.monotonic()
            return
        if msg.message_class != stun.Class.REQUEST:
            return
        user = msg.attributes.get("USERNAME")
        if not isinstance(user, str) or ":" not in user or "MESSAGE-INTEGRITY" not in msg.attributes:
            return
        ufrag = user.split(":", 1)[0]
        if not UFRAG.fullmatch(ufrag):
            return
        key = ufrag.encode()
        try:
            stun.parse_message(data, integrity_key=key)
        except ValueError:
            return
        s = self.sessions.get(ufrag)
        if s is None:
            if len(self.sessions) >= MAX_SESSIONS:
                log.info("webrtc: too many connections at once; ignoring %s", unmap(addr[0]))
                return
            s = Session(self, ufrag)
            self.sessions[ufrag] = s
            log.debug("webrtc: a browser at %s is checking", unmap(addr[0]))
            s.checked(addr, True)
            self.loop.create_task(s.run())
        else:
            s.checked(addr, "USE-CANDIDATE" in msg.attributes)
        self.by_addr[addr] = s
        resp = stun.Message(message_method=stun.Method.BINDING, message_class=stun.Class.RESPONSE,
                            transaction_id=msg.transaction_id)
        resp.attributes["XOR-MAPPED-ADDRESS"] = (unmap(addr[0]), addr[1])
        resp.add_message_integrity(key)
        self.udp.sendto(bytes(resp), addr)

    def _opened(self, s: Session):
        try:
            self.on_channel(s)
        except Exception:
            log.exception("webrtc: a new channel")
            asyncio.ensure_future(s.close())

    def _forget(self, s: Session):
        if self.sessions.get(s.ufrag) is s:
            del self.sessions[s.ufrag]
        for a in [a for a, x in self.by_addr.items() if x is s]:
            del self.by_addr[a]
