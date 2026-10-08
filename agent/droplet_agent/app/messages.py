"""Messages: a chat with each paired device, newest at the bottom."""

from __future__ import annotations

from PySide6.QtCore import QSize, Qt, QTimer
from PySide6.QtGui import QKeyEvent
from PySide6.QtWidgets import (QFrame, QListWidget, QListWidgetItem, QPlainTextEdit, QScrollArea, QSizePolicy,
                               QSplitter, QVBoxLayout, QWidget)

from . import model
from .widgets import device_icon, hbox, label, primary, text_color, title, vbox

POLL_MS = 2500


class Composer(QPlainTextEdit):
    """Enter sends; Shift+Enter starts a new line."""

    def __init__(self, send):
        super().__init__()
        self.send = send
        self.setPlaceholderText("Write a message")
        self.setTabChangesFocus(True)
        self.setFixedHeight(64)

    def keyPressEvent(self, e: QKeyEvent):
        if e.key() in (Qt.Key.Key_Return, Qt.Key.Key_Enter) and not e.modifiers() & Qt.KeyboardModifier.ShiftModifier:
            self.send()
            return
        super().keyPressEvent(e)


class Bubble(QWidget):
    def __init__(self, m: dict):
        super().__init__()
        out = m.get("dir") == "out"
        box = QFrame()
        box.setObjectName("bubble_out" if out else "bubble_in")
        body = label(str(m.get("body") or ""), wrap=True, selectable=True)
        body.setTextFormat(Qt.TextFormat.PlainText)
        # a wrapping label is as narrow as Qt can make it: let short messages stay on one line
        longest = max((body.fontMetrics().horizontalAdvance(line) for line in body.text().splitlines()), default=0)
        body.setMinimumWidth(min(longest + 4, 380))
        note = label(model.message_note(m), size=-1.5, muted=True)
        if m.get("state") == "failed":
            note.setStyleSheet(f"color: {text_color('bad', self)};")
        note.setAlignment(Qt.AlignmentFlag.AlignRight if out else Qt.AlignmentFlag.AlignLeft)
        box.setLayout(vbox(body, note, spacing=3, margins=(12, 8, 12, 6)))
        box.setMaximumWidth(520)
        box.setSizePolicy(QSizePolicy.Policy.Maximum, QSizePolicy.Policy.Preferred)
        self.setLayout(hbox(*((None, box) if out else (box, None)), spacing=0))


