"""The agent's message handling, with a fake transport and dry-run backends."""

import time

import pytest

from droplet_agent import agent as agent_mod
from droplet_agent import config, mediastate


@pytest.fixture
def make_agent(monkeypatch):
    monkeypatch.setattr(mediastate, "available", lambda: (True, "fake"))
    made = []

    def make(**caps_off):
        cfg = config.load()
        cfg.update(hub="http://hub.invalid", token="t", device={"id": "me", "name": "slim"})
        cfg["lock_command"] = ["true"]  # found on every system; the dry run won't run it
        for cap in caps_off:
            cfg["caps"][cap] = False
        a = agent_mod.Agent(cfg, dry_run=True)
        sent = []
        a.transport = lambda m: sent.append(m) or True
        a.input.start()
        made.append(a)
        return a, sent
    yield make
    for a in made:
        a.close()


def wait_for(cond, timeout=3):
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.02)
    return False


def test_hello_lists_working_caps(make_agent):
    a, _ = make_agent()
    hello = a.hello()
    assert hello["t"] == "hello" and hello["platform"] == "linux"
    assert set(hello["caps"]) == {"input", "media", "lock", "screenshot", "clipboard"}


def test_switched_off_caps_are_not_offered_or_used(make_agent):
    a, sent = make_agent(input=True, clipboard=True)
    assert set(a.hello()["caps"]) == {"media", "lock", "screenshot"}
    a.start()
    a.dispatch({"t": "input", "ev": [{"k": "move", "dx": 5, "dy": 5}]})
    written = []
    a.clip.writer = written.append
    a.dispatch({"t": "clip", "text": "hi"})
    time.sleep(0.3)
    assert a.input.backend is None or a.input.backend.calls == []
    assert written == []
    a.clip.local_change("copied")
    assert not [m for m in sent if m.get("t") == "clip"]


def test_input_is_applied_in_order(make_agent):
    a, _ = make_agent()
    a.hello()
    a.start()
    a.dispatch({"t": "input", "ev": [{"k": "move", "dx": 1.5, "dy": 0}, {"k": "move", "dx": 0.5, "dy": 0}]})
    a.dispatch({"t": "input", "ev": [{"k": "key", "key": "ArrowRight"}, {"k": "text", "s": "ok"}]})
    calls = a.input.backend.calls
    assert wait_for(lambda: len(calls) == 4)
    assert calls == [("move", 1, 0), ("move", 1, 0), ("key", "ArrowRight"), ("text", "ok")]


def test_media_and_lock_and_rpc(make_agent, monkeypatch):
    a, sent = make_agent()
    acted = []
    monkeypatch.setattr(a.media, "act", lambda *args: acted.append(args))
    a.hello()
    a.dispatch({"t": "media", "action": "volume", "value": 0.4, "from": {"id": "p", "name": "phone"}})
    a.dispatch({"t": "cmd", "cmd": "lock"})
    a.dispatch({"t": "cmd", "cmd": "reboot"})
    a.dispatch({"t": "rpc", "id": "c1:r7", "method": "files.list", "params": {}})
    assert wait_for(lambda: acted)
    assert acted == [("volume", None, 0.4)]
    assert {"t": "rpc-result", "id": "c1:r7", "error": "Not supported by the Linux agent."} in sent


def test_screenshot_uploads_to_the_requester(make_agent, monkeypatch):
    a, _ = make_agent()
    uploads = []
    monkeypatch.setattr(agent_mod.hub, "upload", lambda *args, **kw: uploads.append(args))
    a.hello()
    a.dispatch({"t": "cmd", "cmd": "screenshot", "from": {"id": "phone1", "name": "phone"}})
    assert wait_for(lambda: uploads)
    hub_url, token, to, name, data = uploads[0]
    assert (hub_url, token, to) == ("http://hub.invalid", "t", "phone1")
    assert name.startswith(f"screenshot-{a.host}-") and name.endswith(".png")
    assert data.startswith(b"\x89PNG")


def test_clip_in_and_out(make_agent):
    a, sent = make_agent()
    a.hello()
    written = []
    a.clip.writer = lambda t: written.append(t)
    a.dispatch({"t": "clip", "text": "from phone", "from": {"id": "p", "name": "phone"}})
    assert wait_for(lambda: written)
    a.clip.local_change("from phone")  # echo
    a.clip.local_change("new here")
    assert [m for m in sent if m["t"] == "clip"] == [{"t": "clip", "text": "new here"}]


def test_unknown_and_malformed_messages_are_ignored(make_agent):
    a, sent = make_agent()
    a.hello()
    for m in ({"t": "brand-new"}, {"t": "input", "ev": "nope"}, {"no": "t"}, [], None,
              {"t": "cmd", "cmd": "screenshot"}):
        a.dispatch(m)
    assert sent == []


def test_state_is_published_only_with_media_cap(make_agent):
    a, sent = make_agent(media=True)
    a.hello()
    assert a.publish("media", {"players": []}) is False
    assert a.publish("battery", {"level": 50, "charging": False}) is True
