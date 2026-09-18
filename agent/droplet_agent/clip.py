"""Clipboard sync: send this machine's clipboard text to the hub, apply what comes back.

Wayland: `wl-paste --watch` tells us when the clipboard changes, then the text
is read with wl-paste (text types only). X11: xclip or xsel, polled once a
second. Incoming text is written with wl-copy / xclip / xsel.

Echoes are suppressed: text we were just given, or just sent, isn't sent
again. Text a password manager marks as secret is never sent.
"""

from __future__ import annotations

import logging
import subprocess
import tempfile
import threading
import time

from . import env

log = logging.getLogger("droplet_agent.clipboard")

TEXT_TYPE = "text/plain;charset=utf-8"
DEBOUNCE = 1.0  # at most one send a second
PASSWORD_HINT = "x-kde-passwordManagerHint"


def detect() -> tuple[str | None, str]:
    """(mode, why): mode is "wayland", "x11-xclip", "x11-xsel" or None."""
    if env.is_wayland():
        if env.which("wl-paste") and env.which("wl-copy"):
            return "wayland", "wl-clipboard"
        return None, "wl-clipboard isn't installed (no wl-paste/wl-copy)"
    if env.x11_display():
        if env.which("xclip"):
            return "x11-xclip", "xclip"
        if env.which("xsel"):
            return "x11-xsel", "xsel"
        return None, "neither xclip nor xsel is installed"
    return None, "no graphical session"


