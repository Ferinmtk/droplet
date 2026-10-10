"""Links that open on your other device, renaming this one and nicknames for the others, and
sending to several devices at once (docs/mesh.md §9.10)."""

import json
import time

import pytest

from droplet_agent.mesh import links, perms
from droplet_agent.mesh.node import Refused
from droplet_agent.mesh.trust import check_name, make_entry
from droplet_agent.mesh.outbox import QUEUED

from test_mesh import nodes, trust_each_other, wait_for  # noqa: F401  (nodes is a fixture)
from test_perms import FakeDesktop


class Desk(FakeDesktop):
    def __init__(self):
        super().__init__()
        self.opened = []

    def open_url(self, url):
        self.opened.append(url)
        return True


def pair(nodes, **kw):
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b, **kw)
    a.desktop, b.desktop = Desk(), Desk()
    return a, b


def frames(node):
    """Record every message `node` sends over its links from now on."""
    sent = []
    import droplet_agent.mesh.wslink as wslink
    orig = wslink.Link.send

    def send(self, msg):
        if self in [lk for links_ in node.links.values() for lk in links_]:
            sent.append(json.loads(json.dumps(msg)))
        return orig(self, msg)
    wslink.Link.send = send
    return sent, lambda: setattr(wslink.Link, "send", orig)


# --- links -------------------------------------------------------------------------------------

@pytest.mark.parametrize("url,ok", [
    ("https://example.com/a?b=c#d", True), ("http://192.168.1.20:8000/", True), ("  https://x.org  ", True),
    ("javascript:alert(1)", False), ("file:///etc/passwd", False), ("data:text/html,hi", False),
    ("ftp://x.org", False), ("intent://x#Intent;end", False), ("https://", False), ("https://a b.com", False),
    ("https://x.org/‮", False), ("https://x.org/" + "a" * 3000, False), ("", False), (None, False)])
def test_only_web_links_travel_and_open(url, ok):
    assert links.is_url(url) is ok


def test_a_message_thats_only_a_link_gets_an_open_button():
    assert links.only_url(" https://x.org/a ") == "https://x.org/a"
    assert links.only_url("look: https://x.org") is None
    assert links.only_url("javascript:alert(1)") is None


def test_a_link_from_your_own_device_opens(nodes):
    a, b = pair(nodes)
    out = a.handle_control({"cmd": "link", "peer": "b", "url": "https://example.com/slides"})
    assert out == {"how": "link", "route": "lan"}
    assert wait_for(lambda: b.desktop.opened == ["https://example.com/slides"])
    got = b.chat_history()
    assert got[-1]["kind"] == "link" and got[-1]["body"] == "https://example.com/slides" and got[-1]["opened"]
    assert a.chat_history()[-1]["kind"] == "link" and a.chat_history()[-1]["dir"] == "out"
    assert b.desktop.shown[-1][0] == "a opened a link"


def test_a_link_from_someone_elses_device_waits_with_an_open_button(nodes):
    a, b = pair(nodes)
    b.set_perms(a.identity.fp, relation="other")
    assert a.send_link(b.identity.fp, "https://example.com/x")["how"] == "link"
    assert wait_for(lambda: b.chat_history() and b.chat_history()[-1]["body"] == "https://example.com/x")
    time.sleep(0.2)
    assert b.desktop.opened == [] and "opened" not in b.chat_history()[-1]
    assert b.desktop.shown[-1][0] == "a sent a link"


def test_bad_links_are_refused_both_ways(nodes):
    a, b = pair(nodes)
    with pytest.raises(ValueError, match="only web links"):
        a.send_link(b.identity.fp, "file:///etc/passwd")
    assert "only web links" in a.handle_control({"cmd": "link", "peer": "b", "url": "javascript:x"})["error"]
    # a peer that sends one anyway: refused, never stored or opened
    a.direct(b.identity.fp).send({"t": "link", "id": "linkid0001", "url": "javascript:alert(1)", "ts": 1})
    time.sleep(0.3)
    assert b.desktop.opened == [] and b.chat_history() == []


