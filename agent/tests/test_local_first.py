"""Local-first: finding the hub on the LAN, pinning its certificate, choosing routes, joining."""

import json
import shutil
import socket
import ssl
import subprocess
import threading
import time

import pytest

from droplet_agent import cli, config, connection, discovery, hub, pairing, routes
from droplet_agent.hub import HubError, PinMismatch, Route
from droplet_agent.pinning import fingerprint, normalize_fingerprint, pinned_context

HUB_ID = "9b16173d305cd15a"
FP = "3c103f2cda4a422ac2da8a86565f4f6664d31ca3f2e720bddc34593e14ad2094"
OTHER_FP = "ab" * 32
TAILNET = "https://t15.tail7375fe.ts.net"
RealRouter = routes.Router


# --- TXT records ------------------------------------------------------------------

def test_txt_records_parse():
    props = {b"id": HUB_ID.encode(), b"fp": FP.upper().encode(), b"name": b"t15",
             b"http": b"8000", b"ts": TAILNET.encode()}
    assert discovery.parse_txt(props) == {"id": HUB_ID, "fingerprint": FP, "name": "t15",
                                         "http_port": 8000, "tailnet": TAILNET}
    # an empty ts (no tailnet), a flag-style key, openssl's colon format
    colons = ":".join(FP[i:i + 2] for i in range(0, 64, 2))
    props = {b"id": HUB_ID.encode(), b"fp": colons.encode(), b"name": None, b"http": b"", b"ts": b""}
    assert discovery.parse_txt(props) == {"id": HUB_ID, "fingerprint": FP, "name": "",
                                         "http_port": None, "tailnet": ""}


@pytest.mark.parametrize("props", [
    {},
    {b"id": HUB_ID.encode()},                                    # no fingerprint
    {b"id": b"not-hex!", b"fp": FP.encode()},
    {b"id": HUB_ID.encode(), b"fp": b"1234"},                    # short
    {b"id": HUB_ID.encode(), b"fp": FP.encode() + b"00"},        # long
    {b"id": b"\xff\xfe", b"fp": FP.encode()},                    # not UTF-8
])
def test_txt_records_that_are_not_a_hub(props):
    assert discovery.parse_txt(props) is None


def test_txt_odd_values_are_dropped_not_trusted():
    props = {b"id": HUB_ID.encode(), b"fp": FP.encode(), b"http": b"99999",
             b"ts": b"http://plain.example"}
    txt = discovery.parse_txt(props)
    assert txt["http_port"] is None and txt["tailnet"] == ""


def test_found_from_service_orders_addresses():
    props = {b"id": HUB_ID.encode(), b"fp": FP.encode(), b"name": b"t15", b"http": b"8000", b"ts": b""}
    f = discovery.from_service("droplet-9b1617._droplet._tcp.local.", 8443, props,
                               ["fe80::1", "2001:db8::5", "192.168.100.20", "192.168.100.20"])
    assert f.addresses == ["192.168.100.20", "2001:db8::5"]  # link-local IPv6 needs a scope: skipped
    assert (f.id, f.https_port, f.http_port, f.name) == (HUB_ID, 8443, 8000, "t15")
    assert discovery.from_service("x", 8443, props, []) is None
    assert discovery.from_service("x", 8443, {b"id": b"zz"}, ["192.168.1.2"]) is None


# --- pinning, against a real TLS server --------------------------------------------

def _make_cert(tmp_path):
    cert, key = tmp_path / "cert.pem", tmp_path / "key.pem"
    try:
        import datetime

        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.x509.oid import NameOID

        k = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "droplet-test.local")])
        now = datetime.datetime.now(datetime.timezone.utc)
        c = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(k.public_key())
             .serial_number(x509.random_serial_number()).not_valid_before(now)
             .not_valid_after(now + datetime.timedelta(days=1)).sign(k, hashes.SHA256()))
        key.write_bytes(k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                        serialization.NoEncryption()))
        cert.write_bytes(c.public_bytes(serialization.Encoding.PEM))
    except ImportError:
        if not shutil.which("openssl"):
            pytest.skip("needs cryptography or openssl to make a test certificate")
        subprocess.run(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256",
                        "-nodes", "-keyout", str(key), "-out", str(cert), "-days", "1",
                        "-subj", "/CN=droplet-test.local"], check=True, capture_output=True)
    return cert, key