class MessagesPage(QWidget):
    def __init__(self, win):
        super().__init__()
        self.win = win
        self.status = None
        self.messages: list[dict] = []
        self.current: str | None = None      # the fingerprint of the device shown
        self._shown = None
        self._rows = None

        self.people = QListWidget()
        self.people.setObjectName("people")
        self.people.setIconSize(QSize(28, 28))
        self.people.setSpacing(2)
        self.people.setMinimumWidth(200)
        self.people.currentItemChanged.connect(self._picked)

        self.who = label(size=2.5, bold=True)
        self.who_state = label(muted=True)
        self.thread = QVBoxLayout()
        self.thread.setSpacing(8)
        self.thread.setContentsMargins(4, 4, 8, 4)
        body = QWidget()
        body.setObjectName("scrollbody")
        body.setLayout(vbox(None, self.thread, spacing=0))
        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setWidget(body)
        self.scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.scroll.verticalScrollBar().rangeChanged.connect(self._stick)
        self._at_bottom = True
        self.empty_thread = label("No messages yet. Say hello.", muted=True)
        self.empty_thread.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.composer = Composer(self.send)
        self.send_button = primary("Send")
        self.send_button.clicked.connect(self.send)
        right = QWidget()
        right.setLayout(vbox(vbox(self.who, self.who_state, spacing=0), self.scroll, self.empty_thread,
                             hbox(self.composer, self.send_button), spacing=8, margins=(12, 0, 0, 0)))
        right.layout().setStretchFactor(self.scroll, 1)
        right.layout().setStretchFactor(self.empty_thread, 1)
        self.right = right
        self.nobody = label("Pair a device to send it messages.", muted=True)
        self.nobody.setAlignment(Qt.AlignmentFlag.AlignCenter)

        split = QSplitter()
        split.addWidget(self.people)
        split.addWidget(right)
        split.setStretchFactor(1, 1)
        split.setSizes([230, 600])
        split.setChildrenCollapsible(False)
        self.split = split
        self.setLayout(vbox(title("Messages"), split, self.nobody, spacing=12, margins=(24, 20, 24, 16)))
        self.layout().setStretchFactor(split, 1)
        self.layout().setStretchFactor(self.nobody, 1)

        self.timer = QTimer(self)
        self.timer.setInterval(POLL_MS)
        self.timer.timeout.connect(self.load)

    # --- page life ---
    def showEvent(self, e):
        super().showEvent(e)
        self.timer.start()

    def hideEvent(self, e):
        super().hideEvent(e)
        self.timer.stop()

    def open(self, fp: str | None):
        if fp:
            self.current = fp
            self._fill_people()
        self.load()

    def set_status(self, status: dict | None):
        self.status = status
        self._fill_people()

    # --- data ---
    def load(self):
        def got(r):
            if r.ok:
                self.messages = r.data.get("messages") or []
                self._fill_people()
                self._fill_thread()
            elif r.not_running:
                self.win.agent_gone()
        self.win.agent.ask({"cmd": "chat", "n": 300}, got)

    def _fill_people(self):
        rows = model.conversations(self.messages, self.status)
        shape = [(r["peer"]["fp"], r["peer"].get("name"), model.device_state(r["peer"]),
                  (r["last"] or {}).get("id"), (r["last"] or {}).get("state")) for r in rows]
        has = bool(rows)
        self.split.setVisible(has)
        self.nobody.setVisible(not has)
        if self.current is None and rows:
            self.current = rows[0]["peer"]["fp"]
        self.people.blockSignals(True)
        if shape != self._rows:
            self._rows = shape
            self.people.clear()
            for r in rows:
                p = r["peer"]
                when = model.when_text((r["last"] or {}).get("ts"))
                text = f"{p.get('name')}\n{model.preview(r['last'], 34)}"
                it = QListWidgetItem(device_icon(p.get("os"), model.is_phone(p.get("os"))), text)
                it.setData(Qt.ItemDataRole.UserRole, p["fp"])
                it.setToolTip(f"{p.get('name')}: {model.state_text(p)}" + (f" · last message {when}" if when else ""))
                it.setSizeHint(QSize(0, 48))
                self.people.addItem(it)
        for i in range(self.people.count()):
            it = self.people.item(i)
            if it.data(Qt.ItemDataRole.UserRole) == self.current and self.people.currentItem() is not it:
                self.people.setCurrentItem(it)
        self.people.blockSignals(False)
        self._fill_head()
        self._fill_thread()

    def _fill_head(self):
        p = next((p for p in model.peers(self.status) if p["fp"] == self.current), None)
        if p is None:
            self.who.setText("")
            self.who_state.setText("")
            return
        self.who.setText(str(p.get("name")))
        self.who_state.setText(model.state_text(p))

    def _picked(self, item, _old=None):
        if item is None:
            return
        self.current = item.data(Qt.ItemDataRole.UserRole)
        self._shown = None
        self._at_bottom = True
        self._fill_head()
        self._fill_thread()
        self.composer.setFocus()

    def _fill_thread(self):
        msgs = model.for_peer(self.messages, self.current or "")
        shape = (self.current, [(m.get("id"), m.get("state")) for m in msgs])
        if shape == self._shown:
            return
        bar = self.scroll.verticalScrollBar()
        self._at_bottom = self._shown is None or shape[0] != (self._shown or (None,))[0] \
            or bar.value() >= bar.maximum() - 24
        self._shown = shape
        while self.thread.count():
            w = self.thread.takeAt(0).widget()
            if w is not None:
                w.setParent(None)
                w.deleteLater()
        for m in msgs:
            self.thread.addWidget(Bubble(m))
        self.scroll.setVisible(bool(msgs))
        self.empty_thread.setVisible(not msgs)

    def _stick(self, _lo, hi):
        if self._at_bottom:
            self.scroll.verticalScrollBar().setValue(hi)

    def send(self):
        text = self.composer.toPlainText().strip()
        if not text or not self.current:
            return
        fp = self.current
        self.composer.clear()

        def done(r):
            if r.ok:
                if r.data.get("state") not in ("done", None):
                    self.win.say("It can't be reached right now. The message waits and goes as soon as it can.")
            else:
                self.composer.setPlainText(text)
                self.win.say(f"Couldn't send it: {r.error}")
            self._at_bottom = True
            self.load()
        self.win.agent.ask({"cmd": "text", "peer": fp, "body": text, "wait": 5}, done, timeout=15)
