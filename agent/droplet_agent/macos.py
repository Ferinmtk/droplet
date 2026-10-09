"""macOS: the background service, the menu bar's start-up, Droplet.app, and the system's own tools.

Everything Mac-only that isn't input (inject/quartz.py) lives here, so the
rest of the agent only asks `sys.platform == "darwin"` and calls in.

- The agent runs as a launchd LaunchAgent (~/Library/LaunchAgents), not a
  systemd service; the menu bar icon (`droplet-agent tray`) is a second one.
- Droplet in Launchpad and Spotlight is a small app bundle the installer
  writes into ~/Applications: made on this Mac, so it isn't quarantined and
  needs no signature.
- Notifications through osascript, the ring through afplay, files opened
  with `open`, the battery from `pmset`.
- A few calls into the system frameworks (is Accessibility allowed, may it
  record the screen, the clipboard's change count, hiding the Dock icon) go
  through ctypes, so nothing has to be installed for them.

Settings and the agent's files stay where they are on Linux
(~/.config/droplet-agent and ~/.local/share/droplet-agent): no spaces in
the path (a venv's scripts and launchd's arguments get awkward with
"Application Support"), hidden from Finder, and one layout for the
installer, the docs and the tests.

`python -m droplet_agent.macos install` is what install.sh runs on a Mac.
"""

from __future__ import annotations

import argparse
import ctypes
import ctypes.util
import logging
import os
import plistlib
import shutil
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

from . import APP_ID, __version__

log = logging.getLogger("droplet_agent.macos")

IS_MAC = sys.platform == "darwin"

AGENT_LABEL = APP_ID                  # io.github.ferinmtk.DropletAgent: the agent itself
MENU_LABEL = f"{APP_ID}.Menu"         # the menu bar icon
BUNDLE_ID = "io.github.ferinmtk.Droplet"
APP_NAME = "Droplet"
RING_SOUND = Path("/System/Library/Sounds/Glass.aiff")
LSREGISTER = Path("/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework"
                  "/Support/lsregister")
# where launchd looks for tools: it starts the agent with a bare PATH
PATH = "/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin:/usr/local/bin"


# --- paths ------------------------------------------------------------------------

def launch_agents_dir() -> Path:
    return Path.home() / "Library" / "LaunchAgents"


def plist_path(label: str = AGENT_LABEL) -> Path:
    return launch_agents_dir() / f"{label}.plist"


def log_path(label: str = AGENT_LABEL) -> Path:
    name = "droplet-agent.log" if label == AGENT_LABEL else "droplet-menu.log"
    return Path.home() / "Library" / "Logs" / name


def app_bundle_path() -> Path:
    return Path.home() / "Applications" / f"{APP_NAME}.app"


def agent_program() -> list[str]:
    """How launchd (and Droplet.app) start this agent, without the subcommand."""
    from . import config
    installed = config.data_dir() / "bin" / "droplet-agent"
    if installed.exists():
        return [str(installed)]
    found = shutil.which("droplet-agent")
    return [found] if found else [sys.executable, "-m", "droplet_agent"]


# --- launchd ------------------------------------------------------------------------

def launch_agent(label: str, args: list[str], *, keep_alive: bool) -> bytes:
    """A LaunchAgent's plist: started at login (and when loaded), in the user's GUI session."""
    log_file = str(log_path(label))
    return plistlib.dumps({
        "Label": label,
        "ProgramArguments": list(args),
        "RunAtLoad": True,
        # the agent comes back if it stops; the menu stays closed when it's quit
        "KeepAlive": True if keep_alive else {"SuccessfulExit": False},
        "ThrottleInterval": 10,
        "ProcessType": "Interactive",
        # the GUI session: notifications, the clipboard and the menu bar are there
        "LimitLoadToSessionType": "Aqua",
        "StandardOutPath": log_file,
        "StandardErrorPath": log_file,
        # pbcopy and pbpaste read and write UTF-8 only when the locale says so
        "EnvironmentVariables": {"LANG": "en_US.UTF-8", "PATH": PATH},
    })


def write_launch_agent(label: str, args: list[str], *, keep_alive: bool) -> Path:
    path = plist_path(label)
    path.parent.mkdir(parents=True, exist_ok=True)
    log_path(label).parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(launch_agent(label, args, keep_alive=keep_alive))
    return path