class TLSServer:
    """Answers every request with /api/hub/info JSON, and records what it received."""

    def __init__(self, tmp_path):
        cert, key = _make_cert(tmp_path)
        self.fp = fingerprint(ssl.PEM_cert_to_DER_cert(cert.read_text()))
        self.ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.ctx.load_cert_chain(str(cert), str(key))
        self.sock = socket.socket()
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(8)
        self.port = self.sock.getsockname()[1]
        self.received = []
        self.body = json.dumps({"id": HUB_ID, "name": "test", "fingerprint": self.fp,
                                "lan": {"addresses": ["127.0.0.1"], "http_port": 8000,
                                        "https_port": self.port},
                                "tailnet": None, "pin": False}).encode()
        threading.Thread(target=self._serve, daemon=True).start()

    def _serve(self):
        while True:
            try:
                conn, _ = self.sock.accept()
            except OSError:
                return
            threading.Thread(target=self._one, args=(conn,), daemon=True).start()

    def _one(self, conn):
        try:
            tls = self.ctx.wrap_socket(conn, server_side=True)
        except (ssl.SSLError, OSError):
            conn.close()
            return
        data = b""
        tls.settimeout(2)
        try:
            while b"\r\n\r\n" not in data:
                chunk = tls.recv(4096)
                if not chunk:
                    break
                data += chunk
        except (OSError, ssl.SSLError):
            pass
        self.received.append(data)
        if data:
            try:
                tls.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + str(len(self.body)).encode() + b"\r\nConnection: close\r\n\r\n" + self.body)
            except OSError:
                pass
        tls.close()

    def close(self):
        self.sock.close()

    def wait_received(self, timeout=3):
        end = time.monotonic() + timeout
        while not self.received and time.monotonic() < end:
            time.sleep(0.02)
        return self.received


@pytest.fixture
def tls_server(tmp_path):
    s = TLSServer(tmp_path)
    yield s
    s.close()


def test_pin_accepts_the_pinned_certificate(tls_server):
    info = hub.info(Route("lan", f"https://127.0.0.1:{tls_server.port}", tls_server.fp))
    assert info["id"] == HUB_ID and info["fingerprint"] == tls_server.fp
    assert tls_server.received[-1].startswith(b"GET /api/hub/info")


def test_pin_rejects_another_certificate_before_sending_anything(tls_server):
    route = Route("lan", f"https://127.0.0.1:{tls_server.port}", OTHER_FP)
    with pytest.raises(PinMismatch) as e:
        hub.me(route, "secret-token")
    assert e.value.got == tls_server.fp and e.value.expected == OTHER_FP
    assert "droplet-agent setup" in str(e.value)
    # the connection was dropped right after the handshake: no request, no token
    assert tls_server.wait_received() and all(r == b"" for r in tls_server.received)


def test_pin_rejects_before_the_websocket_upgrade(tls_server):
    from websockets.sync.client import connect
    with pytest.raises(PinMismatch):
        connect(f"wss://127.0.0.1:{tls_server.port}/ws", ssl=pinned_context(OTHER_FP), proxy=None,
                additional_headers={"Authorization": "Bearer secret-token"}, open_timeout=5)
    assert tls_server.wait_received() and all(r == b"" for r in tls_server.received)


def test_pinned_route_must_be_https():
    with pytest.raises(HubError):
        hub.info(Route("lan", "http://127.0.0.1:9", FP))
    with pytest.raises(HubError):
        hub.info(Route("lan", "https://127.0.0.1:9"))  # no pin
    with pytest.raises(ValueError):
        pinned_context("nope")
    assert normalize_fingerprint(FP.upper()) == FP and normalize_fingerprint(None) is None


