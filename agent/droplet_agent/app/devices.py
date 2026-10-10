"""Devices: each paired device as a card, with what you can do with it."""

from __future__ import annotations

import sys
from pathlib import Path

from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtGui import QGuiApplication
from PySide6.QtWidgets import (QFileDialog, QMenu, QMessageBox, QScrollArea, QToolButton, QVBoxLayout,
                               QWidget)

from . import model
from .widgets import (GOOD, Card, button, device_icon, dot, hbox, icon, icon_label, label, primary, text_color,
                      title, vbox)

TONES = {"busy": None, "ok": "good", "warn": "warn", "bad": "bad"}


def clipboard_secret(mime) -> bool:
    """A password manager marked the clipboard secret: never send it (as clip.py's sync doesn't)."""
    from ..clip import PASSWORD_HINT
    if mime is not None and mime.hasFormat(PASSWORD_HINT) and bytes(mime.data(PASSWORD_HINT)).strip() == b"secret":
        return True
    if sys.platform == "darwin":
        from .. import macos
        return macos.pasteboard_concealed()
    return False


class DeviceCard(Card):
    """One paired device. Drop files on it to send them."""

    files_dropped = Signal(list)

    def __init__(self, page: "DevicesPage", peer: dict):
        super().__init__()
        self.page = page
        self.peer = peer
        self.setAcceptDrops(True)

        self.icon = icon_label(device_icon(peer.get("os"), model.is_phone(peer.get("os"))), 40)
        self.name = label(size=2.5, bold=True)
        self.state_dot = label()
        self.state = label(muted=True)
        self.transfer = label(wrap=True)
        self.transfer.hide()
        self.other_badge = label("Someone else's")
        self.other_badge.setObjectName("badge")
        self.other_badge.setToolTip("Paired as someone else's device: files, messages and ring only, unless "
                                    "you changed its Permissions.")
        self.paused_badge = label("Paused")
        self.paused_badge.setObjectName("badge")
        self.paused_badge.setProperty("tone", "paused")
        self.perms_line = label(muted=True, wrap=True)

        more = QToolButton()
        more.setText("⋯")
        more.setIcon(icon("overflow-menu", "view-more-symbolic", "application-menu"))
        more.setToolTip("More")
        more.setAutoRaise(True)
        more.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        menu = QMenu(more)
        menu.addAction(icon("media-playback-stop"), "Stop ringing", lambda: page.ring(self.peer, stop=True))
        menu.addAction(icon("security-medium", "preferences-system-privacy", "dialog-password"), "Permissions…",
                       lambda: page.permissions(self.peer))
        menu.addAction(icon("help-about", "dialog-information"), "About this device",
                       lambda: page.about(self.peer))
        menu.addSeparator()
        menu.addAction(icon("list-remove", "edit-delete"), "Unpair…", lambda: page.unpair(self.peer))
        more.setMenu(menu)
        self.more = more

        self.b_files = button("Send files…", ("document-send", "document-open"))
        self.b_clip = button("Send clipboard", ("edit-paste",))
        self.b_ring = button("Ring", ("preferences-desktop-notification-bell", "audio-volume-high"))
        self.b_msg = button("Message", ("mail-message-new", "dialog-messages", "mail-send"))
        self.b_pause = button("Pause", ("media-playback-pause",))
        self.b_files.clicked.connect(lambda: page.pick_files(self.peer))
        self.b_clip.clicked.connect(lambda: page.send_clipboard(self.peer))
        self.b_ring.clicked.connect(lambda: page.ring(self.peer))
        self.b_msg.clicked.connect(lambda: page.message(self.peer))
        self.b_pause.clicked.connect(lambda: page.pause(self.peer, not model.is_paused(self.peer)))

        head = vbox(hbox(self.name, self.other_badge, self.paused_badge, None, self.b_pause, self.more, spacing=8),
                    hbox(self.state_dot, self.state, None, spacing=6), spacing=2)
        actions = hbox(self.b_files, self.b_clip, self.b_ring, self.b_msg, None, spacing=6)
        lay = hbox(vbox(self.icon, None), vbox(head, actions, self.perms_line, self.transfer, spacing=10),
                   spacing=14, margins=(16, 14, 12, 14))
        self.setLayout(lay)
        self.files_dropped.connect(lambda paths: page.send_files(self.peer, paths))
        self.update_peer(peer)

    def update_peer(self, peer: dict):
        self.peer = peer
        self.name.setText(str(peer.get("name") or peer.get("id")))
        st = model.device_state(peer)
        color = {model.CONNECTED: GOOD, model.NEARBY: "#38BDF8", model.AWAY: "#9CA3AF"}[st]
        self.state_dot.setPixmap(dot(color))
        osl = model.os_label(peer.get("os"))
        self.state.setText(model.state_text(peer) + (f" · {osl}" if osl else ""))
        paused, paused_all = model.is_paused(peer), self.page.paused_all
        if paused or model.paused_by_it(peer) or paused_all:
            self.state_dot.setPixmap(dot("#F59E0B"))
        self.other_badge.setVisible(model.is_other(peer))
        self.paused_badge.setVisible(paused)
        self.b_pause.setText("Resume" if paused else "Pause")
        self.b_pause.setIcon(icon("media-playback-start" if paused else "media-playback-pause"))
        self.b_pause.setToolTip(f"Share with {self.name.text()} again" if paused else
                                f"Stop sharing anything with {self.name.text()} until you resume")
        for b, cap in ((self.b_files, "files"), (self.b_clip, "clipboard"), (self.b_ring, "ring"),
                       (self.b_msg, "chat")):
            # paused: files and messages can still be written; they wait for the resume
            ok = model.allows(peer, cap) if cap in ("files", "chat") else model.can_send(peer, cap, paused_all)
            b.setEnabled(ok)
        # what's off, in a line, when it isn't everything on
        line = model.perm_summary(peer)
        if paused_all:
            line = "Everything is paused on this computer (Settings)."
        elif model.paused_by_it(peer):
            line = f"{self.name.text()} paused sharing with this computer. What you send waits."
        elif paused:
            line = "Nothing goes to it or comes from it until you resume. What you send waits."
        self.perms_line.setText(line)
        self.perms_line.setVisible(line != "Everything allowed")
        self.setToolTip(f"Drop files here to send them to {self.name.text()}")

    def show_transfer(self, t: model.Transfer | None):
        if t is None or not t.files:
            self.transfer.hide()
            return
        tone = TONES[t.tone()]
        self.transfer.setStyleSheet(f"color: {text_color(tone, self)};" if tone else "")
        self.transfer.setText(t.text())
        self.transfer.show()

    # --- drag and drop ---
    @staticmethod
    def _paths(event) -> list[Path]:
        md = event.mimeData()
        if not md.hasUrls():
            return []
        return [Path(u.toLocalFile()) for u in md.urls() if u.isLocalFile()]

    def dragEnterEvent(self, event):
        if self._paths(event):
            event.acceptProposedAction()
            self._drop_look(True)

    def dragMoveEvent(self, event):
        event.acceptProposedAction()

    def dragLeaveEvent(self, event):
        self._drop_look(False)

    def dropEvent(self, event):
        self._drop_look(False)
        paths = self._paths(event)
        if paths:
            event.acceptProposedAction()
            self.files_dropped.emit(paths)

    def _drop_look(self, on: bool):
        self.setProperty("drop", on)
        self.style().unpolish(self)
        self.style().polish(self)


