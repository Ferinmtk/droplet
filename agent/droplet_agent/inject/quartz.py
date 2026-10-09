"""Input on a Mac: Quartz events, posted the way a real mouse and keyboard's are.

CoreGraphics is called through ctypes, so nothing needs installing. macOS
lets a program post input only once the user allows it under System Settings
→ Privacy & Security → Accessibility; until then events are silently
dropped, so the backend counts as unavailable, the agent asks macOS to list
it there, and input starts working as soon as it's switched on.

Text is typed as Unicode (any character, whatever the keyboard layout);
named keys and shortcuts use the keys' positions (kVK_* codes). Media and
volume keys are the system's own (NX_KEYTYPE_*), through AppKit's NSEvent,
which is what the keyboard's media keys send.
"""

from __future__ import annotations

import ctypes
import logging
import math
import sys
import time

from .. import keys
from . import Backend, Carry

log = logging.getLogger("droplet_agent.input")

CG_PATH = "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics"

# CGEventType
LEFT_DOWN, LEFT_UP, RIGHT_DOWN, RIGHT_UP, MOVED, LEFT_DRAGGED, RIGHT_DRAGGED = 1, 2, 3, 4, 5, 6, 7
OTHER_DOWN, OTHER_UP, OTHER_DRAGGED = 25, 26, 27
BUTTON_EVENTS = {  # button → (CGMouseButton, down type, up type, dragged type)
    "left": (0, LEFT_DOWN, LEFT_UP, LEFT_DRAGGED),
    "right": (1, RIGHT_DOWN, RIGHT_UP, RIGHT_DRAGGED),
    "middle": (2, OTHER_DOWN, OTHER_UP, OTHER_DRAGGED),
}
# CGEventField
CLICK_STATE, DELTA_X, DELTA_Y = 1, 4, 5
HID_TAP = 0                 # kCGHIDEventTap
HID_STATE = 1               # kCGEventSourceStateHIDSystemState
SCROLL_LINE = 1             # kCGScrollEventUnitLine
DOUBLE_CLICK = 0.5          # seconds, macOS's default double-click speed
DOUBLE_CLICK_SLOP = 6       # pixels the pointer may move between the clicks of a double-click

FLAGS = {"shift": 0x20000, "ctrl": 0x40000, "alt": 0x80000, "meta": 0x100000}

# kVK_* virtual key codes (Carbon's Events.h): positions on an ANSI keyboard
LETTERS = {"a": 0x00, "s": 0x01, "d": 0x02, "f": 0x03, "h": 0x04, "g": 0x05, "z": 0x06, "x": 0x07,
           "c": 0x08, "v": 0x09, "b": 0x0B, "q": 0x0C, "w": 0x0D, "e": 0x0E, "r": 0x0F, "y": 0x10,
           "t": 0x11, "o": 0x1F, "u": 0x20, "i": 0x22, "p": 0x23, "l": 0x25, "j": 0x26, "k": 0x28,
           "n": 0x2D, "m": 0x2E}
DIGITS = {"1": 0x12, "2": 0x13, "3": 0x14, "4": 0x15, "6": 0x16, "5": 0x17, "9": 0x19, "7": 0x1A,
          "8": 0x1C, "0": 0x1D}
RETURN, TAB, SPACE, BACKSPACE, ESCAPE = 0x24, 0x30, 0x31, 0x33, 0x35
NAMED = {
    "Enter": RETURN, "Tab": TAB, "Space": SPACE, " ": SPACE, "Backspace": BACKSPACE, "Escape": ESCAPE,
    "Delete": 0x75, "Insert": 0x72, "Home": 0x73, "End": 0x77, "PageUp": 0x74, "PageDown": 0x79,
    "ArrowLeft": 0x7B, "ArrowRight": 0x7C, "ArrowDown": 0x7D, "ArrowUp": 0x7E,
    "F1": 0x7A, "F2": 0x78, "F3": 0x63, "F4": 0x76, "F5": 0x60, "F6": 0x61, "F7": 0x62, "F8": 0x64,
    "F9": 0x65, "F10": 0x6D, "F11": 0x67, "F12": 0x6F,
}
# the system's media keys (NX_KEYTYPE_* in IOKit's ev_keymap.h)
MEDIA = {"AudioVolumeUp": 0, "AudioVolumeDown": 1, "AudioVolumeMute": 7, "MediaPlayPause": 16,
         "MediaNext": 17, "MediaPrevious": 18}
TEXT_CHUNK = 20             # UTF-16 units per event; longer strings get cut by some apps


def keycode(name) -> int | None:
    """The kVK_* code for a protocol key name, or None."""
    if not isinstance(name, str):
        return None
    if name in NAMED:
        return NAMED[name]
    ch = keys.single_char(name)
    if ch is not None:
        return LETTERS.get(ch, DIGITS.get(ch))
    return None


def flags_for(mods: list[str]) -> int:
    out = 0
    for m in keys.clean_mods(mods):
        out |= FLAGS[m]
    return out