def test_connection_checks_the_pin_again_before_hello(monkeypatch):
    """_open passes the pinned context, goes direct, and re-checks the established socket."""
    import websockets.sync.client as wsc

    seen = {}

    class FakeSock:
        def __init__(self, fp_der):
            self.der = fp_der

        def getpeercert(self, binary_form=False):
            return self.der

    class FakeWS:
        def __init__(self, der):
            self.socket = FakeSock(der)
            self.closed = False

        def close(self):
            self.closed = True

    der = b"certificate bytes"
    made = []

    def fake_connect(uri, **kw):
        seen.clear()
        seen.update(kw, uri=uri)
        made.append(FakeWS(der))
        return made[-1]

    monkeypatch.setattr(wsc, "connect", fake_connect)
    conn = connection.Connection(type("A", (), {})(), None, "tok")
    ws = conn._open(Route("lan", "https://192.168.1.5:8443", fingerprint(der)))
    assert seen["uri"] == "wss://192.168.1.5:8443/ws" and seen["proxy"] is None
    assert seen["ssl"].pin == fingerprint(der) and seen["ssl"].verify_mode == ssl.CERT_NONE
    assert ws is made[-1]
    with pytest.raises(PinMismatch):
        conn._open(Route("lan", "https://192.168.1.5:8443", OTHER_FP))
    assert made[-1].closed
    conn._open(Route("tailnet", TAILNET))  # ordinary TLS, proxies as configured
    assert seen["uri"] == "wss://t15.tail7375fe.ts.net/ws" and "ssl" not in seen and "proxy" not in seen


def test_connection_maps_403_to_revoked(monkeypatch):
    import websockets.sync.client as wsc
    from websockets.datastructures import Headers
    from websockets.exceptions import InvalidStatus
    from websockets.http11 import Response

    def refuse(uri, **kw):
        raise InvalidStatus(Response(403, "Forbidden", Headers()))
    monkeypatch.setattr(wsc, "connect", refuse)
    conn = connection.Connection(type("A", (), {})(), None, "tok")
    with pytest.raises(connection.Revoked):
        conn._open(Route("tailnet", TAILNET))


# --- route selection ------------------------------------------------------------------

def info_for(hub_id=HUB_ID, fp=FP, addresses=("192.168.100.20",), http_port=8000, https_port=8443,
             tailnet=TAILNET, name="t15"):
    return {"id": hub_id, "name": name, "fingerprint": fp, "addresses": list(addresses),
            "http_port": http_port, "https_port": https_port if fp else None, "tailnet": tailnet, "pin": False}


def paired_cfg(**ident):
    cfg = config.load()
    cfg.update(hub=TAILNET, token="tok", device={"id": "d1", "name": "slim"})
    cfg["hub_identity"] = {**cfg["hub_identity"], "id": HUB_ID, "name": "t15", "fingerprint": FP,
                           "lan": ["192.168.100.20"], "https_port": 8443, "http_port": 8000,
                           "tailnet": TAILNET, **ident}
    return cfg


class FakeNet:
    """Which URLs answer, and how: an info dict, a PinMismatch, or nothing (unreachable)."""

    def __init__(self, answers=None, found=(), mine=()):
        self.answers = dict(answers or {})
        self.found = list(found)
        self.mine = set(mine)
        self.asked = []

    def info(self, route, timeout=10):
        self.asked.append(route)
        got = self.answers.get(route.url)
        if got is None:
            raise HubError(f"can't reach {route.url}")
        if isinstance(got, Exception):
            raise got
        if route.pin and got.get("fingerprint") != route.pin:
            raise PinMismatch(route.url, route.pin, got.get("fingerprint"))
        return got

    def discover(self, timeout=1.5, want_id=None):
        return [f for f in self.found if want_id is None or f.id == want_id]

    def router(self, cfg, **kw):
        return RealRouter(cfg, persist=kw.pop("persist", False), discover=self.discover,
                             local_addresses=lambda: self.mine, get_info=self.info, lan_timeout=0.5, **kw)


def found_at(addr, port=8443, fp=FP, hub_id=HUB_ID, http_port=8000):
    return discovery.Found(id=hub_id, fingerprint=fp, name="t15", https_port=port, http_port=http_port,
                           tailnet=TAILNET, addresses=[addr])


def test_lan_found_by_mdns_beats_the_stored_address():
    net = FakeNet({"https://192.168.100.20:8443": info_for(), "https://192.168.100.31:8443": info_for(),
                   TAILNET: info_for()}, found=[found_at("192.168.100.31")])
    r = net.router(paired_cfg())
    route = r.select()
    assert route == Route("lan", "https://192.168.100.31:8443", FP)
    assert route.describe() == "LAN https://192.168.100.31:8443 (pinned)"
    # the new address is remembered first
    assert r.identity["lan"][0] == "192.168.100.31"


def test_lan_stored_address_when_mdns_is_silent():
    net = FakeNet({"https://192.168.100.20:8443": info_for(), TAILNET: info_for()})
    assert net.router(paired_cfg()).select().kind == "lan"


