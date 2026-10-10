"""The main window: a sidebar of pages, pairing requests on top, and the agent's state."""

from __future__ import annotations

import shutil
import subprocess
import sys

from PySide6.QtCore import QEvent, QSize, Qt, QTimer
from PySide6.QtWidgets import (QFrame, QListWidget, QListWidgetItem, QMainWindow, QStackedWidget, QVBoxLayout,
                               QWidget)

from . import APP_NAME, model
from .agent import Agent
from .devices import DevicesPage
from .messages import MessagesPage
from .pair import PairPage
from .received import ReceivedPage
from .settings import SettingsPage
from .widgets import app_icon, button, font, hbox, icon, icon_label, label, primary, title, vbox

POLL_MS = 2500       # status, while the window is open and the agent runs
RETRY_MS = 5000      # and while it doesn't

PAGES = [("devices", "Devices", ("smartphone", "computer")),
         ("pair", "Pair a device", ("list-add", "network-connect")),
         ("messages", "Messages", ("mail-message", "dialog-messages", "mail-unread")),
         ("received", "Received", ("folder-download", "download")),
         ("settings", "Settings", ("configure", "preferences-system", "settings-configure"))]


class Banner(QFrame):
    """A device asking to pair with this one: its code, and Accept or Decline."""

    def __init__(self, win, r: dict):
        super().__init__()
        self.setObjectName("banner")
        self.r = r
        name = str(r.get("name") or "A device")
        what = model.os_label(r.get("os"))
        head = label(f"{name} wants to pair" + (f" ({what})" if what else ""), size=1.5, bold=True, wrap=True)
        code = label(model.spaced_code(str(r.get("code") or "?")))
        code.setObjectName("code")
        code.setFont(font(9, bold=True))
        hint = label(f"Accept only if {name} shows the same code.", muted=True, wrap=True)
        accept = primary("Accept")
        decline = button("Decline")
        accept.clicked.connect(lambda: win.answer(r, True))
        decline.clicked.connect(lambda: win.answer(r, False))
        lay = hbox(icon_label(icon("dialog-question", "network-connect"), 32), vbox(head, hint, spacing=2),
                   code, 16, decline, accept, spacing=10, margins=(14, 10, 14, 10))
        lay.setStretch(1, 1)    # the words take the room; they wrap only when there isn't enough
        self.setLayout(lay)


class NotRunning(QWidget):
    def __init__(self, win):
        super().__init__()
        self.win = win
        pic = icon_label(app_icon(), 64)
        pic.setEnabled(False)
        head = title("The droplet agent isn't running")
        head.setAlignment(Qt.AlignmentFlag.AlignCenter)
        text = label("Droplet needs it to reach your devices. It normally starts with your desktop.",
                     muted=True, wrap=True)
        text.setAlignment(Qt.AlignmentFlag.AlignCenter)
        text.setMaximumWidth(440)
        self.why = label(wrap=True, selectable=True)
        self.why.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.why.setMaximumWidth(440)
        self.why.hide()
        self.start = primary("Start it")
        retry = button("Try again")
        self.start.clicked.connect(self.start_agent)
        retry.clicked.connect(win.refresh)
        from ..tray import start_hint
        cli = label(f"Or in a terminal: {start_hint()}", muted=True, selectable=True)
        cli.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setLayout(vbox(None, hbox(None, pic, None), hbox(None, head, None), hbox(None, text, None),
                            8, hbox(None, retry, self.start, None), hbox(None, self.why, None), 8,
                            hbox(None, cli, None), None, spacing=12, margins=(24, 24, 24, 24)))

    def start_agent(self):
        self.start.setEnabled(False)
        self.start.setText("Starting…")
        self.why.hide()

        def go():
            if sys.platform == "darwin":
                from .. import macos
                return macos.start_service()
            if not shutil.which("systemctl"):
                return "This computer has no systemctl. Start the agent with: droplet-agent run"
            r = subprocess.run(["systemctl", "--user", "start", "droplet-agent"], capture_output=True, text=True,
                               timeout=30)
            if r.returncode != 0:
                lines = (r.stderr or r.stdout).strip().splitlines()
                return "It didn't start" + (f": {lines[-1]}" if lines else ".")
            return ""

        def done(result):
            self.start.setEnabled(True)
            self.start.setText("Start it")
            msg = result if isinstance(result, str) else str(getattr(result, "error", "") or "")
            if msg:
                self.why.setText(msg)
                self.why.show()
            # the agent takes a moment to open its socket
            QTimer.singleShot(1500, self.win.refresh)
        self.win.agent.run(go, done)


