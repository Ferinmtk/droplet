"""Input through the xdg-desktop-portal RemoteDesktop interface (KDE, GNOME).

The first time, the desktop shows a dialog asking the user to allow remote
control. We ask for a persistent grant (persist_mode 2) and keep the
restore token it gives back, so later starts don't ask again. While a session
runs, KDE shows an indicator in the system tray, and the user can end it
there; the agent then stops taking input until it's restarted.

Text is typed as keysyms, which don't depend on the keyboard layout.
"""

from __future__ import annotations

import enum
import logging
import threading
from pathlib import Path

from .. import config, keys
from .. import portal as xdp
from . import Backend, Carry

log = logging.getLogger("droplet_agent.input")

IFACE = "org.freedesktop.portal.RemoteDesktop"
KEYBOARD, POINTER = 1, 2
PERSIST_UNTIL_REVOKED = 2
AXIS_VERTICAL, AXIS_HORIZONTAL = 0, 1
PRESSED, RELEASED = 1, 0


def usable() -> tuple[bool, str]:
    """Whether the portal offers keyboard and pointer here. Read-only; shows nothing."""
    types = xdp.read_property(IFACE, "AvailableDeviceTypes")
    if types is None:
        return False, "no RemoteDesktop portal on the session bus"
    if (types & (KEYBOARD | POINTER)) != (KEYBOARD | POINTER):
        # e.g. niri: the interface exists but no backend can inject input
        return False, "the RemoteDesktop portal here can't do keyboard and pointer (no backend for this desktop)"
    return True, "RemoteDesktop portal (keyboard + pointer)"


class State(enum.Enum):
    PENDING = "waiting for you to allow remote control on this computer"
    STARTED = "remote control allowed"
    DENIED = "remote control was declined on this computer (restart the agent to be asked again)"
    FAILED = "the portal failed"
    CLOSED = "remote control was ended on this computer (restart the agent to allow it again)"


class PortalSession:
    """Creates and starts a RemoteDesktop session in a background thread.

    `on_state(state, detail)` is called on every change. The session is
    usable once the state is STARTED.
    """

    def __init__(self, app_id: str, token_path: Path, on_state):
        self.app_id = app_id
        self.token_path = token_path
        self.on_state = on_state
        self.state = State.PENDING
        self.detail = ""
        self.portal: xdp.Portal | None = None
        self.handle: str | None = None
        self._stop = threading.Event()

    def start(self):
        threading.Thread(target=self._run, name="portal-session", daemon=True).start()

    def _set(self, state: State, detail: str = ""):
        self.state, self.detail = state, detail
        log.info("portal: %s%s", state.value, f" ({detail})" if detail else "")
        try:
            self.on_state(state, detail)
        except Exception:
            log.exception("portal state callback failed")

    def _run(self):
        try:
            self.portal = xdp.Portal(self.app_id)
            p = self.portal
            res = p.request(IFACE, "CreateSession", "a{sv}", (),
                            {"session_handle_token": ("s", xdp.request_token())}, stop=self._stop, timeout=30)
            self.handle = res["session_handle"]
            options = {"types": ("u", KEYBOARD | POINTER), "persist_mode": ("u", PERSIST_UNTIL_REVOKED)}
            restore = config.read_secret(self.token_path)
            if restore:
                options["restore_token"] = ("s", restore)
            p.request(IFACE, "SelectDevices", "oa{sv}", (self.handle,), options, stop=self._stop, timeout=30)
            log.info("portal: starting remote control%s", "" if restore else "; the desktop will ask you to allow it")
            # no timeout: the user may take a while to answer the dialog
            res = p.request(IFACE, "Start", "osa{sv}", (self.handle, ""), {}, stop=self._stop)
        except xdp.Cancelled:
            self._set(State.DENIED)
            return
        except Exception as e:
            if not self._stop.is_set():
                self._set(State.FAILED, str(e))
            return
        devices = int(res.get("devices", 0))
        token = res.get("restore_token")
        if token:
            config.write_secret(self.token_path, token)
        if not devices & (KEYBOARD | POINTER):
            self._set(State.DENIED, "no keyboard or pointer was granted")
            return
        p.watch_signal("org.freedesktop.portal.Session", "Closed", self.handle,
                       lambda _body: self._closed(), self._stop)
        self._set(State.STARTED, "" if token else "not remembered: the desktop will ask again next time")

    def _closed(self):
        if not self._stop.is_set():
            self._set(State.CLOSED)

    def notify(self, method: str, signature: str, *args):
        if self.state is not State.STARTED or self.portal is None:
            return
        self.portal.send(IFACE, method, "oa{sv}" + signature, (self.handle, {}, *args))

    def close(self):
        self._stop.set()
        if self.portal is None:
            return
        if self.handle and self.state is State.STARTED:
            # the session is on its own object path, not the portal's
            from jeepney import DBusAddress, new_method_call
            addr = DBusAddress(self.handle, bus_name=xdp.BUS_NAME, interface="org.freedesktop.portal.Session")
            try:
                self.portal.router.send_and_get_reply(new_method_call(addr, "Close"), timeout=2)
            except Exception:
                pass
        self.portal.close()


class PortalBackend(Backend):
    name = "portal"

    def __init__(self, session: PortalSession):
        self.s = session
        self.wheel = Carry(1.0)

    def move(self, dx, dy):
        self.s.notify("NotifyPointerMotion", "dd", float(dx), float(dy))

    def button(self, button, down):
        self.s.notify("NotifyPointerButton", "iu", keys.BUTTONS[button], PRESSED if down else RELEASED)

    def scroll(self, dx, dy):
        # whole lines as discrete wheel steps; positive is down / right, as in the protocol
        sx, sy = self.wheel.take(dx, dy)
        if sy:
            self.s.notify("NotifyPointerAxisDiscrete", "ui", AXIS_VERTICAL, sy)
        if sx:
            self.s.notify("NotifyPointerAxisDiscrete", "ui", AXIS_HORIZONTAL, sx)

    def _keycode(self, code: int, pressed: bool):
        self.s.notify("NotifyKeyboardKeycode", "iu", code, PRESSED if pressed else RELEASED)

    def _keysym(self, sym: int, pressed: bool):
        self.s.notify("NotifyKeyboardKeysym", "iu", sym, PRESSED if pressed else RELEASED)

    def key(self, name, mods):
        mod_codes = [keys.MODIFIERS[m] for m in mods]
        ch = keys.single_char(name) if isinstance(name, str) else None
        if ch is not None:
            # a letter by its keysym, so ctrl+z is z on any layout (QWERTZ, AZERTY…)
            press = lambda down: self._keysym(ord(ch), down)  # noqa: E731
        else:
            code = keys.evdev_code(name)
            if code is None:
                return False
            press = lambda down: self._keycode(code, down)  # noqa: E731
        for m in mod_codes:
            self._keycode(m, True)
        press(True)
        press(False)
        for m in reversed(mod_codes):
            self._keycode(m, False)
        return True

    def text(self, s):
        for ch in s.replace("\r\n", "\n"):
            sym = keys.keysym_for_char(ch)
            if sym is None:
                continue
            self._keysym(sym, True)
            self._keysym(sym, False)

    def close(self):
        self.s.close()
