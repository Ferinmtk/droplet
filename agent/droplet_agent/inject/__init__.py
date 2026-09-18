"""Pointer and keyboard input: the protocol's events, applied through a backend.

Backends, tried in this order when the config says "auto":

- portal: the xdg-desktop-portal RemoteDesktop interface (KDE, GNOME). The
  desktop asks the user once; a restore token saves asking again.
- uinput: a virtual mouse and keyboard in the kernel. Works under any
  compositor (niri, sway, Hyprland…) but needs /dev/uinput to be writable,
  which takes a one-time root step (see `droplet-agent doctor`).
- x11: xdotool, on an X11 session only.
- log: acts on nothing, just records. For tests and --dry-run.
"""

from __future__ import annotations

import logging
import math

from .. import keys

log = logging.getLogger("droplet_agent.input")

MAX_MOVE = 10_000     # pixels per event; anything bigger is a broken controller
MAX_SCROLL = 1_000    # lines per event
MAX_TEXT = 10_000     # characters per text event
MAX_CLICKS = 3


class Carry:
    """Turns fractional deltas into whole steps, keeping the remainder for next time.

    `unit` is how many output steps one input unit is worth: 1 for pixels,
    120 for hi-res wheel units per line.
    """

    def __init__(self, unit: float = 1.0):
        self.unit = unit
        self.rx = 0.0
        self.ry = 0.0

    def take(self, dx: float, dy: float) -> tuple[int, int]:
        # a change of direction drops what was left over the other way,
        # so reversing a scroll responds at once
        if dx and self.rx and (dx > 0) != (self.rx > 0):
            self.rx = 0.0
        if dy and self.ry and (dy > 0) != (self.ry > 0):
            self.ry = 0.0
        self.rx += dx * self.unit
        self.ry += dy * self.unit
        ix, iy = int(self.rx), int(self.ry)  # toward zero, so the remainder keeps its sign
        self.rx -= ix
        self.ry -= iy
        return ix, iy


class Backend:
    """What every input backend can do. Scroll is in lines, with the protocol's signs:
    positive dy scrolls down, positive dx scrolls right."""

    name = "none"

    def move(self, dx: int, dy: int):
        raise NotImplementedError

    def button(self, button: str, down: bool):
        raise NotImplementedError

    def scroll(self, dx: float, dy: float):
        raise NotImplementedError

    def key(self, name: str, mods: list[str]) -> bool:
        """Press and release a named key with modifiers held. False if the name is unknown."""
        raise NotImplementedError

    def text(self, s: str):
        raise NotImplementedError

    def close(self):
        pass


def _num(v, limit: float) -> float | None:
    if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v):
        return None
    return max(-limit, min(limit, float(v)))


class InputHandler:
    """Applies a batch of protocol input events, in order, to a backend.

    Keeps the pointer's fractional pixels between events and remembers held
    buttons, so a controller that vanishes mid-drag can't leave one stuck.
    """

    def __init__(self, backend: Backend):
        self.backend = backend
        self.motion = Carry(1.0)
        self.held: set[str] = set()

    def apply(self, events) -> int:
        """Apply what's valid; returns how many events were applied."""
        if not isinstance(events, list):
            return 0
        done = 0
        for ev in events:
            if not isinstance(ev, dict):
                continue
            try:
                if self._one(ev):
                    done += 1
            except Exception:
                log.exception("input event %r failed", ev.get("k"))
        return done

    def _one(self, ev: dict) -> bool:
        k = ev.get("k")
        if k == "move":
            dx, dy = _num(ev.get("dx", 0), MAX_MOVE), _num(ev.get("dy", 0), MAX_MOVE)
            if dx is None or dy is None:
                return False
            ix, iy = self.motion.take(dx, dy)
            if ix or iy:
                self.backend.move(ix, iy)
            return True
        if k == "button":
            b = ev.get("b")
            if b not in keys.BUTTONS:
                return False
            down = bool(ev.get("down"))
            self.backend.button(b, down)
            (self.held.add if down else self.held.discard)(b)
            return True
        if k == "click":
            b = ev.get("b", "left")
            if b not in keys.BUTTONS:
                return False
            n = ev.get("n", 1)
            n = n if isinstance(n, int) and not isinstance(n, bool) else 1
            for _ in range(max(1, min(MAX_CLICKS, n))):
                self.backend.button(b, True)
                self.backend.button(b, False)
            self.held.discard(b)
            return True
        if k == "scroll":
            dx, dy = _num(ev.get("dx", 0), MAX_SCROLL), _num(ev.get("dy", 0), MAX_SCROLL)
            if dx is None or dy is None:
                return False
            if dx or dy:
                self.backend.scroll(dx, dy)
            return True
        if k == "key":
            return self.backend.key(ev.get("key"), keys.clean_mods(ev.get("mods")))
        if k == "text":
            s = ev.get("s")
            if not isinstance(s, str) or not s:
                return False
            self.backend.text(s[:MAX_TEXT])
            return True
        return False  # unknown kinds are ignored, per the protocol

    def release_all(self):
        """Let go of any held buttons (the controller went quiet mid-drag)."""
        for b in list(self.held):
            try:
                self.backend.button(b, False)
            except Exception:
                log.exception("releasing %s failed", b)
        self.held.clear()
