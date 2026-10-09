"""Settings: this computer, what your devices may do here, the tray, and about.

The agent reads its settings when it starts, so a change is saved to its
config.json (the same file `droplet-agent` and hand edits use) and the
agent is restarted to use it.
"""

from __future__ import annotations

import shutil
import subprocess
import sys

from PySide6.QtCore import Qt, QUrl
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import QCheckBox, QFormLayout, QGroupBox, QScrollArea, QSizePolicy, QWidget

from . import GITHUB, model
from .widgets import hbox, label, primary, title, vbox

# (where in config.json, label): what the switches change
SWITCHES = [
    (("caps", "clipboard"), "Clipboard sync with your devices"),
    (("mesh", "phone_notifications"), "Show my phone's notifications"),
    (("caps", "input"), "Mouse and keyboard"),
    (("caps", "media"), "Media and volume, and what's playing"),
    (("caps", "lock"), "Lock this computer"),
    (("caps", "screenshot"), "Screenshots"),
]


def read_switches(cfg: dict) -> dict:
    out = {}
    for (section, key), _ in SWITCHES:
        default = True
        out[(section, key)] = bool((cfg.get(section) or {}).get(key, default))
    return out


def apply_switches(cfg: dict, values: dict) -> dict:
    for (section, key), on in values.items():
        cfg.setdefault(section, {})
        if not isinstance(cfg[section], dict):
            cfg[section] = {}
        cfg[section][key] = bool(on)
    return cfg


MAC = sys.platform == "darwin"


def restart_agent() -> tuple[bool, str]:
    """Restart the agent's service so it reads the settings again."""
    if MAC:
        from .. import macos
        return macos.restart_service()
    if not shutil.which("systemctl"):
        return False, "Saved. Restart the agent to use the new settings."
    r = subprocess.run(["systemctl", "--user", "restart", "droplet-agent"], capture_output=True, text=True,
                       timeout=30)
    if r.returncode != 0:
        why = (r.stderr or r.stdout).strip().splitlines()
        return False, "Saved, but the agent didn't restart" + (f": {why[-1]}" if why else ".")
    return True, "Saved. The agent restarted with the new settings."


