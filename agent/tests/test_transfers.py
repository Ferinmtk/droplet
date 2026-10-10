"""Progress and Cancel for files (docs/mesh.md §9.10): real byte counts both ways, a transfer
cancelled from either end (the partial file goes, the other side is told), and an older peer
that doesn't know `cancel`."""

import json
import os
import time

import pytest

from droplet_agent.mesh import files, transfers as tx
from droplet_agent.mesh.outbox import CANCELLED, DONE
from droplet_agent.mesh.transfers import Transfers, eta_text, progress_text

from test_mesh import nodes, trust_each_other, wait_for  # noqa: F401  (nodes is a fixture)


class Clock:
    def __init__(self):
        self.t = 100.0

    def __call__(self):
        return self.t


def test_the_list_works_out_speed_and_time_left_from_byte_counts():
    c = Clock()
    t = Transfers(clock=c)
    t.start("a" * 32, direction="out", fp="f" * 64, peer="pixel", name="video.mp4", size=10_000_000)
    assert t.get("a" * 32)["percent"] == 0 and t.get("a" * 32)["rate"] is None
    for i in range(1, 5):
        c.t += 1
        t.progress("a" * 32, i * 1_000_000)
    v = t.get("a" * 32)
    assert v["percent"] == 40 and v["rate"] == 1_000_000 and v["eta"] == 6
    assert progress_text(v) == "40% · 6 s left · 976.6 KB/s"
    c.t += 10                     # nothing for a while: stalled, not "6 s left" forever
    assert t.get("a" * 32)["rate"] is None and t.get("a" * 32)["eta"] is None
    t.finish("a" * 32, tx.CANCELLED, "pixel cancelled it")
    assert progress_text(t.get("a" * 32)) == "Cancelled: pixel cancelled it"
    c.t += tx.KEEP + 1            # finished ones go after a while
    assert t.list() == []
    assert eta_text(5) == "5 s left" and eta_text(125) == "2 min left" and eta_text(3725) == "1 h 2 min left"


def slow(a, rate=2 * 1024 * 1024):
    a.offers.max_rate = rate


def big(tmp_path, mb=4):
    src = tmp_path / "big.bin"
    src.write_bytes(os.urandom(mb * 1024 * 1024))
    return src


def test_progress_comes_from_real_byte_counts_on_both_sides(nodes, tmp_path):
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b)
    slow(a)
    src = big(tmp_path)
    job = a.send_file(b.identity.fp, src)
    seen_a, seen_b = [], []

    def sample():
        ta, tb = a.transfers.get(job["id"]), b.transfers.get(job["id"])
        if ta:
            seen_a.append(ta)
        if tb:
            seen_b.append(tb)
        return tb and tb["state"] == tx.DONE
    assert wait_for(sample, 20)
    assert a.outbox.wait(job["id"], lambda j: j["state"] == DONE, 10)["state"] == DONE
    mid_a = [t for t in seen_a if 0 < t["done"] < t["size"]]
    mid_b = [t for t in seen_b if 0 < t["done"] < t["size"]]
    assert mid_a and mid_b, "progress part-way, on each side"
    assert all(t["dir"] == "out" and t["peer"] == "b" and t["name"] == "big.bin" for t in seen_a)
    assert all(t["dir"] == "in" and t["peer"] == "a" for t in seen_b)
    assert any(t["rate"] for t in mid_a + mid_b)
    assert [t["done"] for t in seen_b] == sorted(t["done"] for t in seen_b)
    assert a.transfers.get(job["id"])["state"] == tx.DONE
    # the agent's status and the control socket carry it, for the window, the tray and the CLI
    assert any(t["id"] == job["id"] for t in a.status()["transfers"])
    assert a.handle_control({"cmd": "transfers"})["transfers"][0]["id"] == job["id"]


def _no_partial(node):
    return not list(node.downloads.glob(".droplet-*"))


def test_the_sender_cancels_and_the_receiver_drops_the_partial_file(nodes, tmp_path):
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b)
    slow(a, 1024 * 1024)
    src = big(tmp_path)
    job = a.send_file(b.identity.fp, src)
    assert wait_for(lambda: (b.transfers.get(job["id"]) or {}).get("done", 0) > 300_000, 10)
    out = a.handle_control({"cmd": "cancel", "id": job["id"][:8]})
    assert out["dir"] == "out" and out["name"] == "big.bin"
    got = a.outbox.wait(job["id"], lambda j: j["state"] == CANCELLED, 5)
    assert got["state"] == CANCELLED and got["error"] == "cancelled here"
    assert wait_for(lambda: b.transfers.get(job["id"])["state"] == tx.CANCELLED, 5)
    assert b.transfers.get(job["id"])["error"] == "a cancelled it"
    assert wait_for(lambda: _no_partial(b), 5) and not (b.downloads / "big.bin").exists()
    assert a.transfers.get(job["id"])["state"] == tx.CANCELLED
    # and it's gone for good: nothing is offered again
    time.sleep(1)
    assert not a.outbox.queued() and not (b.downloads / "big.bin").exists()