def test_links_follow_the_chat_switch(nodes):
    a, b = pair(nodes)
    a.set_perms(b.identity.fp, allow={"chat": False})
    with pytest.raises(Refused, match="switched off here"):
        a.send_link(b.identity.fp, "https://example.com")
    a.set_perms(b.identity.fp, allow={"chat": True})
    b.set_perms(a.identity.fp, allow={"chat": False})
    # b refuses it (a doesn't know yet, or ignores the hint): not opened
    a.remote_perm.pop(b.identity.fp, None)
    with pytest.raises(Refused, match="doesn't allow messages"):
        a.send_link(b.identity.fp, "https://example.com")
    assert b.desktop.opened == []


def test_links_to_a_paused_or_older_peer_go_as_a_message(nodes):
    a, b = pair(nodes)
    a.set_perms(b.identity.fp, paused=True)
    out = a.send_link(b.identity.fp, "https://example.com/p")
    assert out["how"] == "message"
    assert a.outbox.wait(out["job"]["id"], lambda j: j["attempts"] > 0, 5)["state"] == QUEUED
    a.set_perms(b.identity.fp, paused=False)
    assert wait_for(lambda: any(m["body"] == "https://example.com/p" for m in b.chat_history()))
    assert b.desktop.opened == []          # a message is never opened by itself
    # an older peer, which doesn't say it understands `link`
    a.direct(b.identity.fp).hello.pop("features", None)
    assert a.send_link(b.identity.fp, "https://example.com/old")["how"] == "message"


def test_opening_is_limited(nodes):
    a, b = pair(nodes)
    for i in range(8):
        a.send_link(b.identity.fp, f"https://example.com/{i}")
    assert wait_for(lambda: len(b.chat_history()) == 8)
    assert len(b.desktop.opened) == 5


# --- names ----------------------------------------------------------------------------------

def test_names_are_checked():
    assert check_name("  Ferrin's   laptop ") == "Ferrin's laptop"
    for bad, why in (("", "empty"), ("   ", "empty"), ("a" * 41, "40 characters"), ("bad\x07name", "control"),
                     ("evil‮gnp.exe", "control"), (5, "text")):
        with pytest.raises(ValueError, match=why):
            check_name(bad)


def test_renaming_tells_linked_devices_mdns_and_the_next_hello(nodes):
    a, b = pair(nodes)
    a.direct(b.identity.fp)
    out = a.handle_control({"cmd": "rename", "name": "Ferrin's  laptop"})
    assert out == {"name": "Ferrin's laptop", "told": 1}
    assert a.name == "Ferrin's laptop" and a._txt()["name"] == "Ferrin's laptop"
    assert a.hello()["name"] == "Ferrin's laptop"
    assert wait_for(lambda: b.trust.get(a.identity.fp)["name"] == "Ferrin's laptop")
    assert b.status()["peers"][0]["name"] == "Ferrin's laptop"
    assert "control" in a.handle_control({"cmd": "rename", "name": "x\x00"})["error"]
    # b not linked: it learns from the next hello
    for lk in list(b.links.get(a.identity.fp, [])):
        lk.close()
    assert wait_for(lambda: b.open_link(a.identity.fp) is None and a.open_link(b.identity.fp) is None)
    a.rename("slim")
    assert b.trust.get(a.identity.fp)["name"] == "Ferrin's laptop"
    b.direct(a.identity.fp)
    assert wait_for(lambda: b.trust.get(a.identity.fp)["name"] == "slim")


def test_a_peer_the_hub_names_isnt_renamed_by_itself(nodes):
    hub = "ab" * 8
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b, source="roster", hub=hub)
    a.direct(b.identity.fp)
    b.rename("not the hub's name")
    time.sleep(0.3)
    assert a.trust.get(b.identity.fp)["name"] == "b"


