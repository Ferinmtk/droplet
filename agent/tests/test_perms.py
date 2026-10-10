"""Per-device permissions and Pause (mesh/perms.py, docs/mesh.md §9.9).

Each capability is tried both ways between two real peers, in each of four states: allowed,
switched off for that device, that device paused, and everything paused. The receiver
enforces its own settings whatever the sender does, and the sender its own.
"""

import json
import os
import time

import pytest

from droplet_agent.mesh import perms
from droplet_agent.mesh.node import NoRoute, Refused
from droplet_agent.mesh.outbox import DONE, FAILED, QUEUED
from droplet_agent.mesh.trust import TrustList, make_browser_entry, make_entry

from test_mesh import FakeHost, nodes, trust_each_other, wait_for  # noqa: F401  (nodes is a fixture)


class FakeDesktop:
    def __init__(self):
        self.shown, self.closed, self.rings = [], [], []

    def notify(self, title, body, key=None, app="droplet"):
        self.shown.append((title, body, key, app))

    def close_notification(self, key):
        self.closed.append(key)

    def ring(self, name):
        self.rings.append(name)

    def stop_ring(self):
        self.rings.append("stop")


def pair(nodes, **kw):
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b, **kw)
    a.desktop, b.desktop = FakeDesktop(), FakeDesktop()
    return a, b


def set_state(node, peer, cap, state):
    """`node`'s settings about `peer`: "allowed" (as paired), "denied" (cap off), "paused", "global"."""
    if state == "denied":
        node.set_perms(peer.identity.fp, allow={cap: False})
    elif state == "paused":
        node.set_perms(peer.identity.fp, paused=True)
    elif state == "global":
        node.pause_everything(True)


# --- the model ---------------------------------------------------------------------------

def test_defaults_for_your_own_device_and_someone_elses():
    assert perms.defaults("own") == {c: True for c in perms.CAPABILITIES}
    assert perms.defaults("other") == {"files": True, "chat": True, "clipboard": False, "notify": False,
                                       "control": False, "ring": True, "access": False}
    # anything else, or nothing at all, is your own device: what every peer was before
    assert perms.clean_relation(None) == perms.clean_relation("guest") == "own"
    assert perms.clean_allow({"clipboard": False, "bogus": False, "files": "yes"}, "own") == \
        dict(perms.OWN, clipboard=False)


@pytest.mark.parametrize("msg,cap", [
    ({"t": "text"}, "chat"), ({"t": "offer"}, "files"), ({"t": "file"}, "files"), ({"t": "clip"}, "clipboard"),
    ({"t": "notify"}, "notify"), ({"t": "notify-removed"}, "notify"), ({"t": "input"}, "control"),
    ({"t": "media"}, "control"), ({"t": "cmd"}, "control"), ({"t": "ring"}, "ring"), ({"t": "ring-stop"}, "ring"),
    ({"t": "rpc", "method": "sms.list"}, "access"), ({"t": "rpc", "method": "files.get"}, "access"),
    ({"t": "rpc", "method": "media.play"}, "control"), ({"t": "state", "kind": "media"}, "control"),
    ({"t": "state", "kind": "battery"}, None), ({"t": "unpair"}, None), ({"t": "something-new"}, None)])
def test_what_each_message_needs(msg, cap):
    assert perms.capability(msg) == cap


def test_what_always_goes_even_paused():
    entry = {"paused": True, "relation": "own", "allow": dict(perms.OWN)}
    for t in ("hello", "welcome", "ping", "pong", "perm", "unpair", "ack", "nack", "refused", "rpc-result"):
        assert perms.check(entry, {"t": t}) is None
    assert perms.check(entry, {"t": "state", "kind": "battery"}) == ("paused", "")
    assert perms.check({"allow": dict(perms.OWN)}, {"t": "ring"}, paused_all=True) == ("paused", "ring")


