"""End-to-end check of local-first routing against a real hub. Not collected by pytest.

Start a throwaway hub listening on the LAN, then run this with a Python that
has the agent's dependencies plus cryptography:

    DROPLET_HOME=$(mktemp -d) DROPLET_PORT=8851 DROPLET_LAN_TLS_PORT=8852 DROPLET_HOST=0.0.0.0 \\
        DROPLET_PUSH=0 python app.py &
    python agent/tests/e2e_lan.py 8851

It checks, with the real hub:
- the hub is found over mDNS, with its id, fingerprint and ports;
- on the hub machine, the agent picks hub-local (loopback), also when it
  only learns the port from mDNS;
- a dry-run agent connected over the pinned LAN route falls back to a
  (fake) tailnet when the LAN goes away, and moves back when it returns.

The "LAN" here is a TCP forwarder to the hub's LAN HTTPS port that can be
switched off, and the "tailnet" is a TLS proxy to the hub with a certificate
from a throwaway CA the agent is told to trust (SSL_CERT_FILE), so it goes
through ordinary verification like the real tailnet.
"""

import datetime
import json
import os
import socket
import ssl
import sys
import tempfile
import threading
import time
import urllib.request
from http.cookies import SimpleCookie
from pathlib import Path

AGENT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AGENT_DIR))

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8851
TMP = Path(tempfile.mkdtemp(prefix="droplet-e2e-lan-"))
os.environ["XDG_CONFIG_HOME"] = str(TMP / "config")
os.environ["XDG_DATA_HOME"] = str(TMP / "data")

from droplet_agent import config, discovery, hub, routes  # noqa: E402
from droplet_agent.agent import Agent  # noqa: E402
from droplet_agent.connection import Connection  # noqa: E402

checks = []


def check(name, ok, detail=""):
    checks.append((name, bool(ok)))
    print(f"  {'PASS' if ok else 'FAIL'}  {name}{'  ' + str(detail) if detail and not ok else ''}", flush=True)


def wait_for(cond, timeout):
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.1)
    return False


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.connect(("192.0.2.1", 9))
    try:
        return s.getsockname()[0]
    finally:
        s.close()


# --- a switchable "LAN" and a fake "tailnet" -------------------------------------------

def pump(a, b):
    try:
        while True:
            data = a.recv(65536)
            if not data:
                break
            b.sendall(data)
    except OSError:
        pass
    finally:
        for s in (a, b):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


class Forwarder:
    """Listens on `listen`, forwards bytes to `target`, optionally terminating TLS first."""

    def __init__(self, listen, target, tls: ssl.SSLContext | None = None):
        self.listen, self.target, self.tls = listen, target, tls
        self.conns: list = []
        self.sock = None

    def start(self):
        self.sock = socket.socket()
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(self.listen)
        self.sock.listen(16)
        threading.Thread(target=self._accept, args=(self.sock,), daemon=True).start()
        return self

    def _accept(self, lsock):
        while True:
            try:
                c, _ = lsock.accept()
            except OSError:
                return
            threading.Thread(target=self._one, args=(c,), daemon=True).start()

    def _one(self, c):
        try:
            if self.tls:
                c = self.tls.wrap_socket(c, server_side=True)
            up = socket.create_connection(self.target, timeout=5)
            up.settimeout(None)
        except OSError:
            c.close()
            return
        self.conns += [c, up]
        threading.Thread(target=pump, args=(c, up), daemon=True).start()
        threading.Thread(target=pump, args=(up, c), daemon=True).start()

    def stop(self):
        if self.sock:
            try:
                self.sock.shutdown(socket.SHUT_RDWR)  # wakes the accept() blocked on it
            except OSError:
                pass
            self.sock.close()
            self.sock = None
        for s in self.conns:
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            s.close()
        self.conns = []


def make_ca_and_cert(d: Path):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    now = datetime.datetime.now(datetime.timezone.utc)
    ca_key = ec.generate_private_key(ec.SECP256R1())
    ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "droplet e2e test CA")])
    ca = (x509.CertificateBuilder().subject_name(ca_name).issuer_name(ca_name).public_key(ca_key.public_key())
          .serial_number(x509.random_serial_number()).not_valid_before(now)
          .not_valid_after(now + datetime.timedelta(days=1))
          .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
          .add_extension(x509.KeyUsage(digital_signature=True, key_cert_sign=True, crl_sign=True,
                                       content_commitment=False, key_encipherment=False,
                                       data_encipherment=False, key_agreement=False,
                                       encipher_only=False, decipher_only=False), critical=True)
          .add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()), critical=False)
          .sign(ca_key, hashes.SHA256()))
    key = ec.generate_private_key(ec.SECP256R1())
    cert = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")]))
            .issuer_name(ca_name).public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now).not_valid_after(now + datetime.timedelta(days=1))
            .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost")]), critical=False)
            .add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
            .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
            .sign(ca_key, hashes.SHA256()))
    (d / "ca.pem").write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    (d / "cert.pem").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    (d / "key.pem").write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                  serialization.NoEncryption()))


