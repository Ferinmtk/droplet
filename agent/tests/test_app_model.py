"""What Droplet's window shows, worked out without Qt (droplet_agent/app/model.py)."""

import time

from droplet_agent import app
from droplet_agent.app import model
from droplet_agent.app.demo import LINUX_FP, PHONE_FP, WIN_FP, DemoAgent


def test_device_states_and_their_words():
    phone = {"fp": "a", "name": "phone", "link": "lan 192.168.1.5", "on_lan": True}
    tail = {"fp": "b", "name": "laptop", "link": "tailnet 100.64.1.2", "on_lan": False}
    near = {"fp": "c", "name": "pc", "link": None, "on_lan": True}
    away = {"fp": "d", "name": "Zed", "link": None, "on_lan": False}
    hub = {"fp": "e", "name": "Ann", "link": None, "on_lan": False, "hub": "ab" * 8}
    assert [model.device_state(p) for p in (phone, tail, near, away)] == [
        model.CONNECTED, model.CONNECTED, model.NEARBY, model.AWAY]
    assert model.state_text(phone) == "Connected on this network"
    assert model.state_text(tail) == "Connected via Tailscale"
    assert model.state_text(near) == "On this network"
    assert model.state_text(away) == "Not reachable: what you send waits for it"
    assert "through the hub" in model.state_text(hub)
    # connected first, then nearby, then the rest; each by name
    status = {"peers": [away, near, hub, tail, phone, {"name": "no fingerprint"}]}
    assert [p["name"] for p in model.peers(status)] == ["laptop", "phone", "pc", "Ann", "Zed"]
    assert model.summary(status) == "5 devices · 2 connected"
    assert model.summary({"peers": []}) == "No paired devices yet"
    assert model.summary(None) == "The agent isn't running"


def test_codes_and_fingerprints_read_easily():
    assert model.spaced_code("4817") == "4 8 1 7"
    fp = "ab12" * 16
    assert model.grouped_fp(fp) == " ".join(["ab12"] * 16)
    assert model.grouped_fp(fp, per_line=8).splitlines() == [" ".join(["ab12"] * 8)] * 2
    assert model.os_label("android") == "Android" and model.os_label(None) == ""
    assert model.is_phone("android") and not model.is_phone("windows")


def test_a_transfer_says_how_each_file_went():
    t = model.Transfer("fp", "phone")
    t.add("a.jpg", {"id": "j1", "state": "queued", "attempts": 0})
    t.add("b.pdf", {"id": "j2", "state": "queued", "attempts": 0})
    assert t.text() == "Sending 2 files…" and t.tone() == "busy" and set(t.pending()) == {"j1", "j2"}
    t.update({"id": "j1", "state": "done", "route": "lan"})
    t.update({"id": "j2", "state": "sending"})
    assert t.text() == "Sending b.pdf… Sent a.jpg." and not t.finished()
    t.update({"id": "j2", "state": "done", "route": "lan"})
    assert t.text() == "Sent 2 files directly, over the network." and t.tone() == "ok" and t.finished()

    t = model.Transfer("fp", "laptop")
    t.add("big.iso", {"id": "j3", "state": "queued", "attempts": 0})
    t.add("gone.txt", {"error": "/home/x/gone.txt isn't a file"})
    t.update({"id": "j3", "state": "queued", "attempts": 1, "why": "not reachable"})
    assert t.finished()
    assert t.text() == ("big.iso waits until laptop can be reached. "
                        "Couldn't send gone.txt: /home/x/gone.txt isn't a file")
    assert t.tone() == "bad"
    t2 = model.Transfer("fp", "laptop")
    t2.add("x", {"id": "j4"})
    t2.update({"id": "j4", "state": "failed", "why": "the peer refused it"})
    assert t2.text() == "Couldn't send x: the peer refused it"


def test_conversations_and_message_notes():
    demo = DemoAgent(now=1_000_000)
    status = demo.call({"cmd": "status"})
    msgs = demo.call({"cmd": "chat"})["messages"]
    rows = model.conversations(msgs, status)
    # newest conversation first, then devices with none
    assert [r["peer"]["fp"] for r in rows] == [LINUX_FP, PHONE_FP, WIN_FP]
    assert model.preview(rows[0]["last"]) == "You: Backup finished?"
    assert model.preview(None) == "No messages yet"
    assert model.preview({"dir": "in", "body": "x" * 100}, 10) == "x" * 9 + "…"
    assert [m["body"] for m in model.for_peer(msgs, WIN_FP)] == ["The printer is fixed"]
    waiting = [m for m in msgs if m["state"] == "queued"][0]
    assert model.message_note(waiting).endswith("· waiting to send")
    failed = dict(waiting, state="failed", why="not trusted")
    assert model.message_note(failed).endswith("· not sent: not trusted")