def test_an_entry_from_before_permissions_is_your_own_device(tmp_path):
    from test_mesh import make_node
    n = make_node(tmp_path, "x")
    other = make_node(tmp_path, "y")
    try:
        e = make_entry(peer_id=other.peer_id, name="y", cert_pem=other.identity.cert_pem, source="paired")
        for k in ("relation", "allow", "paused"):
            e.pop(k)
        path = tmp_path / "trust.json"
        path.write_text(json.dumps({"v": 1, "peers": {e["fp"]: e}}))
        got = TrustList(path, n.identity.fp).get(e["fp"])
        assert got["relation"] == "own" and got["allow"] == perms.OWN and got["paused"] is False
    finally:
        n.close()
        other.close()


def test_the_roster_keeps_the_owners_switches(nodes):
    hub = "ab" * 8
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b, source="roster", hub=hub)
    assert a.trust.get(b.identity.fp)["relation"] == "own"     # the hub's devices are your own
    a.set_perms(b.identity.fp, allow={"clipboard": False}, paused=True)
    e = make_entry(peer_id=b.peer_id, name="b renamed", cert_pem=b.identity.cert_pem, source="roster",
                   lan=["127.0.0.1"], port=b.port, hub=hub)
    a.trust.sync_roster([e], hub)
    got = a.trust.get(b.identity.fp)
    assert got["name"] == "b renamed" and got["paused"] and got["allow"]["clipboard"] is False


def test_changing_the_relation_starts_from_its_defaults(nodes):
    a, b = pair(nodes)
    fp = b.identity.fp
    a.set_perms(fp, allow={"files": False})
    e = a.set_perms(fp, relation="other")
    assert e["allow"] == perms.OTHER
    e = a.set_perms(fp, relation="own", allow={"ring": False})
    assert e["allow"] == dict(perms.OWN, ring=False)
    with pytest.raises(ValueError, match="no capability"):
        a.set_perms(fp, allow={"teleport": True})
    with pytest.raises(ValueError, match="relation"):
        a.set_perms(fp, relation="friend")
    # kept across a restart
    assert TrustList(a.trust.path, a.identity.fp).get(fp)["allow"] == dict(perms.OWN, ring=False)


# --- the matrix: what the receiver takes ------------------------------------------------------

STATES = ("allowed", "denied", "paused", "global")


def _no_hint(node):
    """Make `node` send whatever it's asked, as an older or misbehaving peer would: only the
    receiver's own checks stand in the way."""
    node.may_send = lambda *args, **kw: None


def _receive(a, b, cap, tmp_path):
    """a sends b something needing `cap`, ignoring any hint. Returns what b did: "taken" or
    the refusal a got back as (why, text)."""
    fp = b.identity.fp
    _no_hint(a)
    if cap in ("files", "chat"):
        if cap == "files":
            src = tmp_path / "photo.jpg"
            src.write_bytes(os.urandom(5000))
            job = a.send_file(fp, src)
        else:
            job = a.send_text(fp, "hello")
        got = a.outbox.wait(job["id"], lambda j: j["state"] in (DONE, FAILED)
                            or (j["state"] == QUEUED and j["attempts"] > 0 and not j.get("retry")), 10)
        if got["state"] == DONE:
            return "taken"
        if got["state"] == FAILED:
            return ("denied", got["error"])
        assert got["error"].startswith("waiting: "), got
        return ("paused", got["error"])
    link = a.direct(fp)
    assert link is not None
    msg = {"clipboard": {"t": "clip", "text": "secret"},
           "notify": {"t": "notify", "key": "k1", "app": "Chat", "title": "Mum", "text": "hi"},
           "control": {"t": "input", "ev": [{"k": "key", "key": "Enter"}]},
           "ring": {"t": "ring"},
           "access": {"t": "rpc", "id": "r1", "method": "files.list", "params": {}}}[cap]
    a.refusals.pop(fp, None)
    link.send(msg)

    def taken():
        if cap == "notify":
            return bool(b.desktop.shown)
        if cap == "ring":
            return bool(b.desktop.rings)
        return any(m["t"] == msg["t"] for m, _s in b.host.dispatched)
    assert wait_for(lambda: taken() or a.refusals.get(fp), 5)
    if taken():
        return "taken"
    r = a.refusals[fp]
    assert r["cap"] == cap and r["re"] == msg["t"]
    return (r["why"], r["text"])


