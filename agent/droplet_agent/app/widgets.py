"""Small pieces the pages share: icons, the look, cards, buttons."""

from __future__ import annotations

import os
import sys
from pathlib import Path

from PySide6.QtCore import QSize, Qt
from PySide6.QtGui import QColor, QFont, QGuiApplication, QIcon, QPainter, QPalette, QPixmap
from PySide6.QtWidgets import (QApplication, QFrame, QHBoxLayout, QLabel, QPushButton, QSizePolicy,
                               QVBoxLayout, QWidget)

from . import ACCENT

GOOD = "#22C55E"      # the dot of a connected device
ICON_THEMES = ("breeze", "breeze-dark", "Adwaita")
# text in those colours: darker on a light window, lighter on a dark one, so it reads
TEXT = {"good": ("#15803D", "#4ADE80"), "warn": ("#B45309", "#FBBF24"), "bad": ("#B91C1C", "#F87171")}


def text_color(tone: str, widget: QWidget | None = None) -> str:
    return TEXT[tone][1 if is_dark(widget) else 0]


def dark_palette() -> QPalette:
    """Fusion's colours for a dark desktop, for when Qt only knows the desktop wants dark."""
    pal = QPalette()
    roles = QPalette.ColorRole
    for role, color in ((roles.Window, "#202326"), (roles.WindowText, "#fcfcfc"), (roles.Base, "#141618"),
                        (roles.AlternateBase, "#1d1f22"), (roles.Text, "#fcfcfc"), (roles.Button, "#292c30"),
                        (roles.ButtonText, "#fcfcfc"), (roles.ToolTipBase, "#292c30"),
                        (roles.ToolTipText, "#fcfcfc"), (roles.PlaceholderText, "#8a8e93"),
                        (roles.Highlight, "#3daee9"), (roles.HighlightedText, "#fcfcfc"), (roles.Link, ACCENT),
                        (roles.Mid, "#3a3e43"), (roles.Dark, "#151719"), (roles.Light, "#3a3e43"),
                        (roles.Midlight, "#33373b"), (roles.Shadow, "#0b0c0d"), (roles.BrightText, "#ffffff")):
        pal.setColor(role, QColor(color))
    for role in (roles.WindowText, roles.Text, roles.ButtonText):
        pal.setColor(QPalette.ColorGroup.Disabled, role, QColor("#6e7175"))
    return pal


def is_dark(widget: QWidget | None = None) -> bool:
    pal = widget.palette() if widget is not None else QGuiApplication.palette()
    return pal.color(QPalette.ColorRole.Window).lightness() < 128


