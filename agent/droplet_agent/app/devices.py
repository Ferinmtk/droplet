"""Devices: each paired device as a card, with what you can do with it."""

from __future__ import annotations

import sys
from pathlib import Path

from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtGui import QGuiApplication
from PySide6.QtWidgets import (QCheckBox, QFileDialog, QFrame, QMenu, QMessageBox, QProgressBar, QScrollArea,
                               QSizePolicy, QToolButton, QVBoxLayout, QWidget)

from . import model
from .widgets import (GOOD, Card, button, device_icon, dot, hbox, icon, icon_label, label, primary, text_color,
                      title, vbox)

TONES = {"busy": None, "ok": "good", "warn": "warn", "bad": "bad"}
FAST_MS = 250        # the transfers, while one is going: four times a second


class TransferRow(QFrame):
    """One file on its way, to or from the device: its name, how far, how fast, and Cancel."""

    def __init__(self, page: "DevicesPage", t: dict):
        super().__init__()
        self.setObjectName("transfer")
        self.page = page
        self.tid = t["id"]
        self.title = label(bold=True)
        self.title.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Preferred)
        self.title.setMinimumWidth(80)
        self.detail = label(muted=True)
        self.bar = QProgressBar()
        self.bar.setRange(0, 1000)
        self.bar.setTextVisible(False)
        self.bar.setFixedHeight(6)
        self.cancel = button("Cancel", ("process-stop", "dialog-cancel"))
        self.cancel.setToolTip("Stop it. The other device is told, and what came of it is deleted there.")
        self.cancel.clicked.connect(lambda: page.cancel_transfer(self.tid, self.name))
        head = hbox(self.title, self.detail, spacing=8)
        head.setStretch(0, 1)
        self.setLayout(hbox(vbox(head, self.bar, spacing=4),
                            self.cancel, spacing=10, margins=(0, 2, 0, 2)))
        self.update_transfer(t)

    def _elide(self):
        """A long name is shortened in the middle to fit, keeping its end (.jpg)."""
        width = max(self.title.width(), 80)
        self.title.setText(self.title.fontMetrics().elidedText(self.full_title, Qt.TextElideMode.ElideMiddle, width))

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self._elide()

    def update_transfer(self, t: dict):
        self.full_title = model.transfer_title(t)
        self._elide()
        self.name = str(t.get("name") or "it")
        self.title.setToolTip(self.name)
        self.detail.setText(model.transfer_detail(t))
        active = t.get("state") == "active"
        self.bar.setVisible(active)
        self.bar.setValue(int(t.get("done", 0) * 1000 / t["size"]) if t.get("size") else 0)
        if active and t.get("route") == "hub" and not t.get("done"):
            self.bar.setRange(0, 0)       # no byte counts through the hub: it's going, that's all
        elif self.bar.maximum() == 0:
            self.bar.setRange(0, 1000)
        self.cancel.setVisible(active)
        tone = {"done": "good", "failed": "bad", "cancelled": "warn", "waiting": "warn"}.get(t.get("state"))
        self.detail.setStyleSheet(f"color: {text_color(tone, self)};" if tone else "")


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
        self.check = QCheckBox()
        self.check.setToolTip("Send to this device too")
        self.check.toggled.connect(lambda _on: page.selection_changed())
        self.check.hide()
        self.name = label(size=2.5, bold=True)
        self.own_name = label(muted=True, size=-1)
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
        menu.addAction(icon("internet-web-browser", "applications-internet", "emblem-web"), "Send a link…",
                       lambda: page.send_link([self.peer]))
        menu.addAction(icon("media-playback-stop"), "Stop ringing", lambda: page.ring(self.peer, stop=True))
        menu.addAction(icon("edit-rename", "document-edit"), "Nickname…", lambda: page.nickname(self.peer))
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

        head = vbox(hbox(self.name, self.own_name, self.other_badge, self.paused_badge, None, self.b_pause, self.more,
                         spacing=8),
                    hbox(self.state_dot, self.state, None, spacing=6), spacing=2)
        actions = hbox(self.b_files, self.b_clip, self.b_ring, self.b_msg, None, spacing=6)
        self.rows: dict[str, TransferRow] = {}
        self.rows_box = QVBoxLayout()
        self.rows_box.setSpacing(4)
        self.rows_box.setContentsMargins(0, 0, 0, 0)
        lay = hbox(vbox(self.check, None), vbox(self.icon, None),
                   vbox(head, actions, self.perms_line, self.rows_box, self.transfer, spacing=10),
                   spacing=14, margins=(16, 14, 12, 14))
        self.setLayout(lay)
        self.files_dropped.connect(lambda paths: page.send_files(self.peer, paths))
        self.update_peer(peer)

    def update_peer(self, peer: dict):
        self.peer = peer
        self.name.setText(model.display_name(peer))
        note = model.own_name_note(peer)
        self.own_name.setText(f"({peer.get('name')})" if note else "")
        self.own_name.setToolTip(note)
        self.own_name.setVisible(bool(note))
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

    def set_transfers(self, transfers: list):
        """Each file on its way to or from it (and the ones just finished), as a row."""
        seen = set()
        for t in transfers:
            seen.add(t["id"])
            row = self.rows.get(t["id"])
            if row is None:
                row = self.rows[t["id"]] = TransferRow(self.page, t)
                self.rows_box.addWidget(row)
            else:
                row.update_transfer(t)
        for tid in [k for k in self.rows if k not in seen]:
            row = self.rows.pop(tid)
            row.setParent(None)
            row.deleteLater()

    def set_selecting(self, on: bool):
        self.check.setVisible(on)
        if not on:
            self.check.setChecked(False)

    def mousePressEvent(self, event):
        if self.check.isVisible() and event.button() == Qt.MouseButton.LeftButton:
            self.check.toggle()      # in Select, a click anywhere on the card picks it
            return
        super().mousePressEvent(event)

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
        self.live: list = []          # the agent's transfers, as last heard
        self.peers: list = []

        pair = button("Pair a device", ("list-add", "network-connect"))
        pair.clicked.connect(lambda: win.go("pair"))
        self.select = button("Select", ("edit-select-all", "checkbox"))
        self.select.setCheckable(True)
        self.select.setToolTip("Pick several devices, and send them all the same thing")
        self.select.toggled.connect(self.set_selecting)
        self.hint = label("Drop files on a device to send them.", muted=True)
        top = hbox(vbox(title("Your devices"), self.hint, spacing=2), None, self.select, pair)

        # Select: what to send to the devices picked
        self.bar = QFrame()
        self.bar.setObjectName("banner")
        self.picked = label(bold=True)
        self.m_files = button("Send files…", ("document-send", "document-open"))
        self.m_clip = button("Send clipboard", ("edit-paste",))
        self.m_msg = button("Send a message…", ("mail-message-new", "mail-send"))
        self.m_link = button("Send a link…", ("internet-web-browser", "applications-internet"))
        self.m_all = button("All my devices")
        self.m_all.setToolTip("Pick every device of yours (not someone else's)")
        done = button("Done")
        self.m_files.clicked.connect(lambda: self.pick_files_many(self.selected()))
        self.m_clip.clicked.connect(lambda: self.send_clipboard_many(self.selected()))
        self.m_msg.clicked.connect(lambda: self.message_many(self.selected()))
        self.m_link.clicked.connect(lambda: self.send_link(self.selected()))
        self.m_all.clicked.connect(self.pick_all_mine)
        done.clicked.connect(lambda: self.select.setChecked(False))
        self.bar.setLayout(vbox(hbox(self.picked, None, self.m_all, done, spacing=8),
                                hbox(self.m_files, self.m_clip, self.m_msg, self.m_link, None, spacing=6),
                                spacing=8, margins=(14, 10, 14, 10)))
        self.bar.hide()

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
        self.setLayout(vbox(top, 6, self.bar, scroll, self.empty, spacing=8, margins=(24, 20, 24, 16)))

        self.poll = QTimer(self)
        self.poll.setInterval(1000)
        self.poll.timeout.connect(self._poll_jobs)
        # how far each file has got, four times a second while one is going
        self.fast = QTimer(self)
        self.fast.setInterval(FAST_MS)
        self.fast.timeout.connect(self._poll_transfers)
        self._asking_transfers = False

    # --- the agent's status ---
    def set_status(self, status: dict | None):
        self.paused_all = bool((status or {}).get("paused_all"))
        self.capabilities = list((status or {}).get("capabilities") or [])
        ps = model.peers(status)
        self.peers = ps
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
                c.set_selecting(self.select.isChecked())
                self.cards[p["fp"]] = c
                self.list.addWidget(c)
        else:
            for p in ps:
                self.cards[p["fp"]].update_peer(p)
        self.empty.setVisible(not ps)
        self.scroll.setVisible(bool(ps))
        self.hint.setVisible(bool(ps))
        self.select.setVisible(len(ps) > 1)
        if status is not None and "transfers" in status:
            self.set_transfers(status.get("transfers") or [])
        self.selection_changed()

    # --- transfers: how far each file has got ---
    def set_transfers(self, transfers: list):
        self.live = list(transfers)
        for fp, c in self.cards.items():
            c.set_transfers(model.transfers_for(self.live, fp))
        if model.any_active(self.live):
            if not self.fast.isActive():
                self.fast.start()
        else:
            self.fast.stop()

    def _poll_transfers(self):
        if self._asking_transfers:
            return
        self._asking_transfers = True

        def got(r):
            self._asking_transfers = False
            if r.ok:
                self.set_transfers(r.data.get("transfers") or [])
            elif r.not_running:
                self.fast.stop()
                self.win.agent_gone()
        self.win.agent.ask({"cmd": "transfers"}, got, timeout=5)

    def cancel_transfer(self, tid: str, name: str = "it"):
        def done(r):
            if r.ok:
                d = r.data
                self.win.say(f"Cancelled {d.get('name') or name}" + (f" to {d['peer']}" if d.get("dir") == "out" and
                             d.get("peer") else f" from {d['peer']}" if d.get("peer") else "") + ".")
            else:
                self.win.say(f"Couldn't cancel it: {r.error}")
            self._poll_transfers()
        self.win.agent.ask({"cmd": "cancel", "id": tid}, done)

    # --- several devices at once ---
    def set_selecting(self, on: bool):
        for c in self.cards.values():
            c.set_selecting(on)
        self.bar.setVisible(on)
        self.hint.setText("Pick the devices to send to." if on else "Drop files on a device to send them.")
        self.selection_changed()

    def selected(self) -> list[dict]:
        return [c.peer for c in self.cards.values() if c.check.isChecked()]

    def selection_changed(self):
        picked = self.selected()
        n = len(picked)
        self.picked.setText(f"{n} device{'s' if n != 1 else ''} selected" if n else "Pick devices below")
        for b, cap in ((self.m_files, "files"), (self.m_msg, "chat"), (self.m_link, "chat"),
                       (self.m_clip, "clipboard")):
            b.setEnabled(any(model.allows(p, cap) for p in picked))

    def pick_all_mine(self):
        from ..mesh.perms import own_targets
        mine = {p["fp"] for p in own_targets(self.peers, "files")}
        for fp, c in self.cards.items():
            c.check.setChecked(fp in mine)

    def _gather(self, what: str, peers: list, request, then=None):
        """Ask `request(peer)` for each device; when all have answered, say how each went."""
        results: list = []
        if not peers:
            return

        def one(peer):
            def got(r):
                st, why = model.outcome(r.data if r.ok else None, None if r.ok else r.error)
                results.append((model.display_name(peer), st, why))
                if len(results) == len(peers):
                    self.win.say(model.outcomes_text(what, results), 15000)
                    if then:
                        then(results)
            return got
        for peer in peers:
            self.win.agent.ask(request(peer), one(peer), timeout=20)

    def pick_files_many(self, peers: list):
        peers = [p for p in peers if model.allows(p, "files")]
        if not peers:
            return
        names = ", ".join(model.display_name(p) for p in peers)
        paths, _ = QFileDialog.getOpenFileNames(self, f"Send files to {names}", str(Path.home()))
        if paths:
            for p in peers:
                self.send_files(p, [Path(x) for x in paths])
            self.win.say(f"Sending {len(paths)} file{'s' if len(paths) != 1 else ''} to {len(peers)} "
                         f"device{'s' if len(peers) != 1 else ''}: each card shows how it goes.")

    def send_clipboard_many(self, peers: list):
        peers = [p for p in peers if model.allows(p, "clipboard")]
        text = self._clipboard_text()
        if text is None or not peers:
            return
        self._gather("the clipboard", peers, lambda p: {"cmd": "clip", "peer": p["fp"], "text": text})

    def message_many(self, peers: list):
        from .dialogs import message_dialog
        peers = [p for p in peers if model.allows(p, "chat")]
        if not peers:
            return
        dlg = message_dialog(self, [model.display_name(p) for p in peers])
        if not dlg.exec():
            return
        self._gather("the message", peers, lambda p: {"cmd": "text", "peer": p["fp"], "body": dlg.value, "wait": 5})

    def send_link(self, peers: list, dialog=None):
        from .dialogs import link_dialog
        peers = [p for p in peers if model.allows(p, "chat")]
        if not peers:
            return
        from ..mesh.links import is_url
        clip = QGuiApplication.clipboard().text().strip()
        dlg = dialog or link_dialog(self, [model.display_name(p) for p in peers], clip if is_url(clip) else "")
        if not dlg.exec():
            return
        self._gather("the link", peers, lambda p: {"cmd": "link", "peer": p["fp"], "url": dlg.value, "wait": 5})

    def nickname(self, peer: dict, dialog=None):
        from .dialogs import nickname_dialog
        dlg = dialog or nickname_dialog(self, peer)
        if not dlg.exec():
            return

        def done(r):
            if r.ok:
                nick = r.data.get("nickname")
                self.win.say(f"{peer.get('name')} is called {nick} on this computer." if nick
                             else f"{peer.get('name')} goes by its own name again.")
            else:
                self.win.say(f"Couldn't save the nickname: {r.error}")
            self.win.refresh()
        self.win.agent.ask({"cmd": "nickname", "peer": peer["fp"], "nickname": dlg.value or ""}, done)

    # --- actions ---
    def pick_files(self, peer: dict):
        name = model.display_name(peer)
        paths, _ = QFileDialog.getOpenFileNames(self, f"Send files to {name}", str(Path.home()))
        if paths:
            self.send_files(peer, [Path(p) for p in paths])

    def send_files(self, peer: dict, paths: list):
        name = model.display_name(peer)
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

    def _clipboard_text(self) -> str | None:
        cb = QGuiApplication.clipboard()
        if clipboard_secret(cb.mimeData()):
            self.win.say("A password manager marked what's on the clipboard secret; it wasn't sent.")
            return None
        text = cb.text()
        if not text:
            self.win.say("The clipboard holds no text.")
            return None
        return text

    def send_clipboard(self, peer: dict):
        name = model.display_name(peer)
        text = self._clipboard_text()
        if text is None:
            return

        def done(r):
            self.win.say(f"Sent the clipboard to {name}, {model.route_text(r.data.get('route'))}." if r.ok
                         else f"Couldn't send the clipboard to {name}: {r.error}")
        self.win.agent.ask({"cmd": "clip", "peer": peer["fp"], "text": text}, done)

    def ring(self, peer: dict, stop: bool = False):
        name = model.display_name(peer)

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
        name = model.display_name(peer)

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
        name = model.display_name(peer)
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