@pytest.mark.parametrize("state", STATES)
@pytest.mark.parametrize("cap", list(perms.CAPABILITIES))
def test_the_receiver_enforces_its_own_settings(nodes, tmp_path, cap, state):
    a, b = pair(nodes)
    set_state(b, a, cap, state)
    got = _receive(a, b, cap, tmp_path)
    if state == "allowed":
        assert got == "taken"
    elif state == "denied":
        noun = perms.NOUNS[cap]
        assert got == ("denied", f"b doesn't allow {noun} from you")
    else:
        # paused: refused for now, and files and messages wait for the resume rather than fail
        why, text = got
        assert why == "paused" and "b paused sharing with you" in text
    # nothing of it reached b
    if state != "allowed":
        assert not b.host.dispatched and not b.desktop.shown and not b.desktop.rings
        assert not [m for m in b.chat.recent() if m["dir"] == "in"]
        assert not (tmp_path / "b" / "dl").exists() or not list((tmp_path / "b" / "dl").iterdir())


# --- the matrix: what the sender sends ---------------------------------------------------------

def _send(a, b, cap, tmp_path):
    """a sends b something needing `cap` the normal way. "sent", "waiting", or the Refused."""
    fp = b.identity.fp
    try:
        if cap == "files":
            src = tmp_path / "doc.txt"
            src.write_text("x")
            job = a.send_file(fp, src)
        elif cap == "chat":
            job = a.send_text(fp, "hi")
        elif cap == "clipboard":
            return "sent" if a.clip(fp, "copied") else None
        elif cap == "control":
            return "sent" if a.send_live(fp, {"t": "input", "ev": []}) else None
        elif cap == "ring":
            return "sent" if a.ring(fp) else None
        else:
            msg = {"notify": {"t": "notify", "key": "k", "title": "t", "text": "x"},
                   "access": {"t": "rpc", "id": "r", "method": "sms.list"}}[cap]
            a.may_send(fp, msg)
            return "sent"
    except Refused as e:
        return e
    got = a.outbox.wait(job["id"], lambda j: j["state"] in (DONE, FAILED)
                        or (j["state"] == QUEUED and j["attempts"] > 0 and not j.get("retry")), 10)
    return {DONE: "sent", QUEUED: "waiting"}.get(got["state"], got.get("error"))


@pytest.mark.parametrize("state", STATES)
@pytest.mark.parametrize("cap", list(perms.CAPABILITIES))
def test_the_sender_enforces_its_own_settings(nodes, tmp_path, cap, state):
    a, b = pair(nodes)
    set_state(a, b, cap, state)
    got = _send(a, b, cap, tmp_path)
    if state == "allowed" or (state == "denied" and cap in perms.INBOUND_ONLY):
        # a switch about what b may do *here* doesn't stop this device using b: b decides that
        assert got == "sent"
    elif state == "denied":
        assert isinstance(got, Refused) and got.local and got.why == "denied"
        assert "switched off here" in str(got)
    elif cap in ("files", "chat"):
        assert got == "waiting"       # it waits for the resume, it doesn't fail
        job = (a.outbox.queued())[0]
        assert job["error"].startswith("waiting: ")
        assert ("everything is paused" if state == "global" else "b is paused") in job["error"]
    else:
        assert isinstance(got, Refused) and got.local and got.why == "paused"


@pytest.mark.parametrize("cap", list(perms.CAPABILITIES))
def test_the_sender_respects_what_the_receiver_said(nodes, tmp_path, cap):
    """b switches cap off for a: b's perm tells a, which then doesn't send what b would refuse."""
    a, b = pair(nodes)
    assert a.direct(b.identity.fp) is not None
    b.set_perms(a.identity.fp, allow={cap: False})
    assert wait_for(lambda: (a.remote_perm.get(b.identity.fp) or {}).get("allow", {}).get(cap) is False)
    got = _send(a, b, cap, tmp_path)
    if cap in ("files", "chat"):
        assert isinstance(got, Refused) and not got.local
    else:
        assert isinstance(got, Refused) and not got.local
    assert str(got) == f"b doesn't allow {perms.NOUNS[cap]} from you"