class Window(QMainWindow):
    def __init__(self, call=None, sync: bool = False, restart=None):
        super().__init__()
        self.setWindowTitle(APP_NAME)
        self.setWindowIcon(app_icon())
        self.setMinimumSize(QSize(760, 520))
        self.resize(960, 640)
        self.agent = Agent(call, sync=sync, parent=self)
        self.status: dict | None = None
        self.running: bool | None = None
        self._asking = False
        self._banners_shape = None

        # sidebar
        side = QFrame()
        side.setObjectName("sidebar")
        side.setFixedWidth(204)
        self.me = label(muted=True)
        self.nav = QListWidget()
        self.nav.setObjectName("nav")
        self.nav.setIconSize(QSize(20, 20))
        for key, text, icons in PAGES:
            it = QListWidgetItem(icon(*icons), text)
            it.setData(Qt.ItemDataRole.UserRole, key)
            it.setSizeHint(QSize(0, 38))
            self.nav.addItem(it)
        self.nav.currentRowChanged.connect(self._nav)
        self.summary = label(muted=True, wrap=True)
        brand = label(APP_NAME, size=4, bold=True)
        side.setLayout(vbox(hbox(icon_label(app_icon(), 32), vbox(brand, self.me, spacing=0), None, spacing=10),
                            14, self.nav, self.summary, spacing=6, margins=(14, 18, 14, 14)))

        # pages
        self.pages = {"devices": DevicesPage(self), "pair": PairPage(self), "messages": MessagesPage(self),
                      "received": ReceivedPage(self),
                      "settings": SettingsPage(self, **({"restart": restart} if restart else {}))}
        self.stack = QStackedWidget()
        for key, _t, _i in PAGES:
            self.stack.addWidget(self.pages[key])
        self.banners = QVBoxLayout()
        self.banners.setSpacing(8)
        self.banners.setContentsMargins(24, 0, 24, 0)
        right = QWidget()
        right.setLayout(vbox(14, self.banners, self.stack, spacing=4))
        main = QWidget()
        main.setLayout(hbox(side, right, spacing=0))

        self.not_running = NotRunning(self)
        self.outer = QStackedWidget()
        self.outer.addWidget(main)
        self.outer.addWidget(self.not_running)
        self.setCentralWidget(self.outer)
        self.statusBar().setSizeGripEnabled(False)

        self.timer = QTimer(self)
        self.timer.timeout.connect(self.refresh)
        self.timer.start(POLL_MS)
        self.nav.setCurrentRow(0)
        self.refresh()

    # --- navigation ---
    def _nav(self, row: int):
        if row >= 0:
            self.stack.setCurrentIndex(row)
            page = self.pages[PAGES[row][0]]
            if hasattr(page, "load"):
                page.load()     # what it shows is fetched when it's opened

    def go(self, page: str, **kw):
        if page == "iphone":    # the Pair page, showing the code an iPhone scans
            self.go("pair")
            self.pages["pair"].show_qr()
            return
        keys = [k for k, _t, _i in PAGES]
        if page not in keys:
            return
        self.nav.setCurrentRow(keys.index(page))
        if page == "messages":
            self.pages["messages"].open(kw.get("fp"))

    def current_page(self) -> str:
        return PAGES[self.stack.currentIndex()][0]

    def say(self, text: str, ms: int = 8000):
        self.statusBar().showMessage(text, ms)

    # --- the agent ---
    def refresh(self):
        if self._asking:
            return
        self._asking = True
        self.agent.ask({"cmd": "status"}, self._got_status, timeout=8)

    def refresh_soon(self, ms: int = 2500):
        QTimer.singleShot(ms, self.refresh)

    def agent_gone(self):
        self.set_status(None)

    def _got_status(self, r):
        self._asking = False
        if r.ok:
            self.set_status(r.data)
        elif r.not_running:
            self.set_status(None)
        else:
            self.say(r.error or "The agent didn't answer.")

    def set_status(self, status: dict | None):
        self.status = status
        running = status is not None
        if running != self.running:
            self.running = running
            self.outer.setCurrentIndex(0 if running else 1)
            self.timer.setInterval(POLL_MS if running else RETRY_MS)
        if not running:
            return
        self.me.setText(f"on {status.get('name') or 'this computer'}")
        self.summary.setText(model.summary(status))
        for p in self.pages.values():
            if hasattr(p, "set_status"):
                p.set_status(status)
        self._show_banners(model.incoming(status))
        if self.current_page() == "received":
            self.pages["received"].load()     # a file may have just arrived

    def _show_banners(self, requests: list):
        shape = [(r.get("request"), r.get("code")) for r in requests]
        if shape == self._banners_shape:
            return
        self._banners_shape = shape
        while self.banners.count():
            w = self.banners.takeAt(0).widget()
            if w is not None:
                w.setParent(None)
                w.deleteLater()
        for r in requests:
            self.banners.addWidget(Banner(self, r))

    def answer(self, r: dict, accept: bool):
        name = r.get("name") or "the device"

        def done(reply):
            if reply.ok:
                self.say(f"Paired with {reply.data.get('name') or name}." if accept else f"Declined {name}.")
            else:
                self.say(f"Couldn't answer {name}: {reply.error}")
            self.refresh()
        self.agent.ask({"cmd": "pair-answer", "request": r.get("request"), "accept": accept}, done)

    # --- the window ---
    def changeEvent(self, e):
        super().changeEvent(e)
        if e.type() == QEvent.Type.ActivationChange and self.isActiveWindow():
            self.refresh()

    def showEvent(self, e):
        super().showEvent(e)
        self.timer.start()

    def hideEvent(self, e):
        super().hideEvent(e)
        self.timer.stop()

    def bring_up(self, page: str | None = None):
        self.show()
        if self.isMinimized():
            self.showNormal()
        self.raise_()
        self.activateWindow()
        if sys.platform == "darwin":
            from .. import macos
            macos.activate_app()   # a Mac brings windows up only with their app
        if page:
            self.go(page)
