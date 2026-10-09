"""The iPhone link (droplet_agent/webrtc, docs/iphone.md): keys, pairing, the channel protocol, the QR.

The transport itself (ICE-lite, DTLS, SCTP) is tested against real browsers by
tests/e2e_iphone.py (Playwright, WebKit and Chromium).
"""

import asyncio
import base64
import hashlib
import json

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

from droplet_agent.mesh import pairing
from droplet_agent.mesh.identity import key_fingerprint, load_or_create, p256_spki, verify_key
from droplet_agent.mesh.trust import TrustList, make_browser_entry
from droplet_agent.webrtc import protocol, qr
from droplet_agent.webrtc.protocol import Conn, Host, auth_transcript


class Browser:
    """What the web app holds: a P-256 key, signing the way WebCrypto does (raw r‖s)."""

    def __init__(self):
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.spki = self.key.public_key().public_bytes(serialization.Encoding.DER,
                                                       serialization.PublicFormat.SubjectPublicKeyInfo)
        self.b64 = base64.b64encode(self.spki).decode()
        self.fp = hashlib.sha256(self.spki).hexdigest()

    def sign(self, data: bytes, raw: bool = True) -> str:
        der = self.key.sign(data, ec.ECDSA(hashes.SHA256()))
        if not raw:
            return base64.b64encode(der).decode()
        r, s = decode_dss_signature(der)
        return base64.b64encode(r.to_bytes(32, "big") + s.to_bytes(32, "big")).decode()


# --- keys ---------------------------------------------------------------------------

def test_key_checks_and_signatures():
    b = Browser()
    assert p256_spki(b.b64) == b.spki
    assert key_fingerprint(b.spki) == b.fp
    for raw in (True, False):
        sig = base64.b64decode(b.sign(b"hello", raw=raw))
        assert verify_key(b.spki, sig, b"hello")
        assert not verify_key(b.spki, sig, b"hellO")
    other = ec.generate_private_key(ec.SECP384R1()).public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    with pytest.raises(ValueError):
        p256_spki(base64.b64encode(other).decode())
    with pytest.raises(ValueError):
        p256_spki("not base64!")


def test_browser_peers_in_the_trust_list(tmp_path):
    own = load_or_create(tmp_path / "me")
    trust = TrustList(tmp_path / "trust.json", own.fp)
    b = Browser()
    e = make_browser_entry(name="  Ann's  iPhone ", key=b.b64)
    assert e["fp"] == b.fp and e["id"] == b.fp[:16] and e["name"] == "Ann's iPhone" and e["cert_pem"] is None
    trust.add_browser(e)
    assert trust.pems() == []          # never a TLS trust anchor
    again = TrustList(tmp_path / "trust.json", own.fp)
    got = again.get(b.fp)
    assert got["source"] == "browser" and got["key"] == base64.b64encode(b.spki).decode()
    assert again.find("ann's iphone")[0]["fp"] == b.fp
    with pytest.raises(ValueError):
        make_browser_entry(name="x", key=b.b64, fp="0" * 64)


# --- pairing ---------------------------------------------------------------------------

def test_a_browser_pairs_with_the_mesh_pairing(tmp_path):
    own = load_or_create(tmp_path / "me")
    inc = pairing.Incoming(own)
    ready = []
    inc.on_ready = ready.append
    b = Browser()
    n_i = pairing.new_nonce()
    status, out = inc.open({"key": b.b64, "name": "iPhone", "os": "ios", "commit": pairing.commitment(n_i)},
                           "aaaabbbbccccdddd", "t15", browser=True)
    assert status == 200 and out["fp"] == own.fp
    sig = b.sign(pairing.transcript(b.fp, own.fp, n_i, out["nonce"]))
    assert inc.confirm(out["request"], {"nonce": n_i, "sig": sig}) == (200, {"state": "waiting"})
    code = pairing.code(b.fp, own.fp, n_i, out["nonce"])
    assert ready[0]["code"] == code and ready[0]["kind"] == "browser" and ready[0]["id"] == b.fp[:16]
    r = inc.answer(out["request"], True)
    assert r["der"] == b.spki and r["kind"] == "browser"


