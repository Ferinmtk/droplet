"""The mesh's mutual TLS on this Python's own ssl (OpenSSL, or a Mac's LibreSSL). Not collected by
pytest: `python agent/tests/tls_probe.py` exits non-zero if a trusted peer can't connect, or if an
untrusted one, or one whose certificate a trusted peer signed, can."""

import datetime
import socket
import ssl
import sys
import tempfile
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from droplet_agent.mesh import identity, tlsctx  # noqa: E402

print(sys.version.split()[0], ssl.OPENSSL_VERSION)
d = Path(tempfile.mkdtemp())
a = identity.load_or_create(d / "a")      # trusted by b
b = identity.load_or_create(d / "b")      # the server
c = identity.load_or_create(d / "c")      # a stranger


def signed_by(issuer: identity.Identity, where: Path) -> identity.Identity:
    """A certificate for a new key, signed by `issuer`'s key: a trusted peer trying to vouch."""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    issuer_key = serialization.load_pem_private_key(issuer.key_path.read_bytes(), None)
    issuer_cert = x509.load_pem_x509_certificate(issuer.cert_pem.encode())
    key = ec.generate_private_key(ec.SECP256R1())
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder()
            .subject_name(x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME, "vouched-for")]))
            .issuer_name(issuer_cert.subject).public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=9))
            .sign(issuer_key, hashes.SHA256()))
    where.mkdir(parents=True)
    (where / "key.pem").write_bytes(key.private_bytes(serialization.Encoding.PEM,
                                                      serialization.PrivateFormat.PKCS8,
                                                      serialization.NoEncryption()))
    pem = cert.public_bytes(serialization.Encoding.PEM).decode()
    (where / "cert.pem").write_text(pem)
    der = cert.public_bytes(serialization.Encoding.DER)
    return identity.Identity(where / "key.pem", where / "cert.pem", pem, der, identity.fingerprint(der), "x")


def attempt(label, client_identity, want_ok: bool) -> bool:
    srv = socket.socket()
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    out = {}

    def serve():
        conn, _ = srv.accept()
        try:
            tls = tlsctx.server_context(b, [a.cert_pem]).wrap_socket(conn, server_side=True)
            fp = tlsctx.peer_fingerprint(tls)
            out["server"] = "ok, client " + (fp[:12] if fp else "with no certificate")
            out["ok"] = fp is None or fp == a.fp     # what server.py then requires
            tls.close()
        except Exception as e:
            out["server"] = f"refused: {type(e).__name__}: {e}"
            out["ok"] = False
            conn.close()
    t = threading.Thread(target=serve)
    t.start()
    try:
        cl = tlsctx.client_context(client_identity, b.fp).wrap_socket(socket.create_connection(srv.getsockname()))
        out["client"] = f"ok, {cl.version()} {cl.cipher()[0]}"
        cl.close()
    except Exception as e:
        out["client"] = f"{type(e).__name__}: {e}"
    t.join(5)
    srv.close()
    good = out.get("ok") is want_ok
    print(f"{'PASS' if good else 'FAIL'}  {label}\n      server: {out.get('server')}\n      client: {out.get('client')}")
    return good


results = [
    attempt("a trusted peer connects", a, True),
    attempt("no certificate (pairing) connects", None, True),
    attempt("a stranger's certificate is refused", c, False),
    attempt("a certificate the trusted peer signed is refused", signed_by(a, d / "v"), False),
]
sys.exit(0 if all(results) else 1)
