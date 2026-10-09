"""Mutual TLS between two fresh mesh identities, the way the mesh does it, on this Python's ssl.
Not collected by pytest: `python agent/tests/tls_probe.py` prints what each variant does."""

import socket
import ssl
import sys
import tempfile
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from droplet_agent.mesh import identity, tlsctx  # noqa: E402

print(sys.version.split()[0], ssl.OPENSSL_VERSION, "HAS_TLSv1_3", ssl.HAS_TLSv1_3)
d = Path(tempfile.mkdtemp())
a = identity.load_or_create(d / "a")
b = identity.load_or_create(d / "b")


def attempt(label, server_ctx, client_ctx):
    srv = socket.socket()
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    out = {}

    def serve():
        conn, _ = srv.accept()
        try:
            tls = server_ctx.wrap_socket(conn, server_side=True)
            out["server"] = "ok, client fp " + str(tlsctx.peer_fingerprint(tls))[:12]
            tls.close()
        except Exception as e:
            out["server"] = f"{type(e).__name__}: {e}"
            conn.close()
    t = threading.Thread(target=serve)
    t.start()
    try:
        c = client_ctx.wrap_socket(socket.create_connection(srv.getsockname()))
        out["client"] = f"ok {c.version()} {c.cipher()[0]}"
        c.close()
    except Exception as e:
        out["client"] = f"{type(e).__name__}: {e}"
    t.join(5)
    srv.close()
    print(f"{label}:\n  server: {out.get('server')}\n  client: {out.get('client')}")


attempt("as the mesh does it", tlsctx.server_context(b, [a.cert_pem]), tlsctx.client_context(a, b.fp))
ctx = tlsctx.server_context(b, [a.cert_pem])
ctx.verify_flags |= 0x80000   # X509_V_FLAG_PARTIAL_CHAIN
attempt("with PARTIAL_CHAIN", ctx, tlsctx.client_context(a, b.fp))
attempt("no client certificate", tlsctx.server_context(b, [a.cert_pem]), tlsctx.client_context(None, b.fp))