def test_falls_back_to_the_tailnet_and_comes_back():
    net = FakeNet({TAILNET: info_for()})
    r = net.router(paired_cfg())
    assert r.select() == Route("tailnet", TAILNET)
    assert r.nearer(Route("tailnet", TAILNET)) is None
    net.answers["https://192.168.100.20:8443"] = info_for()   # home again
    assert r.nearer(Route("tailnet", TAILNET)) == Route("lan", "https://192.168.100.20:8443", FP)
    assert r.nearer(Route("lan", "https://192.168.100.20:8443", FP)) is None  # already near
    assert r.select().kind == "lan"


def test_a_different_hub_on_a_route_is_not_used():
    net = FakeNet({"https://192.168.100.20:8443": info_for(hub_id="0000111122223333"),
                   TAILNET: info_for()})
    assert net.router(paired_cfg()).select().kind == "tailnet"


def test_pin_mismatch_is_an_error_when_nothing_else_works():
    net = FakeNet({"https://192.168.100.20:8443": info_for(fp=OTHER_FP)})
    r = net.router(paired_cfg(tailnet=""))
    with pytest.raises(PinMismatch) as e:
        r.select()
    assert "re-pair" in str(e.value) and "droplet-agent setup" in str(e.value)


def test_pin_mismatch_never_switches_silently_even_with_the_tailnet():
    # something else answers at the LAN address; the tailnet (verified) is still our hub
    net = FakeNet({"https://192.168.100.20:8443": info_for(fp=OTHER_FP), TAILNET: info_for()})
    r = net.router(paired_cfg())
    assert r.select().kind == "tailnet"
    assert "identity changed" in r.warning
    assert r.identity["fingerprint"] == FP  # the pin is untouched


def test_regenerated_certificate_reported_over_the_tailnet_keeps_the_old_pin():
    net = FakeNet({TAILNET: info_for(fp=OTHER_FP)})
    r = net.router(paired_cfg())
    assert r.select().kind == "tailnet"
    assert "identity changed" in r.warning and "droplet-agent setup" in r.warning
    assert r.identity["fingerprint"] == FP


def test_nothing_reachable():
    with pytest.raises(HubError) as e:
        FakeNet().router(paired_cfg()).select()
    assert not isinstance(e.value, PinMismatch)
    assert "can't reach t15" in str(e.value)


def test_direct_url_rules():
    net = FakeNet()
    cfg = paired_cfg(tailnet="")
    for url, expected in [
        ("https://hub.example.org", [Route("direct", "https://hub.example.org")]),
        ("https://192.168.100.20:8443", []),          # the LAN listener: only ever with the pin
        ("http://192.168.100.20:8000", []),           # plain http when LAN HTTPS exists: never
        ("http://127.0.0.1:8000", [Route("hub-local", "http://127.0.0.1:8000")]),
    ]:
        cfg["hub"] = url
        assert net.router(cfg)._remote_routes() == expected, url
    cfg["hub"] = "http://192.168.100.20:8000"
    cfg["hub_identity"]["fingerprint"] = ""  # a hub without LAN HTTPS: as before
    assert net.router(cfg)._remote_routes() == [Route("direct", "http://192.168.100.20:8000")]


# --- on the hub machine -----------------------------------------------------------------

def test_hub_local_is_preferred_on_the_hub_machine():
    net = FakeNet({"http://127.0.0.1:8000": info_for(), "https://192.168.100.20:8443": info_for(),
                   TAILNET: info_for()})
    route = net.router(paired_cfg()).select()
    assert route == Route("hub-local", "http://127.0.0.1:8000")
    assert route.describe() == "hub-local http://127.0.0.1:8000"


def test_loopback_with_another_hub_is_not_hub_local():
    net = FakeNet({"http://127.0.0.1:8000": info_for(hub_id="0000111122223333"),
                   "https://192.168.100.20:8443": info_for()})
    assert net.router(paired_cfg()).select().kind == "lan"