def utf16_chunks(text: str, size: int = TEXT_CHUNK) -> list[list[int]]:
    """Text as UTF-16 code units, in pieces that never split a surrogate pair."""
    units = list(text.encode("utf-16-le", "surrogatepass"))
    words = [units[i] | (units[i + 1] << 8) for i in range(0, len(units), 2)]
    out, i = [], 0
    while i < len(words):
        end = min(i + size, len(words))
        if end < len(words) and 0xD800 <= words[end - 1] <= 0xDBFF:
            end -= 1   # keep a high surrogate with its low one
        out.append(words[i:end])
        i = end
    return out


def text_pieces(text: str) -> list:
    """("key", code) for line breaks and tabs (apps want the keys, not the characters), else ("text", str)."""
    out, run = [], []
    for ch in text.replace("\r\n", "\n"):
        if ch in "\n\r\t":
            if run:
                out.append(("text", "".join(run)))
                run = []
            out.append(("key", TAB if ch == "\t" else RETURN))
        elif ord(ch) >= 0x20 and not 0x7F <= ord(ch) < 0xA0:
            run.append(ch)
    if run:
        out.append(("text", "".join(run)))
    return out


# --- CoreGraphics, through ctypes ----------------------------------------------------------

class CGPoint(ctypes.Structure):
    _fields_ = [("x", ctypes.c_double), ("y", ctypes.c_double)]


class CGSize(ctypes.Structure):
    _fields_ = [("width", ctypes.c_double), ("height", ctypes.c_double)]


class CGRect(ctypes.Structure):
    _fields_ = [("origin", CGPoint), ("size", CGSize)]