def test_renaming_with_a_hub_goes_through_the_hub(monkeypatch):
    from droplet_agent import config, hub, mesh_host
    calls = []

    def rename(route, token, name):
        calls.append(name)
        if name == "taken":
            raise hub.HubError("taken is already a device here. Pick another name, or remove the old one under Devices.")
        return name
    monkeypatch.setattr(hub, "rename", rename)
    saved = {}
    monkeypatch.setattr(config, "load", lambda path=None: {"device": {"name": "old"}})
    monkeypatch.setattr(config, "save", lambda cfg, path=None: saved.update(cfg))

    class A:
        route = None
    h = mesh_host.AgentHost(A(), {"hub": "http://hub:8000", "token": "t", "device": {"id": "abcdabcdabcd", "name": "old"}})
    with pytest.raises(ValueError, match="already a device here"):
        h.set_device_name("taken")
    assert h.device_name() == "old" and not saved
    h.set_device_name("slim")
    assert calls == ["taken", "slim"] and h.device_name() == "slim" and saved["device"]["name"] == "slim"
    # no hub: only the config
    h2 = mesh_host.AgentHost(A(), {"device": {"name": "old"}})
    h2.set_device_name("t15")
    assert calls == ["taken", "slim"] and h2.device_name() == "t15"


def test_nicknames_show_here_and_are_never_sent(nodes):
    a, b = pair(nodes)
    sent, undo = frames(a)
    try:
        out = a.handle_control({"cmd": "nickname", "peer": "b", "nickname": "Brian's  laptop"})
        assert out == {"name": "b", "fp": b.identity.fp, "nickname": "Brian's laptop"}
        assert a.resolve("brian's laptop")["fp"] == b.identity.fp
        p = a.status()["peers"][0]
        assert p["nickname"] == "Brian's laptop" and p["name"] == "b"
        a.send_text(b.identity.fp, "hi")
        a.clip(b.identity.fp, "clip")
        a.send_link(b.identity.fp, "https://example.com")
        a.set_perms(b.identity.fp, paused=False)
        a.rename("a2")
        b.direct(a.identity.fp).send({"t": "text", "id": "fromb00001", "body": "hello"})
        assert wait_for(lambda: any(m["body"] == "hello" for m in a.chat_history()))
        assert a.desktop.shown[-1][0] == "Brian's laptop"   # its notifications use the nickname
        # a restart keeps it; a roster update doesn't take it away
        from droplet_agent.mesh.trust import TrustList
        assert TrustList(a.trust.path, a.identity.fp).get(b.identity.fp)["nickname"] == "Brian's laptop"
        assert wait_for(lambda: len(sent) >= 4)
        everything = json.dumps(sent) + json.dumps(a.hello(fp=b.identity.fp)) + json.dumps(a._txt()) \
            + json.dumps(a.announce_body())
        assert "Brian" not in everything
        assert b.trust.get(a.identity.fp).get("nickname", "") == ""
        assert a.handle_control({"cmd": "nickname", "peer": "b", "nickname": ""})["nickname"] == ""
        assert "40 characters" in a.handle_control({"cmd": "nickname", "peer": "b", "nickname": "x" * 50})["error"]
    finally:
        undo()


def test_a_roster_fetch_keeps_the_nickname(nodes):
    hub = "ab" * 8
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b, source="roster", hub=hub)
    a.set_nickname(b.identity.fp, "the NAS")
    e = make_entry(peer_id=b.peer_id, name="b", cert_pem=b.identity.cert_pem, source="roster", lan=["127.0.0.1"],
                   port=b.port, hub=hub)
    a.trust.sync_roster([e], hub)
    assert a.trust.get(b.identity.fp)["nickname"] == "the NAS"


# --- several devices at once -------------------------------------------------------------------

def test_all_my_devices_means_your_own_that_allow_it():
    peers = [{"fp": "1", "relation": "own", "allow": dict(perms.OWN)},
             {"fp": "2", "relation": "other", "allow": dict(perms.OTHER)},
             {"fp": "3", "relation": "own", "allow": dict(perms.OWN, files=False)},
             {"fp": "4", "relation": "own", "allow": dict(perms.OWN), "paused": True}]
    assert [p["fp"] for p in perms.own_targets(peers, "files")] == ["1", "4"]
    assert [p["fp"] for p in perms.own_targets(peers, "clipboard")] == ["1", "3", "4"]


