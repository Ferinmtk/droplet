"""Preset hub commands: the hub's owner lists them in a JSON file, any device runs them.

Like KDE Connect's "Run command". Clients only ever send a command's id; what
runs is decided entirely by the config file on the hub.
"""

import hashlib
import json
import os
import re
import signal
import subprocess
import threading
import time
from pathlib import Path

from flask import abort, jsonify, request

DEFAULT_TIMEOUT = 30
MAX_TIMEOUT = 300
OUTPUT_TAIL = 8 * 1024  # bytes of combined output returned to the device
KEYS = {"name", "run", "icon", "confirm", "timeout"}
# a custom header can't be sent cross-site without a CORS preflight, which
# droplet never grants — so another website can't make a visitor's browser
# (already trusted by the PIN cookie or the tailnet) run a command
RUN_HEADER = "X-Droplet-Run"


def config_path() -> Path:
    raw = os.environ.get("DROPLET_COMMANDS") or "~/.config/droplet/commands.json"
    return Path(raw).expanduser()


def slug(name: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", re.sub(r"['’]", "", name.lower())).strip("-")[:40]
    # names made only of emoji or non-Latin script still need a URL-safe id
    return s or "cmd-" + hashlib.sha1(name.encode()).hexdigest()[:8]


def parse(entries) -> tuple[list[dict], list[str]]:
    """Validate a decoded config. Bad entries are skipped and described, never fatal."""
    if not isinstance(entries, list):
        return [], ["the file: it must be a JSON list of commands"]
    commands, errors, seen = [], [], set()
    for i, e in enumerate(entries, 1):
        label = f"entry {i}"
        if not isinstance(e, dict):
            errors.append(f"{label}: must be an object")
            continue
        name = e.get("name")
        if isinstance(name, str) and name.strip():
            name = " ".join(name.split())[:60]
            label = f'entry {i} ("{name}")'
        else:
            errors.append(f"{label}: name must be a non-empty string")
            continue
        # a typo like "comfirm" must not silently drop the confirmation
        unknown = sorted(set(e) - KEYS)
        if unknown:
            errors.append(f"{label}: unknown key(s) {', '.join(unknown)}")
            continue
        run = e.get("run")
        if not (isinstance(run, list) and run and all(isinstance(a, str) for a in run) and run[0]):
            errors.append(f'{label}: run must be a non-empty list of strings, e.g. ["uptime"] '
                          '(for a shell, write ["bash", "-lc", "..."])')
            continue
        icon = e.get("icon", "")
        if not isinstance(icon, str) or len(icon) > 8:
            errors.append(f"{label}: icon must be a short string (an emoji)")
            continue
        confirm = e.get("confirm", False)
        if not isinstance(confirm, bool):
            errors.append(f"{label}: confirm must be true or false")
            continue
        timeout = e.get("timeout", DEFAULT_TIMEOUT)
        if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or timeout <= 0:
            errors.append(f"{label}: timeout must be a positive number of seconds")
            continue
        cid = slug(name)
        if cid in seen:
            errors.append(f"{label}: another command already has this name")
            continue
        seen.add(cid)
        commands.append({"id": cid, "name": name, "run": list(run), "icon": icon,
                         "confirm": confirm, "timeout": min(float(timeout), MAX_TIMEOUT)})
    return commands, errors


class CommandBook:
    """The config file, re-read whenever its mtime or size changes."""

    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.Lock()
        self._stamp = object()  # never equal to a real stamp, so the first load() reads
        self.configured = False
        self.commands: list[dict] = []
        self.errors: list[str] = []

    def load(self):
        try:
            st = self.path.stat()
            stamp = (st.st_mtime_ns, st.st_size)
        except OSError:
            stamp = None
        with self._lock:
            if stamp == self._stamp:
                return
            self._stamp = stamp
            self.configured = stamp is not None
            if stamp is None:
                self.commands, self.errors = [], []
                print(f"  hub commands: none ({self.path} doesn't exist)")
                return
            try:
                commands, errors = parse(json.loads(self.path.read_text(encoding="utf-8")))
            except (OSError, UnicodeDecodeError) as exc:
                commands, errors = [], [f"the file: can't read it ({exc})"]
            except ValueError as exc:
                commands, errors = [], [f"the file: not valid JSON ({exc})"]
            self.commands, self.errors = commands, errors
        print(f"  hub commands: {len(commands)} loaded from {self.path}")
        for err in errors:
            print(f"  hub commands: ignoring {err}")

    def get(self, cid: str) -> dict | None:
        self.load()
        return next((c for c in self.commands if c["id"] == cid), None)


def _drain(pipe, tail: bytearray, total: list):
    # keeps only the tail, so a command that floods output can't eat the hub's memory
    for chunk in iter(lambda: pipe.read1(65536), b""):
        total[0] += len(chunk)
        tail += chunk
        del tail[:-OUTPUT_TAIL]


def run_command(cmd: dict) -> dict:
    started = time.monotonic()
    argv = [os.path.expanduser(cmd["run"][0]), *cmd["run"][1:]]
    # droplet's own settings (the PIN among them) are no business of the command
    env = {k: v for k, v in os.environ.items() if not k.startswith("DROPLET_")}
    try:
        proc = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, cwd=Path.home(), env=env,
                                start_new_session=True)
    except OSError as exc:
        # same codes a shell uses for "not found" / "can't execute"
        code = 127 if isinstance(exc, FileNotFoundError) else 126
        return {"code": code, "output": f"droplet: can't run {argv[0]}: {exc.strerror or exc}",
                "seconds": 0.0, "timed_out": False, "truncated": False}

    tail, total = bytearray(), [0]
    reader = threading.Thread(target=_drain, args=(proc.stdout, tail, total), daemon=True)
    reader.start()
    timed_out = False
    try:
        proc.wait(timeout=cmd["timeout"])
    except subprocess.TimeoutExpired:
        timed_out = True
        # the command is its own process group (start_new_session), so this
        # reaches everything it started, e.g. the `sleep` under a `bash -lc`
        _signal_group(proc.pid, signal.SIGTERM)
        try:
            proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            pass
        _signal_group(proc.pid, signal.SIGKILL)
        proc.wait()
    # a child left running in the background (deliberately, or after escaping
    # the group) can hold the pipe open; don't wait on it forever
    reader.join(timeout=1)
    return {
        "code": proc.returncode,
        "output": bytes(tail).decode("utf-8", errors="replace"),
        "seconds": round(time.monotonic() - started, 2),
        "timed_out": timed_out,
        "truncated": total[0] > OUTPUT_TAIL,
    }


def _signal_group(pgid: int, sig: int):
    try:
        os.killpg(pgid, sig)
    except (ProcessLookupError, PermissionError):
        pass  # already gone


def register(ctx):
    book = CommandBook(config_path())
    book.load()
    running: set[str] = set()
    running_lock = threading.Lock()

    @ctx.app.route("/api/hub/commands")
    def api_hub_commands():
        book.load()
        return jsonify({
            "configured": book.configured,
            "path": str(book.path),
            "commands": [{k: c[k] for k in ("id", "name", "icon", "confirm")} for c in book.commands],
            "errors": book.errors,
        })

    @ctx.app.route("/api/hub/commands/<cid>/run", methods=["POST"])
    def api_hub_command_run(cid):
        if request.headers.get(RUN_HEADER) != "1":
            abort(403)
        cmd = book.get(cid)
        if cmd is None:
            abort(404)
        with running_lock:
            if cid in running:
                return jsonify({"error": f"{cmd['name']} is already running"}), 409
            running.add(cid)
        try:
            result = run_command(cmd)
        finally:
            with running_lock:
                running.discard(cid)
        late = " (timed out)" if result["timed_out"] else ""
        print(f'  command "{cmd["name"]}" run by {ctx.sender_name()} → exit {result["code"]} '
              f'in {result["seconds"]}s{late}')
        return jsonify(result)
