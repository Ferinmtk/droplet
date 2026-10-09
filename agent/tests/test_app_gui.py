"""Droplet's window, offscreen, against the demo agent. Skipped without PySide6."""

import os
import time
from pathlib import Path

import pytest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
pytest.importorskip("PySide6.QtWidgets")

from PySide6.QtWidgets import QApplication, QLabel  # noqa: E402

from droplet_agent import config  # noqa: E402
from droplet_agent.app import main as appmain  # noqa: E402
from droplet_agent.app.agent import Agent  # noqa: E402
from droplet_agent.app.demo import ASKING_FP, LINUX_FP, PHONE_FP, DemoAgent  # noqa: E402
from droplet_agent.app.window import Window  # noqa: E402


@pytest.fixture(scope="module")
def qapp():
    return QApplication.instance() or appmain.make_app(["droplet-test"])


@pytest.fixture
def demo():
    return DemoAgent(folder="/tmp/droplet-test-received")


@pytest.fixture
def win(qapp, demo):
    restarts = []
    w = Window(demo.call, sync=True, restart=lambda: restarts.append(1) or (True, "Saved. Restarted."))
    w.restarts = restarts
    w.resize(900, 600)
    yield w
    w.close()
    w.deleteLater()


def texts(widget) -> list[str]:
    return [lb.text() for lb in widget.findChildren(QLabel) if not lb.isHidden()]


def pump(qapp, until, timeout=5):
    end = time.time() + timeout
    while time.time() < end:
        qapp.processEvents()
        if until():
            return True
        time.sleep(0.01)
    return False


def test_the_window_shows_the_devices_and_who_asks_to_pair(win):
    assert win.windowTitle() == "Droplet"
    assert win.outer.currentIndex() == 0 and win.current_page() == "devices"
    cards = win.pages["devices"].cards
    assert [c.name.text() for c in cards.values()] == ["redmi-note-11e-pro", "maryanne", "sheffield"]
    assert cards[PHONE_FP].state.text() == "Connected on this network · Android"
    assert cards[LINUX_FP].state.text().startswith("Not reachable")
    assert win.summary.text() == "3 devices · 1 connected"
    assert win.me.text() == "on slim"
    # the pairing request, with its code, above every page
    assert win.banners.count() == 1
    banner = win.banners.itemAt(0).widget()
    assert "anna-phone wants to pair (Android)" in texts(banner) and "4 8 1 7" in texts(banner)


def test_accepting_a_pairing_request(win, demo):
    banner = win.banners.itemAt(0).widget()
    win.answer(banner.r, True)
    assert not demo.incoming and any(p["fp"] == ASKING_FP for p in demo.peers)
    assert win.banners.count() == 0
    assert ASKING_FP in win.pages["devices"].cards
    assert "Paired with anna-phone" in win.statusBar().currentMessage()


def test_the_agent_not_running_and_coming_back(win, demo):
    demo.running = False
    win.refresh()
    assert win.outer.currentIndex() == 1
    assert "The droplet agent isn't running" in texts(win.not_running)
    demo.running = True
    win.refresh()
    assert win.outer.currentIndex() == 0


def test_sending_files_shows_how_it_went(win, demo, tmp_path):
    f = tmp_path / "photo.jpg"
    f.write_bytes(b"x")
    page = win.pages["devices"]
    phone = next(p for p in demo.peers if p["fp"] == PHONE_FP)
    page.send_files(phone, [f, tmp_path])        # a folder is left out
    card = page.cards[PHONE_FP]
    assert card.transfer.text() == "Sending photo.jpg…"
    page._poll_jobs()
    page._poll_jobs()
    assert card.transfer.text() == "Sent photo.jpg directly, over the network."
    assert demo.calls[-1]["cmd"] == "job"
    sent = [c for c in demo.calls if c["cmd"] == "send-file"]
    assert sent == [{"cmd": "send-file", "peer": PHONE_FP, "path": str(f.resolve()), "wait": 0}]
    # a device that can't be reached: it waits
    away = next(p for p in demo.peers if p["fp"] == LINUX_FP)
    page.send_files(away, [f])
    page._poll_jobs()
    page._poll_jobs()
    assert page.cards[LINUX_FP].transfer.text() == "photo.jpg waits until sheffield can be reached."


def test_ring_and_clipboard_report_back(win, demo, qapp):
    page = win.pages["devices"]
    phone = next(p for p in demo.peers if p["fp"] == PHONE_FP)
    page.ring(phone)
    assert win.statusBar().currentMessage() == "Ringing redmi-note-11e-pro, directly, over the network."
    away = next(p for p in demo.peers if p["fp"] == LINUX_FP)
    page.ring(away)
    assert win.statusBar().currentMessage().startswith("Couldn't ring sheffield")
    qapp.clipboard().setText("hello from the test")
    page.send_clipboard(phone)
    assert demo.calls[-1] == {"cmd": "clip", "peer": PHONE_FP, "text": "hello from the test"}


