"""Droplet's desktop app: `droplet-agent app`.

A window (Qt, through PySide6) with your devices, pairing, messages, what
arrived, and settings. Like the tray, it's a separate process that does
everything through the running agent's control socket; it never talks to
other devices itself.

PySide6 is optional (`pip install 'droplet-agent[app]'`): without it, the
agent, the CLI and the tray work as before, and opening Droplet from the app
menu shows where the tray is instead.
"""

from __future__ import annotations

import importlib.util

APP_NAME = "Droplet"
DESKTOP_ID = "io.github.ferinmtk.Droplet"   # the launcher's .desktop file, so Wayland shows its icon
ACCENT = "#38BDF8"                          # droplet's blue, as on Android
GITHUB = "https://github.com/Ferinmtk/droplet"


def available() -> bool:
    """Whether PySide6 is installed, without loading Qt."""
    try:
        return importlib.util.find_spec("PySide6") is not None
    except (ImportError, ValueError):
        return False


def missing_text() -> str:
    import sys
    return ("Droplet's window needs PySide6, which isn't installed with this agent. Add it with:\n"
            f"  {sys.executable} -m pip install 'droplet-agent[app]'\n"
            "(or run the installer again from a desktop session).")


def run(argv=None) -> int:
    from .main import main
    return main(argv)
