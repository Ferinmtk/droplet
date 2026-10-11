"""Droplet's window: transfers with progress and Cancel, several devices at once, links with an
Open button, renaming this computer and nicknames. Offscreen, against the demo agent; skipped
without PySide6. With $SHOTS set, it saves screenshots there."""

import os
from pathlib import Path

import pytest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
pytest.importorskip("PySide6.QtWidgets")

from PySide6.QtWidgets import QApplication, QLabel, QProgressBar  # noqa: E402

from droplet_agent.app import main as appmain, model  # noqa: E402
from droplet_agent.app.demo import LINUX_FP, PHONE_FP, WIN_FP, DemoAgent  # noqa: E402
from droplet_agent.app.window import Window  # noqa: E402

SHOTS = os.environ.get("SHOTS")


@pytest.fixture(scope="module")
def qapp():
    return QApplication.instance() or appmain.make_app(["droplet-test"])


@pytest.fixture
def demo():
    return DemoAgent(folder="/tmp/droplet-test-received", incoming=False, transfers=True)


@pytest.fixture
def win(qapp, demo):
    w = Window(demo.call, sync=True, restart=lambda: (True, "Saved."))
    w.resize(960, 700)
    w.show()
    qapp.processEvents()
    yield w
    w.close()
    w.deleteLater()


def shot(win, qapp, name):
    if not SHOTS:
        return
    qapp.processEvents()
    Path(SHOTS).mkdir(parents=True, exist_ok=True)
    win.grab().save(str(Path(SHOTS) / f"{name}.png"))


class Answer:
    """Stands in for a dialog: exec() says yes (or no), with this value."""

    def __init__(self, value, yes=True):
        self.value, self.yes = value, yes

    def exec(self):
        return self.yes


def texts(widget):
    return [lb.text() for lb in widget.findChildren(QLabel) if not lb.isHidden()]


# --- the model -------------------------------------------------------------------------------

def test_the_words_for_names_transfers_and_results():
    p = {"name": "maryanne", "nickname": "Mary's laptop"}
    assert model.display_name(p) == "Mary's laptop" and model.own_name_note(p) == "Its own name: maryanne"
    assert model.display_name({"name": "x"}) == "x" and model.own_name_note({"name": "x"}) == ""
    t = {"dir": "out", "name": "a.mp4", "state": "active", "percent": 45, "eta": 12, "rate": 3_355_443,
         "size": 10_000_000, "done": 4_500_000}
    assert model.transfer_title(t) == "↑ a.mp4"
    assert model.transfer_detail(t) == "45% · 12 s left · 3.2 MB/s · 9.5 MB"
    assert model.transfer_detail(dict(t, state="cancelled", error="pixel cancelled it")) == \
        "Cancelled: pixel cancelled it"
    assert model.outcome({"route": "lan"}) == ("sent", "directly, over the network")
    assert model.outcome({"state": "queued", "why": "waiting: c is paused: resume it to send"}) == \
        ("waiting", "c is paused: resume it to send")
    assert model.outcome(None, "b doesn't allow the clipboard from you")[0] == "refused"
    assert model.outcomes_text("the clipboard", [("a", "sent", ""), ("b", "sent", "")]) == \
        "Sent the clipboard to a, b."
    assert model.outcomes_text("the link", [("a", "sent", ""), ("c", "waiting", "paused"), ("d", "refused", "no")]) \
        == "Sent the link to 1 of 3. c waits (paused); d: no."
    assert model.message_url({"body": "https://example.com/x"}) == "https://example.com/x"
    assert model.message_url({"body": "javascript:alert(1)"}) is None
    assert model.message_url({"body": "see https://example.com"}) is None


# --- transfers --------------------------------------------------------------------------------

