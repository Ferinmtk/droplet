"""Received: the latest files your devices sent, to open or find."""

from __future__ import annotations

import sys
from pathlib import Path

from PySide6.QtCore import QMimeDatabase, Qt, QUrl
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import QScrollArea, QVBoxLayout, QWidget

from . import model
from .widgets import Card, button, hbox, icon, icon_label, label, title, vbox


def file_icon(name: str):
    mime = QMimeDatabase().mimeTypeForFile(name, QMimeDatabase.MatchMode.MatchExtension)
    return icon(mime.iconName(), mime.genericIconName(), "text-x-generic", "unknown")


def show_in_folder(path: Path) -> bool:
    """Open the file manager on the file's folder, with the file selected where it can."""
    if sys.platform == "darwin":
        from .. import macos
        return macos.open_path(path, reveal=True)   # Finder, with the file selected
    try:
        from PySide6.QtDBus import QDBusConnection, QDBusMessage
        msg = QDBusMessage.createMethodCall("org.freedesktop.FileManager1", "/org/freedesktop/FileManager1",
                                            "org.freedesktop.FileManager1", "ShowItems")
        msg.setArguments([[QUrl.fromLocalFile(str(path)).toString()], ""])
        reply = QDBusConnection.sessionBus().call(msg, timeout=3000)
        if reply.type() != QDBusMessage.MessageType.ErrorMessage:
            return True
    except Exception:
        pass
    return QDesktopServices.openUrl(QUrl.fromLocalFile(str(path.parent)))


class FileRow(Card):
    def __init__(self, page: "ReceivedPage", f: dict):
        super().__init__()
        path = Path(str(f.get("path") or ""))
        name = label(str(f.get("name") or path.name), bold=True)
        name.setTextFormat(Qt.TextFormat.PlainText)
        line = label(model.received_line(f), muted=True)
        opener = button("Open")
        finder = button("Show in folder")
        opener.clicked.connect(lambda: page.open_file(path))
        finder.clicked.connect(lambda: page.show_file(path))
        gone = f.get("exists") is False
        opener.setEnabled(not gone)
        finder.setEnabled(not gone)
        self.setLayout(hbox(icon_label(file_icon(path.name), 32), vbox(name, line, spacing=2), None, opener,
                            finder, spacing=10, margins=(14, 10, 12, 10)))
        self.setToolTip(str(path))


class ReceivedPage(QWidget):
    def __init__(self, win):
        super().__init__()
        self.win = win
        self.folder: str | None = None
        self._shape = None
        folder = button("Open received folder", ("folder-download", "folder"))
        folder.clicked.connect(self.open_folder)
        self.where = label(muted=True, selectable=True)
        self.rows = QVBoxLayout()
        self.rows.setSpacing(8)
        body = QWidget()
        body.setObjectName("scrollbody")
        body.setLayout(vbox(self.rows, None, spacing=0))
        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setWidget(body)
        self.scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.empty = label("Nothing received yet. Files your devices send you land in the received folder.",
                           muted=True, wrap=True)
        self.empty.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setLayout(vbox(hbox(vbox(title("Received"), self.where, spacing=2), None, folder), 6, self.scroll,
                            self.empty, spacing=8, margins=(24, 20, 24, 16)))
        self.layout().setStretchFactor(self.scroll, 1)
        self.layout().setStretchFactor(self.empty, 1)

    def load(self):
        def got(r):
            if r.ok:
                self.show_files(r.data)
            elif r.not_running:
                self.win.agent_gone()
        self.win.agent.ask({"cmd": "received", "n": 50}, got)

    def show_files(self, data: dict):
        self.folder = data.get("folder")
        self.where.setText(f"Saved in {self.folder}" if self.folder else "")
        files = [f for f in data.get("files") or [] if isinstance(f, dict)]
        shape = [(f.get("path"), f.get("exists"), f.get("ts")) for f in files]
        if shape != self._shape:
            self._shape = shape
            while self.rows.count():
                w = self.rows.takeAt(0).widget()
                if w is not None:
                    w.setParent(None)
                    w.deleteLater()
            for f in files:
                self.rows.addWidget(FileRow(self, f))
        self.scroll.setVisible(bool(files))
        self.empty.setVisible(not files)

    def open_file(self, path: Path):
        if not path.exists():
            self.win.say(f"{path.name} isn't there any more.")
            return
        QDesktopServices.openUrl(QUrl.fromLocalFile(str(path)))

    def show_file(self, path: Path):
        if not path.exists():
            self.win.say(f"{path.name} isn't there any more.")
            return
        show_in_folder(path)

    def open_folder(self):
        folder = Path(self.folder).expanduser() if self.folder else None
        if folder is None:
            from .. import config
            folder = config.downloads_dir(config.load())
        folder.mkdir(parents=True, exist_ok=True)
        QDesktopServices.openUrl(QUrl.fromLocalFile(str(folder)))
