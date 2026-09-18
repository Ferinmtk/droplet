"""Trusting the hub's self-signed LAN certificate by its fingerprint.

On the LAN the hub serves HTTPS with a long-lived self-signed certificate
(docs/local-first.md §2). The agent accepts it if and only if the SHA-256 of
its DER encoding equals the fingerprint it pinned when it paired. Hostname and
CA checks are off for these connections only; the tailnet URL keeps ordinary,
fully verified TLS.

The check runs inside `wrap_socket`, right after the TLS handshake and before
a single byte of HTTP is written. So a machine that isn't the hub never sees
the device token: not in a request, not in the WebSocket upgrade.
"""

from __future__ import annotations

import hashlib
import hmac
import re
import ssl

FINGERPRINT = re.compile(r"^[0-9a-f]{64}$")


class HubError(Exception):
    """Talking to the hub failed. `pair` is set when the hub says this device isn't let in."""

    def __init__(self, message: str, *, pair: bool = False):
        super().__init__(message)
        self.pair = pair


class PinMismatch(HubError):
    """The machine at a LAN address presented a certificate other than the pinned one."""

    def __init__(self, where: str, expected: str, got: str | None):
        self.where = where
        self.expected = expected
        self.got = got
        super().__init__(
            f"the hub's identity changed: {where} presented a certificate with fingerprint "
            f"{got or 'none'}, not the pinned {expected}. Either that isn't your hub, or its "
            "certificate was regenerated. Nothing was sent to it. If you trust it, re-pair "
            "with: droplet-agent setup")


def fingerprint(der: bytes) -> str:
    """SHA-256 of a DER certificate, lowercase hex: the form /api/hub/info and mDNS use."""
    return hashlib.sha256(der).hexdigest()


def normalize_fingerprint(fp) -> str | None:
    """A valid 64-hex fingerprint, lowercased, or None. Colons (openssl's format) are dropped."""
    if not isinstance(fp, str):
        return None
    fp = fp.replace(":", "").strip().lower()
    return fp if FINGERPRINT.match(fp) else None


def check_peer(sock: ssl.SSLSocket, pin: str, where: str = "the hub"):
    """Raise PinMismatch unless the connected peer's certificate matches `pin`."""
    der = sock.getpeercert(binary_form=True)
    got = fingerprint(der) if der else None
    if got is None or not hmac.compare_digest(got, pin):
        raise PinMismatch(where, pin, got)


class PinnedContext(ssl.SSLContext):
    """A client context that trusts exactly one certificate, by fingerprint.

    urllib (http.client) and websockets both call `wrap_socket` on an already
    connected socket, so the handshake completes in there and the pin is
    checked before either of them sends anything.
    """

    pin: str

    def wrap_socket(self, sock, *args, **kwargs):
        kwargs["do_handshake_on_connect"] = True
        tls = super().wrap_socket(sock, *args, **kwargs)
        try:
            try:
                where = "%s:%s" % tls.getpeername()[:2]
            except OSError:
                where = "the hub"
            check_peer(tls, self.pin, where)
        except BaseException:
            tls.close()
            raise
        return tls


def pinned_context(pin: str) -> PinnedContext:
    good = normalize_fingerprint(pin)
    if good is None:
        raise ValueError(f"not a SHA-256 fingerprint: {pin!r}")
    ctx = PinnedContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False          # the certificate is pinned instead
    ctx.verify_mode = ssl.CERT_NONE     # a self-signed certificate has no CA to check
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.pin = good
    return ctx