@pytest.fixture
def three(nodes, monkeypatch):
    """a, with three devices: b (fine), c (paused here), d (refuses files from a)."""
    from droplet_agent import cli
    from droplet_agent.mesh import control
    a, b, c, d = nodes("a"), nodes("b"), nodes("c"), nodes("d")
    for x in (b, c, d):
        trust_each_other(a, x)
    a.set_perms(c.identity.fp, paused=True)
    d.set_perms(a.identity.fp, allow={"files": False})
    a.direct(d.identity.fp)
    assert wait_for(lambda: (a.remote_perm.get(d.identity.fp) or {}).get("allow", {}).get("files") is False)
    monkeypatch.setattr(control, "call", lambda req, timeout=30, path=None: a.handle_control(req))
    monkeypatch.setattr(cli, "_ask_agent", lambda req, timeout=30: (lambda o: None if o.get("error") else o)(
        a.handle_control(req)))
    return a, b, c, d


def test_send_file_to_several_devices_each_gets_its_own_result(three, tmp_path, capsys):
    from droplet_agent import cli
    a, b, c, d = three
    f = tmp_path / "notes.txt"
    f.write_text("for everyone")
    rc = cli.main(["send-file", "b,c,d", str(f)])
    out, err = capsys.readouterr()
    assert rc == 1                                         # one was refused
    assert "Sent notes.txt to b directly, over the LAN." in out
    assert "notes.txt to c: waiting: c is paused: resume it to send." in out
    assert "notes.txt to d: d doesn't allow files from you" in err
    assert (b.downloads / "notes.txt").read_text() == "for everyone"
    assert not (d.downloads / "notes.txt").exists()
    # each its own job: c's goes on resume
    a.set_perms(c.identity.fp, paused=False)
    assert wait_for(lambda: (c.downloads / "notes.txt").exists())


def test_all_and_to_and_text_and_clip(three, tmp_path, capsys):
    from droplet_agent import cli
    a, b, c, d = three
    a.set_perms(d.identity.fp, relation="other")
    f = tmp_path / "x.txt"
    f.write_text("x")
    assert cli.main(["send-file", "--all", str(f)]) == 0     # b and c (d is someone else's)
    out = capsys.readouterr().out
    assert "x.txt to b" in out and "x.txt to c" in out and "to d" not in out
    assert cli.main(["text", "--to", "b", "--to", "c", "hello", "both"]) == 0
    out = capsys.readouterr().out
    assert "Sent a message to b directly" in out and "a message to c: waiting" in out
    assert wait_for(lambda: any(m["body"] == "hello both" for m in b.chat_history()))
    assert cli.main(["clip", "b", "--text", "just b"]) == 0
    assert cli.main(["open-link", "b", "file:///etc/passwd"]) == 1
    assert "only web links" in capsys.readouterr().err
    assert cli.main(["send-file", "--all", "--to", "b", str(f)]) == 2


def test_a_device_whose_name_has_a_comma(nodes, monkeypatch, tmp_path, capsys):
    from droplet_agent import cli
    from droplet_agent.mesh import control
    a, b = nodes("a"), nodes("b, the old one")
    trust_each_other(a, b)
    monkeypatch.setattr(control, "call", lambda req, timeout=30, path=None: a.handle_control(req))
    monkeypatch.setattr(cli, "_ask_agent", lambda req, timeout=30: (lambda o: None if o.get("error") else o)(
        a.handle_control(req)))
    f = tmp_path / "y.txt"
    f.write_text("y")
    assert cli.main(["send-file", "b, the old one", str(f)]) == 0
    assert "Sent y.txt directly" in capsys.readouterr().out


