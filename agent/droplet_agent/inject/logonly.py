"""An input backend that acts on nothing and records what it was asked to do.

Used by `droplet-agent run --dry-run` and the tests.
"""

from __future__ import annotations

import logging

from .. import keys
from . import Backend, Carry

log = logging.getLogger("droplet_agent.input")


class LogBackend(Backend):
    name = "log"

    def __init__(self):
        self.calls: list[tuple] = []
        self.wheel = Carry(1.0)

    def _record(self, *call):
        self.calls.append(call)
        log.info("input (dry run): %s", " ".join(map(str, call)))

    def move(self, dx, dy):
        self._record("move", dx, dy)

    def button(self, button, down):
        self._record("button", button, "down" if down else "up")

    def scroll(self, dx, dy):
        sx, sy = self.wheel.take(dx, dy)
        if sx or sy:
            self._record("scroll", sx, sy)

    def key(self, name, mods):
        if keys.evdev_code(name) is None:
            return False
        self._record("key", "+".join([*mods, name]))
        return True

    def text(self, s):
        self._record("text", s)