# --- pausing and resuming ------------------------------------------------------------------------

def test_messages_to_a_paused_device_wait_and_go_on_resume(nodes):
    a, b = pair(nodes)
    fp = b.identity.fp
    a.set_perms(fp, paused=True)
    job = a.send_text(fp, "after the meeting")
    got = a.outbox.wait(job["id"], lambda j: j["attempts"] > 0 and j["state"] == QUEUED, 5)
    assert got["error"] == "waiting: b is paused: resume it to send"
    assert a.handle_control({"cmd": "resume", "peer": "b"})["paused"] is False
    assert a.outbox.wait(job["id"], lambda j: j["state"] == DONE, 8)["state"] == DONE
    assert [m["body"] for m in b.chat.recent() if m["dir"] == "in"] == ["after the meeting"]


def test_a_device_that_paused_us_gets_our_messages_when_it_resumes(nodes):
    a, b = pair(nodes)
    assert a.direct(b.identity.fp) is not None
    b.set_perms(a.identity.fp, paused=True)
    assert wait_for(lambda: (a.remote_perm.get(b.identity.fp) or {}).get("paused"))
    job = a.send_text(b.identity.fp, "are you there")
    got = a.outbox.wait(job["id"], lambda j: j["attempts"] > 0 and j["state"] == QUEUED, 5)
    assert got["error"] == "waiting: b paused sharing with you"
    status = {p["name"]: p for p in a.status()["peers"]}
    assert status["b"]["remote"]["paused"] is True
    b.set_perms(a.identity.fp, paused=False)        # its perm says so, and what waited goes
    assert a.outbox.wait(job["id"], lambda j: j["state"] == DONE, 8)["state"] == DONE


def test_pause_everything_stops_the_clipboard_to_everyone_and_says_so(nodes):
    a, b, c = nodes("a"), nodes("b"), nodes("c")
    trust_each_other(a, b)
    trust_each_other(a, c)
    for peer in (b, c):
        assert a.direct(peer.identity.fp) is not None
    assert a.broadcast({"t": "clip", "text": "one"})
    assert wait_for(lambda: len(b.host.dispatched) == 1 and len(c.host.dispatched) == 1)
    a.handle_control({"cmd": "pause", "all": True})
    assert a.status()["paused_all"] is True
    assert a.broadcast({"t": "clip", "text": "two"}) is False
    # each peer is told, so its window can say "paused by a"
    assert wait_for(lambda: (b.remote_perm.get(a.identity.fp) or {}).get("paused")
                    and (c.remote_perm.get(a.identity.fp) or {}).get("paused"))
    a.handle_control({"cmd": "resume", "all": True})
    assert wait_for(lambda: not b.remote_perm[a.identity.fp]["paused"])
    assert a.broadcast({"t": "clip", "text": "three"})
    assert wait_for(lambda: [m["text"] for m, _ in b.host.dispatched] == ["one", "three"])


def test_the_clipboard_skips_someone_elses_device(nodes):
    a, mine, guest = nodes("a"), nodes("mine"), nodes("guest")
    trust_each_other(a, mine)
    trust_each_other(a, guest)
    a.set_perms(guest.identity.fp, relation="other")
    for peer in (mine, guest):
        assert a.direct(peer.identity.fp) is not None
    assert a.broadcast({"t": "clip", "text": "my password"})
    assert wait_for(lambda: mine.host.dispatched)
    time.sleep(0.3)
    assert guest.host.dispatched == []


def test_hello_tells_each_peer_only_what_it_may_use(nodes):
    a, b = pair(nodes)
    a.set_perms(b.identity.fp, relation="other")
    hello = a.hello("welcome", b.identity.fp)
    assert hello["caps"] == []          # FakeHost offers input and media: both remote control
    assert hello["perm"] == {"paused": False, "allow": perms.OTHER}
    a.set_perms(b.identity.fp, relation="own")
    assert a.hello("welcome", b.identity.fp)["caps"] == ["input", "media"]
    a.set_perms(b.identity.fp, paused=True)
    assert a.hello("welcome", b.identity.fp)["caps"] == []
    # a link opened now carries it: b learns how a treats it
    assert b.direct(a.identity.fp) is not None
    assert wait_for(lambda: (b.remote_perm.get(a.identity.fp) or {}).get("paused") is True)


