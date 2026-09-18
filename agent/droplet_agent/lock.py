"""Locking the screen.

Desktops with their own lock screen (KDE, GNOME, Cinnamon, …) lock when
logind asks, so `loginctl lock-session` is right there. Compositors like niri
and sway have nothing listening for that, so a screen locker is started
directly, in a way that outlives the agent (see env.spawn_detached).
"""

from __future__ import annotations

import logging
import os

from . import env

log = logging.getLogger("droplet_agent.lock")

# desktops whose session answers logind's Lock signal
LOGIND_DESKTOPS = {"kde", "gnome", "cinnamon", "mate", "xfce", "budgie", "lxqt", "cosmic", "deepin", "pantheon"}

# lockers, best first; each backgrounds itself or is fine being detached
WAYLAND_LOCKERS = (
    ("gtklock", ["gtklock", "-d"]),
    ("swaylock", ["swaylock", "-f"]),
    ("hyprlock", ["hyprlock"]),
    ("waylock", ["waylock"]),
)
X11_LOCKERS = (
    ("xdg-screensaver", ["xdg-screensaver", "lock"]),
    ("i3lock", ["i3lock"]),
    ("xsecurelock", ["xsecurelock"]),
    ("slock", ["slock"]),
)

LOGIND = "loginctl"


def detect(configured=None) -> tuple[list[str] | None, str]:
    """(command, how it was chosen); command None when there's no way to lock."""
    if configured:
        if (not isinstance(configured, list) or not configured
                or not all(isinstance(a, str) and a for a in configured)):
            return None, "lock_command in the config must be a list like [\"swaylock\", \"-f\"]"
        if not env.which(configured[0]):
            return None, f"the configured locker {configured[0]} isn't installed"
        return list(configured), "from the config"
    desks = env.desktops()
    if desks & LOGIND_DESKTOPS and env.which("loginctl"):
        return [LOGIND], f"loginctl ({'/'.join(sorted(desks & LOGIND_DESKTOPS))} listens for it)"
    for name, argv in (WAYLAND_LOCKERS if env.is_wayland() else X11_LOCKERS):
        if env.which(name):
            return argv, name
    if env.which("loginctl") and not desks:
        # unknown desktop: logind is the best remaining guess
        return [LOGIND], "loginctl (desktop not recognised, so this may do nothing)"
    return None, "no screen locker found (install gtklock or swaylock, or set lock_command)"


def display_session() -> str | None:
    """The user's graphical logind session. A systemd service isn't in one,
    so a bare `loginctl lock-session` wouldn't know which to lock."""
    r = env.run(["loginctl", "show-user", str(os.getuid()), "--property=Display", "--value"])
    if r is not None and r.returncode == 0:
        sid = r.stdout.decode().strip()
        if sid:
            return sid
    # the service's environment may carry one; it can be stale, hence second
    return os.environ.get("XDG_SESSION_ID") or None


def lock(command: list[str]) -> str | None:
    """Lock the screen. Returns why it failed, or None."""
    if command == [LOGIND]:
        sid = display_session()
        argv = ["loginctl", "lock-session"] + ([sid] if sid else [])
        r = env.run(argv, timeout=10)
        if r is None:
            return "loginctl didn't run"
        if r.returncode != 0:
            return r.stderr.decode(errors="replace").strip() or "loginctl failed"
        return None
    return env.spawn_detached(command)