def test_a_browser_pairing_with_a_bad_proof_is_dropped(tmp_path):
    own = load_or_create(tmp_path / "me")
    inc = pairing.Incoming(own)
    b, mallory = Browser(), Browser()
    n_i = pairing.new_nonce()
    _, out = inc.open({"key": b.b64, "commit": pairing.commitment(n_i)}, "aaaabbbbccccdddd", "t15", browser=True)
    sig = mallory.sign(pairing.transcript(b.fp, own.fp, n_i, out["nonce"]))
    assert inc.confirm(out["request"], {"nonce": n_i, "sig": sig})[0] == 403
    assert inc.status(out["request"])[0] == 404


# --- the channel ---------------------------------------------------------------------------

class Channel:
    def __init__(self):
        self.sent = []
        self.bufferedAmount = 0
        self.closed = False

    def send(self, data):
        self.sent.append(data)

    def close(self):
        self.closed = True

    def json(self):
        return [json.loads(x) for x in self.sent if isinstance(x, str)]


class FakeHost(Host):
    def __init__(self, tmp_path, own):
        self.own = own
        self.trusted = {}
        self.tokens = {"good-token"}
        self.inc = pairing.Incoming(own)
        self.delivered, self.saved, self.auths = [], [], []
        self.dl = tmp_path / "Downloads"

    def server_info(self):
        return {"id": "aaaabbbbccccdddd", "name": "t15", "fp": self.own.fp}

    def browser(self, fp):
        return self.trusted.get(fp)

    def token_ok(self, token):
        return token in self.tokens

    def pair_open(self, body):
        return self.inc.open(body, "aaaabbbbccccdddd", "t15", browser=True)

    def pair_confirm(self, rid, body):
        return self.inc.confirm(rid, body)

    def pair_status(self, rid):
        return self.inc.status(rid)[1]["state"]

    def pair_cancel(self, rid):
        self.inc.cancel(rid)

    def authenticated(self, conn):
        self.auths.append(conn.fp)

    def deliver(self, conn, msg):
        self.delivered.append(msg)

    def received(self, conn, fid, path):
        self.saved.append((fid, path))

    def downloads(self):
        return self.dl


DTLS = "d" * 64


def make(tmp_path):
    own = load_or_create(tmp_path / "me")
    host = FakeHost(tmp_path, own)
    ch = Channel()
    conn = Conn(host, ch, dtls_fp=DTLS, address="192.0.2.7")
    return host, ch, conn


def authed(tmp_path, b):
    host, ch, conn = make(tmp_path)
    host.trusted[b.fp] = make_browser_entry(name="iPhone", key=b.b64)
    conn.opened()
    hello = ch.json()[0]
    sig = b.sign(auth_transcript(b.fp, host.own.fp, DTLS, hello["nonce"]))
    conn.message(json.dumps({"t": "auth", "key": b.b64, "sig": sig}))
    assert ch.json()[-1]["t"] == "welcome"
    return host, ch, conn


def test_a_paired_browser_proves_itself_bound_to_its_dtls_session(tmp_path):
    async def go():
        b = Browser()
        host, ch, conn = authed(tmp_path, b)
        assert conn.fp == b.fp and host.auths == [b.fp]
        # now it can talk
        conn.message(json.dumps({"t": "text", "id": "abcdefgh12", "body": "hi"}))
        assert host.delivered[-1]["body"] == "hi"
    asyncio.run(go())