def test_transfers_show_on_the_device_card_with_progress_and_cancel(win, demo, qapp):
    page = win.pages["devices"]
    card = page.cards[PHONE_FP]
    rows = list(card.rows.values())
    assert [r.title.text() for r in rows] == ["↑ Holiday video.mp4", "↓ IMG_20261010_101544.jpg"]
    assert rows[0].detail.text() == "45% · 14 s left · 7 MB/s · 176 MB"
    assert rows[0].bar.value() == 454 and not rows[0].cancel.isHidden()
    assert page.fast.isActive()                   # four times a second while one is going
    done = page.cards[WIN_FP].rows["c3" * 16]
    assert done.detail.text() == "Sent" and done.cancel.isHidden() and done.bar.isHidden()
    shot(win, qapp, "1-transfers")
    rows[0].cancel.click()
    assert {"cmd": "cancel", "id": "a1" * 16} in demo.calls
    assert win.statusBar().currentMessage() == "Cancelled Holiday video.mp4 to redmi-note-11e-pro."
    assert card.rows["a1" * 16].detail.text() == "Cancelled" and card.rows["a1" * 16].cancel.isHidden()
    # nothing going any more: no more fast polling
    rows[1].cancel.click()
    assert not page.fast.isActive()


# --- several devices at once ------------------------------------------------------------------

def test_select_several_devices_and_send_them_the_clipboard(win, demo, qapp):
    page = win.pages["devices"]
    assert page.bar.isHidden() and all(c.check.isHidden() for c in page.cards.values())
    page.select.click()
    assert not page.bar.isHidden() and all(not c.check.isHidden() for c in page.cards.values())
    assert page.picked.text() == "Pick devices below" and not page.m_clip.isEnabled()
    page.m_all.click()                    # your own devices: the phone and sheffield, not maryanne's
    assert {c.peer["fp"] for c in page.cards.values() if c.check.isChecked()} == {PHONE_FP, LINUX_FP}
    assert page.picked.text() == "2 devices selected"
    shot(win, qapp, "2-select")
    QApplication.clipboard().setText("the wifi password is on the fridge")
    page.m_clip.click()
    clips = [c for c in demo.calls if c["cmd"] == "clip"]
    assert {c["peer"] for c in clips} == {PHONE_FP, LINUX_FP}
    assert win.statusBar().currentMessage() == \
        "Sent the clipboard to 1 of 2. sheffield: sheffield isn't reachable directly, and not through the hub either."
    page.select.click()
    assert page.bar.isHidden() and not any(c.check.isChecked() for c in page.cards.values())


def test_a_message_and_a_link_to_several(win, demo, monkeypatch):
    page = win.pages["devices"]
    page.select.click()
    page.cards[PHONE_FP].check.setChecked(True)
    page.cards[WIN_FP].check.setChecked(True)
    import droplet_agent.app.dialogs as dialogs
    monkeypatch.setattr(dialogs, "message_dialog", lambda parent, names: Answer("see you at 6"))
    page.m_msg.click()
    sent = [c for c in demo.calls if c["cmd"] == "text"]
    assert {c["peer"] for c in sent} == {PHONE_FP, WIN_FP} and all(c["body"] == "see you at 6" for c in sent)
    assert win.statusBar().currentMessage() == "Sent the message to 1 of 2. maryanne waits (it can't be reached now)."
    page.send_link(page.selected(), dialog=Answer("https://example.com/slides"))
    links = demo.links
    assert {x["fp"] for x in links} == {PHONE_FP, WIN_FP}
    assert "Sent the link to 1 of 2. maryanne waits" in win.statusBar().currentMessage()


def test_the_link_dialog_checks_the_address(win, qapp):
    from droplet_agent.app.dialogs import link_dialog
    dlg = link_dialog(win, ["redmi"])
    dlg.field.setText("file:///etc/passwd")
    dlg._ok()
    assert not dlg.why.isHidden() and "Only web links" in dlg.why.text() and dlg.value is None
    dlg.field.setText("https://example.com")
    dlg._ok()
    assert dlg.value == "https://example.com" and dlg.result() == dlg.DialogCode.Accepted


# --- links in Messages ------------------------------------------------------------------------

