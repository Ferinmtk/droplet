"""Asking the agent from the window without ever blocking it.

Each request runs on a worker thread (a QThreadPool) and its answer comes
back as a signal on the GUI thread. `sync=True` answers at once, on the
calling thread, for tests.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

from PySide6.QtCore import QObject, QRunnable, QThreadPool, Signal, Slot

log = logging.getLogger("droplet_agent.app")


@dataclass
class Reply:
    data: dict | None = None        # the answer, when there was one without an error
    error: str | None = None        # what went wrong: the agent's error, or talking to it
    not_running: bool = False       # there's no agent to ask

    @property
    def ok(self) -> bool:
        return self.data is not None


def ask_now(call, request: dict, timeout: float) -> Reply:
    from ..mesh import control
    try:
        out = call(request, timeout=timeout)
    except control.NotRunning:
        return Reply(error="The droplet agent isn't running.", not_running=True)
    except (OSError, ValueError) as e:
        return Reply(error=f"Couldn't talk to the agent: {e}")
    except Exception as e:   # a bug shouldn't kill the window
        log.exception("app: %s failed", request.get("cmd"))
        return Reply(error=str(e))
    if not isinstance(out, dict):
        return Reply(error="The agent gave an answer this app doesn't understand.")
    if out.get("error"):
        return Reply(error=str(out["error"]))
    return Reply(data=out)


class _Done(QObject):
    """Lives on the GUI thread, so `done`, emitted on a worker, is delivered there."""
    done = Signal(object)

    def __init__(self, then):
        super().__init__()
        self.then = then
        self.done.connect(self.deliver)

    @Slot(object)
    def deliver(self, result):
        self.then(result)


class _Call(QRunnable):
    def __init__(self, fn, signals: _Done):
        super().__init__()
        self.fn, self.signals = fn, signals
        self.setAutoDelete(True)

    def run(self):
        try:
            result = self.fn()
        except Exception as e:
            log.exception("app: background work failed")
            result = Reply(error=str(e))
        try:
            self.signals.done.emit(result)
        except RuntimeError:
            pass   # the window went away


class Agent(QObject):
    """`ask(request, then)` calls `then(Reply)` on the GUI thread."""

    def __init__(self, call=None, sync: bool = False, parent=None):
        super().__init__(parent)
        if call is None:
            from ..mesh import control
            call = control.call
        self.call = call
        self.sync = sync
        self.pool = QThreadPool(self)
        self.pool.setMaxThreadCount(6)
        self._live: set = set()

    def ask(self, request: dict, then=None, timeout: float = 15):
        self.run(lambda: ask_now(self.call, request, timeout), then)

    def run(self, fn, then=None):
        """Run fn() on a worker thread; then(result) on the GUI thread."""
        if self.sync:
            result = fn()
            if then is not None:
                then(result)
            return
        def finished(result):
            self._live.discard(signals)
            if then is not None:
                then(result)
        signals = _Done(finished)
        self._live.add(signals)
        self.pool.start(_Call(fn, signals))

    def wait(self, msecs: int = 5000) -> bool:
        return self.pool.waitForDone(msecs)