class SettingsPage(QWidget):
    def __init__(self, win, restart=restart_agent):
        super().__init__()
        self.win = win
        self.restart = restart
        self.saved: dict = {}
        self.boxes: dict = {}

        # this computer
        self.name = label(selectable=True)
        self.ident = label(selectable=True)
        self.fp = label(selectable=True, wrap=True)
        self.fp.setTextFormat(Qt.TextFormat.PlainText)
        self.hub = label(wrap=True)
        self.folder = label(selectable=True, wrap=True)
        me = QGroupBox("This computer")
        form = QFormLayout()
        form.setFieldGrowthPolicy(QFormLayout.FieldGrowthPolicy.AllNonFixedFieldsGrow)
        form.addRow("Name", self.name)
        form.addRow("Id", self.ident)
        form.addRow("Fingerprint", self.fp)
        form.addRow("Hub", self.hub)
        form.addRow("Received files", self.folder)
        note = label("Its name comes from this computer's name. Paired devices check the fingerprint.",
                     muted=True, wrap=True)
        me.setLayout(vbox(form, note, spacing=8, margins=(12, 12, 12, 12)))

        # what devices may do
        def box(key, text):
            cb = QCheckBox(text)
            cb.toggled.connect(self._changed)
            self.boxes[key] = cb
            return cb
        everyday = QGroupBox("Clipboard and notifications")
        everyday.setLayout(vbox(*(box(k, t) for k, t in SWITCHES[:2]), spacing=6, margins=(12, 12, 12, 12)))
        remote = QGroupBox("Remote control")
        remote.setLayout(vbox(label("What your paired devices may do on this computer.", muted=True, wrap=True),
                              *(box(k, t) for k, t in SWITCHES[2:]), spacing=6, margins=(12, 12, 12, 12)))
        self.apply = primary("Save and restart the agent")
        self.apply.clicked.connect(self.save)
        self.apply.setSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
        self.apply_note = label("The agent restarts to use these, which takes a few seconds.", muted=True,
                                wrap=True)

        # the tray
        self.tray = QCheckBox("Show droplet in the menu bar, from when you log in" if MAC
                              else "Show droplet in the system tray, from when you sign in")
        self.tray.toggled.connect(self._tray)
        tray_box = QGroupBox("Menu bar" if MAC else "System tray")
        tray_box.setLayout(vbox(self.tray, spacing=6, margins=(12, 12, 12, 12)))

        # about
        from .. import __version__
        about = QGroupBox("About")
        link = label(f'<a href="{GITHUB}">{GITHUB.removeprefix("https://")}</a>')
        link.setOpenExternalLinks(False)
        link.linkActivated.connect(lambda url: QDesktopServices.openUrl(QUrl(url)))
        about.setLayout(vbox(label(f"Droplet {__version__} for {'Mac' if MAC else 'Linux'}", bold=True),
                             label("Your devices, together, on your own network.", muted=True), link,
                             spacing=4, margins=(12, 12, 12, 12)))

        body = QWidget()
        body.setObjectName("scrollbody")
        body.setLayout(vbox(me, everyday, remote, hbox(self.apply_note, self.apply), tray_box, about, None,
                            spacing=14, margins=(0, 0, 8, 0)))
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setWidget(body)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setLayout(vbox(title("Settings"), scroll, spacing=12, margins=(24, 20, 24, 16)))
        self.load()

    # --- reading ---
    def load(self):
        from .. import config, tray
        try:
            cfg = config.load()
        except (OSError, ValueError) as e:
            self.win.say(f"Couldn't read the settings: {e}")
            cfg = config._merge(config.DEFAULTS, {})
        self.saved = read_switches(cfg)
        for key, cb in self.boxes.items():
            cb.blockSignals(True)
            cb.setChecked(self.saved.get(key, True))
            cb.blockSignals(False)
        hub = cfg.get("hub") if config.is_set_up(cfg) else ""
        self.hub.setText(f"Linked to {hub}" if hub else "None: your devices pair with this computer directly")
        self.folder.setText(str(config.downloads_dir(cfg)))
        self.tray.blockSignals(True)
        self.tray.setChecked(tray.autostart_path().exists())
        self.tray.blockSignals(False)
        self._changed()

    def set_status(self, status: dict | None):
        if not status:
            return
        self.name.setText(str(status.get("name") or ""))
        self.ident.setText(str(status.get("id") or ""))
        self.fp.setText(model.grouped_fp(str(status.get("fp") or ""), per_line=8))

    # --- changing ---
    def values(self) -> dict:
        return {key: cb.isChecked() for key, cb in self.boxes.items()}

    def _changed(self, *_):
        dirty = self.values() != self.saved
        self.apply.setEnabled(dirty)
        self.apply_note.setText("Not saved yet. The agent restarts to use them, which takes a few seconds."
                                if dirty else "Changes are saved when you press the button.")

    def save(self):
        from .. import config
        try:
            cfg = config.load()
            config.save(apply_switches(cfg, self.values()))
        except (OSError, ValueError) as e:
            self.win.say(f"Couldn't save the settings: {e}")
            return
        self.saved = self.values()
        self._changed()
        self.apply.setEnabled(False)
        self.win.say("Saved. Restarting the agent…")
        self.win.agent.run(self.restart, self._restarted)

    def _restarted(self, result):
        ok, text = result if isinstance(result, tuple) else (False, str(getattr(result, "error", result)))
        self.win.say(text)
        self.win.refresh_soon()

    def _tray(self, on: bool):
        from .. import tray

        def change():
            if on:
                tray.install_launcher()
                tray.enable_autostart()
                if not tray.is_running():
                    tray.start_detached()
                return ("droplet is in the menu bar, and starts there when you log in." if MAC
                        else "droplet is in the system tray, and starts there when you sign in.")
            tray.disable_autostart()
            tray.stop_running()
            return ("The menu bar icon is closed, and won't start when you log in." if MAC
                    else "The tray icon is closed, and won't start when you sign in.")

        def done(result):
            self.win.say(result if isinstance(result, str) else f"Couldn't change that: {result.error}")
        self.win.agent.run(change, done)
