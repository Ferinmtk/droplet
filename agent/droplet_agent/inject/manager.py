"""Picks the input backend at startup and keeps track of whether input works."""

from __future__ import annotations

import logging
from pathlib import Path

from . import Backend, InputHandler
from . import remotedesktop, uinput, xdotool
from .logonly import LogBackend

log = logging.getLogger("droplet_agent.input")

AUTO_ORDER = ("portal", "uinput", "x11")


def probe(choice: str = "auto") -> list[tuple[str, bool, str]]:
    """(backend, usable, why) for each backend the choice would try. Read-only."""
    order = AUTO_ORDER if choice == "auto" else (choice,)
    out = []
    for name in order:
        if name == "portal":
            ok, why = remotedesktop.usable()
        elif name == "uinput":
            ok, why = uinput.writable()
        elif name == "x11":
            ok, why = xdotool.usable()
        elif name == "log":
            ok, why = True, "dry run: input is only logged"
        else:
            ok, why = False, f"unknown input backend {name!r}"
        out.append((name, ok, why))
    return out


class InputManager:
    """Owns the input backend. `on_change()` is called when input starts or stops working."""

    def __init__(self, choice: str, app_id: str, token_path: Path, on_change, text_mode: str = "auto"):
        self.choice = choice
        self.app_id = app_id
        self.token_path = token_path
        self.on_change = on_change
        self.text_mode = text_mode
        self.backend: Backend | None = None
        self.handler: InputHandler | None = None
        self.reason = "not started"
        self._session: remotedesktop.PortalSession | None = None
        self._remaining: list[str] = []
        self._why: list[str] = []

    @property
    def available(self) -> bool:
        return self.handler is not None

    def start(self):
        self._remaining = list(AUTO_ORDER if self.choice == "auto" else (self.choice,))
        self._next()

    def use(self, backend: Backend):
        self.backend = backend
        self.handler = InputHandler(backend)
        self.reason = f"using {backend.name}"
        log.info("input: using the %s backend", backend.name)

    def _next(self):
        while self._remaining:
            name = self._remaining.pop(0)
            if name == "log":
                self.use(LogBackend())
                return
            if name == "portal":
                ok, why = remotedesktop.usable()
                if ok:
                    self.reason = remotedesktop.State.PENDING.value
                    self._session = remotedesktop.PortalSession(self.app_id, self.token_path, self._portal_state)
                    self._session.start()
                    return  # carries on in _portal_state once the user answers
            elif name == "uinput":
                ok, why = uinput.writable()
                if ok:
                    try:
                        self.use(uinput.open_backend(self.text_mode))
                        return
                    except OSError as e:
                        why = f"couldn't create the virtual devices: {e}"
            elif name == "x11":
                ok, why = xdotool.usable()
                if ok:
                    self.use(xdotool.XdotoolBackend())
                    return
            else:
                why = f"unknown input backend {name!r}"
            self._why.append(f"{name}: {why}")
        self.reason = "no way to inject input here. " + "; ".join(self._why)
        log.warning("input: %s", self.reason)

    def _portal_state(self, state, detail):
        S = remotedesktop.State
        if state is S.STARTED:
            self.use(remotedesktop.PortalBackend(self._session))
            self.on_change()
        elif state in (S.DENIED, S.CLOSED):
            # the user's answer: don't go round the portal with uinput
            was = self.available
            self.backend = self.handler = None
            self.reason = state.value
            if was:
                self.on_change()
        elif state is S.FAILED:
            self._why.append(f"portal: {detail or 'failed'}")
            was = self.available
            self.backend = self.handler = None
            self._next()
            if was or self.available:
                self.on_change()

    def apply(self, events) -> int:
        h = self.handler
        return h.apply(events) if h else 0

    def release_all(self):
        h = self.handler
        if h and h.held:
            h.release_all()

    def close(self):
        if self.backend is not None:
            self.release_all()
            self.backend.close()
        elif self._session is not None:
            self._session.close()
