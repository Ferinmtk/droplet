"""Files sent directly: offers, serving them with Range, and downloading with resume.

The sender offers `{"t":"offer","id","name","size","mime"}` over the link. The
receiver fetches `GET https://<sender>:<port>/mesh/files/<id>` over the same
mutual TLS, streamed, and resumes an interrupted download with
`Range: bytes=<what it has>-`. Only the peer the offer was made to can fetch
it. When the file is complete and its size is right, the receiver answers
`{"t":"ack","id"}`; if it won't take it at all, `{"t":"nack","id","error"}`.

A partial download is kept as a hidden `.part` file beside the downloads,
keyed by the sender's fingerprint and the offer id, so a re-offer of the same
file (after either side restarted) carries on where it stopped.
"""

from __future__ import annotations

import http.client
import json
import logging
import os
import re
import shutil
import threading
import time
from pathlib import Path

from .tlsctx import client_context

log = logging.getLogger("droplet_agent.mesh.files")

OFFER_ID = re.compile(r"^[0-9a-f]{16,64}$")
CHUNK = 256 * 1024
MAX_SIZE = 1 << 40          # 1 TiB: anything bigger is a lie
SPACE_MARGIN = 64 * 1024 * 1024
DOWNLOAD_TRIES = 5
KEEP_COMPLETED = 500


def safe_name(raw) -> str:
    """A file name that can't escape the downloads folder or hide itself."""
    name = str(raw or "").replace("\\", "/").split("/")[-1]
    name = "".join(ch for ch in name if ch.isprintable() and ch not in '<>:"|?*').strip()
    name = name.lstrip(".").strip()
    if len(name.encode()) > 200:
        stem, dot, ext = name.rpartition(".")
        ext = ext if dot and len(ext) <= 16 else ""
        base = (stem if dot else name).encode()[:200 - len(ext) - 1].decode(errors="ignore")
        name = f"{base}.{ext}" if ext else base
    return name or "file"


def unique_path(directory: Path, name: str) -> Path:
    p = directory / name
    stem, suffix = p.stem, p.suffix
    i = 1
    while p.exists():
        p = directory / f"{stem} ({i}){suffix}"
        i += 1
    return p


def parse_range(header: str | None, size: int) -> tuple[int, int] | None | str:
    """A `Range` header → (first, last) inclusive; None for the whole file; "bad" if unsatisfiable.

    Only a single `bytes=` range is supported, which is all resuming needs.
    """
    if not header:
        return None
    m = re.fullmatch(r"\s*bytes\s*=\s*(\d*)\s*-\s*(\d*)\s*", header)
    if not m or (m.group(1) == "" and m.group(2) == ""):
        return None   # a form we don't support: send it all, as HTTP allows
    if m.group(1) == "":
        n = int(m.group(2))            # the last n bytes
        if n == 0 or size == 0:
            return "bad"
        return max(0, size - n), size - 1
    first = int(m.group(1))
    last = int(m.group(2)) if m.group(2) else size - 1
    if first >= size or last < first:
        return "bad"
    return first, min(last, size - 1)


class Offer:
    def __init__(self, oid: str, fp: str, name: str, size: int, mime: str, opener, check=None):
        self.id, self.fp, self.name, self.size, self.mime = oid, fp, name, size, mime
        self.opener = opener          # () -> a binary file object
        self.check = check            # () -> None, or why the file can't be sent any more
        self.sent = 0                 # bytes served, all requests together
        self.last_activity = time.monotonic()
        self.done = threading.Event()
        self.result: tuple[bool, str] | None = None   # (ok, error)

    def message(self) -> dict:
        return {"t": "offer", "id": self.id, "name": self.name, "size": self.size, "mime": self.mime}

    def finish(self, ok: bool, error: str = ""):
        if self.result is None:
            self.result = (ok, error)
        self.done.set()


