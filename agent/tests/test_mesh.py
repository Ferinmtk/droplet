"""The mesh peer: identity, trust, mutual TLS, pairing, dispatch, routing, the outbox, files."""

import base64
import datetime
import hashlib
import http.client
import json
import os
import socket
import ssl
import stat
import threading
import time
from pathlib import Path

import pytest

from droplet_agent.mesh import discovery, files, identity, pairing, tlsctx
from droplet_agent.mesh.node import Host, MeshNode, NoRoute
from droplet_agent.mesh.outbox import DONE, QUEUED, Outbox
from droplet_agent.mesh.trust import TrustList, make_entry


def wait_for(cond, timeout=5):
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.02)
    return False


class FakeHost(Host):
    def __init__(self, name, peer_id=None):
        self.name = name
        self.peer_id = peer_id
        self.dispatched = []
        self.hub_up = False
        self.hub = "ab" * 8
        self.online = set()
        self.hub_calls = []

    def device_name(self): return self.name
    def mesh_caps(self): return ["input", "media"]
    def hub_device_id(self): return self.peer_id
    def hub_id(self): return self.hub
    def dispatch_remote(self, msg, source): self.dispatched.append((msg, source))
    def hub_connected(self): return self.hub_up
    def hub_online(self, device_id): return device_id in self.online

    def hub_send(self, msg):
        self.hub_calls.append(("ws", msg))
        return self.hub_up

    def hub_text(self, device_id, body):
        if not self.hub_up:
            raise NoRoute("down")
        self.hub_calls.append(("text", device_id, body))

    def hub_upload(self, device_id, path, name, mime):
        self.hub_calls.append(("upload", device_id, name))

    def hub_ring(self, device_id, stop):
        self.hub_calls.append(("ring", device_id, stop))


def make_node(tmp_path, name, port=0, host=None):
    host = host or FakeHost(name)
    base = tmp_path / name
    n = MeshNode(host, config_dir=base / "cfg", data_dir=base / "data", downloads=base / "dl", port=port,
                 announce=False, control=False, retry_every=0.3, local_addresses=lambda: ["127.0.0.1"],
                 dry_run=True)
    n.start()
    return n


def trust_each_other(a, b, source="paired", hub=None):
    for x, y in ((a, b), (b, a)):
        e = make_entry(peer_id=y.peer_id, name=y.name, cert_pem=y.identity.cert_pem, source=source,
                       lan=["127.0.0.1"], port=y.port, hub=hub)
        if source == "paired":
            x.trust.add_paired(e)
        else:
            x.trust.sync_roster([e], hub)


@pytest.fixture
def nodes(tmp_path):
    made = []

    def make(name, **kw):
        n = make_node(tmp_path, name, **kw)
        made.append(n)
        return n
    yield make
    for n in made:
        n.close()


# --- identity -----------------------------------------------------------------------

def test_identity_is_made_once_and_kept_private(tmp_path):
    d = tmp_path / "mesh"
    one = identity.load_or_create(d)
    two = identity.load_or_create(d)
    assert one.fp == two.fp == hashlib.sha256(one.der).hexdigest()
    assert one.local_id == two.local_id and len(one.local_id) == 16
    assert stat.S_IMODE(d.stat().st_mode) == 0o700
    for f in ("key.pem", "cert.pem", "identity.json"):
        assert stat.S_IMODE((d / f).stat().st_mode) == 0o600
    from cryptography import x509
    cert = x509.load_der_x509_certificate(one.der)
    assert cert.public_key().curve.name == "secp256r1"
    assert cert.extensions.get_extension_for_class(x509.BasicConstraints).value.ca is False
    assert cert.not_valid_after_utc > datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=365 * 29)
    assert cert.not_valid_before_utc <= datetime.datetime(2020, 1, 1, tzinfo=datetime.timezone.utc)


def test_peer_id_is_the_hub_device_id_when_linked(tmp_path):
    ident = identity.load_or_create(tmp_path / "m")
    assert ident.peer_id(None) == ident.local_id
    assert ident.peer_id("8a0f1234abcd") == "8a0f1234abcd"
    assert ident.peer_id("not hex!") == ident.local_id


