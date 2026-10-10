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


# --- the clipboard ---------------------------------------------------------------------------

def test_a_clip_from_the_browser_is_delivered_only_once_it_has_said_who_it_is(tmp_path):
    async def go():
        b = Browser()
        host, ch, conn = make(tmp_path)
        conn.opened()
        conn.message(json.dumps({"t": "clip", "id": "abcdefgh12", "text": "sneaky"}))
        assert host.delivered == []
        host.trusted[b.fp] = make_browser_entry(name="iPhone", key=b.b64)
        sig = b.sign(auth_transcript(b.fp, host.own.fp, DTLS, ch.json()[0]["nonce"]))
        conn.message(json.dumps({"t": "auth", "key": b.b64, "sig": sig}))
        conn.message(json.dumps({"t": "clip", "id": "abcdefgh12", "text": "copied on the iPhone"}))
        assert host.delivered[-1] == {"t": "clip", "id": "abcdefgh12", "text": "copied on the iPhone"}
        conn.message(json.dumps({"t": "clip", "id": "abcdefgh13", "text": 5}))
        assert ch.json()[-1] == {"t": "nack", "id": "abcdefgh13", "error": "no text"}
        assert len(host.delivered) == 1
    asyncio.run(go())


def test_frames_carry_utf8_and_a_frame_too_large_isnt_sent(tmp_path):
    async def go():
        b = Browser()
        host, ch, conn = authed(tmp_path, b)
        assert conn.send({"t": "clip", "text": "héllo ✓ 日本"})
        assert ch.sent[-1] == '{"t":"clip","text":"héllo ✓ 日本"}'
        big = {"t": "clip", "text": "\n" * (200 * 1024)}   # 200 KB of text, 400 KB as JSON
        assert not protocol.fits(big)
        n = len(ch.sent)
        assert conn.send(big) is False and len(ch.sent) == n
        assert protocol.fits({"t": "clip", "text": "x" * (250 * 1024)})
        # a lone surrogate (JSON allows one) still goes, escaped
        assert conn.send({"t": "clip", "text": "a\ud800b"}) and "\\ud800" in ch.sent[-1]
    asyncio.run(go())


class FakeListener:
    """The UDP listener without the network: an asyncio loop on a thread, like the real one."""

    def __init__(self, cert, key, on_channel, port=None):
        import threading
        self.on_channel, self.port = on_channel, port or 1739
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self.loop.run_forever, daemon=True)

    def start(self):
        self.thread.start()

    def close(self):
        self.loop.call_soon_threadsafe(self.loop.stop)
        self.thread.join(2)

    def call(self, fn, *args):
        self.loop.call_soon_threadsafe(fn, *args)


class Session:
    def __init__(self, channel):
        self.channel, self.remote_fp, self.peer_address, self.on_close = channel, DTLS, "192.0.2.7", []
        self.handlers = {}
        channel.on = lambda ev, fn: self.handlers.setdefault(ev, fn)


def wait_for(cond, timeout=5):
    import time
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.02)
    return False


@pytest.fixture
def iphone(tmp_path, monkeypatch):
    """A dry-run agent with a mesh node, the iPhone link (no network), and a paired, connected browser.

    Yields (agent, node, browser fp, the channel, send(msg) as the browser, clipboard writes)."""
    from droplet_agent import agent as agent_mod, config, mediastate
    from droplet_agent.mesh.node import Host, MeshNode
    from droplet_agent.webrtc.bridge import Bridge
    monkeypatch.setattr(mediastate, "available", lambda: (True, "fake"))
    made = []

    def start(clipboard_sync=True):
        cfg = config.load()
        cfg["device"]["name"] = "t15"
        cfg["caps"]["clipboard"] = clipboard_sync
        a = agent_mod.Agent(cfg, dry_run=True)
        a.hello()
        written = []
        a.clip.writer = lambda t: written.append(t)

        class AgentHost(Host):
            def device_name(self): return "t15"
            def dispatch_remote(self, msg, source): a.dispatch(msg, source)
            def last_states(self): return {}

        base = tmp_path / "node"
        node = MeshNode(AgentHost(), config_dir=base / "cfg", data_dir=base / "data", downloads=base / "dl",
                        port=0, announce=False, control=False, local_addresses=lambda: ["127.0.0.1"],
                        dry_run=True, gateways=lambda: [])
        node.start()
        a.peers_broadcast = node.broadcast
        bridge = Bridge(node, listener_factory=FakeListener)
        bridge.start()
        made.append((a, node, bridge))
        b = Browser()
        node.trust.add_browser(make_browser_entry(name="Ann's iPhone", key=b.b64))
        ch = Channel()
        bridge.listener.call(bridge._on_channel, Session(ch))
        assert wait_for(lambda: ch.json())
        sig = b.sign(auth_transcript(b.fp, node.identity.fp, DTLS, ch.json()[0]["nonce"]))
        conn = next(iter(bridge.conns))
        bridge.listener.call(conn.message, json.dumps({"t": "auth", "key": b.b64, "sig": sig}))
        assert wait_for(lambda: node.open_link(b.fp) is not None)

        def send(msg):
            bridge.listener.call(conn.message, json.dumps(msg))
        return a, node, b.fp, ch, send, written
    yield start
    for a, node, bridge in made:
        bridge.close()
        node.close()
        a.close()