def test_an_older_peer_with_no_perm_is_treated_as_before(nodes):
    a, b = pair(nodes)
    b.perm_for = lambda fp: None
    orig = b.hello
    b.hello = lambda t="hello", fp=None: {k: v for k, v in orig(t, fp).items() if k != "perm"}
    assert a.direct(b.identity.fp) is not None
    assert a.remote_perm.get(b.identity.fp) is None
    assert a.clip(b.identity.fp, "x") == "lan"


def test_refusals_of_a_stream_are_said_once_in_a_while(nodes):
    a, b = pair(nodes)
    b.set_perms(a.identity.fp, allow={"control": False})
    link = a.direct(b.identity.fp)
    seen = []
    a._got_refused = lambda link, entry, msg: seen.append(msg)
    for _ in range(30):
        link.send({"t": "input", "ev": [{"k": "move", "dx": 1, "dy": 1}]})
    time.sleep(0.5)
    assert len(seen) == 1 and seen[0]["why"] == "denied" and seen[0]["cap"] == "control"


def test_a_paused_device_cant_fetch_a_file_offered_before(nodes, tmp_path):
    a, b = pair(nodes)
    a.set_perms(b.identity.fp, paused=True)
    heads = []
    a.serve_file(None, "0" * 32, b.identity.fp, type("R", (), {"headers": {}, "method": "GET"})(),
                 lambda status, headers: heads.append(status))
    assert heads == [403]


# --- pairing asks: your device or someone else's ----------------------------------------------------

def test_pairing_as_someone_elses_device_on_both_sides(nodes):
    a, b = nodes("a"), nodes("b")
    out = a.pair_start(f"127.0.0.1:{b.port}")
    rid = out["request"]
    (req,) = b.incoming.waiting()
    # each side chooses for itself
    b.handle_control({"cmd": "pair-answer", "request": req["request"], "accept": True, "relation": "other"})
    a.handle_control({"cmd": "pair-confirm", "request": rid, "yes": True, "relation": "own"})
    assert wait_for(lambda: a.trust.get(b.identity.fp) is not None, 10)
    assert b.trust.get(a.identity.fp)["relation"] == "other"
    assert b.trust.get(a.identity.fp)["allow"] == perms.OTHER
    assert a.trust.get(b.identity.fp)["relation"] == "own"
    # an old caller that doesn't say: your own device, as before
    assert a.handle_control({"cmd": "pair-answer", "request": "0" * 32, "accept": True})["error"]
    assert "relation" in a.handle_control({"cmd": "pair-confirm", "request": rid, "yes": True,
                                           "relation": "deskmate"})["error"]


def test_control_commands_and_status(nodes):
    a, b = pair(nodes)
    got = a.handle_control({"cmd": "perm-set", "peer": "b", "capability": "clipboard", "on": False})
    assert got["allow"]["clipboard"] is False and got["relation"] == "own"
    got = a.handle_control({"cmd": "perm-set", "peer": "b", "relation": "other"})
    assert got["allow"] == perms.OTHER
    assert a.handle_control({"cmd": "pause", "peer": "b"})["paused"] is True
    st = a.status()
    (p,) = st["peers"]
    assert p["relation"] == "other" and p["paused"] is True and p["allow"] == perms.OTHER
    assert st["paused_all"] is False and st["capabilities"] == list(perms.CAPABILITIES)
    assert "error" in a.handle_control({"cmd": "perm-set", "peer": "b", "capability": "x", "on": True})
    assert "error" in a.handle_control({"cmd": "pause", "peer": "nobody"})


# --- through the hub ------------------------------------------------------------------------------

