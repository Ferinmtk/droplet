"""Files on their way, either way: how far each has got, how fast, and how long it has left.

The numbers are real byte counts: for a file this device sends over a link, the bytes it has
served (mesh/files.py) or written to the iPhone's channel; for one it receives, the bytes it
has saved. What the window, the tray, the CLI and `status` show of a transfer comes from here,
and `cancel` stops one by its id (an offer's id, which is also the outbox job's).

A finished transfer is kept for a little while (KEEP seconds), so a window that looks a moment
later still sees "Sent" or "Cancelled".
"""

from __future__ import annotations

import threading
import time
from collections import deque

ACTIVE, DONE, FAILED, CANCELLED, WAITING = "active", "done", "failed", "cancelled", "waiting"
KEEP = 120          # seconds a finished transfer stays in the list
WINDOW = 4.0        # seconds of samples the speed is worked out from


def size_text(n) -> str:
    if not isinstance(n, (int, float)):
        return ""
    for unit in ("bytes", "KB", "MB", "GB"):
        if n < 1000 or unit == "GB":
            return f"{int(n)} {unit}" if unit == "bytes" else f"{n:.1f} {unit}".replace(".0 ", " ")
        n /= 1024
    return ""


def eta_text(seconds) -> str:
    """"12 s left", "3 min left", "1 h 5 min left"."""
    if not isinstance(seconds, (int, float)) or seconds < 0:
        return ""
    s = int(seconds)
    if s < 60:
        return f"{max(s, 1)} s left"
    if s < 3600:
        return f"{(s + 30) // 60} min left"
    return f"{s // 3600} h {(s % 3600) // 60} min left"


def progress_text(t: dict) -> str:
    """One transfer, in a line: "45% · 12 s left · 3.2 MB/s", or how it ended."""
    state = t.get("state")
    if state == DONE:
        return "Sent" if t.get("dir") == "out" else "Received"
    if state == CANCELLED:
        return f"Cancelled: {t['error']}" if t.get("error") and t["error"] != "cancelled here" else "Cancelled"
    if state == FAILED:
        return f"Failed: {t.get('error') or 'it stopped'}"
    if state == WAITING:
        return f"Stopped at {t.get('percent', 0)}%: it carries on when it can"
    if t.get("route") == "hub" and not t.get("done"):
        return "Sending through the hub…"
    bits = [f"{t.get('percent', 0)}%"]
    if t.get("eta") is not None:
        bits.append(eta_text(t["eta"]))
    if t.get("rate"):
        bits.append(f"{size_text(t['rate'])}/s")
    elif not t.get("done"):
        bits.append("starting…")
    return " · ".join(bits)


class Transfers:
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self._lock = threading.Lock()
        self._items: dict[str, dict] = {}
        self._samples: dict[str, deque] = {}

    def start(self, tid: str, *, direction: str, fp: str, peer: str, name: str, size: int, route: str | None = None,
              done: int = 0):
        """A transfer begins (or starts again, resuming: what it had stays unless `done` says more)."""
        now = self.clock()
        with self._lock:
            old = self._items.get(tid)
            t = {"id": tid, "dir": direction, "fp": fp, "peer": peer, "name": name, "size": max(0, int(size or 0)),
                 "done": max(done, (old or {}).get("done", 0) if old and old["state"] != CANCELLED else 0),
                 "state": ACTIVE, "error": None, "route": route, "started": (old or {}).get("started", now),
                 "ended": None, "wall": time.time()}
            self._items[tid] = t
            self._samples[tid] = deque([(now, t["done"])])

    def progress(self, tid: str, done: int):
        now = self.clock()
        with self._lock:
            t = self._items.get(tid)
            if t is None or t["state"] != ACTIVE:
                return
            t["done"] = min(max(int(done), 0), t["size"]) if t["size"] else max(int(done), 0)
            s = self._samples.setdefault(tid, deque())
            s.append((now, t["done"]))
            while len(s) > 2 and now - s[0][0] > WINDOW:
                s.popleft()

    def finish(self, tid: str, state: str, error: str | None = None):
        with self._lock:
            t = self._items.get(tid)
            if t is None:
                return
            t["state"], t["error"] = state, error
            t["ended"] = self.clock()
            if state == DONE:
                t["done"] = t["size"]

    def get(self, tid: str) -> dict | None:
        with self._lock:
            t = self._items.get(tid)
            return self._view(t, self.clock()) if t else None

    def active(self) -> list[dict]:
        return [t for t in self.list() if t["state"] == ACTIVE]

    def list(self) -> list[dict]:
        """Every transfer, active ones first (oldest first), then the recently finished."""
        now = self.clock()
        with self._lock:
            for tid in [k for k, t in self._items.items() if t["ended"] is not None and now - t["ended"] > KEEP]:
                self._items.pop(tid, None)
                self._samples.pop(tid, None)
            items = sorted(self._items.values(), key=lambda t: (t["state"] != ACTIVE, t["started"]))
            return [self._view(t, now) for t in items]

    def _view(self, t: dict, now: float) -> dict:
        out = {k: t[k] for k in ("id", "dir", "fp", "peer", "name", "size", "done", "state", "error", "route")}
        out["percent"] = int(t["done"] * 100 / t["size"]) if t["size"] else (100 if t["state"] == DONE else 0)
        rate = None
        s = self._samples.get(t["id"])
        if t["state"] == ACTIVE and s and len(s) >= 2:
            (t0, d0), (t1, d1) = s[0], s[-1]
            # quiet for a while: it's stalled, not still going at the last speed
            if now - t1 < 3 and t1 > t0:
                rate = (d1 - d0) / (t1 - t0)
        out["rate"] = int(rate) if rate else None
        left = t["size"] - t["done"]
        out["eta"] = int(left / rate + 0.5) if rate and rate > 0 and left > 0 else None
        return out