def test_the_receiver_cancels_and_the_sender_is_told(nodes, tmp_path):
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b)
    slow(a, 1024 * 1024)
    src = big(tmp_path)
    job = a.send_file(b.identity.fp, src)
    assert wait_for(lambda: (b.transfers.get(job["id"]) or {}).get("done", 0) > 300_000, 10)
    assert b.handle_control({"cmd": "cancel", "id": job["id"]})["dir"] == "in"
    assert wait_for(lambda: b.transfers.get(job["id"])["state"] == tx.CANCELLED, 5)
    assert wait_for(lambda: _no_partial(b), 5)
    got = a.outbox.wait(job["id"], lambda j: j["state"] == CANCELLED, 5)
    assert got["state"] == CANCELLED and got["error"] == "b cancelled it"
    assert a.transfers.get(job["id"])["state"] == tx.CANCELLED
    assert not (b.downloads / "big.bin").exists()


def test_an_older_sender_that_offers_it_again_is_refused(nodes, tmp_path):
    """A sender that ignores `cancel` stalls and offers the same id again: it's nacked."""
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b)
    slow(a, 512 * 1024)
    src = big(tmp_path, 2)
    job = a.send_file(b.identity.fp, src)
    assert wait_for(lambda: (b.transfers.get(job["id"]) or {}).get("done", 0) > 100_000, 10)
    b.cancel(job["id"])
    assert wait_for(lambda: b.transfers.get(job["id"])["state"] == tx.CANCELLED, 5)
    replies = []
    for links in b.links.values():
        for lk in links:
            orig = lk.send
            lk.send = lambda m, orig=orig: replies.append(m) or orig(m)
    a.direct(b.identity.fp).send({"t": "offer", "id": job["id"], "name": "big.bin", "size": src.stat().st_size,
                                  "mime": "application/octet-stream"})
    assert wait_for(lambda: any(m.get("t") == "nack" and m.get("id") == job["id"] for m in replies), 5)
    nack = next(m for m in replies if m.get("t") == "nack")
    assert nack["why"] == "cancelled" and nack["error"] == "b cancelled it"
    assert not (b.downloads / "big.bin").exists()


def test_a_job_waiting_in_the_outbox_can_be_cancelled(nodes, tmp_path):
    a, b = nodes("a"), nodes("b")
    trust_each_other(a, b)
    a.set_perms(b.identity.fp, paused=True)
    src = tmp_path / "later.txt"
    src.write_text("later")
    job = a.send_file(b.identity.fp, src)
    a.outbox.wait(job["id"], lambda j: j["attempts"] > 0, 5)
    assert a.cancel(job["id"])["dir"] == "out"
    assert a.outbox.get(job["id"])["state"] == CANCELLED
    assert a._job_answer(job["id"], 0)["state"] == "cancelled"
    a.set_perms(b.identity.fp, paused=False)
    time.sleep(0.8)
    assert not (b.downloads / "later.txt").exists()
    with pytest.raises(ValueError, match="no transfer"):
        a.cancel("ffffffffffff")
    assert a.handle_control({"cmd": "cancel", "id": ""})["error"].startswith("say which")


def test_cancel_is_taken_even_while_paused_and_only_for_its_own_peer(nodes, tmp_path):
    from droplet_agent.mesh import perms
    assert perms.check({"paused": True}, {"t": "cancel"}) is None
    assert perms.check({"paused": True}, {"t": "rename"}) is None
    a, b, c = nodes("a"), nodes("b"), nodes("c")
    trust_each_other(a, b)
    trust_each_other(a, c)
    a.set_perms(b.identity.fp, paused=True)
    src = tmp_path / "x.txt"
    src.write_text("x")
    job = a.send_file(b.identity.fp, src)
    a.outbox.wait(job["id"], lambda j: j["attempts"] > 0, 5)
    # c can't cancel what a sends b
    c.direct(a.identity.fp).send({"t": "cancel", "id": job["id"]})
    time.sleep(0.3)
    assert a.outbox.get(job["id"])["state"] != CANCELLED
    b.direct(a.identity.fp).send({"t": "cancel", "id": job["id"]})
    assert a.outbox.wait(job["id"], lambda j: j["state"] == CANCELLED, 5)["error"] == "b cancelled it"