def test_sign_and_verify(tmp_path):
    ident = identity.load_or_create(tmp_path / "m")
    other = identity.load_or_create(tmp_path / "o")
    sig = identity.sign(ident, b"data")
    assert identity.verify(ident.der, sig, b"data")
    assert not identity.verify(ident.der, sig, b"datA")
    assert not identity.verify(other.der, sig, b"data")
    assert not identity.verify(ident.der, b"junk", b"data")


# --- TXT records ------------------------------------------------------------------------

def test_txt_records_round_trip():
    fp = "ab" * 32
    txt = discovery.txt_records(peer_id="0123456789abcdef", fp=fp, name="  slim\n laptop ",
                                caps=["media", "input", "bad cap!"], hub_id="9b16173d305cd15a")
    assert txt == {"id": "0123456789abcdef", "fp": fp, "name": "slim laptop", "os": "linux",
                   "caps": "input,media", "hub": "9b16173d305cd15a", "v": "1"}
    wire = {k.encode(): v.encode() for k, v in txt.items()}
    got = discovery.parse_txt(wire)
    assert got == {"id": "0123456789abcdef", "fp": fp, "name": "slim laptop", "os": "linux",
                   "caps": ["input", "media"], "hub": "9b16173d305cd15a", "v": 1}
    assert discovery.parse_txt({**wire, b"hub": b""})["hub"] == ""
    assert discovery.parse_txt({**wire, b"os": b"beos"})["os"] == ""


@pytest.mark.parametrize("change", [{b"id": b"zz"}, {b"fp": b"1234"}, {b"v": b""}, {b"fp": None},
                                    {b"id": b"\xff\xfe"}])
def test_txt_records_that_are_not_a_peer(change):
    wire = {b"id": b"0123456789abcdef", b"fp": b"ab" * 32, b"name": b"x", b"v": b"1"}
    wire.update(change)
    assert discovery.parse_txt(wire) is None


# --- the trust list ---------------------------------------------------------------------

def test_trust_list_roster_and_paired(tmp_path):
    ids = [identity.load_or_create(tmp_path / n) for n in ("me", "p1", "p2", "p3")]
    me = ids[0]
    tl = TrustList(tmp_path / "trust.json", me.fp)
    hub = "cd" * 8

    def entry(i, source, **kw):
        return make_entry(peer_id=f"{i:012x}", name=f"p{i}", cert_pem=ids[i].cert_pem, source=source, hub=hub, **kw)
    tl.add_paired(entry(1, "paired"))
    assert tl.sync_roster([entry(1, "roster", lan=["192.168.1.5"]), entry(2, "roster"),
                           make_entry(peer_id="aaaaaaaaaaaa", name="me", cert_pem=me.cert_pem, source="roster")],
                          hub) == (1, 0)
    assert tl.get(ids[1].fp)["source"] == "paired"           # paired stays paired
    assert tl.get(ids[1].fp)["lan"] == ["192.168.1.5"]        # and learns where it is
    assert tl.get(me.fp) is None                              # never trusts itself
    # the hub removes p2 and adds p3; p1 isn't on the roster any more but stays paired
    assert tl.sync_roster([entry(3, "roster")], hub) == (1, 1)
    assert tl.get(ids[2].fp) is None and tl.get(ids[3].fp) and tl.get(ids[1].fp)
    assert stat.S_IMODE((tmp_path / "trust.json").stat().st_mode) == 0o600
    # persisted, and a tampered entry is dropped rather than trusted
    raw = json.loads((tmp_path / "trust.json").read_text())
    raw["peers"][ids[3].fp]["cert_pem"] = ids[2].cert_pem
    (tmp_path / "trust.json").write_text(json.dumps(raw))
    again = TrustList(tmp_path / "trust.json", me.fp)
    assert again.get(ids[3].fp) is None and again.get(ids[1].fp)


def test_make_entry_checks_the_fingerprint(tmp_path):
    ident = identity.load_or_create(tmp_path / "x")
    with pytest.raises(ValueError):
        make_entry(peer_id="0123456789ab", name="x", cert_pem=ident.cert_pem, source="roster", fp="00" * 32)
    with pytest.raises(ValueError):
        make_entry(peer_id="0123456789ab", name="x", cert_pem="-----BEGIN CERTIFICATE-----\nAAAA\n"
                   "-----END CERTIFICATE-----\n", source="roster")


