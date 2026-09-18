"""Mutual TLS between peers, with self-signed certificates and no CA.

**Server.** Python's ssl module has no verify callback, so it can't accept
an arbitrary self-signed client certificate and decide later. Instead:

- every trusted peer's full certificate is loaded as a trust anchor, and the
  context asks for a client certificate with CERT_OPTIONAL;
- a client presenting a trusted certificate completes the handshake;
- a client presenting any other certificate fails the handshake: OpenSSL
  refuses it ("unknown CA") before a byte of HTTP is read;
- a client presenting no certificate completes the handshake, and may only
  use the pairing endpoints (server.py enforces that);
- after the handshake, the SHA-256 of the presented leaf certificate must be
  in the trust list. That's defence in depth: OpenSSL checks chains, and a
  trusted peer's certificate must never be able to vouch for another one.

The trust list changes (pairing, the roster), and an OpenSSL store can't drop
a certificate, so the server builds a fresh context for each change and
uses the newest one for each new connection (`ServerContexts`).

**Client.** It presents its own certificate, doesn't verify the server's
chain, and checks the server's fingerprint against the expected one right
after the handshake, before sending anything.
"""

from __future__ import annotations

import hmac
import ssl
import threading

from .identity import Identity, fingerprint


class FingerprintMismatch(ssl.SSLError):
    def __init__(self, where: str, expected: str, got: str | None):
        self.where, self.expected, self.got = where, expected, got
        super().__init__(f"{where} presented certificate {got or 'none'}, not the expected {expected}")


def peer_fingerprint(sock: ssl.SSLSocket) -> str | None:
    der = sock.getpeercert(binary_form=True)
    return fingerprint(der) if der else None


def server_context(identity: Identity, trusted_pems: list[str]) -> ssl.SSLContext:
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(str(identity.cert_path), str(identity.key_path))
    ctx.verify_mode = ssl.CERT_OPTIONAL
    pems = "".join(p if p.endswith("\n") else p + "\n" for p in trusted_pems)
    if pems:
        ctx.load_verify_locations(cadata=pems)
    return ctx


class ServerContexts:
    """The current server context, rebuilt whenever the trust list changes."""

    def __init__(self, identity: Identity, trusted_pems: list[str]):
        self.identity = identity
        self._lock = threading.Lock()
        self._ctx = server_context(identity, trusted_pems)

    def update(self, trusted_pems: list[str]):
        ctx = server_context(self.identity, trusted_pems)
        with self._lock:
            self._ctx = ctx

    def current(self) -> ssl.SSLContext:
        with self._lock:
            return self._ctx


class _CheckedClientContext(ssl.SSLContext):
    """Checks the server's fingerprint inside wrap_socket, right after the handshake."""

    expect: str | None = None

    def wrap_socket(self, sock, *args, **kwargs):
        kwargs["do_handshake_on_connect"] = True
        tls = super().wrap_socket(sock, *args, **kwargs)
        if self.expect is not None:
            got = peer_fingerprint(tls)
            if got is None or not hmac.compare_digest(got, self.expect):
                try:
                    where = "%s:%s" % tls.getpeername()[:2]
                except OSError:
                    where = "the peer"
                tls.close()
                raise FingerprintMismatch(where, self.expect, got)
        return tls


def client_context(identity: Identity | None, expect_fp: str | None) -> ssl.SSLContext:
    """A client context presenting `identity` (None: no certificate, for pairing),
    accepting only a server whose certificate has `expect_fp` (None: any, for first
    contact when pairing by address; the pairing code then confirms it)."""
    ctx = _CheckedClientContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.check_hostname = False        # no names: the fingerprint is the identity
    ctx.verify_mode = ssl.CERT_NONE   # no CA: checked by fingerprint instead
    if identity is not None:
        ctx.load_cert_chain(str(identity.cert_path), str(identity.key_path))
    ctx.expect = expect_fp
    return ctx
