"""What kind of desktop this is, and how to reach it.

The agent usually runs as a systemd user service, whose environment only has
what the desktop exported to it. Everything here copes with a missing
variable by looking at the session itself (sockets in the runtime dir,
running compositors) rather than guessing.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

# process names of compositors and desktops, as /proc/<pid>/comm shows them
# (comm is cut to 15 characters)
COMPOSITORS = {
    "kwin_wayland": "kde",
    "kwin_x11": "kde",
    "plasmashell": "kde",
    "gnome-shell": "gnome",
    "niri": "niri",
    "sway": "sway",
    "Hyprland": "hyprland",
    "river": "river",
    "labwc": "labwc",
    "wayfire": "wayfire",
    "cosmic-comp": "cosmic",
    "xfce4-session": "xfce",
    "cinnamon": "cinnamon",
    "mate-session": "mate",
}


def which(name: str) -> str | None:
    return shutil.which(name)


def runtime_dir() -> Path:
    return Path(os.environ.get("XDG_RUNTIME_DIR") or f"/run/user/{os.getuid()}")


def wayland_display() -> str | None:
    """The Wayland socket to use, found in the runtime dir if the env lacks it."""
    if os.environ.get("WAYLAND_DISPLAY"):
        return os.environ["WAYLAND_DISPLAY"]
    try:
        sockets = sorted(p.name for p in runtime_dir().glob("wayland-*")
                         if p.is_socket() and not p.name.endswith(".lock"))
    except OSError:
        return None
    return sockets[0] if sockets else None


def session_env() -> dict:
    """os.environ plus the desktop's display variables, for launching desktop tools.

    Same idea as the hub's clipboard.wayland_env(), which can't be shared
    because that module needs Flask.
    """
    env = dict(os.environ)
    env.setdefault("XDG_RUNTIME_DIR", str(runtime_dir()))
    wl = wayland_display()
    if wl and not env.get("WAYLAND_DISPLAY"):
        env["WAYLAND_DISPLAY"] = wl
    if not env.get("DBUS_SESSION_BUS_ADDRESS"):
        bus = runtime_dir() / "bus"
        if bus.exists():
            env["DBUS_SESSION_BUS_ADDRESS"] = f"unix:path={bus}"
    return env


def is_wayland() -> bool:
    if os.environ.get("XDG_SESSION_TYPE") == "x11" and not os.environ.get("WAYLAND_DISPLAY"):
        return False
    return wayland_display() is not None


def x11_display() -> str | None:
    """$DISPLAY, but only on a real X11 session.

    On Wayland, $DISPLAY is Xwayland: xdotool there reaches only X11 apps,
    so it doesn't count.
    """
    if is_wayland():
        return None
    return os.environ.get("DISPLAY") or None


def _running_compositors() -> set[str]:
    found = set()
    uid = os.getuid()
    try:
        entries = os.listdir("/proc")
    except OSError:
        return found
    for pid in entries:
        if not pid.isdigit():
            continue
        try:
            if os.stat(f"/proc/{pid}").st_uid != uid:
                continue
            with open(f"/proc/{pid}/comm") as f:
                comm = f.read().strip()
        except OSError:
            continue
        if comm in COMPOSITORS:
            found.add(COMPOSITORS[comm])
    return found


def desktops() -> set[str]:
    """Lower-case desktop names: {"kde"}, {"gnome"}, {"niri"}, … (may be empty)."""
    names = set()
    for part in (os.environ.get("XDG_CURRENT_DESKTOP") or "").split(":"):
        part = part.strip().lower()
        if part:
            names.add({"plasma": "kde", "ubuntu": "gnome", "unity": "gnome"}.get(part, part))
    if os.environ.get("NIRI_SOCKET"):
        names.add("niri")
    if os.environ.get("SWAYSOCK"):
        names.add("sway")
    if os.environ.get("HYPRLAND_INSTANCE_SIGNATURE"):
        names.add("hyprland")
    if not names:
        names = _running_compositors()
    return names


def run(argv: list[str], *, timeout: float = 5, input: bytes | None = None,
        env: dict | None = None) -> subprocess.CompletedProcess | None:
    """Run a desktop tool quietly; None when it's missing or hung."""
    try:
        return subprocess.run(argv, input=input, capture_output=True, timeout=timeout,
                              env=env or session_env(),
                              stdin=None if input is not None else subprocess.DEVNULL)
    except (FileNotFoundError, PermissionError, subprocess.TimeoutExpired):
        return None


def spawn_detached(argv: list[str]) -> str | None:
    """Start a program that must outlive the agent, e.g. a screen locker.

    Under systemd, stopping or restarting the agent's service kills every
    process in its cgroup, setsid or not, which would unlock the screen. So
    when the user's systemd is there, the program gets a scope of its own.
    Returns why it failed, or None.
    """
    env = session_env()
    cmd = ["setsid", "-f", *argv] if which("setsid") else list(argv)
    if which("systemd-run") and os.environ.get("INVOCATION_ID"):
        # INVOCATION_ID: we're running inside a systemd unit
        cmd = ["systemd-run", "--user", "--scope", "--quiet", "--collect", "--", *cmd]
    try:
        p = subprocess.Popen(cmd, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, start_new_session=True)
    except OSError as e:
        return f"couldn't start {argv[0]}: {e}"
    try:
        # setsid -f returns at once; systemd-run --scope waits for setsid
        code = p.wait(timeout=5)
    except subprocess.TimeoutExpired:
        return None  # still running in the foreground: it started
    return None if code == 0 else f"{argv[0]} exited with {code}"