# --- mutual TLS -------------------------------------------------------------------------

def _raw_tls(port, ident, expect=None):
    ctx = tlsctx.client_context(ident, expect)
    s = socket.create_connection(("127.0.0.1", port), timeout=5)
    return ctx.wrap_socket(s)


def _get(tls, path, method="GET", body=None):
    data = json.dumps(body).encode() if body is not None else b""
    head = f"{method} {path} HTTP/1.1\r\nHost: x\r\nContent-Length: {len(data)}\r\nConnection: close\r\n\r\n"
    tls.sendall(head.encode() + data)
    out = b""
    while True:
        try:
            chunk = tls.recv(65536)
        except ssl.SSLError:
            raise
        if not chunk:
            break
        out += chunk
    return int(out.split(b" ", 2)[1]), out


def test_untrusted_clients_only_reach_pairing(nodes, tmp_path):
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b)
    stranger = identity.load_or_create(tmp_path / "stranger")

    # a trusted peer gets past TLS and to the endpoints (the offer doesn't exist: 404)
    status, _ = _get(_raw_tls(b.port, a.identity, b.identity.fp), "/mesh/files/" + "0" * 32)
    assert status == 404

    # an untrusted certificate never gets past the handshake
    with pytest.raises((ssl.SSLError, ConnectionError)):
        _get(_raw_tls(b.port, stranger, b.identity.fp), "/mesh/pair", "POST", {})
    assert wait_for(lambda: b.server.refused >= 1)

    # no certificate: only pairing
    for path, method in (("/mesh", "GET"), ("/mesh/files/" + "0" * 32, "GET"), ("/nope", "GET")):
        status, _ = _get(_raw_tls(b.port, None, b.identity.fp), path, method)
        assert status == 403, path
    status, body = _get(_raw_tls(b.port, None, b.identity.fp), "/mesh/pair", "POST", {"id": "bad"})
    assert status == 400   # reached the pairing code, which checks the request


def test_a_trusted_peers_key_cannot_vouch_for_another_cert(nodes, tmp_path):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b)
    a_cert = x509.load_der_x509_certificate(a.identity.der)
    a_key = serialization.load_pem_private_key(a.identity.key_path.read_bytes(), None)
    key = ec.generate_private_key(ec.SECP256R1())
    child = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "child")]))
             .issuer_name(a_cert.subject).public_key(key.public_key()).serial_number(1)
             .not_valid_before(datetime.datetime(2020, 1, 1)).not_valid_after(datetime.datetime(2040, 1, 1))
             .sign(a_key, hashes.SHA256()))
    d = tmp_path / "child"
    d.mkdir()
    (d / "c.pem").write_bytes(child.public_bytes(serialization.Encoding.PEM))
    (d / "k.pem").write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                serialization.NoEncryption()))
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname, ctx.verify_mode = False, ssl.CERT_NONE
    ctx.load_cert_chain(str(d / "c.pem"), str(d / "k.pem"))
    with pytest.raises((ssl.SSLError, ConnectionError)):
        s = ctx.wrap_socket(socket.create_connection(("127.0.0.1", b.port), timeout=5))
        _get(s, "/mesh/files/" + "0" * 32)


def test_client_checks_the_servers_fingerprint(nodes):
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b)
    with pytest.raises(tlsctx.FingerprintMismatch):
        _raw_tls(b.port, a.identity, "00" * 32)


def test_unpairing_takes_effect_for_new_connections(nodes):
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b)
    assert a.direct(b.identity.fp) is not None
    b.trust.remove(a.identity.fp)
    assert wait_for(lambda: a.open_link(b.identity.fp) is None)   # b closed the link
    with pytest.raises((ssl.SSLError, ConnectionError)):
        _get(_raw_tls(b.port, a.identity, b.identity.fp), "/mesh/files/" + "0" * 32)


# --- pairing -----------------------------------------------------------------------------