def test_hub_machine_detected_from_mdns_on_another_port():
    net = FakeNet({"http://127.0.0.1:8851": info_for(http_port=8851)},
                  found=[found_at("192.168.100.13", port=8852, http_port=8851)], mine={"192.168.100.13"})
    r = net.router(paired_cfg(lan=[], http_port=None))
    assert r.select() == Route("hub-local", "http://127.0.0.1:8851")
    assert r.on_hub_machine()
    assert r.identity["http_port"] == 8851
    assert not FakeNet(found=[found_at("192.168.100.20")], mine={"192.168.100.13"}).router(
        paired_cfg()).on_hub_machine([found_at("192.168.100.20")])


# --- configs from before local-first -------------------------------------------------------

def test_migration_reads_the_identity_over_the_tailnet(isolated_home):
    cfg = config.load()
    cfg.update(hub=TAILNET, token="tok", device={"id": "d1", "name": "T15"}, lock_command=["swaylock"])
    config.save(cfg)
    cfg = config.load()
    assert cfg["hub_identity"]["id"] == ""
    net = FakeNet({TAILNET: info_for(), "http://127.0.0.1:8000": info_for()})
    r = net.router(cfg, persist=True)
    route = r.select()
    assert route.kind == "hub-local"  # the T15 itself
    stored = config.load()
    assert stored["hub_identity"]["id"] == HUB_ID and stored["hub_identity"]["fingerprint"] == FP
    assert stored["hub_identity"]["tailnet"] == TAILNET and stored["hub_identity"]["http_port"] == 8000
    assert stored["token"] == "tok" and stored["lock_command"] == ["swaylock"]  # the rest is kept
    assert net.asked[0] == Route("direct", TAILNET)  # verified TLS first


def test_migration_of_a_plain_lan_url_switches_to_the_pinned_route(isolated_home):
    cfg = config.load()
    cfg.update(hub="http://192.168.100.20:8000", token="tok")
    net = FakeNet({"http://192.168.100.20:8000": info_for(tailnet=None),
                   "https://192.168.100.20:8443": info_for(tailnet=None)})
    route = net.router(cfg).select()
    assert route == Route("lan", "https://192.168.100.20:8443", FP)
    assert cfg["hub_identity"]["fingerprint"] == FP


def test_migration_that_fails_keeps_working_as_before(isolated_home):
    cfg = config.load()
    cfg.update(hub=TAILNET, token="tok")
    r = FakeNet().router(cfg)
    assert r.select() == Route("direct", TAILNET)
    assert "couldn't read the hub's identity" in r.warning
    assert cfg["hub_identity"]["id"] == ""  # tried again next time


def test_save_identity_keeps_hand_edits(isolated_home):
    cfg = config.load()
    cfg.update(hub=TAILNET, token="tok")
    config.save(cfg)
    on_disk = config.load()
    on_disk["caps"]["clipboard"] = False  # edited by hand while the agent runs
    config.save(on_disk)
    config.save_identity({**cfg["hub_identity"], "id": HUB_ID})
    back = config.load()
    assert back["hub_identity"]["id"] == HUB_ID and back["caps"]["clipboard"] is False


def test_pending_is_not_set_up(isolated_home):
    cfg = config.load()
    cfg.update(hub=TAILNET, token="tok", pending=True)
    assert not config.is_set_up(cfg)
    cfg["pending"] = False
    assert config.is_set_up(cfg)


# --- waiting to be let in ---------------------------------------------------------------------

def scripted(*answers):
    it = iter(answers)

    def check():
        a = next(it)
        if isinstance(a, Exception):
            raise a
        return a
    return check


def test_pending_state_of():
    assert pairing.state_of({"id": "a", "pending": True, "code": "1234"}) == pairing.PENDING
    assert pairing.state_of({"id": "a", "name": "slim"}) == pairing.APPROVED
    assert pairing.state_of(None) == pairing.DENIED


def test_wait_until_approved_through_hiccups():
    naps = []
    check = scripted({"pending": True}, HubError("blip"), {"pending": True}, {"id": "a"})
    assert pairing.wait_for_approval(check, sleep=naps.append, every=2) == pairing.APPROVED
    assert naps == [2, 2, 2]


def test_wait_ends_when_denied():
    assert pairing.wait_for_approval(scripted({"pending": True}, None), sleep=lambda s: None) == pairing.DENIED


def test_wait_gives_up_on_a_hub_that_is_gone():
    with pytest.raises(HubError):
        pairing.wait_for_approval(scripted(*[HubError("down")] * 3), sleep=lambda s: None, max_errors=3)


