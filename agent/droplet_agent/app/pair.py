"""Pair a device: pick one on this network (or type its address), compare the code, wait for it."""

from __future__ import annotations

from PySide6.QtCore import Qt, QTimer
from PySide6.QtWidgets import QLineEdit, QScrollArea, QStackedWidget, QVBoxLayout, QWidget

from . import model
from .widgets import Card, button, device_icon, font, hbox, icon_label, label, primary, title, vbox


class NearbyRow(Card):
    def __init__(self, page: "PairPage", n: dict):
        super().__init__()
        self.n = n
        name = label(str(n.get("name") or n.get("id")), size=1.5, bold=True)
        bits = [model.os_label(n.get("os")), ", ".join(n.get("addresses") or [])]
        go = primary("Pair")
        go.clicked.connect(lambda: page.start(str(n.get("fp") or n.get("name")), n.get("name")))
        self.setLayout(hbox(icon_label(device_icon(n.get("os"), model.is_phone(n.get("os"))), 32),
                            vbox(name, label(" · ".join(b for b in bits if b), muted=True), spacing=2),
                            None, go, spacing=12, margins=(14, 10, 12, 10)))


class PairPage(QWidget):
    def __init__(self, win):
        super().__init__()
        self.win = win
        self.flow = model.PairFlow()
        self._shape = None
        self.stack = QStackedWidget()

        # 1. pick a device
        pick = QWidget()
        self.rows = QVBoxLayout()
        self.rows.setSpacing(8)
        self.none_nearby = label("No unpaired droplet devices are announcing themselves on this network. "
                                 "You can type one's address instead.", muted=True, wrap=True)
        self.address = QLineEdit()
        self.address.setPlaceholderText("192.168.1.20, or t15.local:1740")
        self.address.returnPressed.connect(lambda: self.start(self.address.text()))
        by_address = button("Pair")
        by_address.clicked.connect(lambda: self.start(self.address.text()))
        body = QWidget()
        body.setObjectName("scrollbody")
        body.setLayout(vbox(
            label("On this network", size=1.5, bold=True), self.rows, self.none_nearby, 14,
            label("Or by address (an IP or name, with :port if it isn't 1739)", size=1.5, bold=True),
            hbox(self.address, by_address), 14,
            label("Open droplet on the other device, on the same network, and keep it open while you pair. "
                  "A device that doesn't show up (some routers hide devices from each other) pairs by its "
                  "address. Another device can also ask this computer: you'll be asked here to accept.",
                  muted=True, wrap=True),
            None, spacing=8))
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setWidget(body)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        pick.setLayout(vbox(scroll))

        # 2. the code, and what happens next
        flow = QWidget()
        self.headline = title("")
        self.headline.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.code = label()
        self.code.setObjectName("code")
        self.code.setFont(font(26, bold=True))
        self.code.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.detail = label(wrap=True)
        self.detail.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.detail.setMinimumWidth(380)
        self.detail.setMaximumWidth(460)
        self.cert = label(muted=True, wrap=True, selectable=True)
        self.cert.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.cert.setMinimumWidth(380)
        self.cert.setMaximumWidth(460)
        self.yes = primary("They match")
        self.no = button("No, cancel")
        self.again = primary("Try again")
        self.done = primary("Show my devices")
        self.back = button("Back")
        self.yes.clicked.connect(lambda: self.confirm(True))
        self.no.clicked.connect(lambda: self.confirm(False))
        self.again.clicked.connect(lambda: self.start(self.flow.target, self.flow.peer.get("name")))
        self.done.clicked.connect(self._finish)
        self.back.clicked.connect(self.reset)
        flow.setLayout(vbox(None, self.headline, 6, self.code, 6, hbox(None, self.detail, None),
                            hbox(None, self.no, self.back, self.yes, self.again, self.done, None),
                            12, hbox(None, self.cert, None), None, spacing=10))

        self.stack.addWidget(pick)
        self.stack.addWidget(flow)
        self.setLayout(vbox(title("Pair a device"), self.stack, spacing=12, margins=(24, 20, 24, 16)))

        self.timer = QTimer(self)
        self.timer.setInterval(1500)
        self.timer.timeout.connect(self._poll)
        self.show_flow()

    # --- what's on this network ---
    def set_status(self, status: dict | None):
        nearby = [n for n in (status or {}).get("nearby") or [] if isinstance(n, dict)]
        shape = [(n.get("fp"), n.get("name"), tuple(n.get("addresses") or [])) for n in nearby]
        if shape == self._shape:
            return
        self._shape = shape
        while self.rows.count():
            w = self.rows.takeAt(0).widget()
            if w is not None:
                w.setParent(None)
                w.deleteLater()
        for n in nearby:
            self.rows.addWidget(NearbyRow(self, n))
        self.none_nearby.setVisible(not nearby)

    # --- the flow ---
    def start(self, target: str, name: str | None = None):
        req = self.flow.start(target, name)
        if req is None:
            if not target.strip():
                self.win.say("Type the other device's address first.")
            return
        self.show_flow()
        self.win.agent.ask(req, self._started, timeout=40)

    def _started(self, r):
        self.flow.on_started(r.data, r.error)
        self.show_flow()

    def confirm(self, yes: bool):
        req = self.flow.confirm(yes)
        if req is None:
            return
        self.show_flow()
        self.win.agent.ask(req, self._confirmed)

    def _confirmed(self, r):
        self.flow.on_confirmed(r.data, r.error)
        self.show_flow()

    def _poll(self):
        req = self.flow.poll()
        if req is None:
            self.timer.stop()
            return
        self.win.agent.ask(req, self._polled)

    def _polled(self, r):
        before = self.flow.state
        self.flow.on_status(r.data, r.error)
        if self.flow.state != before:
            self.show_flow()
            if self.flow.state == "paired":
                self.win.refresh()

    def reset(self):
        if self.flow.state in ("code", "waiting"):
            self.confirm(False)
        self.flow.reset()
        self.address.clear()
        self.show_flow()

    def _finish(self):
        self.flow.reset()
        self.show_flow()
        self.win.go("devices")

    def show_flow(self):
        f = self.flow
        st = f.state
        if st == "pick":
            self.stack.setCurrentIndex(0)
            self.timer.stop()
            return
        self.stack.setCurrentIndex(1)
        self.headline.setText(f.headline())
        self.detail.setText(f.detail())
        self.detail.setVisible(bool(f.detail()))
        self.code.setText(model.spaced_code(f.code) if st in ("code", "waiting") else "")
        self.code.setVisible(st in ("code", "waiting"))
        fp = f.peer.get("fp")
        showing = bool(fp) and st in ("code", "waiting")
        self.cert.setText(f"Its certificate:\n{model.grouped_fp(fp, per_line=8)}" if showing else "")
        self.cert.setVisible(bool(self.cert.text()))
        self.yes.setVisible(st == "code")
        self.no.setVisible(st in ("code", "waiting"))
        self.no.setText("No, cancel" if st == "code" else "Cancel")
        self.again.setVisible(st in ("declined", "expired", "error") and bool(f.target))
        self.done.setVisible(st == "paired")
        self.back.setVisible(st in ("declined", "expired", "cancelled", "error"))
        if st == "waiting":
            self.timer.start()
        else:
            self.timer.stop()