def test_pairing_code_is_the_same_on_both_sides(nodes):
    a, b = nodes("a"), nodes("b")
    og = pairing.Outgoing(a.identity, a.peer_id, "a", "127.0.0.1", b.port, b.identity.fp)
    og.start()
    assert og.peer["fp"] == b.identity.fp and len(og.code) == 4 and og.code.isdigit()
    waiting = b.incoming.waiting()
    assert len(waiting) == 1 and waiting[0]["code"] == og.code and waiting[0]["fp"] == a.identity.fp
    assert og.poll() == "waiting"
    b.pair_answer(waiting[0]["request"], True)
    assert og.poll() == "accepted"
    assert b.trust.get(a.identity.fp)["source"] == "paired"


def test_code_derivation_is_order_sensitive():
    na, nb = pairing.new_nonce(), pairing.new_nonce()
    c = pairing.code("aa" * 32, "bb" * 32, na, nb)
    assert c == pairing.code("aa" * 32, "bb" * 32, na, nb)
    assert len({c, pairing.code("bb" * 32, "aa" * 32, na, nb), pairing.code("aa" * 32, "bb" * 32, nb, na)}) >= 2


def _pair_post(port, ident, commit, peer_id="0123456789abcdef"):
    conn = http.client.HTTPSConnection("127.0.0.1", port, context=tlsctx.client_context(None, None), timeout=5)
    status, out = pairing._call(conn, "POST", "/mesh/pair", {"v": 1, "id": peer_id, "name": "x", "cert": ident.cert_pem,
                                                              "commit": commit})
    return conn, status, out


def test_pairing_refuses_a_wrong_reveal_or_signature(nodes, tmp_path):
    b = nodes("b")
    me = identity.load_or_create(tmp_path / "me")
    other = identity.load_or_create(tmp_path / "other")
    na = pairing.new_nonce()

    # the nonce doesn't match the commitment
    conn, status, out = _pair_post(b.port, me, pairing.commitment(na))
    assert status == 200
    s, _ = pairing._call(conn, "POST", f"/mesh/pair/{out['request']}/confirm",
                         {"nonce": pairing.new_nonce(), "sig": base64.b64encode(b"x").decode()})
    assert s == 403

    # signed by a key other than the certificate's (someone else's certificate)
    conn, status, out = _pair_post(b.port, me, pairing.commitment(na))
    sig = identity.sign(other, pairing.transcript(me.fp, b.identity.fp, na, out["nonce"]))
    s, _ = pairing._call(conn, "POST", f"/mesh/pair/{out['request']}/confirm",
                         {"nonce": na, "sig": base64.b64encode(sig).decode()})
    assert s == 403

    # signed for a different responder: what a relay in the middle would forward
    conn, status, out = _pair_post(b.port, me, pairing.commitment(na))
    sig = identity.sign(me, pairing.transcript(me.fp, "ee" * 32, na, out["nonce"]))
    s, _ = pairing._call(conn, "POST", f"/mesh/pair/{out['request']}/confirm",
                         {"nonce": na, "sig": base64.b64encode(sig).decode()})
    assert s == 403
    assert b.incoming.waiting() == []
    assert b.trust.all() == []


def test_pairing_requests_are_limited(nodes, tmp_path):
    b = nodes("b")
    me = identity.load_or_create(tmp_path / "me")
    codes = []
    for i in range(pairing.MAX_OPEN + 1):
        _, status, _ = _pair_post(b.port, me, pairing.commitment(pairing.new_nonce()))
        codes.append(status)
    assert codes == [200] * pairing.MAX_OPEN + [429]


# --- dispatch ------------------------------------------------------------------------------

def test_messages_reach_the_agents_handlers_with_the_real_sender(nodes):
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b)
    ev = {"t": "input", "ev": [{"k": "key", "key": "Enter"}], "from": {"id": "spoofed", "name": "evil"}}
    assert a.send_live(b.identity.fp, ev) == "lan"
    assert wait_for(lambda: b.host.dispatched)
    msg, source = b.host.dispatched[0]
    assert msg["t"] == "input" and msg["ev"] == ev["ev"]
    assert msg["from"] == {"id": a.peer_id, "name": "a"}
    assert source.kind == "peer" and source.fp == a.identity.fp
    # replies (rpc-result) go back over the link
    got = []
    a.host.dispatch_remote = lambda m, s: got.append(m)
    assert source.reply({"t": "rpc-result", "id": "r1", "error": "no"})
    # (rpc-result is not dispatched to the agent; check it arrived by sending an rpc the other way)
    b.send_live(a.identity.fp, {"t": "cmd", "cmd": "lock"})
    assert wait_for(lambda: got and got[-1]["t"] == "cmd")