def domain() -> str:
    return f"gui/{os.getuid()}"


def _launchctl(*args: str, timeout: float = 30) -> subprocess.CompletedProcess | None:
    try:
        return subprocess.run(["launchctl", *args], capture_output=True, text=True, timeout=timeout,
                              stdin=subprocess.DEVNULL)
    except (OSError, subprocess.TimeoutExpired):
        return None


def _why(r: subprocess.CompletedProcess | None) -> str:
    if r is None:
        return "launchctl didn't run"
    lines = (r.stderr or r.stdout or "").strip().splitlines()
    return lines[-1] if lines else f"exit status {r.returncode}"


def loaded(label: str = AGENT_LABEL) -> bool:
    r = _launchctl("print", f"{domain()}/{label}", timeout=10)
    return r is not None and r.returncode == 0


def service_state(label: str = AGENT_LABEL) -> str:
    """"running", "not running", "not loaded" or "not installed", for `status`."""
    if not plist_path(label).exists():
        return "not installed"
    r = _launchctl("print", f"{domain()}/{label}", timeout=10)
    if r is None:
        return "unknown"
    if r.returncode != 0:
        return "not loaded"
    for line in r.stdout.splitlines():
        line = line.strip()
        if line.startswith("state = "):
            return "running" if line.split("=", 1)[1].strip() == "running" else "not running"
    return "loaded"


def load(label: str = AGENT_LABEL) -> str | None:
    """Load (and so start) a LaunchAgent, replacing a loaded copy. Returns why it failed, or None."""
    path = plist_path(label)
    if not path.exists():
        return f"{path} isn't there"
    _launchctl("bootout", f"{domain()}/{label}")
    r = None
    for attempt in range(5):
        r = _launchctl("bootstrap", domain(), str(path))
        if r is not None and r.returncode == 0:
            return None
        # right after a bootout launchd can still be tearing the old one down ("5: Input/output error")
        time.sleep(1 + attempt)
    # older macOS, or no GUI session for gui/<uid> (over ssh): the old way
    old = _launchctl("load", "-w", str(path))
    if old is not None and old.returncode == 0 and not (old.stderr or "").strip():
        return None
    return _why(r)


def unload(label: str = AGENT_LABEL) -> bool:
    """Stop a LaunchAgent and keep it from starting again until it's loaded. True if it was loaded."""
    r = _launchctl("bootout", f"{domain()}/{label}")
    if r is not None and r.returncode == 0:
        return True
    path = plist_path(label)
    if path.exists():
        old = _launchctl("unload", str(path))
        return old is not None and old.returncode == 0 and not (old.stderr or "").strip()
    return False


def start_service() -> str:
    """Start the agent. Returns "" when it worked, else why not (for the window)."""
    if not plist_path().exists():
        return "The agent's service isn't installed. Start it with: droplet-agent run"
    if not loaded():
        why = load()
        return "" if why is None else f"It didn't start: {why}"
    r = _launchctl("kickstart", f"{domain()}/{AGENT_LABEL}")
    return "" if r is not None and r.returncode == 0 else f"It didn't start: {_why(r)}"


def restart_service() -> tuple[bool, str]:
    """Restart the agent so it reads its settings again (the window's Save)."""
    if not plist_path().exists():
        return False, "Saved. Restart the agent to use the new settings."
    if not loaded():
        why = load()
    else:
        r = _launchctl("kickstart", "-k", f"{domain()}/{AGENT_LABEL}")
        why = None if r is not None and r.returncode == 0 else _why(r)
    if why:
        return False, f"Saved, but the agent didn't restart: {why}"
    return True, "Saved. The agent restarted with the new settings."


def start_hint() -> str:
    return f"launchctl kickstart {domain()}/{AGENT_LABEL}"


def restart_hint() -> str:
    return f"launchctl kickstart -k {domain()}/{AGENT_LABEL}"


# --- the menu bar icon: one at a time -------------------------------------------------

def menu_socket() -> Path:
    """Where the running menu bar icon listens (a Qt local server, which is a Unix socket here)."""
    from .mesh import control
    return control.socket_path().parent / "menu.sock"


