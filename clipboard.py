"""Shared clipboard: read and set the hub's Wayland clipboard from any device."""

import os
import subprocess
import tempfile
from pathlib import Path

from flask import jsonify, request, session

MAX_BYTES = 1024 * 1024  # text past this is cut, both ways
TIMEOUT = 3  # seconds; wl-paste waits on whichever app owns the clipboard
ENABLED = os.environ.get("DROPLET_CLIPBOARD", "1") not in ("", "0", "false")
TEXT_TYPE = "text/plain;charset=utf-8"


def wayland_env() -> dict | None:
    """Environment for wl-clipboard, or None when there's no Wayland session."""
    env = dict(os.environ)
    if env.get("WAYLAND_DISPLAY"):
        return env
    # started over SSH or from a TTY: use the logged-in desktop's socket
    runtime = Path(env.get("XDG_RUNTIME_DIR") or f"/run/user/{os.getuid()}")
    try:
        sockets = sorted(p.name for p in runtime.glob("wayland-*") if p.is_socket())
    except OSError:
        return None
    if not sockets:
        return None
    env["XDG_RUNTIME_DIR"] = str(runtime)
    env["WAYLAND_DISPLAY"] = sockets[0]
    return env


def _first_line(raw: bytes) -> str:
    lines = raw.decode("utf-8", "replace").strip().splitlines()
    return lines[0] if lines else ""


def read_clipboard() -> dict:
    """{"available", "text", "reason"}; text is None when there's no text to give."""
    env = wayland_env()
    if env is None:
        return {"available": False, "text": None, "reason": "The hub isn't running a Wayland desktop session."}
    # stdout goes to a file, not a pipe, so an enormous clipboard costs disk
    # for a moment rather than memory
    with tempfile.TemporaryFile() as out:
        try:
            r = subprocess.run(["wl-paste", "--no-newline", "--type", "text"], env=env, timeout=TIMEOUT,
                               stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.PIPE)
        except FileNotFoundError:
            return {"available": False, "text": None,
                    "reason": "wl-clipboard isn't installed on the hub (no wl-paste)."}
        except subprocess.TimeoutExpired:
            return {"available": True, "text": None,
                    "reason": "The app holding the hub's clipboard didn't answer in time."}
        if r.returncode != 0:
            err = _first_line(r.stderr)
            if "Nothing is copied" in err or "No selection" in err:
                return {"available": True, "text": None, "reason": "The hub's clipboard is empty."}
            if "not available as requested type" in err or "No suitable type" in err:
                return {"available": True, "text": None, "reason": "The hub's clipboard holds something that isn't text."}
            if "connect to a Wayland server" in err:
                return {"available": False, "text": None, "reason": "Can't reach the hub's Wayland session."}
            return {"available": False, "text": None, "reason": f"wl-paste failed: {err or r.returncode}"}
        out.seek(0)
        data = out.read(MAX_BYTES + 1)
    truncated = len(data) > MAX_BYTES
    # a cut can land inside a multi-byte character; drop the broken tail
    text = data[:MAX_BYTES].decode("utf-8", "replace")
    if truncated:
        text = text.rstrip("\ufffd")
    if not text:
        return {"available": True, "text": None, "reason": "The hub's clipboard is empty."}
    return {"available": True, "text": text, "reason": None, "truncated": truncated}


def write_clipboard(data: bytes) -> str | None:
    """Put UTF-8 text on the hub's clipboard. Returns why it failed, or None."""
    env = wayland_env()
    if env is None:
        return "The hub isn't running a Wayland desktop session."
    # wl-copy forks a child that keeps serving the clipboard and inherits
    # stdout/stderr; pipes there would never close and run() would hang.
    # A file doesn't block, and still gives us wl-copy's own error message.
    # A new session keeps the child alive when a terminal-run hub gets Ctrl+C.
    with tempfile.TemporaryFile() as err:
        try:
            r = subprocess.run(["wl-copy", "--type", TEXT_TYPE], input=data, env=env,
                               timeout=TIMEOUT, stdout=subprocess.DEVNULL, stderr=err, start_new_session=True)
        except FileNotFoundError:
            return "wl-clipboard isn't installed on the hub (no wl-copy)."
        except subprocess.TimeoutExpired:
            return "wl-copy didn't finish in time."
        if r.returncode != 0:
            err.seek(0)
            msg = _first_line(err.read(4096))
            if "connect to a Wayland server" in msg:
                return "Can't reach the hub's Wayland session."
            return f"wl-copy failed: {msg or r.returncode}"
    return None


def register(ctx):
    if not ENABLED:
        return  # no routes: the page sees a 404 and leaves the card out
    app = ctx.app

    def refused() -> str | None:
        # clipboards hold passwords, so unlike files this isn't open to anyone
        # who can reach the LAN URL: it takes the tailnet (tailscale serve
        # connects from loopback) or a PIN login
        if request.remote_addr in ("127.0.0.1", "::1") or session.get("authed"):
            return None
        return "The hub clipboard is only shared over the tailnet, or on the LAN once the hub has a PIN."

    @app.route("/api/hub/clipboard", methods=["GET"])
    def hub_clipboard_get():
        why = refused()
        out = {"available": False, "text": None, "reason": why} if why else read_clipboard()
        resp = jsonify(out)
        resp.headers["Cache-Control"] = "no-store"
        return resp

    @app.route("/api/hub/clipboard", methods=["POST"])
    def hub_clipboard_set():
        why = refused()
        if why:
            return jsonify({"ok": False, "reason": why}), 403
        # escaped JSON can be several times the size of the text it carries
        if (request.content_length or 0) > 8 * MAX_BYTES:
            return jsonify({"ok": False, "reason": "That's more than 1 MB of text."}), 413
        body = request.get_json(silent=True)
        text = body.get("text") if isinstance(body, dict) else None
        if not isinstance(text, str) or not text:
            return jsonify({"ok": False, "reason": "Nothing to copy."}), 400
        try:
            data = text.encode("utf-8")
        except UnicodeEncodeError:  # lone surrogates, which JSON allows and UTF-8 doesn't
            return jsonify({"ok": False, "reason": "That text isn't valid Unicode."}), 400
        if len(data) > MAX_BYTES:
            return jsonify({"ok": False, "reason": "That's more than 1 MB of text."}), 413
        why = write_clipboard(data)
        if why:
            return jsonify({"ok": False, "reason": why}), 503
        return jsonify({"ok": True})