def test_a_message_thats_a_link_has_an_open_button(win, demo, qapp, monkeypatch):
    import droplet_agent.app.messages as messages
    opened = []
    monkeypatch.setattr(messages.QDesktopServices, "openUrl", lambda url: opened.append(url.toString()) or True)
    demo.chat.append(dict(demo._msg("in", PHONE_FP, "https://example.com/menu", demo.now - 60), kind="link"))
    demo.chat.append(demo._msg("in", PHONE_FP, "javascript:alert(1)", demo.now - 50))
    win.go("messages", fp=PHONE_FP)
    page = win.pages["messages"]
    bubbles = [page.thread.itemAt(i).widget() for i in range(page.thread.count())]
    with_open = [b for b in bubbles if b.open is not None]
    assert [b.url for b in with_open] == ["https://example.com/menu"]
    shot(win, qapp, "3-link-in-messages")
    with_open[0].open.click()
    assert opened == ["https://example.com/menu"]


# --- names --------------------------------------------------------------------------------------

def test_rename_this_computer_from_settings(win, demo, qapp):
    win.go("settings")
    s = win.pages["settings"]
    from droplet_agent.app.dialogs import rename_dialog
    dlg = rename_dialog(win, "slim", False)
    dlg.show()
    dlg.field.setText("bad\x07name")
    dlg._ok()
    assert "control characters" in dlg.why.text()
    dlg.field.setText("x" * 41)
    dlg._ok()
    assert "40 characters" in dlg.why.text()
    dlg.field.setText("Ferrin's   laptop")
    if SHOTS:
        dlg.why.hide()
        qapp.processEvents()
        Path(SHOTS).mkdir(parents=True, exist_ok=True)
        dlg.grab().save(str(Path(SHOTS) / "4-rename-dialog.png"))
    dlg._ok()
    assert dlg.value == "Ferrin's laptop"
    dlg.close()
    s.rename(dialog=Answer(dlg.value))
    assert {"cmd": "rename", "name": "Ferrin's laptop"} in demo.calls
    assert s.name.text() == "Ferrin's laptop" and win.me.text() == "on Ferrin's laptop"
    assert win.statusBar().currentMessage().startswith("This computer is called Ferrin's laptop now. 1 connected")
    shot(win, qapp, "5-settings-renamed")


def test_the_trays_rename_opens_settings_and_asks(win, qapp, monkeypatch):
    asked = []
    monkeypatch.setattr(win.pages["settings"], "rename", lambda: asked.append(1))
    win.go("rename")
    qapp.processEvents()
    assert win.current_page() == "settings" and asked == [1]


def test_a_nickname_shows_everywhere_with_its_own_name_small(win, demo, qapp):
    page = win.pages["devices"]
    peer = page.cards[WIN_FP].peer
    from droplet_agent.app.dialogs import nickname_dialog
    dlg = nickname_dialog(win, peer)
    dlg.field.setText("Mary's laptop")
    if SHOTS:
        dlg.show()
        qapp.processEvents()
        dlg.grab().save(str(Path(SHOTS) / "6-nickname-dialog.png"))
    dlg._ok()
    dlg.close()
    page.nickname(peer, dialog=Answer(dlg.value))
    assert {"cmd": "nickname", "peer": WIN_FP, "nickname": "Mary's laptop"} in demo.calls
    card = page.cards[WIN_FP]
    assert card.name.text() == "Mary's laptop" and card.own_name.text() == "(maryanne)"
    assert win.statusBar().currentMessage() == "maryanne is called Mary's laptop on this computer."
    shot(win, qapp, "7-nickname")
    win.go("messages", fp=WIN_FP)
    m = win.pages["messages"]
    assert m.who.text() == "Mary's laptop" and "Its own name: maryanne" in m.who_state.text()
    # empty: back to its own name
    page.nickname(card.peer, dialog=Answer(""))
    assert page.cards[WIN_FP].name.text() == "maryanne" and page.cards[WIN_FP].own_name.isHidden()


def test_progress_bars_are_thin_and_rows_fit(win, qapp):
    win.resize(760, 520)
    qapp.processEvents()
    card = win.pages["devices"].cards[PHONE_FP]
    for row in card.rows.values():
        assert row.bar.height() <= 8
        assert row.cancel.isVisible() and row.cancel.geometry().right() <= card.width()
    assert all(isinstance(b, QProgressBar) for b in card.findChildren(QProgressBar))
    shot(win, qapp, "8-narrow")
