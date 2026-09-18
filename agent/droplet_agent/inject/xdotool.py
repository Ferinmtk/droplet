"""Input on an X11 session, through xdotool."""

from __future__ import annotations

import logging
import subprocess

from .. import env, keys
from . import Backend, Carry

log = logging.getLogger("droplet_agent.input")

X_BUTTONS = {"left": "1", "middle": "2", "right": "3"}
# X's wheel is buttons: 4 up, 5 down, 6 left, 7 right
WHEEL_UP, WHEEL_DOWN, WHEEL_LEFT, WHEEL_RIGHT = "4", "5", "6", "7"


def usable() -> tuple[bool, str]:
    if not env.x11_display():
        return False, "not an X11 session"
    if not env.which("xdotool"):
        return False, "xdotool isn't installed"
    return True, "X11 with xdotool"


class XdotoolBackend(Backend):
    name = "x11"

    def __init__(self, runner=None):
        self.wheel = Carry(1.0)
        self._runner = runner or self._run

    @staticmethod
    def _run(args: list[str]):
        try:
            r = subprocess.run(["xdotool", *args], env=env.session_env(), timeout=10,
                               stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        except (OSError, subprocess.TimeoutExpired) as e:
            log.warning("xdotool failed: %s", e)
            return
        if r.returncode != 0:
            log.warning("xdotool %s failed: %s", args[0], r.stderr.decode(errors="replace").strip())

    def move(self, dx, dy):
        self._runner(["mousemove_relative", "--", str(dx), str(dy)])

    def button(self, button, down):
        self._runner(["mousedown" if down else "mouseup", X_BUTTONS[button]])

    def scroll(self, dx, dy):
        sx, sy = self.wheel.take(dx, dy)
        if sy:
            self._runner(["click", "--repeat", str(abs(sy)), WHEEL_DOWN if sy > 0 else WHEEL_UP])
        if sx:
            self._runner(["click", "--repeat", str(abs(sx)), WHEEL_RIGHT if sx > 0 else WHEEL_LEFT])

    def key(self, name, mods):
        spec = keys.xdotool_key(name, mods)
        if spec is None:
            return False
        self._runner(["key", "--clearmodifiers", spec])
        return True

    def text(self, s):
        # xdotool types any Unicode, remapping a spare keycode when it must
        self._runner(["type", "--clearmodifiers", "--delay", "2", "--", s])