@pytest.mark.parametrize("wrong", ["dtls", "nonce", "key"])
def test_a_proof_for_another_session_fails(tmp_path, wrong):
    async def go():
        b = Browser()
        host, ch, conn = make(tmp_path)
        host.trusted[b.fp] = make_browser_entry(name="iPhone", key=b.b64)
        conn.opened()
        nonce = ch.json()[0]["nonce"]
        dtls = "e" * 64 if wrong == "dtls" else DTLS
        nonce = "0" * 64 if wrong == "nonce" else nonce
        signer = Browser() if wrong == "key" else b
        sig = signer.sign(auth_transcript(b.fp, host.own.fp, dtls, nonce))
        conn.message(json.dumps({"t": "auth", "key": b.b64, "sig": sig}))
        assert ch.json()[-1]["t"] == "auth-failed"
        assert conn.fp is None and conn.closed
        # and nothing else gets through
        conn.message(json.dumps({"t": "text", "id": "abcdefgh12", "body": "hi"}))
        assert host.delivered == []
    asyncio.run(go())


def test_an_unknown_browser_is_told_it_isnt_paired(tmp_path):
    async def go():
        b = Browser()
        host, ch, conn = make(tmp_path)
        conn.opened()
        nonce = ch.json()[0]["nonce"]
        conn.message(json.dumps({"t": "auth", "key": b.b64,
                                 "sig": b.sign(auth_transcript(b.fp, host.own.fp, DTLS, nonce))}))
        assert ch.json()[-1] == {"t": "auth-failed", "error": "this device isn't paired with it", "paired": False}
        conn.message(json.dumps({"t": "text", "id": "abcdefgh12", "body": "hi"}))
        assert host.delivered == []
    asyncio.run(go())


def test_pairing_over_the_channel_needs_the_qr_token_and_shows_the_code(tmp_path):
    async def go():
        b = Browser()
        host, ch, conn = make(tmp_path)
        conn.opened()
        n_i = pairing.new_nonce()
        conn.message(json.dumps({"t": "pair", "key": b.b64, "name": "iPhone", "token": "stale",
                                 "commit": pairing.commitment(n_i)}))
        assert ch.json()[-1]["t"] == "pair-failed"
        conn.message(json.dumps({"t": "pair", "key": b.b64, "name": "iPhone", "token": "good-token",
                                 "commit": pairing.commitment(n_i)}))
        m = ch.json()[-1]
        assert m["t"] == "pair-nonce"
        conn.message(json.dumps({"t": "pair-confirm", "nonce": n_i,
                                 "sig": b.sign(pairing.transcript(b.fp, host.own.fp, n_i, m["nonce"]))}))
        assert ch.json()[-1] == {"t": "pair-state", "state": "waiting"}
        req = host.inc.waiting()[0]
        assert req["code"] == pairing.code(b.fp, host.own.fp, n_i, m["nonce"])
        host.inc.answer(req["request"], True)
        for _ in range(20):
            await asyncio.sleep(0.1)
            if ch.json()[-1].get("state") == "accepted":
                break
        assert ch.json()[-1] == {"t": "pair-state", "state": "accepted"}
    asyncio.run(go())


def test_closing_while_pairing_cancels_the_request(tmp_path):
    async def go():
        b = Browser()
        host, ch, conn = make(tmp_path)
        conn.opened()
        n_i = pairing.new_nonce()
        conn.message(json.dumps({"t": "pair", "key": b.b64, "token": "good-token", "commit": pairing.commitment(n_i)}))
        rid = ch.json()[-1]["request"]
        conn.close()
        assert host.inc.status(rid)[1]["state"] == "cancelled"
    asyncio.run(go())


def file_frames(fid: str, data: bytes, chunk=5):
    key = bytes.fromhex(fid[:16])
    return [key + data[i:i + chunk] for i in range(0, len(data), chunk)]


def test_a_file_arrives_in_chunks_and_is_saved_under_a_safe_unique_name(tmp_path):
    async def go():
        b = Browser()
        host, ch, conn = authed(tmp_path, b)
        host.dl.mkdir(parents=True)
        (host.dl / "photo.jpg").write_bytes(b"old")
        fid = "1" * 32
        data = b"0123456789" * 7
        conn.message(json.dumps({"t": "file", "id": fid, "name": "../photo.jpg", "size": len(data)}))
        for f in file_frames(fid, data):
            conn.message(f)
        conn.message(json.dumps({"t": "file-end", "id": fid}))
        assert ch.json()[-1] == {"t": "ack", "id": fid}
        (got_id, path), = host.saved
        assert got_id == fid and path == host.dl / "photo (1).jpg" and path.read_bytes() == data
        assert not list(host.dl.glob(".droplet-*"))
    asyncio.run(go())