def test_when_text():
    now = time.mktime((2026, 10, 8, 15, 0, 0, 0, 0, -1))
    assert model.when_text(now - 3600, now) == "14:00"
    assert model.when_text(now - 86400 * 2, now).split()[0] in ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    assert model.when_text(now - 86400 * 30, now) == time.strftime("%-d %b %H:%M", time.localtime(now - 86400 * 30))
    assert model.when_text(None) == ""


def test_received_lines():
    assert model.size_text(512) == "512 bytes"
    assert model.size_text(211_455) == "206.5 KB"
    assert model.size_text(1_048_120) == "1 MB"
    assert model.size_text(3_481_120) == "3.3 MB"
    assert model.size_text(None) == ""
    now = time.time()
    f = {"from": "phone", "ts": now, "size": 2048, "exists": True}
    assert model.received_line(f, now) == f"From phone · {time.strftime('%H:%M', time.localtime(now))} · 2 KB"
    assert model.received_line(dict(f, exists=False, size=None), now).endswith("moved or deleted")


def test_pairing_flow_from_this_side():
    demo = DemoAgent(accept_after=2)
    f = model.PairFlow()
    assert f.state == "pick" and f.start("  ") is None
    req = f.start(demo.nearby[0]["fp"], "pixel-tablet")
    assert req == {"cmd": "pair-start", "target": demo.nearby[0]["fp"]}
    assert f.state == "asking" and f.headline() == "Asking pixel-tablet…"
    assert f.start("other") is None            # one at a time
    f.on_started(demo.call(req))
    assert f.state == "code" and f.code == "2604" and f.detail() == "Does pixel-tablet show the same code?"
    assert f.poll() is None                    # nothing to wait for until the codes match
    req = f.confirm(True)
    assert req["cmd"] == "pair-confirm" and req["yes"] and f.state == "waiting"
    f.on_confirmed(demo.call(req))
    assert f.confirm(True) is None
    f.on_status(demo.call(f.poll()))
    assert f.state == "waiting"
    f.on_status(None, "the agent didn't answer")   # a missed poll changes nothing
    f.on_status(demo.call(f.poll()))
    assert f.state == "paired" and f.headline() == "Paired with pixel-tablet"
    assert f.poll() is None
    # it's a paired device now
    assert any(p["name"] == "pixel-tablet" for p in demo.call({"cmd": "status"})["peers"])


def test_pairing_flow_ends():
    f = model.PairFlow()
    f.start("10.0.0.9")
    f.on_started({"error": "couldn't reach 10.0.0.9:1739: refused"})
    assert f.state == "error" and "couldn't reach" in f.detail()
    assert f.start("10.0.0.9") is not None     # try again from the end
    f.on_started({"request": "r", "code": "1111", "peer": {"name": "box", "fp": "ab" * 32}})
    assert f.confirm(False) == {"cmd": "pair-confirm", "request": "r", "yes": False}
    assert f.state == "cancelled"
    for answer, end in (("denied", "declined"), ("expired", "expired"), ("cancelled", "cancelled")):
        f = model.PairFlow()
        f.start("box")
        f.on_started({"request": "r", "code": "1", "peer": {"name": "box"}})
        f.confirm(True)
        f.on_status({"state": answer})
        assert f.state == end and f.headline()


def test_the_demo_answers_like_the_agent():
    demo = DemoAgent()
    assert {p["name"] for p in demo.call({"cmd": "status"})["peers"]} == {
        "redmi-note-11e-pro", "maryanne", "sheffield"}
    job = demo.call({"cmd": "send-file", "peer": PHONE_FP, "path": "/tmp/x.jpg", "wait": 0})
    assert job["state"] == "queued" and job["name"] == "x.jpg"
    demo.call({"cmd": "job", "id": job["id"]})
    assert demo.call({"cmd": "job", "id": job["id"]})["state"] == "done"
    assert "error" in demo.call({"cmd": "ring", "peer": LINUX_FP})
    assert "error" in demo.call({"cmd": "nope"})
    import pytest
    from droplet_agent.mesh import control
    demo.running = False
    with pytest.raises(control.NotRunning):
        demo.call({"cmd": "status"})


def test_app_is_optional():
    assert isinstance(app.available(), bool)
    assert "droplet-agent[app]" in app.missing_text()