def use_icon_theme():
    """The bundled Qt doesn't know the desktop's icon theme: use Breeze (or Adwaita) if it's there."""
    if QIcon.themeName() not in ("", "hicolor", *ICON_THEMES):
        return   # the desktop told Qt its theme
    dirs = [Path(d) / "icons" for d in (os.environ.get("XDG_DATA_DIRS") or "/usr/local/share:/usr/share").split(":")]
    dirs.insert(0, Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share") / "icons")
    paths = [str(d) for d in dirs if d.is_dir()]
    QIcon.setThemeSearchPaths(paths + [p for p in QIcon.themeSearchPaths() if p not in paths])
    names = list(ICON_THEMES)
    if is_dark():
        names.insert(0, "breeze-dark")
    for name in names:
        if any((d / name / "index.theme").exists() for d in dirs):
            QIcon.setThemeName(name)
            return


def icon(*names: str) -> QIcon:
    for n in names:
        if QIcon.hasThemeIcon(n):
            return QIcon.fromTheme(n)
    return QIcon()


def app_icon() -> QIcon:
    """The drop, from the tray's pixels."""
    from .. import tray
    ic = QIcon()
    for w, h, px in tray.load_pixmaps():
        pm = QPixmap()
        if pm.loadFromData(tray.png(w, h, px), "PNG"):
            ic.addPixmap(pm)
    if sys.platform == "darwin":
        # the Dock shows it large
        from .. import macos
        pm = QPixmap(str(macos.ICON_FILE))
        if not pm.isNull():
            ic.addPixmap(pm)
    return ic


def dot(color: str, size: int = 10) -> QPixmap:
    ratio = QGuiApplication.primaryScreen().devicePixelRatio() if QGuiApplication.primaryScreen() else 1
    pm = QPixmap(int(size * ratio), int(size * ratio))
    pm.setDevicePixelRatio(ratio)
    pm.fill(Qt.GlobalColor.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(QColor(color))
    p.drawEllipse(1, 1, size - 2, size - 2)
    p.end()
    return pm


def device_icon(os_name: str | None, phone: bool) -> QIcon:
    if phone:
        return icon("smartphone", "phone", "multimedia-player")
    if (os_name or "") == "windows":
        return icon("computer-laptop", "computer", "video-display")
    return icon("computer", "computer-laptop", "video-display")


def font(size_delta: float = 0, bold: bool = False, base: QFont | None = None) -> QFont:
    f = QFont(base or QApplication.font())
    if size_delta:
        f.setPointSizeF(max(6.0, f.pointSizeF() + size_delta))
    f.setBold(bold)
    return f


def label(text: str = "", *, size: float = 0, bold: bool = False, muted: bool = False, wrap: bool = False,
          selectable: bool = False) -> QLabel:
    lb = QLabel(text)
    if size or bold:
        lb.setFont(font(size, bold))
    if muted:
        lb.setProperty("muted", True)
    if wrap:
        lb.setWordWrap(True)
    if selectable:
        lb.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
    return lb


def title(text: str) -> QLabel:
    return label(text, size=5, bold=True)


def primary(text: str) -> QPushButton:
    """The one button that matters on a screen, in droplet's blue."""
    b = QPushButton(text)
    b.setProperty("primary", True)
    b.setDefault(False)
    b.setAutoDefault(False)
    return b


def button(text: str, icon_names=()) -> QPushButton:
    b = QPushButton(text)
    if icon_names:
        b.setIcon(icon(*icon_names))
    b.setAutoDefault(False)
    return b


class Card(QFrame):
    """A rounded box on the page's background."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("card")
        self.setFrameShape(QFrame.Shape.NoFrame)
        self.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Maximum)


def hbox(*items, spacing: int = 8, margins=(0, 0, 0, 0)) -> QHBoxLayout:
    lay = QHBoxLayout()
    lay.setSpacing(spacing)
    lay.setContentsMargins(*margins)
    for it in items:
        if it is None:
            lay.addStretch(1)
        elif isinstance(it, int):
            lay.addSpacing(it)
        elif isinstance(it, QWidget):
            lay.addWidget(it)
        else:
            lay.addLayout(it)
    return lay


def vbox(*items, spacing: int = 8, margins=(0, 0, 0, 0)) -> QVBoxLayout:
    lay = QVBoxLayout()
    lay.setSpacing(spacing)
    lay.setContentsMargins(*margins)
    for it in items:
        if it is None:
            lay.addStretch(1)
        elif isinstance(it, int):
            lay.addSpacing(it)
        elif isinstance(it, QWidget):
            lay.addWidget(it)
        else:
            lay.addLayout(it)
    return lay


def icon_label(ic: QIcon, size: int) -> QLabel:
    lb = QLabel()
    lb.setPixmap(ic.pixmap(QSize(size, size)))
    lb.setFixedSize(size, size)
    return lb


def style_sheet(dark: bool) -> str:
    """The few things the system style doesn't do: cards, the sidebar, the blue buttons."""
    card = "rgba(255,255,255,0.05)" if dark else "#ffffff"
    border = "rgba(255,255,255,0.10)" if dark else "rgba(0,0,0,0.10)"
    side = "rgba(255,255,255,0.03)" if dark else "rgba(0,0,0,0.035)"
    muted = "rgba(255,255,255,0.62)" if dark else "rgba(0,0,0,0.58)"
    banner = "rgba(245,158,11,0.16)" if dark else "#FFF4DE"
    sel = "rgba(56,189,248,0.22)" if dark else "rgba(56,189,248,0.18)"
    bubble_in = "rgba(255,255,255,0.08)" if dark else "#EEF1F4"
    bubble_out = "rgba(56,189,248,0.28)" if dark else "#D6F0FD"
    return f"""
    QFrame#card {{ background: {card}; border: 1px solid {border}; border-radius: 10px; }}
    QFrame#card[drop="true"] {{ border: 2px solid {ACCENT}; }}
    QFrame#sidebar {{ background: {side}; border: none; border-right: 1px solid {border}; }}
    QListWidget#nav {{ background: transparent; border: none; outline: none; }}
    QListWidget#nav::item {{ padding: 7px 10px; border-radius: 7px; margin: 1px 0; }}
    QListWidget#nav::item:selected {{ background: {sel}; color: palette(text); }}
    QListWidget#nav::item:hover:!selected {{ background: {side}; }}
    QLabel[muted="true"] {{ color: {muted}; }}
    QPushButton[primary="true"] {{ background: {ACCENT}; color: #06263a; border: 1px solid #0EA5E9;
        border-radius: 6px; padding: 6px 14px; font-weight: 600; }}
    QPushButton[primary="true"]:hover {{ background: #7DD3FC; }}
    QPushButton[primary="true"]:pressed {{ background: #0EA5E9; }}
    QPushButton[primary="true"]:disabled {{ background: {border}; color: {muted}; border-color: {border}; }}
    QFrame#banner {{ background: {banner}; border: 1px solid rgba(245,158,11,0.55); border-radius: 10px; }}
    QFrame#bubble_in {{ background: {bubble_in}; border-radius: 12px; }}
    QFrame#bubble_out {{ background: {bubble_out}; border-radius: 12px; }}
    QScrollArea {{ background: transparent; border: none; }}
    QToolButton::menu-indicator {{ image: none; width: 0; }}
    QScrollArea > QWidget > QWidget#scrollbody {{ background: transparent; }}
    QLabel#code {{ color: {"#7DD3FC" if dark else "#0369A1"}; }}
    """