def test_text_is_acknowledged_and_stored_once(nodes):
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b)
    job = a.send_text(b.identity.fp, "hi there")
    done = a.outbox.wait(job["id"], lambda j: j["state"] == DONE, 5)
    assert done["state"] == DONE and done["route"] == "lan"
    got = [m for m in b.chat.recent() if m["dir"] == "in"]
    assert [m["body"] for m in got] == ["hi there"]
    # the same message again (a lost ack) isn't stored twice
    link = a.direct(b.identity.fp)
    link.send({"t": "text", "id": job["id"], "body": "hi there", "ts": 1})
    time.sleep(0.3)
    assert len([m for m in b.chat.recent() if m["dir"] == "in"]) == 1


# --- routing -------------------------------------------------------------------------------

def test_routing_order_direct_hub_mailbox_outbox(nodes, tmp_path):
    hub = "ab" * 8
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b, source="roster", hub=hub)
    fp = b.identity.fp
    # 1: direct
    j = a.send_text(fp, "one")
    assert a.outbox.wait(j["id"], lambda j: j["state"] == DONE, 5)["route"] == "lan"
    port = b.port
    b.close()
    time.sleep(0.2)
    # 3: through the hub (b is connected to it)
    a.host.hub_up = True
    a.host.online = {b.peer_id}
    j = a.send_text(fp, "two")
    assert a.outbox.wait(j["id"], lambda j: j["state"] == DONE, 8)["route"] == "hub"
    # 4: the hub's mailbox (b is offline)
    a.host.online = set()
    j = a.send_text(fp, "three")
    assert a.outbox.wait(j["id"], lambda j: j["state"] == DONE, 8)["route"] == "hub-mailbox"
    assert [c[2] for c in a.host.hub_calls if c[0] == "text"] == ["two", "three"]
    # live messages never queue: through the hub while the peer is connected to it, else an error
    with pytest.raises(NoRoute):
        a.send_live(fp, {"t": "input", "ev": []})
    a.host.online = {b.peer_id}
    assert a.send_live(fp, {"t": "input", "ev": []}) == "hub"
    assert a.host.hub_calls[-1] == ("ws", {"t": "input", "ev": [], "to": b.peer_id})
    # 5: nothing works: kept in the outbox, in order
    a.host.hub_up = False
    with pytest.raises(NoRoute):
        a.send_live(fp, {"t": "input", "ev": []})
    j4 = a.send_text(fp, "four")
    j5 = a.send_text(fp, "five")
    got = a.outbox.wait(j4["id"], lambda j: j["attempts"] > 0 and j["state"] != "sending", 8)
    assert got["state"] == QUEUED
    # b comes back (same port): both go out, in order
    b2 = nodes("b", port=port)
    assert a.outbox.wait(j5["id"], lambda j: j["state"] == DONE, 15)["route"] == "lan"
    assert a.outbox.get(j4["id"])["state"] == DONE
    bodies = [m["body"] for m in b2.chat.recent() if m["dir"] == "in"]
    assert bodies == ["one", "four", "five"]


def test_the_hub_route_is_only_for_peers_the_hub_knows(nodes):
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b)           # paired directly: no hub knows it
    b.close()
    a.host.hub_up = True
    j = a.send_text(b.identity.fp, "x")
    assert a.outbox.wait(j["id"], lambda j: j["attempts"] > 0 and j["state"] != "sending", 8)["state"] == QUEUED
    assert a.host.hub_calls == []


# --- the outbox --------------------------------------------------------------------------------

def test_outbox_survives_a_restart(tmp_path):
    ob = Outbox(tmp_path / "outbox.json")
    f = tmp_path / "f.txt"
    f.write_text("x")
    t = ob.add_text("aa" * 32, "b", "hello")
    ob.update(t["id"], state="sending", attempts=1)
    ob.add_file("aa" * 32, "b", f, "f.txt", "text/plain")
    done = ob.add_text("aa" * 32, "b", "done")
    ob.update(done["id"], state=DONE, route="lan")
    again = Outbox(tmp_path / "outbox.json")
    jobs = again.queued()
    assert [j["kind"] for j in jobs] == ["text", "file"]
    assert all(j["state"] == QUEUED for j in jobs)
    assert jobs[0]["body"] == "hello" and jobs[1]["size"] == 1