class CG:
    """The few CoreGraphics calls the backend needs. Each event is released after it's posted."""

    def __init__(self):
        lib = ctypes.cdll.LoadLibrary(CG_PATH)
        P, U32, I32, I64, U64 = ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int32, ctypes.c_int64, ctypes.c_uint64

        def fn(name, restype, *argtypes):
            f = getattr(lib, name)
            f.restype, f.argtypes = restype, list(argtypes)
            return f
        self.source_create = fn("CGEventSourceCreate", P, I32)
        self.event_create = fn("CGEventCreate", P, P)
        self.get_location = fn("CGEventGetLocation", CGPoint, P)
        self.mouse_event = fn("CGEventCreateMouseEvent", P, P, U32, CGPoint, U32)
        self.key_event = fn("CGEventCreateKeyboardEvent", P, P, ctypes.c_uint16, ctypes.c_bool)
        self.set_flags = fn("CGEventSetFlags", None, P, U64)
        self.get_flags = fn("CGEventGetFlags", U64, P)
        self.set_unicode = fn("CGEventKeyboardSetUnicodeString", None, P, ctypes.c_ulong,
                              ctypes.POINTER(ctypes.c_uint16))
        self.get_unicode = fn("CGEventKeyboardGetUnicodeString", None, P, ctypes.c_ulong,
                              ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_uint16))
        self.scroll_event = fn("CGEventCreateScrollWheelEvent2", P, P, U32, U32, I32, I32, I32)
        self.set_field = fn("CGEventSetIntegerValueField", None, P, U32, I64)
        self.get_field = fn("CGEventGetIntegerValueField", I64, P, U32)
        self.get_type = fn("CGEventGetType", U32, P)
        self.post = fn("CGEventPost", None, U32, P)
        self.display_list = fn("CGGetActiveDisplayList", I32, U32, ctypes.POINTER(U32), ctypes.POINTER(U32))
        self.display_bounds = fn("CGDisplayBounds", CGRect, U32)
        cf = ctypes.cdll.LoadLibrary("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
        self.release = cf.CFRelease
        self.release.argtypes = [P]
        self.source = self.source_create(HID_STATE)

    def location(self) -> tuple[float, float]:
        ev = self.event_create(None)
        try:
            p = self.get_location(ev)
            return p.x, p.y
        finally:
            self.release(ev)

    def screen(self) -> tuple[float, float, float, float]:
        """(left, top, right, bottom) of all displays together."""
        ids = (ctypes.c_uint32 * 16)()
        n = ctypes.c_uint32(0)
        if self.display_list(16, ids, ctypes.byref(n)) != 0 or not n.value:
            return (-1e9, -1e9, 1e9, 1e9)
        rects = [self.display_bounds(ids[i]) for i in range(n.value)]
        return (min(r.origin.x for r in rects), min(r.origin.y for r in rects),
                max(r.origin.x + r.size.width for r in rects), max(r.origin.y + r.size.height for r in rects))

    def send(self, ev):
        """Post an event and let it go."""
        if not ev:
            raise OSError("CoreGraphics didn't make the event")
        try:
            self.post(HID_TAP, ev)
        finally:
            self.release(ev)

    def media_key(self, nx_key: int):
        """A media key press and release, as NSEvent system-defined events (subtype 8)."""
        from .. import macos
        if macos._appkit() is None:
            raise OSError("AppKit isn't there")
        with macos.autorelease():
            for down in (True, False):
                data1 = (nx_key << 16) | ((0xA if down else 0xB) << 8)
                ev = macos.msg_send(
                    macos.objc_class("NSEvent"),
                    "otherEventWithType:location:modifierFlags:timestamp:windowNumber:context:subtype:data1:data2:",
                    14, CGPoint(0, 0), 0xA00 if down else 0xB00, 0.0, 0, None, 8, data1, -1,
                    argtypes=(ctypes.c_ulong, CGPoint, ctypes.c_ulong, ctypes.c_double, ctypes.c_long,
                              ctypes.c_void_p, ctypes.c_short, ctypes.c_long, ctypes.c_long))
                cg = macos.msg_send(ev, "CGEvent") if ev else None
                if not cg:
                    raise OSError("AppKit didn't make the media key event")
                self.post(HID_TAP, cg)   # owned by the NSEvent: not released here


def usable() -> tuple[bool, str]:
    if sys.platform != "darwin":
        return False, "not a Mac"
    from .. import macos
    try:
        ctypes.cdll.LoadLibrary(CG_PATH).CGEventCreateScrollWheelEvent2
    except (OSError, AttributeError) as e:
        return False, f"CoreGraphics can't be used: {e}"
    if not macos.accessibility_allowed():
        return False, ("not allowed yet: switch on Python in System Settings → Privacy & Security → "
                       "Accessibility (input starts working a few seconds later)")
    return True, "Quartz events (allowed under Accessibility)"


class QuartzBackend(Backend):
    name = "quartz"

    def __init__(self, cg=None, clock=time.monotonic):
        self.cg = cg or CG()
        self.clock = clock
        self.wheel = Carry(1.0)
        self.held: set[str] = set()
        self._last_down: tuple[str, float, float, float] | None = None   # button, when, x, y
        self._clicks = 1
        self._pos: tuple[float, float] | None = None

    # --- the pointer ---
    def _where(self) -> tuple[float, float]:
        return self.cg.location()

    def move(self, dx, dy):
        x, y = self._where()
        left, top, right, bottom = self.cg.screen()
        nx = min(max(x + dx, left), right - 1)
        ny = min(max(y + dy, top), bottom - 1)
        kind, button = MOVED, 0
        for b in ("left", "right", "middle"):
            if b in self.held:
                button, _, _, kind = BUTTON_EVENTS[b]
                break
        ev = self.cg.mouse_event(self.cg.source, kind, CGPoint(nx, ny), button)
        if ev:
            # games and 3D apps read the deltas, not the position
            self.cg.set_field(ev, DELTA_X, int(dx))
            self.cg.set_field(ev, DELTA_Y, int(dy))
        self.cg.send(ev)

    def button(self, button, down):
        cg_button, down_type, up_type, _ = BUTTON_EVENTS[button]
        x, y = self._where()
        if down:
            now = self.clock()
            last = self._last_down
            if (last and last[0] == button and now - last[1] <= DOUBLE_CLICK
                    and math.hypot(x - last[2], y - last[3]) <= DOUBLE_CLICK_SLOP):
                self._clicks = min(self._clicks + 1, 3)
            else:
                self._clicks = 1
            self._last_down = (button, now, x, y)
            self.held.add(button)
        else:
            self.held.discard(button)
        ev = self.cg.mouse_event(self.cg.source, down_type if down else up_type, CGPoint(x, y), cg_button)
        if ev:
            # a second click soon after is a double-click only if it says so
            self.cg.set_field(ev, CLICK_STATE, self._clicks)
        self.cg.send(ev)

    def scroll(self, dx, dy):
        sx, sy = self.wheel.take(dx, dy)
        if sx or sy:
            # Quartz's wheels go the other way: positive scrolls up (and left)
            self.cg.send(self.cg.scroll_event(self.cg.source, SCROLL_LINE, 2, -sy, -sx, 0))

    # --- the keyboard ---
    def _key(self, code: int, flags: int):
        for down in (True, False):
            ev = self.cg.key_event(self.cg.source, code, down)
            if ev:
                self.cg.set_flags(ev, flags)
            self.cg.send(ev)

    def key(self, name, mods):
        if isinstance(name, str) and name in MEDIA:
            self.cg.media_key(MEDIA[name])
            return True
        code = keycode(name)
        if code is None:
            return False
        self._key(code, flags_for(mods))
        return True

    def text(self, s):
        for kind, piece in text_pieces(s):
            if kind == "key":
                self._key(piece, 0)
                continue
            for chunk in utf16_chunks(piece):
                buf = (ctypes.c_uint16 * len(chunk))(*chunk)
                for down in (True, False):
                    ev = self.cg.key_event(self.cg.source, 0, down)
                    if ev:
                        self.cg.set_flags(ev, 0)   # held modifiers mustn't turn letters into shortcuts
                        self.cg.set_unicode(ev, len(chunk), buf)
                    self.cg.send(ev)

    def close(self):
        for b in list(self.held):
            try:
                self.button(b, False)
            except Exception:
                log.exception("releasing %s failed", b)