def test_the_cli_shows_progress_on_a_terminal(three, tmp_path, capsys):
    from droplet_agent import cli
    line = cli._progress_line([{"id": "j1", "name": "video.mp4", "peer": "b", "state": "active", "percent": 45,
                                "eta": 12, "rate": 3_355_443, "done": 1, "dir": "out"}],
                              [{"id": "j1", "state": "sending"}, {"id": "j2", "state": "done"}])
    assert line == "1/2 · video.mp4 → b: 45% · 12 s left · 3.2 MB/s"
    a, b, c, d = three
    a.offers.max_rate = 1024 * 1024
    f = tmp_path / "big.bin"
    f.write_bytes(b"0" * 2 * 1024 * 1024)
    jobs = cli._send_jobs([b.identity.fp], [{"cmd": "send-file", "path": str(f), "what": "big.bin"}])
    cli._follow(jobs, True, every=0.1)
    out = capsys.readouterr().out
    assert "\r" in out and "big.bin → b: " in out and "%" in out
    assert jobs[0]["state"] == "done"


def test_the_cli_transfers_cancel_rename_and_nickname(three, tmp_path, capsys):
    from droplet_agent import cli
    a, b, c, d = three
    f = tmp_path / "w.txt"
    f.write_text("w")
    job = a.send_file(c.identity.fp, f)
    a.outbox.wait(job["id"], lambda j: j["attempts"] > 0, 5)
    assert cli.main(["transfers"]) == 0
    out = capsys.readouterr().out
    assert job["id"][:12] in out and "w.txt to c" in out
    assert cli.main(["cancel", job["id"][:12]]) == 0
    assert "Cancelled w.txt to c" in capsys.readouterr().out
    assert cli.main(["rename", "my", "laptop"]) == 0 and a.name == "my laptop"
    assert "called my laptop now" in capsys.readouterr().out
    assert cli.main(["rename", "x" * 50]) == 2
    assert cli.main(["nickname", "b", "Brian's", "laptop"]) == 0
    assert "b is called Brian's laptop on this computer" in capsys.readouterr().out
    assert cli.main(["peers"]) == 0
    assert "Brian's laptop" in capsys.readouterr().out


# --- the iPhone ------------------------------------------------------------------------------

from test_webrtc import iphone  # noqa: E402,F401


def test_the_iphone_hears_the_features_and_a_rename_and_renames_itself(iphone):
    a, node, fp, ch, send, written = iphone()
    welcome = next(m for m in ch.json() if m["t"] == "welcome")
    assert welcome["features"] == ["cancel", "link", "rename"]
    node.rename("slim")
    assert wait_for(lambda: {"t": "rename", "name": "slim"} in ch.json())
    send({"t": "rename", "name": "Ann's  new iPhone"})
    assert wait_for(lambda: node.trust.get(fp)["name"] == "Ann's new iPhone")
    send({"t": "rename", "name": "bad\x00"})
    time.sleep(0.2)
    assert node.trust.get(fp)["name"] == "Ann's new iPhone"


def test_links_to_and_from_the_iphone(iphone):
    import threading
    a, node, fp, ch, send, written = iphone()
    node.desktop = Desk()
    # the app said nothing about features (an older one): the link goes as a message
    assert node.send_link(fp, "https://example.com/a")["how"] == "message"
    assert wait_for(lambda: any(m["t"] == "text" and m["body"] == "https://example.com/a" for m in ch.json()))
    # one that understands links
    node.open_link(fp).hello["features"] = ["cancel", "link", "rename"]
    got = {}
    t = threading.Thread(target=lambda: got.update(node.send_link(fp, "https://example.com/b")))
    t.start()
    assert wait_for(lambda: any(m["t"] == "link" for m in ch.json()))
    m = next(m for m in ch.json() if m["t"] == "link")
    send({"t": "ack", "id": m["id"]})
    t.join(5)
    assert got == {"how": "link", "route": "webrtc"} and m["url"] == "https://example.com/b"
    # from the iPhone, your own: it opens here
    send({"t": "link", "id": "iphonelink1", "url": "https://example.com/from-phone", "ts": 1})
    assert wait_for(lambda: node.desktop.opened == ["https://example.com/from-phone"])
    assert {"t": "ack", "id": "iphonelink1"} in ch.json()