def test_pairing_from_the_pair_page(win, demo):
    win.go("pair")
    page = win.pages["pair"]
    assert page.rows.count() == 2 and page.none_nearby.isHidden()
    page.start(demo.nearby[0]["fp"], "pixel-tablet")
    assert page.stack.currentIndex() == 1
    assert page.code.text() == "2 6 0 4" and page.headline.text() == "Pairing with pixel-tablet"
    assert not page.yes.isHidden() and page.done.isHidden()
    page.confirm(True)
    assert page.headline.text() == "Waiting for pixel-tablet to accept" and page.timer.isActive()
    page._poll()
    page._poll()
    assert page.headline.text() == "Paired with pixel-tablet" and not page.done.isHidden()
    assert not page.timer.isActive()
    page._finish()
    assert win.current_page() == "devices" and page.flow.state == "pick"
    assert any(c.name.text() == "pixel-tablet" for c in win.pages["devices"].cards.values())


def test_pairing_an_iphone_shows_a_qr_code(win, demo):
    win.go("pair")
    page = win.pages["pair"]
    page.iphone.click()
    assert page.stack.currentIndex() == 2
    try:
        import segno  # noqa: F401
    except ImportError:
        assert "pip install segno" in page.qr_text.text()
    else:
        assert len(page.qr.matrix) >= 21 and "192.168.1.20" in page.qr_text.text()
    page.reset()
    assert page.stack.currentIndex() == 0


def test_pairing_by_address_that_fails(win, demo):
    page = win.pages["pair"]
    page.address.setText("nowhere")
    page.start(page.address.text())
    assert page.flow.state == "error" and "isn't announcing" not in page.headline.text()
    assert "announcing itself" in page.detail.text()
    page.reset()
    assert page.stack.currentIndex() == 0 and page.address.text() == ""


def test_messages(win, demo):
    win.go("messages", fp=PHONE_FP)
    page = win.pages["messages"]
    assert page.who.text() == "redmi-note-11e-pro"
    assert page.thread.count() == 5
    assert page.people.currentItem().data(0x0100) == PHONE_FP
    page.composer.setPlainText("On my way")
    page.send()
    assert demo.chat[-1]["body"] == "On my way" and demo.chat[-1]["fp"] == PHONE_FP
    assert page.composer.toPlainText() == "" and page.thread.count() == 6
    # to a device that can't be reached: sent later
    win.go("messages", fp=LINUX_FP)
    page.composer.setPlainText("hello?")
    page.send()
    assert "waits" in win.statusBar().currentMessage()


def test_received(win, demo):
    win.go("received")
    page = win.pages["received"]
    assert page.rows.count() == 4
    assert page.where.text() == "Saved in /tmp/droplet-test-received"
    gone = page.rows.itemAt(3).widget()
    assert "moved or deleted" in " ".join(texts(gone))


def test_settings_save_to_config_and_restart_the_agent(win):
    win.go("settings")
    page = win.pages["settings"]
    assert page.name.text() == "slim" and "9be1 9be1" in page.fp.text()
    assert not page.apply.isEnabled()
    page.boxes[("caps", "clipboard")].setChecked(False)
    page.boxes[("mesh", "phone_notifications")].setChecked(False)
    assert page.apply.isEnabled()
    page.save()
    cfg = config.load()
    assert cfg["caps"]["clipboard"] is False and cfg["mesh"]["phone_notifications"] is False
    assert cfg["caps"]["input"] is True
    assert win.restarts == [1] and not page.apply.isEnabled()
    assert win.statusBar().currentMessage() == "Saved. Restarted."


def test_the_worker_answers_on_the_gui_thread(qapp):
    import threading
    seen = []
    agent = Agent(lambda req, timeout: {"echo": req["cmd"], "thread": threading.current_thread().name})
    agent.ask({"cmd": "status"}, lambda r: seen.append((r, threading.current_thread() is threading.main_thread())))
    assert pump(qapp, lambda: seen)
    reply, on_gui = seen[0]
    assert reply.ok and reply.data["echo"] == "status" and on_gui
    assert reply.data["thread"] != threading.main_thread().name

    from droplet_agent.mesh import control

    def down(req, timeout):
        raise control.NotRunning("no")
    agent = Agent(down)
    agent.ask({"cmd": "status"}, seen.append)
    assert pump(qapp, lambda: len(seen) == 2)
    assert seen[1].not_running and not seen[1].ok


def test_a_second_launch_brings_up_the_first(qapp, tmp_path):
    import tempfile
    # a Mac's temp dir is too deep for a Unix socket's 104 bytes (the real one is in ~/.config)
    short = Path(tempfile.mkdtemp(dir="/tmp")) if len(str(tmp_path)) > 70 else tmp_path
    name = str(short / "app.sock")
    got = []
    server = appmain.listen(name, got.append)
    try:
        assert appmain.hand_over(name, {"cmd": "raise", "page": "messages", "token": "t"})
        assert pump(qapp, lambda: got)
        assert got[0] == {"cmd": "raise", "page": "messages", "token": "t"}
    finally:
        server.close()
    assert not appmain.hand_over(str(short / "nobody.sock"), {"cmd": "raise"})


def test_the_window_has_the_drop_icon(qapp):
    from droplet_agent.app.widgets import app_icon
    assert not app_icon().isNull()
    assert QApplication.desktopFileName() == "io.github.ferinmtk.Droplet"
    assert Path(appmain.server_name()).name in ("app.sock",) or appmain.server_name().startswith("droplet-app-")
