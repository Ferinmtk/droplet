"""The hub's mesh roster (mesh.py): announcing, who sees the roster, and what's in it.

Run from the repo root:  python -m unittest tests.test_mesh_roster
"""

import datetime
import hashlib
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
os.environ["DROPLET_HOME"] = tempfile.mkdtemp(prefix="droplet-mesh-test-")
os.environ["DROPLET_PUSH"] = "0"

from cryptography import x509  # noqa: E402
from cryptography.hazmat.primitives import hashes, serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402
from cryptography.x509.oid import NameOID  # noqa: E402

import app as hub_app  # noqa: E402
import remote  # noqa: E402


def make_cert(cn="peer"):
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn)])
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(datetime.datetime(2020, 1, 1))
            .not_valid_after(datetime.datetime(2050, 1, 1)).sign(key, hashes.SHA256()))
    pem = cert.public_bytes(serialization.Encoding.PEM).decode()
    return pem, hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()


class RosterTest(unittest.TestCase):
    def setUp(self):
        self.client = hub_app.app.test_client()
        self.broadcasts = []
        self._orig = remote.hub.broadcast
        remote.hub.broadcast = lambda msg, **kw: self.broadcasts.append(msg)
        # REMOTE_ADDR other than loopback: not trusted just for being on the hub machine
        self.env = {"REMOTE_ADDR": "192.168.1.50"}

    def tearDown(self):
        remote.hub.broadcast = self._orig

    def device(self, name, approved=True):
        dev, token = hub_app.devices.create(f"{name}-{os.urandom(3).hex()}", approved=approved)
        return dev, {"Authorization": f"Bearer {token}"}

    def announce(self, auth, pem, fp, **extra):
        body = {"fp": fp, "cert_pem": pem, "port": 1739, "lan": ["192.168.1.20", "127.0.0.1", "nonsense",
                                                                 "100.100.1.2"], "os": "linux",
                "caps": ["input", "media", "BAD CAP"], **extra}
        return self.client.post("/api/mesh/announce", json=body, headers=auth, environ_base=self.env)

    def roster(self, auth):
        return self.client.get("/api/mesh/roster", headers=auth, environ_base=self.env)

    def test_approved_devices_see_each_other(self):
        a, auth_a = self.device("a")
        b, auth_b = self.device("b")
        pem_a, fp_a = make_cert("a")
        pem_b, fp_b = make_cert("b")
        self.assertEqual(self.announce(auth_a, pem_a, fp_a).status_code, 200)
        self.assertEqual(self.announce(auth_b, pem_b, fp_b).status_code, 200)
        self.assertIn({"t": "roster"}, self.broadcasts)
        r = self.roster(auth_a).get_json()
        self.assertEqual(r["hub"], hub_app.HUB_ID)
        mine = [p for p in r["peers"] if p["id"] in (a["id"], b["id"])]
        self.assertEqual([p["id"] for p in mine], [b["id"]])      # not the caller itself
        p = mine[0]
        self.assertEqual(p["fp"], fp_b)
        self.assertEqual(p["lan"], ["192.168.1.20"])               # no loopback, junk or tailnet in "lan"
        self.assertEqual(p["caps"], ["input", "media"])
        self.assertEqual(set(p), {"id", "name", "fp", "cert_pem", "port", "lan", "tailnet_ip", "caps", "os"})
        self.assertNotIn("token", str(r))

    def test_only_approved_devices_announce_or_read(self):
        _, auth_p = self.device("pending", approved=False)
        pem, fp = make_cert()
        self.assertEqual(self.announce(auth_p, pem, fp).status_code, 403)
        self.assertEqual(self.roster(auth_p).status_code, 403)
        self.assertEqual(self.client.get("/api/mesh/roster", environ_base=self.env).status_code, 403)
        # a pending device that announced earlier (impossible, but) never appears
        p, _ = self.device("p2", approved=False)
        hub_app.devices.update(p["id"], mesh={"fp": fp, "cert_pem": pem, "port": 1739})
        _, auth = self.device("reader")
        self.assertNotIn(p["id"], [x["id"] for x in self.roster(auth).get_json()["peers"]])

    def test_bad_announcements_are_refused(self):
        _, auth = self.device("x")
        pem, fp = make_cert()
        other_pem, _ = make_cert()
        self.assertEqual(self.announce(auth, other_pem, fp).status_code, 400)    # fp doesn't match
        self.assertEqual(self.announce(auth, "junk", fp).status_code, 400)
        self.assertEqual(self.announce(auth, pem + pem, fp).status_code, 400)
        self.assertEqual(self.announce(auth, pem, fp, port=70000).status_code, 400)
        self.assertEqual(self.announce(auth, pem, fp).status_code, 200)
        _, auth2 = self.device("y")
        self.assertEqual(self.announce(auth2, pem, fp).status_code, 409)         # someone else's certificate

    def test_removing_a_device_drops_it_and_says_so(self):
        a, auth_a = self.device("a")
        b, auth_b = self.device("b")
        pem, fp = make_cert()
        self.announce(auth_b, pem, fp)
        self.assertIn(b["id"], [p["id"] for p in self.roster(auth_a).get_json()["peers"]])
        self.broadcasts.clear()
        r = self.client.post(f"/api/device/{b['id']}/remove", headers=auth_a, environ_base=self.env)
        self.assertEqual(r.status_code, 200)
        self.assertIn({"t": "roster"}, self.broadcasts)
        self.assertNotIn(b["id"], [p["id"] for p in self.roster(auth_a).get_json()["peers"]])

    def test_tailnet_ip_comes_only_through_serve(self):
        _, auth = self.device("t")
        pem, fp = make_cert()
        # from the LAN, a forged X-Forwarded-For is ignored
        self.announce(auth, pem, fp)
        r = self.client.post("/api/mesh/announce", headers={**auth, "X-Forwarded-For": "100.64.1.1"},
                             json={"fp": fp, "cert_pem": pem, "port": 1739}, environ_base=self.env)
        self.assertEqual(r.status_code, 200)
        dev = hub_app.devices.by_token(auth["Authorization"][7:])
        self.assertIsNone(dev["mesh"]["tailnet_ip"])
        # through tailscale serve (loopback, with the tailnet on), it's recorded
        old = hub_app.TAILNET_URL
        hub_app.TAILNET_URL = "https://hub.example.ts.net"
        try:
            r = self.client.post("/api/mesh/announce", headers={**auth, "X-Forwarded-For": "100.64.1.1"},
                                 json={"fp": fp, "cert_pem": pem, "port": 1739},
                                 environ_base={"REMOTE_ADDR": "127.0.0.1"})
        finally:
            hub_app.TAILNET_URL = old
        self.assertEqual(r.status_code, 200)
        self.assertEqual(hub_app.devices.by_token(auth["Authorization"][7:])["mesh"]["tailnet_ip"], "100.64.1.1")


if __name__ == "__main__":
    unittest.main()