class NoLoopback(routes.Router):
    """The agent as it would be on another machine: no hub on its loopback.

    With `lan_only`, the LAN address and port stay the forwarder's: the real
    router would learn the hub's own port from /api/hub/info, which this
    machine can always reach, so the LAN could never go away.
    """

    lan_only: tuple | None = None

    def _local_route(self, port):
        return hub.Route("hub-local", "http://127.0.0.1:1")

    def _learn(self, route, info):
        super()._learn(route, info)
        if self.lan_only:
            self.identity["lan"], self.identity["https_port"] = [self.lan_only[0]], self.lan_only[1]


def main() -> int:
    # before any HTTPS opener exists: urllib makes its default context once, up front
    make_ca_and_cert(TMP)
    os.environ["SSL_CERT_FILE"] = str(TMP / "ca.pem")  # the fake tailnet's CA, and only it
    ip = lan_ip()
    local = f"http://127.0.0.1:{PORT}"
    info = hub.info(local)
    print(f"hub {info['name']} (id {info['id']}) at {ip}, LAN https port {info['https_port']}")

    # --- mDNS ------------------------------------------------------------------------
    print("discovery")
    mine = discovery.browse(timeout=3, want_id=info["id"])
    check("the hub is found over mDNS", len(mine) == 1, mine)
    if mine:
        f = mine[0]
        check("TXT: fingerprint, ports and address match /api/hub/info",
              (f.fingerprint, f.https_port, f.http_port) == (info["fingerprint"], info["https_port"], PORT)
              and ip in f.addresses, f)

    # a device that's in (registered from loopback, which the hub trusts)
    req = urllib.request.Request(f"{local}/api/device", method="POST", data=json.dumps({"name": f"e2e-lan-{os.getpid()}"}).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        dev = json.loads(r.read())
        c = SimpleCookie()
        c.load(r.headers["Set-Cookie"])
        token = c["droplet_device"].value

    # --- hub-local -------------------------------------------------------------------
    print("on the hub machine")
    cfg = config.load()
    cfg.update(hub=f"http://{ip}:{PORT}", token=token, device={"id": dev["id"], "name": dev["name"]})
    cfg["hub_identity"] = routes.identity_from(info)
    r = routes.Router(cfg, persist=False)
    route = r.select()
    check("hub-local is chosen here", route == hub.Route("hub-local", local), route)
    cfg["hub_identity"]["http_port"] = None
    cfg["hub_identity"]["lan"] = []
    r = routes.Router(cfg, persist=False)
    route = r.select()
    check("hub-local found through mDNS (the hub announces one of this machine's addresses)",
          route == hub.Route("hub-local", local) and r.on_hub_machine(), route)
    route = NoLoopback(cfg, persist=False, discover=lambda **k: []).select()
    check("without loopback, the pinned LAN route is next",
          route == hub.Route("lan", f"https://{ip}:{info['https_port']}", info["fingerprint"]), route)

    # --- fallback to the tailnet and back ------------------------------------------------
    print("LAN → tailnet → LAN")
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(str(TMP / "cert.pem"), str(TMP / "key.pem"))
    tailnet = Forwarder(("127.0.0.1", 0), ("127.0.0.1", PORT), tls).start()
    tailnet_url = f"https://localhost:{tailnet.sock.getsockname()[1]}"
    lan = Forwarder((ip, 0), (ip, info["https_port"])).start()
    lan_port = lan.sock.getsockname()[1]
    check("the fake tailnet passes ordinary TLS verification", hub.info(tailnet_url)["id"] == info["id"])

    cfg["hub_identity"].update(https_port=lan_port, lan=[ip], tailnet=tailnet_url)
    cfg["hub"] = tailnet_url
    router = NoLoopback(cfg, persist=False, discover=lambda **k: [])
    router.lan_only = (ip, lan_port)
    agent = Agent(cfg, dry_run=True)
    conn = Connection(agent, router, token, net_poll=1, lan_recheck=3)
    agent.start()
    t = threading.Thread(target=conn.run, args=(agent.stop,), daemon=True)
    t.start()

    def on(kind):
        return lambda: conn.ws is not None and conn.route is not None and conn.route.kind == kind

    check("connects over the pinned LAN", wait_for(on("lan"), 10), conn.route)
    if conn.route and conn.route.kind == "lan":
        from droplet_agent.screenshot import fake_png
        saved = hub.upload(conn.route, token, dev["id"], "e2e-lan.png", fake_png())
        check("an upload goes over the pinned LAN route too", saved.get("saved"), saved)
    lan.stop()
    check("the LAN goes away: falls back to the tailnet", wait_for(on("tailnet"), 20), conn.route)
    lan = Forwarder((ip, lan_port), (ip, info["https_port"])).start()
    check("the LAN comes back: moves back to it", wait_for(on("lan"), 20), conn.route)

    agent.stop.set()
    conn.reconnect()
    t.join(10)
    agent.close()
    lan.stop()
    tailnet.stop()

    failed = [n for n, ok in checks if not ok]
    print(f"\n{len(checks) - len(failed)}/{len(checks)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