class Offers:
    """What this device is offering, by id."""

    def __init__(self, max_rate: int = 0):
        self._lock = threading.Lock()
        self._offers: dict[str, Offer] = {}
        self.max_rate = max_rate   # bytes a second when serving, 0 for no limit

    def add(self, offer: Offer):
        with self._lock:
            self._offers[offer.id] = offer

    def get(self, oid: str) -> Offer | None:
        with self._lock:
            return self._offers.get(oid)

    def remove(self, oid: str):
        with self._lock:
            self._offers.pop(oid, None)

    def resolve(self, fp: str, oid: str, ok: bool, error: str = ""):
        """The receiver's ack or nack. Only the peer the offer was made to counts."""
        o = self.get(oid)
        if o is not None and o.fp == fp:
            o.finish(ok, error)

    def serve(self, sock, oid: str, fp: str, headers: dict, method: str, send_head):
        """Answer GET/HEAD /mesh/files/<oid> for the peer `fp`. `send_head(status, headers)`."""
        o = self.get(oid) if OFFER_ID.match(oid or "") else None
        if o is None or o.fp != fp:
            # the same answer whether it doesn't exist or isn't for you
            send_head(404, {"Content-Length": "0"})
            return
        why = o.check() if o.check else None
        if why:
            send_head(410, {"Content-Length": "0"})
            o.finish(False, why)
            return
        rng = parse_range(headers.get("range"), o.size)
        if rng == "bad":
            send_head(416, {"Content-Range": f"bytes */{o.size}", "Content-Length": "0"})
            return
        first, last = (0, o.size - 1) if rng is None else rng
        length = max(0, last - first + 1)
        head = {"Content-Type": o.mime or "application/octet-stream", "Content-Length": str(length),
                "Accept-Ranges": "bytes"}
        if rng is not None:
            head["Content-Range"] = f"bytes {first}-{last}/{o.size}"
        send_head(206 if rng is not None else 200, head)
        if method == "HEAD" or length == 0:
            return
        o.last_activity = time.monotonic()
        started = time.monotonic()
        done = 0
        with o.opener() as f:
            f.seek(first)
            while done < length:
                data = f.read(min(CHUNK, length - done))
                if not data:
                    raise OSError("the file got shorter while it was being sent")
                sock.sendall(data)
                done += len(data)
                o.sent += len(data)
                o.last_activity = time.monotonic()
                if self.max_rate:
                    ahead = done / self.max_rate - (time.monotonic() - started)
                    if ahead > 0:
                        time.sleep(ahead)


class Completed:
    """Offers already received, by (sender fingerprint, id), so a re-offer isn't saved twice."""

    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.Lock()
        try:
            data = json.loads(path.read_text())
            self._items = [x for x in data if isinstance(x, dict)] if isinstance(data, list) else []
        except (OSError, ValueError):
            self._items = []

    def has(self, fp: str, oid: str) -> str | None:
        with self._lock:
            for x in self._items:
                if x.get("fp") == fp and x.get("id") == oid:
                    return x.get("path") or ""
        return None

    def add(self, fp: str, oid: str, path: str):
        with self._lock:
            self._items.append({"fp": fp, "id": oid, "path": path, "ts": int(time.time())})
            self._items = self._items[-KEEP_COMPLETED:]
            self.path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self._items))
            tmp.replace(self.path)


class DownloadError(Exception):
    def __init__(self, message: str, permanent: bool = False):
        super().__init__(message)
        self.permanent = permanent


def part_paths(directory: Path, fp: str, oid: str) -> tuple[Path, Path]:
    base = directory / f".droplet-{fp[:16]}-{oid}"
    return base.with_name(base.name + ".part"), base.with_name(base.name + ".json")


def check_offer(msg: dict) -> tuple[str, str, int, str]:
    """(id, safe name, size, mime) from an offer. Raises DownloadError(permanent) if it's malformed."""
    oid, size = msg.get("id"), msg.get("size")
    if not isinstance(oid, str) or not OFFER_ID.match(oid):
        raise DownloadError("bad offer id", True)
    if not isinstance(size, int) or isinstance(size, bool) or not 0 <= size <= MAX_SIZE:
        raise DownloadError("bad size", True)
    mime = msg.get("mime") if isinstance(msg.get("mime"), str) else ""
    mime = mime if re.fullmatch(r"[\w.+-]+/[\w.+-]+", mime or "") else "application/octet-stream"
    return oid, safe_name(msg.get("name")), size, mime


