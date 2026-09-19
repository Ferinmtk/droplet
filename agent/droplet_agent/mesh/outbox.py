"""Chat messages and files waiting for a route (docs/mesh.md §5, route 5).

Every text and file this device sends becomes a job here first, so a send
survives the agent restarting, and each peer's messages go out in order.
Jobs are kept in the data directory until they're delivered or fail for good.

A file job refers to the file where it is (no copy: a video can be large).
Its size and modification time are recorded, and a file that changed or went
away before it could be delivered fails the job, rather than sending
something else.
"""

from __future__ import annotations

import json
import os
import secrets
import threading
import time
from pathlib import Path

QUEUED, SENDING, DONE, FAILED = "queued", "sending", "done", "failed"
KEEP_FINISHED = 200     # finished jobs remembered (in memory) so the CLI can ask how they went


class Outbox:
    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.RLock()
        self._jobs: dict[str, dict] = {}
        self._finished: dict[str, dict] = {}
        self.changed = threading.Condition(self._lock)
        try:
            raw = json.loads(path.read_text())
        except (OSError, ValueError):
            raw = []
        for j in raw if isinstance(raw, list) else []:
            if isinstance(j, dict) and isinstance(j.get("id"), str) and j.get("kind") in ("text", "file"):
                j["state"] = QUEUED   # whatever it was doing when the agent stopped, it's waiting now
                self._jobs[j["id"]] = j

    def _save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump(sorted(self._jobs.values(), key=lambda j: j["created"]), f, indent=1)
        tmp.replace(self.path)

    def add_text(self, fp: str, peer_name: str, body: str) -> dict:
        return self._add({"kind": "text", "body": body}, fp, peer_name)

    def add_file(self, fp: str, peer_name: str, path: Path, name: str, mime: str) -> dict:
        st = path.stat()
        return self._add({"kind": "file", "path": str(path), "name": name, "mime": mime, "size": st.st_size,
                          "mtime_ns": st.st_mtime_ns}, fp, peer_name)

    def _add(self, fields: dict, fp: str, peer_name: str) -> dict:
        job = {"id": secrets.token_hex(16), "fp": fp, "peer": peer_name, "created": time.time(),
               "state": QUEUED, "attempts": 0, "route": None, "error": None, **fields}
        with self._lock:
            self._jobs[job["id"]] = job
            self._save()
            self.changed.notify_all()
        return dict(job)

    def get(self, jid: str) -> dict | None:
        with self._lock:
            j = self._jobs.get(jid) or self._finished.get(jid)
            return dict(j) if j else None

    def queued(self) -> list[dict]:
        with self._lock:
            return [dict(j) for j in sorted(self._jobs.values(), key=lambda j: j["created"])]

    def for_peer(self, fp: str) -> list[dict]:
        return [j for j in self.queued() if j["fp"] == fp]

    def update(self, jid: str, **fields):
        with self._lock:
            j = self._jobs.get(jid)
            if j is None:
                return
            j.update(fields)
            if j["state"] in (DONE, FAILED):
                self._finished[jid] = self._jobs.pop(jid)
                while len(self._finished) > KEEP_FINISHED:
                    self._finished.pop(next(iter(self._finished)))
            self._save()
            self.changed.notify_all()

    def drop_peer(self, fp: str, why: str):
        for j in self.for_peer(fp):
            self.update(j["id"], state=FAILED, error=why)

    def wait(self, jid: str, until, timeout: float) -> dict | None:
        """Wait until `until(job)` is true, or `timeout` seconds pass. Returns the job."""
        end = time.monotonic() + timeout
        with self._lock:
            while True:
                j = self._jobs.get(jid) or self._finished.get(jid)
                if j is None or until(j):
                    return dict(j) if j else None
                left = end - time.monotonic()
                if left <= 0:
                    return dict(j)
                self.changed.wait(left)