def _menu_send(message: bytes) -> bool:
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(2)
    try:
        s.connect(str(menu_socket()))
        s.sendall(message)
        return True
    except OSError:
        return False
    finally:
        s.close()


def menu_running() -> bool:
    return _menu_send(b'{"cmd": "ping"}\n')


def stop_menu() -> bool:
    """Close the menu bar icon, if one is up. True if there was one."""
    if not _menu_send(b'{"cmd": "quit"}\n'):
        return False
    for _ in range(30):
        if not menu_running():
            break
        time.sleep(0.1)
    return True


# --- Droplet.app -----------------------------------------------------------------

ICON_FILE = Path(__file__).with_name("app_icon.png")   # 512x512, the same drop as everywhere else


def icns(big_png: bytes | None, pixmaps: list) -> bytes:
    """An .icns from PNGs (macOS reads PNG inside icns since 10.7): the 512 px drop and the tray's sizes."""
    from .tray import png
    kinds = {16: b"icp4", 32: b"icp5", 64: b"icp6", 128: b"ic07", 256: b"ic08", 512: b"ic09"}
    parts = []
    for w, h, px in pixmaps:
        if w == h and w in kinds:
            parts.append((kinds[w], png(w, h, px)))
    if big_png and big_png[16:24] == struct.pack(">II", 512, 512):
        parts.append((b"ic09", big_png))
    body = b"".join(kind + struct.pack(">I", len(data) + 8) + data for kind, data in parts)
    return b"icns" + struct.pack(">I", len(body) + 8) + body


def info_plist() -> bytes:
    return plistlib.dumps({
        "CFBundleName": APP_NAME,
        "CFBundleDisplayName": APP_NAME,
        "CFBundleIdentifier": BUNDLE_ID,
        "CFBundleExecutable": APP_NAME,
        "CFBundleIconFile": APP_NAME,
        "CFBundlePackageType": "APPL",
        "CFBundleShortVersionString": __version__,
        "CFBundleVersion": __version__,
        "CFBundleInfoDictionaryVersion": "6.0",
        "LSApplicationCategoryType": "public.app-category.utilities",
        "LSMinimumSystemVersion": "10.13",
        "NSHighResolutionCapable": True,
    })


def launcher_script(program: list[str]) -> str:
    import shlex
    return "#!/bin/sh\n# Droplet: opens its window (and starts the menu bar icon)\n" \
           f"exec {' '.join(shlex.quote(a) for a in program)} open \"$@\"\n"