class DevicesPage(QWidget):
    def __init__(self, win):
        super().__init__()
        self.win = win
        self.cards: dict[str, DeviceCard] = {}
        self.transfers: dict[str, model.Transfer] = {}
        self._shape = None
        self.paused_all = False
        self.capabilities: list = []

        pair = button("Pair a device", ("list-add", "network-connect"))
        pair.clicked.connect(lambda: win.go("pair"))
        self.hint = label("Drop files on a device to send them.", muted=True)
        top = hbox(vbox(title("Your devices"), self.hint, spacing=2), None, pair)

        self.list = QVBoxLayout()
        self.list.setSpacing(10)
        self.list.setContentsMargins(0, 0, 0, 0)
        body = QWidget()
        body.setObjectName("scrollbody")
        body.setLayout(vbox(self.list, None, spacing=0))
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setWidget(body)
        scroll.setFrameShape(QScrollArea.Shape.NoFrame)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)

        # nothing paired yet
        self.empty = QWidget()
        go = primary("Pair a device")
        go.clicked.connect(lambda: win.go("pair"))
        how = label("Pair this computer with your phone or another computer. Open droplet there, "
                    "on the same network, and pair from either side.", muted=True, wrap=True)
        how.setMinimumWidth(360)
        how.setMaximumWidth(420)
        how.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.empty.setLayout(vbox(
            None, hbox(None, icon_label(icon("network-wireless", "smartphone", "computer"), 64), None),
            hbox(None, title("No paired devices yet"), None), hbox(None, how, None),
            hbox(None, go, None), None, spacing=12))

        self.scroll = scroll
        self.setLayout(vbox(top, 6, scroll, self.empty, spacing=8, margins=(24, 20, 24, 16)))

        self.poll = QTimer(self)
        self.poll.setInterval(1000)
        self.poll.timeout.connect(self._poll_jobs)

    # --- the agent's status ---
    def set_status(self, status: dict | None):
        self.paused_all = bool((status or {}).get("paused_all"))
        self.capabilities = list((status or {}).get("capabilities") or [])
        ps = model.peers(status)
        shape = [p["fp"] for p in ps]
        if shape != self._shape:
            self._shape = shape
            for c in self.cards.values():
                c.setParent(None)
                c.deleteLater()
            self.cards.clear()
            for p in ps:
                c = DeviceCard(self, p)
                c.show_transfer(self.transfers.get(p["fp"]))
                self.cards[p["fp"]] = c
                self.list.addWidget(c)
        else:
            for p in ps:
                self.cards[p["fp"]].update_peer(p)
        self.empty.setVisible(not ps)
        self.scroll.setVisible(bool(ps))
        self.hint.setVisible(bool(ps))

    # --- actions ---
    def pick_files(self, peer: dict):
        name = peer.get("name") or "the device"
        paths, _ = QFileDialog.getOpenFileNames(self, f"Send files to {name}", str(Path.home()))
        if paths:
            self.send_files(peer, [Path(p) for p in paths])

    def send_files(self, peer: dict, paths: list):
        name = str(peer.get("name") or "the device")
        files = [p for p in paths if Path(p).is_file()]
        skipped = [p for p in paths if not Path(p).is_file()]
        if skipped and not files:
            self.win.say("Folders can't be sent; send the files in them.")
            return
        t = self.transfers.get(peer["fp"])
        if t is None or t.finished():
            t = self.transfers[peer["fp"]] = model.Transfer(peer["fp"], name)
        for p in files:
            p = Path(p).resolve()

            def took(r, p=p):
                t.add(p.name, r.data if r.ok else {"error": r.error})
                self._show(peer["fp"])
            self.win.agent.ask({"cmd": "send-file", "peer": peer["fp"], "path": str(p), "wait": 0}, took)
        if skipped:
            self.win.say("Folders were left out; send the files in them.")
        self._show(peer["fp"])
        self.poll.start()

    def _poll_jobs(self):
        busy = False
        for fp, t in self.transfers.items():
            for jid in t.pending():
                busy = True

                def got(r, t=t, fp=fp):
                    if r.ok:
                        t.update(r.data)
                    elif r.not_running:
                        self.win.agent_gone()
                    self._show(fp)
                self.win.agent.ask({"cmd": "job", "id": jid, "wait": 0}, got)
        if not busy:
            self.poll.stop()

    def _show(self, fp: str):
        c = self.cards.get(fp)
        if c is not None:
            c.show_transfer(self.transfers.get(fp))

    def send_clipboard(self, peer: dict):
        name = peer.get("name")
        cb = QGuiApplication.clipboard()
        if clipboard_secret(cb.mimeData()):
            self.win.say("A password manager marked what's on the clipboard secret; it wasn't sent.")
            return
        text = cb.text()
        if not text:
            self.win.say("The clipboard holds no text.")
            return

        def done(r):
            self.win.say(f"Sent the clipboard to {name}, {model.route_text(r.data.get('route'))}." if r.ok
                         else f"Couldn't send the clipboard to {name}: {r.error}")
        self.win.agent.ask({"cmd": "clip", "peer": peer["fp"], "text": text}, done)

    def ring(self, peer: dict, stop: bool = False):
        name = peer.get("name")

        def done(r):
            if r.ok:
                self.win.say(f"{'Stopped ringing' if stop else 'Ringing'} {name}, "
                             f"{model.route_text(r.data.get('route'))}.")
            else:
                self.win.say(f"Couldn't ring {name}: {r.error}")
        self.win.agent.ask({"cmd": "ring", "peer": peer["fp"], "stop": stop}, done)

    def message(self, peer: dict):
        self.win.go("messages", fp=peer["fp"])

    def pause(self, peer: dict, on: bool):
        name = peer.get("name")

        def done(r):
            if r.ok:
                self.win.say(f"Paused {name}: nothing goes to it or comes from it until you resume." if on
                             else f"Resumed {name}.")
            else:
                self.win.say(f"Couldn't {'pause' if on else 'resume'} {name}: {r.error}")
            self.win.refresh()
        self.win.agent.ask({"cmd": "pause" if on else "resume", "peer": peer["fp"]}, done)

    def permissions(self, peer: dict, dialog=None):
        from .perms import PermissionsDialog
        dlg = dialog or PermissionsDialog(self, peer, self.capabilities or None)
        if not dlg.exec():
            return
        name = peer.get("name")

        def done(r):
            self.win.say(f"Saved {name}'s permissions." if r.ok else f"Couldn't save them: {r.error}")
            self.win.refresh()
        self.win.agent.ask(dlg.values(), done)

    def about(self, peer: dict):
        how = "Paired directly" if peer.get("source") in ("paired", "browser") else "Trusted because your hub lists it"
        how += ", as someone else's device" if model.is_other(peer) else ", as your own device"
        where = ", ".join(peer.get("lan") or []) or "not known yet"
        QMessageBox.information(
            self, str(peer.get("name")),
            f"<b>{peer.get('name')}</b><br>{model.os_label(peer.get('os')) or 'Unknown system'}<br><br>"
            f"{how}.<br>Id: {peer.get('id')}<br>Addresses: {where}<br><br>"
            f"Its certificate (what this computer checks):<br><tt>"
            f"{model.grouped_fp(peer.get('fp'), per_line=8).replace(chr(10), '<br>')}</tt>")

    def unpair(self, peer: dict):
        name = peer.get("name")
        box = QMessageBox(QMessageBox.Icon.Question, f"Unpair {name}?",
                          f"Unpair {name}?",
                          QMessageBox.StandardButton.Cancel, self)
        box.setInformativeText(f"{name} and this computer stop trusting each other. To send to it again, "
                               "pair again.")
        yes = box.addButton("Unpair", QMessageBox.ButtonRole.DestructiveRole)
        box.exec()
        if box.clickedButton() is not yes:
            return

        def done(r):
            if r.ok:
                told = r.data.get("told")
                self.win.say(f"Unpaired {name}." + ("" if told else
                             f" {name} wasn't reachable, so it lists this computer until it's unpaired there."))
                self.win.refresh()
            else:
                self.win.say(f"Couldn't unpair {name}: {r.error}")
        self.win.agent.ask({"cmd": "unpair", "peer": peer["fp"]}, done)
