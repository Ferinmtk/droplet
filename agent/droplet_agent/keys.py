"""Key names from the protocol, mapped to Linux evdev codes, X keysyms and xdotool names.

The protocol names keys the way the web's KeyboardEvent.key does (docs/remote.md
§3.1). Codes are from linux/input-event-codes.h.
"""

from __future__ import annotations

import unicodedata

# --- evdev codes -------------------------------------------------------------

KEY_ESC = 1
KEY_BACKSPACE = 14
KEY_TAB = 15
KEY_ENTER = 28
KEY_LEFTCTRL = 29
KEY_LEFTSHIFT = 42
KEY_LEFTALT = 56
KEY_SPACE = 57
KEY_LEFTMETA = 125

BTN_LEFT = 0x110
BTN_RIGHT = 0x111
BTN_MIDDLE = 0x112

BUTTONS = {"left": BTN_LEFT, "right": BTN_RIGHT, "middle": BTN_MIDDLE}

MODIFIERS = {
    "ctrl": KEY_LEFTCTRL,
    "alt": KEY_LEFTALT,
    "shift": KEY_LEFTSHIFT,
    "meta": KEY_LEFTMETA,
}

# letters by their place on the keyboard (US QWERTY positions)
_LETTERS = {
    "q": 16, "w": 17, "e": 18, "r": 19, "t": 20, "y": 21, "u": 22, "i": 23, "o": 24, "p": 25,
    "a": 30, "s": 31, "d": 32, "f": 33, "g": 34, "h": 35, "j": 36, "k": 37, "l": 38,
    "z": 44, "x": 45, "c": 46, "v": 47, "b": 48, "n": 49, "m": 50,
}
_DIGITS = {"1": 2, "2": 3, "3": 4, "4": 5, "5": 6, "6": 7, "7": 8, "8": 9, "9": 10, "0": 11}

NAMED_KEYS = {
    "Enter": KEY_ENTER,
    "Backspace": KEY_BACKSPACE,
    "Tab": KEY_TAB,
    "Escape": KEY_ESC,
    "Space": KEY_SPACE,
    " ": KEY_SPACE,  # what KeyboardEvent.key actually says for the space bar
    "Delete": 111,
    "Insert": 110,
    "Home": 102,
    "End": 107,
    "PageUp": 104,
    "PageDown": 109,
    "ArrowUp": 103,
    "ArrowDown": 108,
    "ArrowLeft": 105,
    "ArrowRight": 106,
    "F1": 59, "F2": 60, "F3": 61, "F4": 62, "F5": 63, "F6": 64,
    "F7": 65, "F8": 66, "F9": 67, "F10": 68, "F11": 87, "F12": 88,
    "MediaPlayPause": 164,   # KEY_PLAYPAUSE
    "MediaNext": 163,        # KEY_NEXTSONG
    "MediaPrevious": 165,    # KEY_PREVIOUSSONG
    "MediaStop": 166,        # KEY_STOPCD
    "AudioVolumeUp": 115,
    "AudioVolumeDown": 114,
    "AudioVolumeMute": 113,
    "PrintScreen": 99,       # KEY_SYSRQ, which is the Print key
    "ContextMenu": 127,      # KEY_COMPOSE, the menu key
}

# X keysyms for the same names, for the portal and xdotool
NAMED_KEYSYMS = {
    "Enter": ("Return", 0xFF0D),
    "Backspace": ("BackSpace", 0xFF08),
    "Tab": ("Tab", 0xFF09),
    "Escape": ("Escape", 0xFF1B),
    "Space": ("space", 0x20),
    " ": ("space", 0x20),
    "Delete": ("Delete", 0xFFFF),
    "Insert": ("Insert", 0xFF63),
    "Home": ("Home", 0xFF50),
    "End": ("End", 0xFF57),
    "PageUp": ("Prior", 0xFF55),
    "PageDown": ("Next", 0xFF56),
    "ArrowUp": ("Up", 0xFF52),
    "ArrowDown": ("Down", 0xFF54),
    "ArrowLeft": ("Left", 0xFF51),
    "ArrowRight": ("Right", 0xFF53),
    **{f"F{i}": (f"F{i}", 0xFFBE + i - 1) for i in range(1, 13)},
    "MediaPlayPause": ("XF86AudioPlay", 0x1008FF14),
    "MediaNext": ("XF86AudioNext", 0x1008FF17),
    "MediaPrevious": ("XF86AudioPrev", 0x1008FF16),
    "MediaStop": ("XF86AudioStop", 0x1008FF15),
    "AudioVolumeUp": ("XF86AudioRaiseVolume", 0x1008FF13),
    "AudioVolumeDown": ("XF86AudioLowerVolume", 0x1008FF11),
    "AudioVolumeMute": ("XF86AudioMute", 0x1008FF12),
    "PrintScreen": ("Print", 0xFF61),
    "ContextMenu": ("Menu", 0xFF67),
}