def download(identity, fp: str, hosts: list[tuple[str, int]], oid: str, name: str, size: int,
             directory: Path, *, tries: int = DOWNLOAD_TRIES, timeout: float = 20, sleep=time.sleep,
             on_progress=None) -> Path:
    """Fetch an offer into `directory`, resuming a partial one. Returns the saved file."""
    directory.mkdir(parents=True, exist_ok=True)
    part, meta = part_paths(directory, fp, oid)
    try:
        prev = json.loads(meta.read_text())
    except (OSError, ValueError):
        prev = None
    if not isinstance(prev, dict) or prev.get("size") != size or not part.exists() or part.stat().st_size > size:
        part.unlink(missing_ok=True)
        meta.write_text(json.dumps({"size": size, "name": name, "fp": fp}))
    have = part.stat().st_size if part.exists() else 0
    free = shutil.disk_usage(directory).free
    if size - have + SPACE_MARGIN > free:
        raise DownloadError(f"not enough space for {name} ({size} bytes)", True)
    last_error = "no address to fetch it from"
    delay = 1.0
    for attempt in range(tries):
        for host, port in hosts:
            have = part.stat().st_size if part.exists() else 0
            if have == size:
                break
            try:
                _fetch(identity, fp, host, port, oid, part, have, size, timeout, on_progress)
            except DownloadError as e:
                if e.permanent:
                    raise
                last_error = str(e)
                continue
            except (OSError, http.client.HTTPException) as e:
                last_error = f"{host}: {e}"
                continue
            break
        if part.exists() and part.stat().st_size == size:
            break
        if attempt < tries - 1:
            got = part.stat().st_size if part.exists() else 0
            log.info("download of %s stopped at %d of %d bytes (%s); resuming in %.0f s",
                     name, got, size, last_error, delay)
            sleep(delay)
            delay = min(delay * 2, 16)
    else:
        raise DownloadError(last_error)
    if not part.exists():
        part.touch()
    if part.stat().st_size != size:
        raise DownloadError(f"got {part.stat().st_size} bytes, expected {size}")
    final = _claim(directory, name, part)
    meta.unlink(missing_ok=True)
    return final


def _claim(directory: Path, name: str, part: Path) -> Path:
    """Give the finished part its final, unique name, never overwriting anything."""
    while True:
        final = unique_path(directory, name)
        try:
            os.link(part, final)   # fails if the name was taken meanwhile
        except FileExistsError:
            continue
        except OSError:
            if final.exists():
                continue
            os.replace(part, final)   # a filesystem without hard links
            return final
        part.unlink()
        return final


def _fetch(identity, fp, host, port, oid, part: Path, have: int, size: int, timeout, on_progress):
    ctx = client_context(identity, fp)
    conn = http.client.HTTPSConnection(host, port, context=ctx, timeout=timeout)
    try:
        headers = {"User-Agent": "droplet-agent"}
        if have:
            headers["Range"] = f"bytes={have}-"
        conn.request("GET", f"/mesh/files/{oid}", headers=headers)
        resp = conn.getresponse()
        if resp.status in (404, 410):
            raise DownloadError("the sender doesn't offer it any more", True)
        if have and resp.status == 200:
            have = 0          # it ignored the range: start again
        elif resp.status not in (200, 206):
            raise DownloadError(f"the sender answered {resp.status}")
        if resp.status == 206:
            m = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", resp.headers.get("Content-Range") or "")
            if not m or int(m.group(1)) != have or int(m.group(3)) != size:
                raise DownloadError("the sender's range doesn't match the partial file")
        if have:
            log.info("resuming %s at byte %d of %d", part.name, have, size)
        with open(part, "r+b" if part.exists() and have else "wb") as f:
            f.seek(have)
            f.truncate()
            got = have
            while True:
                data = resp.read(CHUNK)
                if not data:
                    break
                if got + len(data) > size:
                    raise DownloadError("the sender sent more than it offered", True)
                f.write(data)
                got += len(data)
                if on_progress:
                    on_progress(got, size)
            f.flush()
            os.fsync(f.fileno())
        if got != size:
            raise DownloadError(f"the connection ended at {got} of {size} bytes")
    finally:
        conn.close()