def test_the_hub_route_follows_the_same_switches(nodes):
    hub = "ab" * 8
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b, source="roster", hub=hub)
    b.close()
    a.host.hub_up, a.host.online = True, {b.peer_id}
    fp = b.identity.fp
    assert a.send_live(fp, {"t": "input", "ev": []}) == "hub"
    a.set_perms(fp, paused=True)
    with pytest.raises(Refused):
        a.send_live(fp, {"t": "input", "ev": []})
    with pytest.raises(Refused):
        a.ring(fp)
    job = a.send_text(fp, "later")
    assert a.outbox.wait(job["id"], lambda j: j["attempts"] > 0 and j["state"] == QUEUED, 5)
    assert not [c for c in a.host.hub_calls if c[0] == "text"]
    a.set_perms(fp, paused=False)
    assert a.outbox.wait(job["id"], lambda j: j["state"] == DONE, 8)["route"] == "hub"
    # a clipboard through the hub reaches every device it has: not while one shouldn't have it
    assert a.hub_may_share({"t": "clip"})
    a.set_perms(fp, allow={"clipboard": False})
    assert not a.hub_may_share({"t": "clip"}) and a.hub_may_share({"t": "state", "kind": "battery"})
    with pytest.raises(Refused):
        a.clip(fp, "x")
    a.pause_everything(True)
    assert not a.hub_may_share({"t": "state", "kind": "battery"})


def test_messages_through_the_hub_are_checked_against_the_sender(nodes):
    hub = "ab" * 8
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b, source="roster", hub=hub)
    frm = {"id": b.peer_id, "name": "b"}
    assert a.check_hub_message({"t": "input", "from": frm}) is None
    a.set_perms(b.identity.fp, relation="other")
    assert a.check_hub_message({"t": "input", "from": frm}) == "denied"
    assert a.check_hub_message({"t": "clip", "from": frm}) == "denied"
    # a device of the hub's this one doesn't know (the hub's web page): the same user's
    assert a.check_hub_message({"t": "input", "from": {"id": "0123456789ab"}}) is None
    a.pause_everything(True)
    assert a.check_hub_message({"t": "input", "from": {"id": "0123456789ab"}}) == "paused"


def test_the_agent_refuses_through_the_hub_and_keeps_the_clipboard_off_it(monkeypatch):
    from droplet_agent import agent as agent_mod, config, mediastate
    monkeypatch.setattr(mediastate, "available", lambda: (True, "fake"))
    cfg = config.load()
    a = agent_mod.Agent(cfg, dry_run=True)
    a.hello()
    sent, applied = [], []
    a.transport = lambda m: sent.append(m) or True
    a.clip.writer = lambda t: applied.append(t)
    a.hub_check = lambda msg: "paused" if (msg.get("from") or {}).get("id") == "deskmate0000" else None
    a.hub_share = lambda msg: False
    a.dispatch({"t": "clip", "text": "from the deskmate", "from": {"id": "deskmate0000", "name": "Brian"}})
    a.dispatch({"t": "rpc", "id": "r9", "method": "files.list", "from": {"id": "deskmate0000"}})
    a.dispatch({"t": "clip", "text": "from my phone", "from": {"id": "myphone00000", "name": "phone"}})
    a.serial.submit(lambda: None).result(5)
    assert applied == ["from my phone"]
    assert sent == [{"t": "rpc-result", "id": "r9", "error": "Paused."}]
    assert a.send_clip("copied") is False and len(sent) == 1
    a.close()


def test_pause_everything_is_kept_in_the_config():
    from droplet_agent import config
    from droplet_agent.mesh_host import AgentHost

    class Agent:
        advertised = set()
    cfg = config.load()
    host = AgentHost(Agent(), cfg)
    assert host.paused_everything() is False
    host.set_paused_everything(True)
    assert config.load()["mesh"]["paused"] is True and host.paused_everything()
    host.set_paused_everything(False)
    assert config.load()["mesh"]["paused"] is False


# --- the iPhone (a browser peer over WebRTC) ----------------------------------------------------------

def test_a_browser_entry_takes_a_relation():
    from test_webrtc import Browser
    b = Browser()
    e = make_browser_entry(name="Brian's iPhone", key=b.b64, relation="other")
    assert e["relation"] == "other" and e["allow"] == perms.OTHER and e["paused"] is False


# --- the CLI ------------------------------------------------------------------------------------