XDOTOOL_MODS = {"ctrl": "ctrl", "alt": "alt", "shift": "shift", "meta": "super"}

# ASCII on a US layout: character → (evdev code, needs shift)
US_LAYOUT: dict[str, tuple[int, bool]] = {}
for _ch, _code in _LETTERS.items():
    US_LAYOUT[_ch] = (_code, False)
    US_LAYOUT[_ch.upper()] = (_code, True)
for _ch, _code in _DIGITS.items():
    US_LAYOUT[_ch] = (_code, False)
for _plain, _shifted, _code in (
    ("-", "_", 12), ("=", "+", 13), ("[", "{", 26), ("]", "}", 27), (";", ":", 39),
    ("'", '"', 40), ("`", "~", 41), ("\\", "|", 43), (",", "<", 51), (".", ">", 52),
    ("/", "?", 53),
):
    US_LAYOUT[_plain] = (_code, False)
    US_LAYOUT[_shifted] = (_code, True)
for _ch, _code in zip("!@#$%^&*()", (2, 3, 4, 5, 6, 7, 8, 9, 10, 11)):
    US_LAYOUT[_ch] = (_code, True)
US_LAYOUT[" "] = (KEY_SPACE, False)
US_LAYOUT["\n"] = (KEY_ENTER, False)
US_LAYOUT["\t"] = (KEY_TAB, False)

MODS = tuple(MODIFIERS)


def clean_mods(mods) -> list[str]:
    """The valid modifiers, in a fixed order, once each."""
    if not isinstance(mods, (list, tuple)):
        return []
    wanted = {m for m in mods if isinstance(m, str)}
    return [m for m in MODS if m in wanted]


def single_char(name: str) -> str | None:
    """"a"–"z" / "0"–"9" (any case) as a lower-case character, else None."""
    if len(name) == 1 and (name.lower() in _LETTERS or name in _DIGITS):
        return name.lower()
    return None


def evdev_code(name: str) -> int | None:
    """The evdev code for a protocol key name, or None when it isn't one."""
    if not isinstance(name, str):
        return None
    if name in NAMED_KEYS:
        return NAMED_KEYS[name]
    ch = single_char(name)
    if ch is not None:
        return _LETTERS.get(ch) or _DIGITS.get(ch)
    return None


def keysym_for_char(ch: str) -> int | None:
    """The X keysym that types this character, on any layout.

    Latin-1 characters are their own keysym; everything else is
    0x01000000 + the code point. Control characters other than newline and
    tab have none.
    """
    cp = ord(ch)
    if ch == "\n" or ch == "\r":
        return 0xFF0D  # Return
    if ch == "\t":
        return 0xFF09  # Tab
    if cp < 0x20 or 0x7F <= cp < 0xA0:
        return None
    if cp <= 0xFF:
        return cp
    if 0xD800 <= cp <= 0xDFFF:
        return None  # a lone surrogate isn't a character
    return 0x01000000 + cp


def keysym_for_key(name: str) -> int | None:
    """The keysym for a protocol key name (a named key or a single character)."""
    if not isinstance(name, str):
        return None
    if name in NAMED_KEYSYMS:
        return NAMED_KEYSYMS[name][1]
    ch = single_char(name)
    return ord(ch) if ch else None


def xdotool_key(name: str, mods: list[str]) -> str | None:
    """"ctrl+shift+Return"-style key spec for xdotool, or None."""
    if not isinstance(name, str):
        return None
    if name in NAMED_KEYSYMS:
        key = NAMED_KEYSYMS[name][0]
    else:
        key = single_char(name)
        if key is None:
            return None
    return "+".join([XDOTOOL_MODS[m] for m in clean_mods(mods)] + [key])


# typographic characters people paste a lot, with their plain ASCII stand-ins
_ASCII_STANDINS = {
    "‘": "'", "’": "'", "‚": "'", "‛": "'",
    "“": '"', "”": '"', "„": '"', "‟": '"',
    "–": "-", "—": "-", "−": "-", "…": "...",
    " ": " ", " ": " ", " ": " ", "×": "x",
    "«": '"', "»": '"', "•": "*", "ß": "ss",
    "æ": "ae", "Æ": "AE", "œ": "oe", "Œ": "OE", "ø": "o", "Ø": "O",
}


def to_ascii(text: str) -> tuple[str, int]:
    """Text a US keyboard can type: accents dropped (é→e), quotes straightened.

    Returns (typeable text, how many characters were left out).
    """
    out, skipped = [], 0
    for ch in text.replace("\r\n", "\n"):
        if ch in US_LAYOUT:
            out.append(ch)
            continue
        if ch in _ASCII_STANDINS:
            out.append(_ASCII_STANDINS[ch])
            continue
        base = "".join(c for c in unicodedata.normalize("NFKD", ch) if c in US_LAYOUT)
        if base:
            out.append(base)
        else:
            skipped += 1
    return "".join(out), skipped