def test_a_short_or_long_file_is_refused(tmp_path):
    async def go():
        b = Browser()
        host, ch, conn = authed(tmp_path, b)
        fid = "2" * 32
        conn.message(json.dumps({"t": "file", "id": fid, "name": "a.txt", "size": 10}))
        conn.message(file_frames(fid, b"12345")[0])
        conn.message(json.dumps({"t": "file-end", "id": fid}))
        assert ch.json()[-1]["t"] == "nack"
        fid = "3" * 32
        conn.message(json.dumps({"t": "file", "id": fid, "name": "b.txt", "size": 3}))
        conn.message(bytes.fromhex(fid[:16]) + b"too long")
        assert ch.json()[-1]["t"] == "nack"
        assert host.saved == [] and not list(host.dl.glob("*"))
    asyncio.run(go())


def test_sending_a_file_waits_for_the_ack(tmp_path):
    async def go():
        b = Browser()
        host, ch, conn = authed(tmp_path, b)
        src = tmp_path / "big.bin"
        data = bytes(range(256)) * 300            # 76.8 KB: five chunks
        src.write_bytes(data)
        fid = "4" * 32
        task = asyncio.ensure_future(conn.send_file(fid, "big.bin", len(data), "application/octet-stream", src))
        for _ in range(50):
            await asyncio.sleep(0.01)
            if ch.json()[-1]["t"] == "file-end":
                break
        frames = [x for x in ch.sent if isinstance(x, bytes)]
        assert b"".join(f[8:] for f in frames) == data
        assert all(f[:8] == bytes.fromhex(fid[:16]) and len(f) <= 8 + protocol.CHUNK for f in frames)
        conn.message(json.dumps({"t": "ack", "id": fid}))
        assert await task == (True, "")
    asyncio.run(go())


def test_sending_a_file_reports_a_refusal(tmp_path):
    async def go():
        b = Browser()
        host, ch, conn = authed(tmp_path, b)
        src = tmp_path / "a.bin"
        src.write_bytes(b"x" * 10)
        fid = "5" * 32
        task = asyncio.ensure_future(conn.send_file(fid, "a.bin", 10, "", src))
        await asyncio.sleep(0.05)
        conn.message(json.dumps({"t": "nack", "id": fid, "error": "no space"}))
        assert await task == (False, "refused: no space")
    asyncio.run(go())


# --- the QR code ---------------------------------------------------------------------------

def test_qr_payload_round_trip():
    fp = hashlib.sha256(b"x").hexdigest()
    data = qr.payload(name="t15", peer_id="aaaabbbbccccdddd", fp=fp, addresses=["192.168.1.20"], port=1739,
                      token="tok")
    link = qr.link("https://droplet.noxeratech.com/app/", data)
    assert link.startswith("https://droplet.noxeratech.com/app/#pair=")
    back = qr.parse(link)
    assert back["fp"] == fp and back["a"] == ["192.168.1.20"] and back["p"] == 1739 and back["t"] == "tok"
    assert len(link) < 300


def test_qr_draws_in_a_terminal():
    pytest.importorskip("segno")
    text = qr.terminal("https://droplet.noxeratech.com/app/#pair=abc")
    lines = text.splitlines()
    assert len({len(line) for line in lines}) == 1 and set(text) <= set("█▀▄ \n")


def test_the_av_stand_in_lets_aiortc_load_without_pyav():
    from droplet_agent.webrtc import deps
    if not deps.available()[0]:
        pytest.skip("aiortc isn't installed")
    rtc = deps.load()
    assert rtc.RTCSctpTransport and rtc.RTCDtlsTransport
