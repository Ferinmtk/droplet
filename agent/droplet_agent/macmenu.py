"""droplet in a Mac's menu bar: `droplet-agent tray` on macOS.

A Mac has no StatusNotifierItem (and no D-Bus), so the icon is Qt's
QSystemTrayIcon, with the menu tray.build_view makes from the agent's
status and the same tray.Actions behind it. Like the Linux tray, it's a
separate process that does everything through the agent's control socket,
and it has no Dock icon (it's an accessory app).

One at a time: it listens on menu.sock next to the control socket, and a
new one tells the old one to quit first, so starting it again (or
upgrading) replaces it.
"""

from __future__ import annotations

import logging
import sys
import threading
from pathlib import Path

from PySide6.QtCore import QObject, QTimer, Signal, Slot
from PySide6.QtGui import QAction, QIcon, QImage, QPixmap
from PySide6.QtWidgets import QApplication, QFileDialog, QMenu, QSystemTrayIcon

from . import tray

log = logging.getLogger("droplet_agent.menu")


def _icon(pixmaps: list) -> QIcon:
    ic = QIcon()
    for w, h, px in pixmaps:
        img = QImage()
        if img.loadFromData(tray.png(w, h, px), "PNG"):
            ic.addPixmap(QPixmap.fromImage(img))
    return ic


def fill(menu: QMenu, items: list, trigger) -> None:
    """The view's items as Qt menu entries; `trigger(action)` when one is picked."""
    for it in items:
        if it.separator:
            menu.addSeparator()
        elif it.children:
            sub = menu.addMenu(it.label)
            sub.setEnabled(it.enabled)
            fill(sub, it.children, trigger)
        else:
            act = QAction(it.label, menu)
            act.setEnabled(it.enabled and it.action is not None)
            if it.action is not None:
                act.triggered.connect(lambda _=False, a=it.action: trigger(a))
            menu.addAction(act)


class MenuBar(QObject):
    got = Signal(object)   # the agent's status (or None), from a worker thread

    def __init__(self, call=None, notify=None, sync: bool = False):
        super().__init__()
        from .mesh import control
        from . import macos
        self.call = call or control.call
        self.sync = sync
        self.actions = tray.Actions(self.call, notify or macos.notify, refresh=self.kick)
        icons = tray.Icons.load()
        self.icons = {"normal": _icon(icons.normal), "attention": _icon(icons.attention), "off": _icon(icons.off)}
        self.icon = QSystemTrayIcon(self.icons["off"])
        self.menu = QMenu()
        self.icon.setContextMenu(self.menu)
        self.icon.setToolTip("droplet")
        self.menu.aboutToShow.connect(self.kick)
        self.got.connect(self.show)
        self.view = None
        self._shape = None
        self._busy = threading.Lock()
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.kick)
        self.timer.start(int(tray.POLL * 1000))
        self.show(None)

    # --- the agent's status, off the GUI thread ---
    def status(self) -> dict | None:
        from .mesh import control
        try:
            st = self.call({"cmd": "status"}, timeout=5)
        except (control.NotRunning, OSError, ValueError):
            return None
        return None if not isinstance(st, dict) or st.get("error") else st

    def kick(self):
        if self.sync:
            self.show(self.status())
            return
        if not self._busy.acquire(blocking=False):
            return   # one look at a time

        def work():
            try:
                st = self.status()
            finally:
                self._busy.release()
            try:
                self.got.emit(st)
            except RuntimeError:
                pass   # quitting
        threading.Thread(target=work, name="menu-status", daemon=True).start()

    @Slot(object)
    def show(self, status):
        view = tray.build_view(status, app=True)
        self.view = view
        shape = tray._shape(view.items)
        if not view.running:
            self.icon.setIcon(self.icons["off"])
        else:
            self.icon.setIcon(self.icons["attention" if view.status == tray.ATTENTION else "normal"])
        self.icon.setToolTip(f"droplet: {view.tooltip}" if view.tooltip else "droplet")
        if shape == self._shape:
            return
        self._shape = shape
        self.menu.clear()
        fill(self.menu, view.items, self.trigger)
        self.menu.addSeparator()
        quit_ = QAction("Quit droplet's menu bar icon", self.menu)
        quit_.triggered.connect(QApplication.quit)
        self.menu.addAction(quit_)

    # --- what the menu does ---
    def pick_files(self, name: str) -> list:
        from . import macos
        macos.activate_app()
        paths, _ = QFileDialog.getOpenFileNames(None, f"Send files to {name}")
        return [Path(p) for p in paths]

    def trigger(self, action: tuple):
        log.info("menu: %s", action[0])
        if action[0] == "send-files":
            _, peer, name = action
            paths = self.pick_files(name)
            if paths:
                self.actions.run(("send-files", peer, name, paths))
            return
        self.actions.run(action)


def listen(on_message):
    """The single-instance socket: an older menu bar icon is told to quit first."""
    from PySide6.QtNetwork import QLocalServer
    from . import macos
    from .app.main import listen as qt_listen
    path = str(macos.menu_socket())
    if macos.stop_menu():
        log.info("menu: replaced the menu bar icon that was running")
    try:
        macos.menu_socket().parent.mkdir(parents=True, exist_ok=True)
    except OSError:
        pass
    QLocalServer.removeServer(path)
    return qt_listen(path, on_message)


def run(argv=None) -> int:
    from . import macos
    app = QApplication.instance() or QApplication(list(argv or [sys.argv[0]]))
    app.setApplicationName("droplet")
    app.setQuitOnLastWindowClosed(False)   # file dialogs close; the menu bar icon stays
    macos.hide_dock_icon()
    if not QSystemTrayIcon.isSystemTrayAvailable():
        print("There's no menu bar to show droplet in (is this a desktop session?).", file=sys.stderr)
        return 1

    def message(msg: dict):
        if msg.get("cmd") == "quit":
            log.info("menu: asked to quit (a newer one is starting)")
            app.quit()
    server = listen(message)
    bar = MenuBar()
    bar.icon.show()
    bar.kick()
    log.info("menu: in the menu bar")
    code = app.exec()
    server.close()
    bar.icon.hide()
    return code