def test_the_cli_commands(monkeypatch, capsys):
    from droplet_agent import cli
    asked = []

    def ask(req, timeout=30):
        asked.append(req)
        if req["cmd"] == "perm-set":
            allow = dict(perms.OWN, **({req["capability"]: req["on"]} if "capability" in req else {}))
            rel = req.get("relation") or "own"
            return {"name": "Brian's laptop", "relation": rel, "allow": perms.defaults(rel) if "relation" in req else allow,
                    "paused": False}
        return {"name": "Brian's laptop", "paused": req["cmd"] == "pause", "paused_all": bool(req.get("all"))}
    monkeypatch.setattr(cli, "_ask_agent", ask)
    assert cli.main(["allow", "brian", "clipboard", "off"]) == 0
    assert asked[-1] == {"cmd": "perm-set", "peer": "brian", "capability": "clipboard", "on": False}
    assert "Shared clipboard with Brian's laptop: off." in capsys.readouterr().out
    assert cli.main(["allow", "brian", "other"]) == 0
    assert asked[-1] == {"cmd": "perm-set", "peer": "brian", "relation": "other"}
    assert "someone else's device; off: clipboard, notify, control, access" in capsys.readouterr().out
    assert cli.main(["allow", "brian", "teleport", "on"]) == 2
    assert "Capabilities:" in capsys.readouterr().err
    assert cli.main(["pause", "brian"]) == 0 and asked[-1] == {"cmd": "pause", "peer": "brian"}
    assert "Paused Brian's laptop" in capsys.readouterr().out
    assert cli.main(["resume", "--all"]) == 0 and asked[-1] == {"cmd": "resume", "all": True}
    assert cli.main(["pause"]) == 2


def test_the_cli_asks_whose_device_it_is(monkeypatch, capsys):
    from droplet_agent import cli

    class A:
        own = other = False
    monkeypatch.setattr(cli, "_interactive", lambda: False)
    assert cli._ask_relation("x", A()) == "own"          # a script: as before
    a = A()
    a.other = True
    assert cli._ask_relation("x", a) == "other"
    monkeypatch.setattr(cli, "_interactive", lambda: True)
    answers = iter(["maybe", "other"])
    monkeypatch.setattr("builtins.input", lambda _p="": next(answers))
    assert cli._ask_relation("Brian's laptop", A()) == "other"
    out = capsys.readouterr().out
    assert "Is Brian's laptop your device, or someone else's" in out and "Answer own or other." in out
    line = cli._perm_line({"relation": "other", "allow": perms.OTHER, "paused": True})
    assert line == "someone else's, PAUSED; off: clipboard, notify, control, access"


from test_webrtc import file_frames, iphone  # noqa: E402,F401  (iphone is a fixture)


def _as_browser(node, msg):
    conn = next(iter(node.webrtc.conns))
    node.webrtc.listener.call(conn.message, msg if isinstance(msg, bytes) else json.dumps(msg))


def test_the_iphone_is_told_how_it_is_treated(iphone):
    a, node, fp, ch, send, written = iphone()
    welcome = next(m for m in ch.json() if m["t"] == "welcome")
    assert welcome["perm"] == {"paused": False, "allow": perms.OWN}
    node.handle_control({"cmd": "perm-set", "peer": fp, "relation": "other"})
    assert wait_for(lambda: any(m["t"] == "perm" for m in ch.json()))
    perm = [m for m in ch.json() if m["t"] == "perm"][-1]
    assert perm["paused"] is False and perm["allow"] == perms.OTHER
    node.handle_control({"cmd": "pause", "peer": fp})
    assert wait_for(lambda: [m for m in ch.json() if m["t"] == "perm"][-1]["paused"] is True)


