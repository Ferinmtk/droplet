"""Input through /dev/uinput: a virtual mouse and keyboard in the kernel.

Talks to uinput with plain ioctl/write calls (no python-evdev, so pip needs
no compiler). The compositor sees two ordinary devices, "droplet virtual
pointer" and "droplet virtual keyboard", which works under any compositor.

uinput sends key positions, not characters, so text is typed as on a US
keyboard. Characters a US keyboard can't type go through wtype when it's
installed (wlroots compositors and niri), otherwise accents are dropped and
the rest skipped. On KDE and GNOME the portal backend types any text instead.
"""

from __future__ import annotations

import errno
import fcntl
import logging
import os
import struct
import subprocess
import time

from .. import env, keys
from . import Backend, Carry

log = logging.getLogger("droplet_agent.input")

DEVICE = "/dev/uinput"


def _IOC(direction: int, kind: str, nr: int, size: int) -> int:
    return (direction << 30) | (size << 16) | (ord(kind) << 8) | nr


def _IOW(kind: str, nr: int, size: int) -> int:
    return _IOC(1, kind, nr, size)


SETUP_FORMAT = "HHHH80sI"  # struct uinput_setup: input_id, name[80], ff_effects_max
UI_DEV_CREATE = _IOC(0, "U", 1, 0)
UI_DEV_DESTROY = _IOC(0, "U", 2, 0)
UI_DEV_SETUP = _IOW("U", 3, struct.calcsize(SETUP_FORMAT))
UI_SET_EVBIT = _IOW("U", 100, 4)
UI_SET_KEYBIT = _IOW("U", 101, 4)
UI_SET_RELBIT = _IOW("U", 102, 4)

EV_SYN, EV_KEY, EV_REL = 0, 1, 2
SYN_REPORT = 0
REL_X, REL_Y, REL_HWHEEL, REL_WHEEL = 0, 1, 6, 8
REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES = 11, 12
BUS_VIRTUAL = 0x06
HI_RES_PER_LINE = 120  # the kernel's hi-res wheel unit: 120 per detent

# struct input_event: struct timeval (two longs), type, code, value.
# The kernel stamps the time itself, so it's left at zero.
EVENT_FORMAT = "llHHi"

KEYBOARD_KEYS = range(1, 249)  # KEY_ESC … KEY_MICMUTE: enough to look like a real keyboard


def event(kind: int, code: int, value: int) -> bytes:
    return struct.pack(EVENT_FORMAT, 0, 0, kind, code, value)


def syn() -> bytes:
    return event(EV_SYN, SYN_REPORT, 0)


def writable() -> tuple[bool, str]:
    """Whether uinput can be used, and why not."""
    if not os.path.exists(DEVICE):
        return False, f"{DEVICE} doesn't exist (the uinput kernel module isn't loaded)"
    if not os.access(DEVICE, os.W_OK):
        return False, f"{DEVICE} isn't writable by this user (it needs a udev rule; see `droplet-agent doctor`)"
    return True, f"{DEVICE} is writable"


class VirtualDevice:
    """One uinput device. `writer` replaces the real file for tests."""

    def __init__(self, name: str, product: int, evbits, keybits=(), relbits=(), writer=None):
        self.name = name
        self.fd = None
        if writer is not None:
            self._write = writer
            return
        fd = os.open(DEVICE, os.O_WRONLY | os.O_NONBLOCK | os.O_CLOEXEC)
        try:
            for bit in evbits:
                fcntl.ioctl(fd, UI_SET_EVBIT, bit)
            for bit in keybits:
                fcntl.ioctl(fd, UI_SET_KEYBIT, bit)
            for bit in relbits:
                fcntl.ioctl(fd, UI_SET_RELBIT, bit)
            setup = struct.pack(SETUP_FORMAT, BUS_VIRTUAL, 0x1209, product, 1, name.encode()[:79], 0)
            fcntl.ioctl(fd, UI_DEV_SETUP, setup)
            fcntl.ioctl(fd, UI_DEV_CREATE)
        except OSError:
            os.close(fd)
            raise
        self.fd = fd
        self._write = self._write_fd

    def _write_fd(self, data: bytes):
        view = memoryview(data)
        while view:
            try:
                n = os.write(self.fd, view)
            except BlockingIOError:
                time.sleep(0.001)
                continue
            view = view[n:]

    def emit(self, events: list[bytes]):
        """Write events followed by a SYN_REPORT, as one frame."""
        if events:
            self._write(b"".join(events) + syn())

    def close(self):
        if self.fd is not None:
            try:
                fcntl.ioctl(self.fd, UI_DEV_DESTROY)
            except OSError:
                pass
            os.close(self.fd)
            self.fd = None