def test_a_changed_file_is_not_sent(nodes, tmp_path):
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b)
    b.close()
    f = tmp_path / "doc.txt"
    f.write_text("version 1")
    j = a.send_file(b.identity.fp, f)
    a.outbox.wait(j["id"], lambda j: j["attempts"] > 0 and j["state"] != "sending", 8)
    f.write_text("version 2, longer")
    got = a.outbox.wait(j["id"], lambda j: j["state"] == "failed", 8)
    assert got["state"] == "failed" and "changed" in got["error"]


# --- files -----------------------------------------------------------------------------------

@pytest.mark.parametrize("header,size,want", [
    (None, 100, None), ("bytes=0-", 100, (0, 99)), ("bytes=40-", 100, (40, 99)), ("bytes=40-49", 100, (40, 49)),
    ("bytes=-10", 100, (90, 99)), ("bytes=100-", 100, "bad"), ("bytes=50-40", 100, "bad"),
    ("bytes=0-999", 100, (0, 99)), ("items=0-1", 100, None), ("bytes=-0", 100, "bad"),
])
def test_parse_range(header, size, want):
    assert files.parse_range(header, size) == want


@pytest.mark.parametrize("raw,want", [("../../etc/passwd", "passwd"), (".bashrc", "bashrc"), ("a/b\\c.txt", "c.txt"),
                                      ("", "file"), ("..", "file"), ("x\x00y.txt", "xy.txt"), ("ok.pdf", "ok.pdf")])
def test_safe_name(raw, want):
    assert files.safe_name(raw) == want


def test_file_transfer_resumes_with_range(nodes, tmp_path):
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b)
    data = os.urandom(3 * 1024 * 1024 + 17)
    src = tmp_path / "big.bin"
    src.write_bytes(data)
    oid = "1f" * 16
    offer = files.Offer(oid, b.identity.fp, "big.bin", len(data), "application/octet-stream",
                        opener=lambda: open(src, "rb"))
    a.offers.add(offer)
    # b already has the first megabyte from an earlier, interrupted attempt
    dl = tmp_path / "dl"
    dl.mkdir()
    part, meta = files.part_paths(dl, a.identity.fp, oid)
    part.write_bytes(data[:1024 * 1024])
    meta.write_text(json.dumps({"size": len(data)}))
    saved = files.download(b.identity, a.identity.fp, [("127.0.0.1", a.port)], oid, "big.bin", len(data), dl)
    assert saved.read_bytes() == data
    assert offer.sent == len(data) - 1024 * 1024       # only the rest was sent
    assert not part.exists() and not meta.exists()
    # someone else can't fetch an offer that isn't theirs
    c = nodes("c")
    trust_each_other(a, c)
    with pytest.raises(files.DownloadError):
        files.download(c.identity, a.identity.fp, [("127.0.0.1", a.port)], oid, "big.bin", len(data),
                       tmp_path / "dl2", tries=1)


def test_file_over_a_direct_link(nodes, tmp_path):
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b)
    data = os.urandom(200_000)
    src = tmp_path / "photo.jpg"
    src.write_bytes(data)
    j = a.send_file(b.identity.fp, src)
    got = a.outbox.wait(j["id"], lambda j: j["state"] == DONE, 10)
    assert got["state"] == DONE and got["route"] == "lan"
    saved = list((tmp_path / "b" / "dl").iterdir())
    assert [p.name for p in saved] == ["photo.jpg"] and saved[0].read_bytes() == data
    # a re-offer of the same id (its ack was lost) is acknowledged, not saved twice
    link = a.direct(b.identity.fp)
    offer = files.Offer(j["id"], b.identity.fp, "photo.jpg", len(data), "image/jpeg", lambda: open(src, "rb"))
    a.offers.add(offer)
    link.send(offer.message())
    assert offer.done.wait(5) and offer.result == (True, "")
    assert len(list((tmp_path / "b" / "dl").iterdir())) == 1