def test_someone_elses_iphone_sends_files_but_not_its_clipboard(iphone):
    a, node, fp, ch, send, written = iphone(clipboard_sync=True)
    node.set_perms(fp, relation="other")
    send({"t": "clip", "id": "clip000009", "text": "from Brian's iPhone"})
    assert wait_for(lambda: any(m.get("id") == "clip000009" for m in ch.json()))
    nack = next(m for m in ch.json() if m.get("id") == "clip000009")
    assert nack == {"t": "nack", "id": "clip000009", "error": "t15 doesn't allow the clipboard from you",
                    "cap": "clipboard", "why": "denied"}
    assert written == []
    # this computer's clipboard doesn't go to it either, synced or sent on purpose
    a.clip.local_change("my password")
    with pytest.raises(Refused, match="switched off here"):
        node.clip(fp, "sent on purpose")
    # files are fine
    fid = "2" * 32
    data = b"holiday photo" * 10
    _as_browser(node, {"t": "file", "id": fid, "name": "photo.jpg", "size": len(data)})
    for f in file_frames(fid, data, 64):
        _as_browser(node, f)
    _as_browser(node, {"t": "file-end", "id": fid})
    assert wait_for(lambda: {"t": "ack", "id": fid} in ch.json())
    assert (node.downloads / "photo.jpg").read_bytes() == data
    assert not [m for m in ch.json() if m["t"] == "clip"]
    # and messages
    send({"t": "text", "id": "text000001", "body": "thanks!"})
    assert wait_for(lambda: {"t": "ack", "id": "text000001"} in ch.json())


def test_a_paused_iphone_is_refused_for_now_and_its_files_wait(iphone, tmp_path):
    a, node, fp, ch, send, written = iphone(clipboard_sync=True)
    node.set_perms(fp, paused=True)
    fid = "3" * 32
    _as_browser(node, {"t": "file", "id": fid, "name": "x.txt", "size": 3})
    send({"t": "text", "id": "text000002", "body": "hello?"})
    assert wait_for(lambda: any(m.get("id") == "text000002" for m in ch.json()))
    got = {m["id"]: m for m in ch.json() if m.get("id") in (fid, "text000002")}
    assert got[fid]["t"] == got["text000002"]["t"] == "refused"
    assert got[fid]["why"] == "paused" and got[fid]["error"] == "t15 paused sharing with you"
    assert not list(node.chat.recent())
    # what this computer sends it waits
    src = tmp_path / "for-the-iphone.txt"
    src.write_text("later")
    job = node.send_file(fp, src)
    assert node.outbox.wait(job["id"], lambda j: j["attempts"] > 0 and j["state"] == QUEUED, 5)["error"] == \
        "waiting: Ann's iPhone is paused: resume it to send"
    assert not [m for m in ch.json() if m["t"] == "file"]


def test_the_iphone_pausing_this_computer_holds_what_goes_to_it(iphone, tmp_path):
    a, node, fp, ch, send, written = iphone(clipboard_sync=True)
    send({"t": "perm", "paused": True, "allow": {}})
    assert wait_for(lambda: (node.remote_perm.get(fp) or {}).get("paused"))
    with pytest.raises(Refused, match="paused sharing with you") as e:
        node.clip(fp, "hi")
    assert not e.value.local
    a.clip.local_change("not for a paused iPhone")
    job = node.send_text(fp, "see you")
    assert node.outbox.wait(job["id"], lambda j: j["attempts"] > 0 and j["state"] == QUEUED, 5)["error"] == \
        "waiting: Ann's iPhone paused sharing with you"
    send({"t": "perm", "paused": False, "allow": {}})
    assert wait_for(lambda: any(m["t"] == "text" for m in ch.json()))
    assert not [m for m in ch.json() if m["t"] == "clip"]


def test_a_file_the_paused_iphone_refuses_waits_instead_of_failing(iphone, tmp_path):
    """An iPhone that paused without saying so first (or said so too late): its `refused` for a
    file keeps the file in the outbox."""
    a, node, fp, ch, send, written = iphone()
    src = tmp_path / "f.bin"
    src.write_bytes(b"z" * 100)
    job = node.send_file(fp, src)
    assert wait_for(lambda: any(m["t"] == "file" for m in ch.json()))
    offer = next(m for m in ch.json() if m["t"] == "file")
    send({"t": "refused", "re": "file", "id": offer["id"], "why": "paused", "error": "iPhone paused sharing with you"})
    got = node.outbox.wait(job["id"], lambda j: j["state"] == QUEUED and j["attempts"] > 0 and not j.get("retry"), 5)
    assert got["state"] == QUEUED and got["error"] == "waiting: iPhone paused sharing with you"
