"""Locking the screen.

Desktops with their own lock screen (KDE, GNOME, Cinnamon, …) lock when
logind asks, so `loginctl lock-session` is right there. Compositors like niri
and sway have nothing listening for that, so a screen locker is started
directly, in a way that outlives the agent (see env.spawn_detached).

A Mac locks through the login framework's SACLockScreenImmediate (what the
menu bar's Lock Screen does), in a separate process; CGSession -suspend on
systems that still have it; or, failing both, by putting the display to
sleep, which locks when a password is required after sleep.
"""

from __future__ import annotations

import logging
import os
import sys
from pathlib import Path

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

# macOS
MAC_LOGIN_FRAMEWORK = Path("/System/Library/PrivateFrameworks/login.framework/Versions/Current/login")
MAC_CGSESSION = Path("/System/Library/CoreServices/Menu Extras/User.menu/Contents/Resources/CGSession")
MAC_LOCK = "lock-screen"   # SACLockScreenImmediate, through MAC_LOGIN_FRAMEWORK


def _detect_mac() -> tuple[list[str] | None, str]:
    if MAC_LOGIN_FRAMEWORK.exists():
        return [MAC_LOCK], "the login framework's lock screen"
    if MAC_CGSESSION.exists():
        return [str(MAC_CGSESSION), "-suspend"], "CGSession"
    if env.which("pmset"):
        return ["pmset", "displaysleepnow"], "display sleep (locks if a password is required right after sleep)"
    return None, "no way to lock this Mac found"


def _lock_mac(command: list[str]) -> str | None:
    if command == [MAC_LOCK]:
        # its own process: a crash in a private framework can't take the agent with it
        code = ("import ctypes, sys; "
                f"ctypes.cdll.LoadLibrary({str(MAC_LOGIN_FRAMEWORK)!r}).SACLockScreenImmediate()")
        argv = [sys.executable, "-c", code]
    else:
        argv = command
    r = env.run(argv, timeout=10)
    if r is None:
        return f"{argv[0]} didn't run"
    if r.returncode != 0:
        return r.stderr.decode(errors="replace").strip() or f"exit status {r.returncode}"
    return None


def detect(configured=None) -> tuple[list[str] | None, str]:
    """(command, how it was chosen); command None when there's no way to lock."""
    if configured:
        if (not isinstance(configured, list) or not configured
                or not all(isinstance(a, str) and a for a in configured)):
            return None, "lock_command in the config must be a list like [\"swaylock\", \"-f\"]"
        if not env.which(configured[0]):
            return None, f"the configured locker {configured[0]} isn't installed"
        return list(configured), "from the config"
    if sys.platform == "darwin":
        return _detect_mac()
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
    if sys.platform == "darwin" and command and command[0] in (MAC_LOCK, str(MAC_CGSESSION), "pmset"):
        return _lock_mac(command)
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