def clips(ch):
    return [m for m in ch.json() if m["t"] == "clip"]


def test_the_iphone_sends_its_clipboard_even_with_sync_off_and_it_doesnt_echo(iphone):
    a, node, fp, ch, send, written = iphone(clipboard_sync=False)
    assert "clipboard" not in a.advertised
    send({"t": "clip", "id": "clip000001", "text": "from the iPhone"})
    assert wait_for(lambda: {"t": "ack", "id": "clip000001"} in ch.json())
    assert written == ["from the iPhone"]
    # the write comes back as a local change: not sent anywhere, the iPhone least of all
    a.clip.local_change("from the iPhone")
    assert clips(ch) == []


def test_text_the_iphone_sent_isnt_sent_back_to_it(iphone):
    a, node, fp, ch, send, written = iphone(clipboard_sync=True)
    send({"t": "clip", "id": "clip000002", "text": "round trip"})
    assert wait_for(lambda: {"t": "ack", "id": "clip000002"} in ch.json())
    a.clip.local_change("round trip")       # wl-copy's write, seen by the watcher
    a.clip.local_change("copied here")      # then something new copied on the computer
    assert wait_for(lambda: clips(ch))
    assert clips(ch) == [{"t": "clip", "text": "copied here"}]


def test_a_clip_over_the_computers_limit_is_refused(iphone):
    a, node, fp, ch, send, written = iphone()
    a.clip.max_bytes = 1024
    send({"t": "clip", "id": "clip000003", "text": "x" * 2000})
    assert wait_for(lambda: any(m.get("id") == "clip000003" for m in ch.json()))
    nack = next(m for m in ch.json() if m.get("id") == "clip000003")
    assert nack["t"] == "nack" and "too large" in nack["error"] and written == []


def test_the_computers_clipboard_reaches_the_iphone_only_with_sync_on(iphone):
    a, node, fp, ch, send, written = iphone(clipboard_sync=True)
    a.clip.local_change("copied on the computer")
    assert wait_for(lambda: clips(ch))
    assert clips(ch) == [{"t": "clip", "text": "copied on the computer"}]


def test_with_sync_off_only_an_explicit_send_reaches_the_iphone(iphone):
    a, node, fp, ch, send, written = iphone(clipboard_sync=False)
    assert a.send_clip("copied on the computer") is False
    import time
    time.sleep(0.2)
    assert clips(ch) == []
    # tray / window / droplet-agent clip <iphone>
    assert node.handle_control({"cmd": "clip", "peer": "Ann's iPhone", "text": "sent on purpose"}) == {"route": "webrtc"}
    assert wait_for(lambda: clips(ch) == [{"t": "clip", "text": "sent on purpose"}])


def test_a_secret_on_the_clipboard_never_reaches_the_iphone(iphone, monkeypatch):
    from droplet_agent import clip, env

    a, node, fp, ch, send, written = iphone(clipboard_sync=True)

    class R:
        def __init__(self, out): self.stdout, self.returncode = out, 0
    monkeypatch.setattr(env, "run", lambda argv, **kw: R(
        b"text/plain " + clip.PASSWORD_HINT.encode() if "--list-types" in argv else b"secret"))
    sync = clip.ClipboardSync("wayland", a.send_clip)
    sync.local_change(sync.reader())          # what the watcher does on a change
    import time
    time.sleep(0.2)
    assert sync.reader() is None and clips(ch) == []


def test_too_much_text_for_the_iphone_is_refused_not_dropped(iphone):
    a, node, fp, ch, send, written = iphone()
    with pytest.raises(ValueError, match="too much text"):
        node.clip(fp, "\n" * (200 * 1024))
    assert node.broadcast({"t": "clip", "text": "\n" * (200 * 1024)}) is False
    assert clips(ch) == []


def test_a_clip_from_the_hub_or_a_native_peer_cant_claim_to_be_explicit(iphone):
    a, node, fp, ch, send, written = iphone(clipboard_sync=False)
    a.dispatch({"t": "clip", "text": "from the hub", "explicit": True})
    import time
    time.sleep(0.2)
    assert written == []

    class Lan:
        kind, outbound, closed = "lan", False, False
    link = Lan()
    link.fp = fp
    link.ready = type("E", (), {"is_set": lambda self: True})()
    got = []
    node.host.dispatch_remote = lambda msg, source: got.append(msg)
    node._on_message(link, {"t": "clip", "text": "native", "explicit": True})
    assert got[-1]["explicit"] is False


def test_an_iphone_that_isnt_connected_is_said_so(iphone):
    from droplet_agent.mesh.node import NoRoute
    a, node, fp, ch, send, written = iphone()
    for conn in list(node.webrtc.conns):
        node.webrtc.listener.call(conn.close)
    assert wait_for(lambda: node.open_link(fp) is None)
    with pytest.raises(NoRoute, match="open droplet on it"):
        node.clip(fp, "hello")


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
