"""Starting the app: one window per session. Opening it again brings that window up."""

from __future__ import annotations

import argparse
import json
import os
import sys

from PySide6.QtCore import QByteArray, Qt
from PySide6.QtGui import QGuiApplication
from PySide6.QtNetwork import QLocalServer, QLocalSocket
from PySide6.QtWidgets import QApplication, QStyleFactory

from . import APP_NAME, DESKTOP_ID
from .widgets import app_icon, dark_palette, is_dark, style_sheet, use_icon_theme


def server_name() -> str:
    """Where the running window listens for another launch: next to the agent's control socket."""
    from ..mesh import control
    d = control.socket_path().parent
    try:
        d.mkdir(parents=True, exist_ok=True)
        os.chmod(d, 0o700)
    except OSError:
        return f"droplet-app-{os.getuid()}"
    return str(d / "app.sock")


def hand_over(name: str, message: dict) -> bool:
    """Tell the window that's already open to come up. True if there is one."""
    sock = QLocalSocket()
    sock.connectToServer(name)
    if not sock.waitForConnected(1000):
        return False
    sock.write(QByteArray(json.dumps(message).encode() + b"\n"))
    sock.flush()
    sock.waitForBytesWritten(1000)
    sock.disconnectFromServer()
    return True


def listen(name: str, on_message) -> QLocalServer:
    server = QLocalServer()
    server.setSocketOptions(QLocalServer.SocketOption.UserAccessOption)
    if not server.listen(name):
        # a window that crashed leaves its socket behind
        QLocalServer.removeServer(name)
        server.listen(name)

    def arrived():
        while server.hasPendingConnections():
            conn = server.nextPendingConnection()

            def read(conn=conn):
                if not conn.canReadLine():
                    return
                try:
                    msg = json.loads(bytes(conn.readLine().data()).decode() or "{}")
                except ValueError:
                    msg = {}
                on_message(msg if isinstance(msg, dict) else {})
                conn.disconnectFromServer()
            conn.readyRead.connect(read)
            conn.disconnected.connect(conn.deleteLater)
            read()
    server.newConnection.connect(arrived)
    return server


def make_app(argv=None) -> QApplication:
    if sys.platform == "darwin" and QApplication.instance() is None:
        from .. import macos
        macos.set_app_name(APP_NAME)   # "Droplet" in the menu bar and the Dock, not "Python"
    app = QApplication.instance() or QApplication(list(argv or [sys.argv[0]]))
    QGuiApplication.setApplicationDisplayName(APP_NAME)
    QGuiApplication.setDesktopFileName(DESKTOP_ID)
    app.setApplicationName("droplet")
    app.setWindowIcon(app_icon())
    # PySide6 brings its own Qt, which can't load the desktop's style plugin; Fusion follows its colours
    if "fusion" in [s.lower() for s in QStyleFactory.keys()] and app.style().name().lower() in ("windows", ""):
        app.setStyle("Fusion")
    follow_scheme(app)
    app.styleHints().colorSchemeChanged.connect(lambda *_: follow_scheme(app))
    return app


def follow_scheme(app: QApplication):
    """Dark when the desktop is, even where Qt knows the scheme but not the colours."""
    scheme = app.styleHints().colorScheme()
    if scheme == Qt.ColorScheme.Dark and not is_dark():
        app.setPalette(dark_palette())
        app.setProperty("droplet_dark", True)
    elif scheme == Qt.ColorScheme.Light and app.property("droplet_dark"):
        app.setPalette(app.style().standardPalette())
        app.setProperty("droplet_dark", False)
    app.setStyleSheet(style_sheet(is_dark()))
    use_icon_theme()


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="droplet-agent app", description="Open Droplet's window.")
    p.add_argument("--page", choices=["devices", "pair", "iphone", "messages", "received", "settings"],
                   help="open on this page")
    p.add_argument("--demo", action="store_true", help="show pretend devices, without the agent")
    args = p.parse_args(argv)

    app = make_app()
    name = server_name() + ("-demo" if args.demo else "")
    if hand_over(name, {"cmd": "raise", "page": args.page, "token": os.environ.get("XDG_ACTIVATION_TOKEN")}):
        return 0

    from .window import Window
    call = None
    if args.demo:
        from .demo import DemoAgent
        call = DemoAgent().call
    win = Window(call)

    def message(msg: dict):
        if msg.get("cmd") != "raise":
            return
        token = msg.get("token")
        if token:
            # Wayland lets a window take focus with the launcher's activation token
            os.environ["XDG_ACTIVATION_TOKEN"] = str(token)
        win.bring_up(msg.get("page"))

    server = listen(name, message)
    win.show()
    if args.page:
        win.go(args.page)
    code = app.exec()
    server.close()
    return code