def test_wait_stops_at_once_on_a_pin_mismatch():
    with pytest.raises(PinMismatch):
        pairing.wait_for_approval(scripted({"pending": True}, PinMismatch("x", FP, OTHER_FP)),
                                  sleep=lambda s: None)


def test_wait_times_out():
    t = [0.0]

    def nap(s):
        t[0] += s
    state = pairing.wait_for_approval(lambda: {"pending": True}, sleep=nap, clock=lambda: t[0],
                                      every=10, timeout=30)
    assert state == pairing.TIMED_OUT and t[0] == 30


# --- setup over the LAN, end to end with a fake hub -----------------------------------------

class FakeHub:
    def __init__(self, answers):
        self.answers = list(answers)  # what /api/me says, in turn
        self.registered = []

    def install(self, monkeypatch):
        lan = Route("lan", "https://192.168.100.20:8443", FP)
        monkeypatch.setattr(routes, "establish", lambda url: (lan, info_for(tailnet=None), "first-use"))
        monkeypatch.setattr(hub, "register", self.register)
        monkeypatch.setattr(hub, "me", self.me)
        monkeypatch.setattr(pairing, "POLL_EVERY", 0)
        monkeypatch.setattr(pairing.time, "sleep", lambda s: None)

    def register(self, route, name):
        assert route.kind == "lan" and route.pin == FP  # the token only ever travels pinned
        self.registered.append(name)
        return {"id": "d9", "name": name, "token": "newtok", "pending": True, "code": "7156"}

    def me(self, route, token):
        assert route.pin == FP and token == "newtok"
        return self.answers.pop(0)


def test_setup_waits_for_approval(monkeypatch, capsys, isolated_home):
    fake = FakeHub([{"id": "d9", "name": "slim", "pending": True, "code": "7156"}, {"id": "d9", "name": "slim"}])
    fake.install(monkeypatch)
    assert cli.main(["setup", "--hub", "http://192.168.100.20:8000", "--name", "slim"]) == 0
    out = capsys.readouterr().out
    assert "7156" in out and "allow slim" in out and "check the code matches" in out
    cfg = config.load()
    assert config.is_set_up(cfg) and cfg["token"] == "newtok" and cfg["pending"] is False
    assert cfg["hub_identity"]["id"] == HUB_ID and cfg["hub_identity"]["fingerprint"] == FP
    assert cfg["hub_identity"]["lan"] == ["192.168.100.20"]


def test_setup_denied(monkeypatch, capsys, isolated_home):
    FakeHub([{"id": "d9", "name": "slim", "pending": True}, None]).install(monkeypatch)
    assert cli.main(["setup", "--hub", "http://192.168.100.20:8000", "--name", "slim"]) == 1
    assert "denied" in capsys.readouterr().err
    cfg = config.load()
    assert not config.is_set_up(cfg) and cfg["token"] == ""


def test_nothing_nearby_is_trusted_without_the_hub_id():
    net = FakeNet({"http://127.0.0.1:8000": info_for(hub_id="0000111122223333")})
    cfg = paired_cfg(id="")
    assert net.router(cfg).nearer(Route("direct", TAILNET)) is None
    assert net.asked == []


def test_status_shows_route_and_identity(monkeypatch, capsys, isolated_home):
    config.save(paired_cfg())
    net = FakeNet({"https://192.168.100.20:8443": info_for()})
    monkeypatch.setattr(routes, "Router", lambda cfg, **kw: net.router(cfg, **kw))
    monkeypatch.setattr(hub, "me", lambda route, token: {"id": "d1", "name": "slim"})
    assert cli.main(["status"]) == 0
    out = capsys.readouterr().out
    assert "route:    LAN https://192.168.100.20:8443 (pinned)" in out
    assert f"hub:      t15  (id {HUB_ID})" in out and f"pin:      {FP}" in out
    assert "token:    accepted by the hub" in out


def test_status_shows_a_pin_mismatch(monkeypatch, capsys, isolated_home):
    config.save(paired_cfg(tailnet=""))
    net = FakeNet({"https://192.168.100.20:8443": info_for(fp=OTHER_FP)})
    monkeypatch.setattr(routes, "Router", lambda cfg, **kw: net.router(cfg, **kw))
    assert cli.main(["status"]) == 0
    out = capsys.readouterr().out
    assert "route:    none: the hub's identity changed" in out and "droplet-agent setup" in out