def install_app_bundle(program: list[str] | None = None) -> Path:
    """~/Applications/Droplet.app, so Droplet is in Launchpad and Spotlight."""
    from .tray import load_pixmaps
    app = app_bundle_path()
    contents = app / "Contents"
    (contents / "MacOS").mkdir(parents=True, exist_ok=True)
    (contents / "Resources").mkdir(parents=True, exist_ok=True)
    (contents / "Info.plist").write_bytes(info_plist())
    exe = contents / "MacOS" / APP_NAME
    exe.write_text(launcher_script(program or agent_program()))
    exe.chmod(0o755)
    try:
        big = ICON_FILE.read_bytes()
    except OSError:
        big = None
    (contents / "Resources" / f"{APP_NAME}.icns").write_bytes(icns(big, load_pixmaps()))
    os.utime(app)   # Finder and the Dock notice a changed bundle by its date
    if LSREGISTER.exists():
        subprocess.run([str(LSREGISTER), "-f", str(app)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       check=False)
    return app


def remove_app_bundle() -> bool:
    app = app_bundle_path()
    if not app.exists():
        return False
    shutil.rmtree(app, ignore_errors=True)
    return True


# --- the system's tools ------------------------------------------------------------

def _run(argv: list[str], timeout: float = 10, input: bytes | None = None) -> subprocess.CompletedProcess | None:
    try:
        return subprocess.run(argv, capture_output=True, timeout=timeout, input=input,
                              stdin=None if input is not None else subprocess.DEVNULL,
                              env=dict(os.environ, LANG=os.environ.get("LANG") or "en_US.UTF-8"))
    except (OSError, subprocess.TimeoutExpired):
        return None


def notify(title: str, body: str) -> bool:
    """A notification in Notification Centre (from osascript, so it's listed as Script Editor)."""
    # the text goes in as arguments, never into the script
    r = _run(["osascript", "-e", "on run argv", "-e",
              "display notification (item 2 of argv) with title (item 1 of argv)", "-e", "end run",
              str(title)[:200], str(body)[:1000]], timeout=10)
    return r is not None and r.returncode == 0


def open_path(path, reveal: bool = False) -> bool:
    """Open a file or folder with its app (or show it selected in Finder)."""
    try:
        subprocess.Popen(["open", *(["-R"] if reveal else []), str(path)], stdin=subprocess.DEVNULL,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        return True
    except OSError:
        return False


def battery_from_pmset(text: str) -> dict | None:
    """`pmset -g batt` → {"level", "charging"}, or None without an internal battery."""
    import re
    on_ac = "'AC Power'" in text
    for line in text.splitlines():
        if "InternalBattery" not in line:
            continue
        m = re.search(r"(\d+)%;\s*([^;]+)", line)
        if not m:
            continue
        state = m.group(2).strip().lower()
        charging = state in ("charging", "charged", "finishing charge", "ac attached") or (
            on_ac and state != "discharging")
        return {"level": max(0, min(100, int(m.group(1)))), "charging": charging}
    return None


def battery() -> dict | None:
    r = _run(["pmset", "-g", "batt"], timeout=5)
    if r is None or r.returncode != 0:
        return None
    return battery_from_pmset(r.stdout.decode(errors="replace"))


def volume_from_osascript(text: str) -> dict | None:
    """"50,false" → {"level": 0.5, "muted": False}; None when the output has no volume (HDMI, say)."""
    try:
        level, muted = text.strip().split(",")
        return {"level": max(0.0, min(1.0, int(level) / 100)), "muted": muted.strip() == "true"}
    except ValueError:
        return None


def read_volume() -> dict | None:
    r = _run(["osascript", "-e", "set s to get volume settings",
              "-e", 'return (output volume of s as text) & "," & (output muted of s as text)'], timeout=5)
    if r is None or r.returncode != 0:
        return None
    return volume_from_osascript(r.stdout.decode(errors="replace"))


def volume_command(level: float | None = None, muted: bool | None = None) -> list[str]:
    script = []
    if level is not None:
        script.append(f"set volume output volume {round(max(0.0, min(1.0, level)) * 100)}")
    if muted is not None:
        script.append(f"set volume output muted {'true' if muted else 'false'}")
    argv = ["osascript"]
    for line in script:
        argv += ["-e", line]
    return argv


# --- the frameworks, through ctypes ------------------------------------------------------

_libs: dict = {}


def _lib(name: str, path: str):
    if name not in _libs:
        try:
            _libs[name] = ctypes.cdll.LoadLibrary(path)
        except OSError:
            _libs[name] = None
    return _libs[name]


def _appservices():
    return _lib("as", "/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices")


def _cf():
    return _lib("cf", "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")


def _objc():
    lib = _lib("objc", ctypes.util.find_library("objc") or "/usr/lib/libobjc.A.dylib")
    if lib is not None and not getattr(lib, "_droplet_set_up", False):
        lib.objc_getClass.restype = ctypes.c_void_p
        lib.objc_getClass.argtypes = [ctypes.c_char_p]
        lib.sel_registerName.restype = ctypes.c_void_p
        lib.sel_registerName.argtypes = [ctypes.c_char_p]
        lib.objc_autoreleasePoolPush.restype = ctypes.c_void_p
        lib.objc_autoreleasePoolPop.argtypes = [ctypes.c_void_p]
        lib._droplet_set_up = True
    return lib


def msg_send(receiver, selector: str, *args, restype=ctypes.c_void_p, argtypes=()):
    """[receiver selector...] with exact C types: each call gets its own prototype (needed on Apple silicon)."""
    objc = _objc()
    proto = ctypes.CFUNCTYPE(restype, ctypes.c_void_p, ctypes.c_void_p, *argtypes)
    fn = proto(ctypes.cast(objc.objc_msgSend, ctypes.c_void_p).value)
    return fn(receiver, objc.sel_registerName(selector.encode()), *args)


def objc_class(name: str):
    return _objc().objc_getClass(name.encode())


class autorelease:
    """An autorelease pool for the calls in a `with` block (worker threads have none)."""

    def __enter__(self):
        self.pool = _objc().objc_autoreleasePoolPush()
        return self

    def __exit__(self, *exc):
        _objc().objc_autoreleasePoolPop(self.pool)


def ns_string(text: str):
    return msg_send(objc_class("NSString"), "stringWithUTF8String:", text.encode(), argtypes=(ctypes.c_char_p,))


def accessibility_allowed(prompt: bool = False) -> bool:
    """Whether this process may post input events (Privacy & Security → Accessibility).

    With `prompt`, macOS asks (once) and lists this Python there, switched off, for the user to switch on.
    """
    lib = _appservices()
    if lib is None:
        return False
    if not prompt:
        lib.AXIsProcessTrusted.restype = ctypes.c_bool
        return bool(lib.AXIsProcessTrusted())
    cf = _cf()
    key = ctypes.c_void_p.in_dll(lib, "kAXTrustedCheckOptionPrompt")
    yes = ctypes.c_void_p.in_dll(cf, "kCFBooleanTrue")
    keys = (ctypes.c_void_p * 1)(key.value)
    values = (ctypes.c_void_p * 1)(yes.value)
    cf.CFDictionaryCreate.restype = ctypes.c_void_p
    cf.CFDictionaryCreate.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_long,
                                      ctypes.c_void_p, ctypes.c_void_p]
    cf.CFRelease.argtypes = [ctypes.c_void_p]
    opts = cf.CFDictionaryCreate(None, keys, values, 1,
                                 ctypes.addressof(ctypes.c_char.in_dll(cf, "kCFTypeDictionaryKeyCallBacks")),
                                 ctypes.addressof(ctypes.c_char.in_dll(cf, "kCFTypeDictionaryValueCallBacks")))
    lib.AXIsProcessTrustedWithOptions.restype = ctypes.c_bool
    lib.AXIsProcessTrustedWithOptions.argtypes = [ctypes.c_void_p]
    try:
        return bool(lib.AXIsProcessTrustedWithOptions(opts))
    finally:
        cf.CFRelease(opts)


def screen_capture_allowed(request: bool = False) -> bool | None:
    """Whether this process may record the screen (macOS 11+); None when the system can't say.

    Without it, screencapture still "works" but shows only the desktop picture.
    """
    lib = _appservices()
    name = "CGRequestScreenCaptureAccess" if request else "CGPreflightScreenCaptureAccess"
    fn = getattr(lib, name, None) if lib is not None else None
    if fn is None:
        return None
    fn.restype = ctypes.c_bool
    return bool(fn())


def _appkit():
    return _lib("appkit", "/System/Library/Frameworks/AppKit.framework/AppKit")


def pasteboard_change_count() -> int | None:
    """The clipboard's change counter, which goes up on every copy: cheap enough to poll."""
    if _appkit() is None:
        return None
    try:
        with autorelease():
            pb = msg_send(objc_class("NSPasteboard"), "generalPasteboard")
            if not pb:
                return None
            return int(msg_send(pb, "changeCount", restype=ctypes.c_long))
    except Exception as e:
        log.debug("clipboard change count: %s", e)
        return None


def pasteboard_concealed() -> bool:
    """A password manager marked what's on the clipboard secret (nspasteboard.org's convention)."""
    if _appkit() is None:
        return False
    try:
        with autorelease():
            pb = msg_send(objc_class("NSPasteboard"), "generalPasteboard")
            if not pb:
                return False
            for kind in ("org.nspasteboard.ConcealedType", "org.nspasteboard.TransientType"):
                if msg_send(pb, "dataForType:", ns_string(kind), argtypes=(ctypes.c_void_p,)):
                    return True
    except Exception as e:
        log.debug("clipboard types: %s", e)
    return False


def hide_dock_icon() -> bool:
    """A menu bar app: no Dock icon and no app menu (NSApplicationActivationPolicyAccessory)."""
    if _appkit() is None:
        return False
    try:
        with autorelease():
            app = msg_send(objc_class("NSApplication"), "sharedApplication")
            return bool(msg_send(app, "setActivationPolicy:", 1, restype=ctypes.c_bool, argtypes=(ctypes.c_long,)))
    except Exception as e:
        log.debug("hiding the Dock icon: %s", e)
        return False


def set_app_name(name: str = APP_NAME) -> bool:
    """Call the app `name` in the menu bar and the Dock, not "Python" (before Qt starts)."""
    if _appkit() is None:
        return False
    try:
        with autorelease():
            bundle = msg_send(objc_class("NSBundle"), "mainBundle")
            info = msg_send(bundle, "infoDictionary") if bundle else None
            # only a mutable dictionary can take it; anything else would raise inside AppKit
            if not info or not msg_send(info, "respondsToSelector:", _objc().sel_registerName(b"setObject:forKey:"),
                                        restype=ctypes.c_bool, argtypes=(ctypes.c_void_p,)):
                return False
            msg_send(info, "setObject:forKey:", ns_string(name), ns_string("CFBundleName"), restype=None,
                     argtypes=(ctypes.c_void_p, ctypes.c_void_p))
            return True
    except Exception as e:
        log.debug("naming the app: %s", e)
        return False


def activate_app() -> None:
    """Bring this process to the front (before a file dialog from the menu bar)."""
    if _appkit() is None:
        return
    try:
        with autorelease():
            app = msg_send(objc_class("NSApplication"), "sharedApplication")
            msg_send(app, "activateIgnoringOtherApps:", True, restype=None, argtypes=(ctypes.c_bool,))
    except Exception as e:
        log.debug("activating: %s", e)


# --- installing (install.sh runs this) ------------------------------------------------

def gui_session() -> bool:
    """Whether there's a logged-in desktop for this user (not only an ssh login)."""
    r = _launchctl("print", domain(), timeout=10)
    return r is not None and r.returncode == 0


def install(service: bool = True, menu: bool = True) -> int:
    from . import app as qt_app
    program = agent_program()
    write_launch_agent(AGENT_LABEL, [*program, "run"], keep_alive=True)
    if service:
        why = load(AGENT_LABEL)
        if why is None:
            print(f"The agent is running, and starts when you log in (log: {log_path()}).")
        else:
            print(f"Couldn't start the agent's service ({why}). Start it with: {program[0]} run")
    else:
        print(f"Service not loaded. Start the agent with: {program[0]} run")

    if menu:
        print(f"Droplet is in Launchpad and Spotlight ({install_app_bundle(program)}).")
    else:
        remove_app_bundle()
    if menu and qt_app.available():
        write_launch_agent(MENU_LABEL, [*program, "tray"], keep_alive=False)
        if gui_session():
            why = load(MENU_LABEL)
            print("droplet is in the menu bar, and starts there when you log in." if why is None
                  else f"Couldn't start the menu bar icon ({why}). Start it with: {program[0]} tray")
        else:
            print("The menu bar icon starts when you log in.")
    else:
        if loaded(MENU_LABEL):
            unload(MENU_LABEL)
        try:
            plist_path(MENU_LABEL).unlink()
        except FileNotFoundError:
            pass
        stop_menu()
        if menu:
            print("No menu bar icon: it needs Droplet's window (PySide6), which isn't installed.")
    return 0


def uninstall() -> list[str]:
    """Stop and remove the LaunchAgents and Droplet.app. Returns what was removed."""
    removed = []
    for label in (MENU_LABEL, AGENT_LABEL):
        unload(label)
        try:
            plist_path(label).unlink()
            removed.append(str(plist_path(label)))
        except FileNotFoundError:
            pass
    stop_menu()
    if remove_app_bundle():
        removed.append(str(app_bundle_path()))
    for label in (AGENT_LABEL, MENU_LABEL):
        try:
            log_path(label).unlink()
        except FileNotFoundError:
            pass
    return removed


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="python -m droplet_agent.macos")
    sub = p.add_subparsers(dest="cmd", required=True)
    i = sub.add_parser("install", help="the LaunchAgents and Droplet.app (what install.sh does on a Mac)")
    i.add_argument("--no-service", action="store_true")
    i.add_argument("--no-menu", action="store_true")
    args = p.parse_args(argv)
    if args.cmd == "install":
        return install(service=not args.no_service, menu=not args.no_menu)
    return 2


if __name__ == "__main__":
    sys.exit(main())