def test_download_deletes_the_partial_file_when_cancelled(tmp_path, nodes):
    a = nodes("a")
    part, meta = files.part_paths(tmp_path, "f" * 64, "1" * 32)
    part.write_bytes(b"x" * 10)
    meta.write_text(json.dumps({"size": 100, "name": "n", "fp": "f" * 64}))
    with pytest.raises(files.Cancelled):
        files.download(a.identity, "f" * 64, [("127.0.0.1", 1)], "1" * 32, "n", 100, tmp_path, cancelled=lambda: True)
    assert not part.exists() and not meta.exists()


# --- the iPhone ------------------------------------------------------------------------

from test_webrtc import file_frames, iphone  # noqa: E402,F401


def _as_browser(node, msg):
    conn = next(iter(node.webrtc.conns))
    node.webrtc.listener.call(conn.message, msg if isinstance(msg, bytes) else json.dumps(msg))


def test_a_file_from_the_iphone_shows_progress_and_can_be_cancelled_here(iphone):
    a, node, fp, ch, send, written = iphone()
    fid = "4" * 32
    data = b"y" * 50_000
    _as_browser(node, {"t": "file", "id": fid, "name": "clip.mov", "size": len(data)})
    for f in file_frames(fid, data[:20_000], 5_000):
        _as_browser(node, f)
    assert wait_for(lambda: (node.transfers.get(fid) or {}).get("done") == 20_000)
    t = node.transfers.get(fid)
    assert t["dir"] == "in" and t["peer"] == "Ann's iPhone" and t["route"] == "webrtc" and t["percent"] == 40
    assert node.handle_control({"cmd": "cancel", "id": fid})["dir"] == "in"
    assert wait_for(lambda: {"t": "cancel", "id": fid} in ch.json())
    assert node.transfers.get(fid)["state"] == tx.CANCELLED
    assert not list(node.downloads.glob(".droplet-*"))
    for f in file_frames(fid, data[20_000:], 5_000):    # what was on its way is dropped
        _as_browser(node, f)
    _as_browser(node, {"t": "file-end", "id": fid})
    time.sleep(0.3)
    assert not (node.downloads / "clip.mov").exists() and not any(m.get("t") == "ack" and m.get("id") == fid
                                                                    for m in ch.json())


def test_the_iphone_cancels_a_file_it_was_sending(iphone):
    a, node, fp, ch, send, written = iphone()
    fid = "5" * 32
    _as_browser(node, {"t": "file", "id": fid, "name": "big.mov", "size": 1000})
    for f in file_frames(fid, b"z" * 300, 100):
        _as_browser(node, f)
    send({"t": "cancel", "id": fid})
    assert wait_for(lambda: (node.transfers.get(fid) or {}).get("state") == tx.CANCELLED)
    assert not list(node.downloads.glob(".droplet-*"))
    assert not any(m.get("id") == fid for m in ch.json())      # no nack: it knows


def test_a_file_to_the_iphone_cancelled_here_or_there(iphone, tmp_path):
    a, node, fp, ch, send, written = iphone()
    src = tmp_path / "f.bin"
    src.write_bytes(b"q" * 400_000)
    # the channel never drains, so the file stays part-way until it's cancelled
    conn = next(iter(node.webrtc.conns))
    conn.channel.bufferedAmount = 10 ** 9
    job = node.send_file(fp, src)
    assert wait_for(lambda: any(m["t"] == "file" for m in ch.json()))
    assert wait_for(lambda: (node.transfers.get(job["id"]) or {}).get("state") == "active")
    node.cancel(job["id"])
    got = node.outbox.wait(job["id"], lambda j: j["state"] == CANCELLED, 5)
    assert got["state"] == CANCELLED
    assert wait_for(lambda: {"t": "cancel", "id": job["id"]} in ch.json())
    # and the iPhone cancelling one it's being sent
    job2 = node.send_file(fp, src)
    assert wait_for(lambda: [m for m in ch.json() if m["t"] == "file"][-1]["id"] == job2["id"])
    send({"t": "cancel", "id": job2["id"]})
    got = node.outbox.wait(job2["id"], lambda j: j["state"] == CANCELLED, 5)
    assert got["state"] == CANCELLED and got["error"] == "Ann's iPhone cancelled it"