class UinputBackend(Backend):
    name = "uinput"

    def __init__(self, text_mode: str = "auto", writer=None, key_delay: float = 0.002):
        """`writer`, for tests: one callable that receives every frame of both devices."""
        self.pointer = VirtualDevice(
            "droplet virtual pointer", 0xD701, (EV_KEY, EV_REL),
            keybits=tuple(keys.BUTTONS.values()),
            relbits=(REL_X, REL_Y, REL_WHEEL, REL_HWHEEL, REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES),
            writer=writer)
        try:
            self.keyboard = VirtualDevice("droplet virtual keyboard", 0xD702, (EV_KEY,),
                                          keybits=KEYBOARD_KEYS, writer=writer)
        except OSError:
            self.pointer.close()
            raise
        self.wheel = Carry(HI_RES_PER_LINE)
        self.detent_x = 0  # hi-res units not yet sent as a whole legacy wheel click
        self.detent_y = 0
        self.text_mode = text_mode
        self.key_delay = key_delay
        self._wtype_broken = False

    # --- pointer -----------------------------------------------------------

    def move(self, dx, dy):
        evs = []
        if dx:
            evs.append(event(EV_REL, REL_X, dx))
        if dy:
            evs.append(event(EV_REL, REL_Y, dy))
        self.pointer.emit(evs)

    def button(self, button, down):
        self.pointer.emit([event(EV_KEY, keys.BUTTONS[button], 1 if down else 0)])

    def scroll(self, dx, dy):
        hx, hy = self.wheel.take(dx, dy)
        # the kernel's wheel counts up for "away from the user", which
        # scrolls up; the protocol's positive dy scrolls down
        wheel_y = -hy
        evs = []
        if wheel_y:
            evs.append(event(EV_REL, REL_WHEEL_HI_RES, wheel_y))
        if hx:
            evs.append(event(EV_REL, REL_HWHEEL_HI_RES, hx))
        # plus the classic one-click-per-line events, for apps and
        # compositors that don't read the hi-res ones
        if wheel_y and (self.detent_y > 0) != (wheel_y > 0):
            self.detent_y = 0
        if hx and (self.detent_x > 0) != (hx > 0):
            self.detent_x = 0
        self.detent_y += wheel_y
        self.detent_x += hx
        clicks_y = int(self.detent_y / HI_RES_PER_LINE)
        clicks_x = int(self.detent_x / HI_RES_PER_LINE)
        if clicks_y:
            evs.append(event(EV_REL, REL_WHEEL, clicks_y))
            self.detent_y -= clicks_y * HI_RES_PER_LINE
        if clicks_x:
            evs.append(event(EV_REL, REL_HWHEEL, clicks_x))
            self.detent_x -= clicks_x * HI_RES_PER_LINE
        self.pointer.emit(evs)

    # --- keyboard ----------------------------------------------------------

    def _tap(self, code: int, mod_codes: list[int]):
        kb = self.keyboard
        for m in mod_codes:
            kb.emit([event(EV_KEY, m, 1)])
        kb.emit([event(EV_KEY, code, 1)])
        kb.emit([event(EV_KEY, code, 0)])
        for m in reversed(mod_codes):
            kb.emit([event(EV_KEY, m, 0)])
        if self.key_delay:
            time.sleep(self.key_delay)

    def key(self, name, mods):
        code = keys.evdev_code(name)
        if code is None:
            return False
        self._tap(code, [keys.MODIFIERS[m] for m in mods])
        return True

    def text(self, s):
        s = s.replace("\r\n", "\n")
        if all(ch in keys.US_LAYOUT for ch in s):
            self._type_us(s)
            return
        if self.text_mode in ("auto", "wtype") and self._wtype(s):
            return
        typeable, skipped = keys.to_ascii(s)
        if skipped:
            log.info("uinput can't type %d character(s) of that text; install wtype, "
                     "or use a desktop with the RemoteDesktop portal (KDE, GNOME)", skipped)
        self._type_us(typeable)

    def _type_us(self, s: str):
        for ch in s:
            code, shift = keys.US_LAYOUT[ch]
            self._tap(code, [keys.KEY_LEFTSHIFT] if shift else [])

    def _wtype(self, s: str) -> bool:
        """Type through wtype (the Wayland virtual-keyboard protocol). False if it can't."""
        if self._wtype_broken or not env.which("wtype") or not env.is_wayland():
            return False
        try:
            r = subprocess.run(["wtype", "--", s], env=env.session_env(), timeout=30,
                               stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        except (OSError, subprocess.TimeoutExpired) as e:
            log.warning("wtype failed: %s", e)
            self._wtype_broken = True
            return False
        if r.returncode != 0:
            # e.g. KDE: "Compositor does not support the virtual keyboard protocol"
            log.warning("wtype failed: %s", r.stderr.decode(errors="replace").strip())
            self._wtype_broken = True
            return False
        return True

    def close(self):
        self.pointer.close()
        self.keyboard.close()


def open_backend(text_mode: str = "auto") -> UinputBackend:
    """Create the virtual devices. Raises OSError with a readable message when it can't."""
    ok, why = writable()
    if not ok:
        raise OSError(errno.EACCES, why)
    backend = UinputBackend(text_mode)
    # compositors pick up new devices asynchronously; events sent before
    # they've opened it are lost
    time.sleep(0.3)
    return backend