class ClipboardSync:
    """`send(text) -> bool` delivers a local change to the hub.

    `reader()`/`writer(text)` default to the real clipboard; tests and the
    dry run replace them.
    """

    def __init__(self, mode: str | None, send, max_bytes: int = 256 * 1024,
                 reader=None, writer=None, clock=time.monotonic):
        self.mode = mode
        self.send = send
        self.max_bytes = max_bytes
        self.reader = reader or self._read
        self.writer = writer or self._write
        self.clock = clock
        self.last_applied: str | None = None  # text another device gave us
        self.last_sent: str | None = None     # text we last sent (or found at start)
        self._pending: str | None = None
        self._last_send_at = -1e9
        self._lock = threading.Lock()
        self._timer: threading.Timer | None = None
        self.failed: str | None = None  # set when watching stopped working
        self._stop = threading.Event()
        self._proc: subprocess.Popen | None = None

    # --- outgoing ------------------------------------------------------------

    def local_change(self, text: str | None):
        """The clipboard now holds `text` (None: not text). Send it if it's new."""
        if not text:
            return
        if len(text.encode("utf-8", "surrogatepass")) > self.max_bytes:
            log.info("clipboard: not sending %d characters (over the size limit)", len(text))
            return
        with self._lock:
            if text == self.last_applied or text == self.last_sent:
                return  # our own write coming back, or nothing new
            self._pending = text
            wait = DEBOUNCE - (self.clock() - self._last_send_at)
            if wait > 0:
                if self._timer is None:
                    self._timer = threading.Timer(wait, self._flush)
                    self._timer.daemon = True
                    self._timer.start()
                return
        self._flush()

    def _flush(self):
        with self._lock:
            self._timer = None
            text, self._pending = self._pending, None
            if text is None or text == self.last_sent or text == self.last_applied:
                return
            self._last_send_at = self.clock()
            self.last_sent = text
        if not self.send(text):
            log.info("clipboard: not connected, change not sent")

    # --- incoming ------------------------------------------------------------

    def apply(self, text) -> str | None:
        """Put text from another device on this clipboard. Returns why it failed."""
        if not isinstance(text, str) or not text:
            return "no text"
        if len(text.encode("utf-8", "surrogatepass")) > self.max_bytes:
            return "too large"
        try:
            text.encode("utf-8")
        except UnicodeEncodeError:  # lone surrogates, which JSON allows
            return "not valid Unicode"
        with self._lock:
            # set before writing: the watcher can fire before wl-copy returns
            self.last_applied = text
            if self._pending == text:
                self._pending = None
        return self.writer(text)

    # --- the real clipboard ---------------------------------------------------

    def _read(self) -> str | None:
        env_ = env.session_env()
        if self.mode == "wayland":
            types = env.run(["wl-paste", "--list-types"], timeout=3, env=env_)
            if types is None or types.returncode != 0:
                return None
            offered = types.stdout.decode(errors="replace").split()
            if PASSWORD_HINT in offered:
                hint = env.run(["wl-paste", "--no-newline", "--type", PASSWORD_HINT], timeout=3, env=env_)
                if hint is not None and hint.stdout.strip() == b"secret":
                    log.info("clipboard: a password manager marked this as secret; not sending it")
                    return None
            argv = ["wl-paste", "--no-newline", "--type", "text"]
        elif self.mode == "x11-xclip":
            argv = ["xclip", "-selection", "clipboard", "-o", "-t", "UTF8_STRING"]
        elif self.mode == "x11-xsel":
            argv = ["xsel", "--clipboard", "--output"]
        else:
            return None
        # stdout to a file, not a pipe: a huge clipboard costs disk briefly, not memory
        with tempfile.TemporaryFile() as out:
            try:
                r = subprocess.run(argv, env=env_, timeout=3, stdin=subprocess.DEVNULL,
                                   stdout=out, stderr=subprocess.DEVNULL)
            except (OSError, subprocess.TimeoutExpired):
                return None
            if r.returncode != 0:
                return None  # empty, or not text
            out.seek(0)
            data = out.read(self.max_bytes + 1)
        if len(data) > self.max_bytes:
            log.info("clipboard: not sending it (over %d bytes)", self.max_bytes)
            return None
        return data.decode("utf-8", "replace") or None

    def _write(self, text: str) -> str | None:
        if self.mode == "wayland":
            argv = ["wl-copy", "--type", TEXT_TYPE]
        elif self.mode == "x11-xclip":
            argv = ["xclip", "-selection", "clipboard", "-i"]
        elif self.mode == "x11-xsel":
            argv = ["xsel", "--clipboard", "--input"]
        else:
            return "no clipboard here"
        # wl-copy and xclip fork a child that keeps serving the clipboard and
        # inherits stdout/stderr; a pipe there would never close. A file
        # doesn't block and still gives us the error message.
        with tempfile.TemporaryFile() as err:
            try:
                r = subprocess.run(argv, input=text.encode("utf-8"), env=env.session_env(), timeout=5,
                                   stdout=subprocess.DEVNULL, stderr=err, start_new_session=True)
            except (OSError, subprocess.TimeoutExpired) as e:
                return f"{argv[0]} failed: {e}"
            if r.returncode != 0:
                err.seek(0)
                return f"{argv[0]} failed: {err.read(2000).decode(errors='replace').strip() or r.returncode}"
        return None

    # --- watching -------------------------------------------------------------

    def start(self):
        # what's on the clipboard now isn't a change: don't push it to every
        # device each time the agent starts
        self.last_sent = self.reader()
        target = self._watch_wayland if self.mode == "wayland" else self._poll_x11
        threading.Thread(target=target, name="clipboard", daemon=True).start()

    def _watch_wayland(self):
        # the command reads and discards the content (so wl-paste never
        # writes to a closed pipe), then prints one line per change
        argv = ["wl-paste", "--watch", "sh", "-c", "cat >/dev/null; echo changed"]
        backoff = 1.0
        while not self._stop.is_set():
            started = time.monotonic()
            try:
                self._proc = subprocess.Popen(argv, env=env.session_env(), stdin=subprocess.DEVNULL,
                                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            except OSError as e:
                self._give_up(f"can't run wl-paste: {e}")
                return
            for _line in self._proc.stdout:
                if self._stop.is_set():
                    break
                try:
                    self.local_change(self.reader())
                except Exception:
                    log.exception("clipboard: reading a change failed")
            self._proc.wait()
            err = self._proc.stderr.read().decode(errors="replace").strip()
            if self._stop.is_set():
                return
            if time.monotonic() - started < 2:
                # e.g. GNOME: "Watch mode requires a compositor that supports
                # the wlr-data-control or ext-data-control protocol"
                self._give_up(err or "wl-paste --watch exited at once")
                return
            log.warning("clipboard: wl-paste --watch stopped (%s); restarting", err or self._proc.returncode)
            self._stop.wait(backoff)
            backoff = min(backoff * 2, 30)

    def _poll_x11(self):
        while not self._stop.wait(1.0):
            try:
                self.local_change(self.reader())
            except Exception:
                log.exception("clipboard: reading failed")

    def _give_up(self, why: str):
        self.failed = why
        log.warning("clipboard: can't watch the clipboard: %s", why)
        if self.on_failed:
            self.on_failed()

    on_failed = None  # set by the agent: drop the capability

    def stop(self):
        self._stop.set()
        if self._proc and self._proc.poll() is None:
            self._proc.terminate()
        if self._timer:
            self._timer.cancel()
