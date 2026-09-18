"""Media: what's playing, for the "media" state, and the controller's media actions.

The playerctl/wpctl reading is mediactl, shared with the hub's own media
card. What's added here is the protocol's shape: art as a small data: URL
(other devices can't open this machine's files) and volume clamped to 0..1.
"""

from __future__ import annotations

import base64
import logging
import math
import subprocess
import threading
import time

from . import env
from . import mediactl as mc

log = logging.getLogger("droplet_agent.media")

ACTIONS = ("play-pause", "play", "pause", "next", "previous", "stop", "seek", "volume", "mute")
ART_URL_MAX = 64 * 1024
# base64 grows data by 4/3; this leaves room for the "data:image/…;base64," prefix
ART_RAW_MAX = (ART_URL_MAX - 64) * 3 // 4
MAX_SEEK = 24 * 3600


def available() -> tuple[bool, str]:
    pc, wp = bool(env.which("playerctl")), bool(env.which("wpctl"))
    if pc and wp:
        return True, "playerctl and wpctl"
    if pc:
        return True, "playerctl (no wpctl, so no volume)"
    if wp:
        return True, "wpctl (no playerctl, so volume only)"
    return False, "neither playerctl nor wpctl is installed"


class ArtCache:
    """Local cover art as data: URLs of at most 64 KB, shrunk with ImageMagick if needed."""

    def __init__(self):
        self._cache: dict[tuple, str | None] = {}

    def data_url(self, path: str | None) -> str | None:
        found = mc.safe_art(path)
        if not found:
            return None
        p, mime = found
        try:
            st = p.stat()
        except OSError:
            return None
        key = (str(p), st.st_mtime_ns, st.st_size)
        if key not in self._cache:
            if len(self._cache) > 32:
                self._cache.clear()
            self._cache[key] = self._make(p, mime, st.st_size)
        return self._cache[key]

    @staticmethod
    def _encode(mime: str, data: bytes) -> str:
        return f"data:{mime};base64,{base64.b64encode(data).decode()}"

    def _make(self, p, mime: str, size: int) -> str | None:
        if size <= ART_RAW_MAX:
            try:
                return self._encode(mime, p.read_bytes())
            except OSError:
                return None
        tool = env.which("magick") or env.which("convert")
        if not tool:
            return None  # too big to send, and nothing to shrink it with
        for dim, quality in ((256, 80), (160, 70)):
            r = env.run([tool, f"{p}[0]", "-thumbnail", f"{dim}x{dim}>", "-strip",
                         "-quality", str(quality), "jpeg:-"], timeout=10)
            if r is not None and r.returncode == 0 and 0 < len(r.stdout) <= ART_RAW_MAX:
                return self._encode("image/jpeg", r.stdout)
        return None


class Media:
    """Reads the media state and carries out media actions.

    `runner` runs the commands that change something (playerctl play…,
    wpctl set-volume…); the dry run swaps in one that only logs.
    """

    def __init__(self, runner=None):
        self.state: dict = {"last": None}  # the player last playing or controlled
        self.art = ArtCache()
        self.runner = runner or mc._run
        self._lock = threading.Lock()

    def snapshot(self) -> dict:
        has_pc, has_wp = bool(env.which("playerctl")), bool(env.which("wpctl"))
        players = [mc.read_player(p) for p in mc.list_players()] if has_pc else []
        mc._dedupe_names(players)
        with self._lock:
            active = mc.pick_active(players, self.state)
        for p in players:
            art_file = p.pop("_art_file", None)
            if art_file:
                p["art"] = self.art.data_url(art_file)
            elif p["art"] and len(p["art"]) > 2048:
                p["art"] = None  # an absurd remote URL; don't pass it round
        volume = mc.read_volume() if has_wp else None
        if volume is not None:
            volume = {"level": round(min(1.0, max(0.0, volume["level"])), 3), "muted": volume["muted"]}
        return {"players": players, "active": active, "volume": volume}

    def act(self, action, player=None, value=None) -> str | None:
        """Do a media action. Returns why it failed, or None."""
        if action not in ACTIONS:
            return f"unknown media action {action!r}"
        if action in ("volume", "mute"):
            return self._audio(action, value)
        names = mc.list_players()
        if player is not None:
            if not isinstance(player, str) or player not in names:
                return "that player isn't running"
        else:
            with self._lock:
                last = self.state.get("last")
            playing = [p for p in names if mc.read_player(p)["status"] == "Playing"] if names else []
            player = playing[0] if playing else (last if last in names else (names[0] if names else None))
            if player is None:
                return "nothing is playing"
        with self._lock:
            self.state["last"] = player
        if action == "seek":
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
                return "seek needs a position in seconds"
            pos = max(0.0, min(MAX_SEEK, float(value)))
            return self._check(self.runner("playerctl", "-p", player, "position", f"{pos:.2f}"))
        return self._check(self.runner("playerctl", "-p", player, action))

    def _audio(self, action, value) -> str | None:
        if not env.which("wpctl"):
            return "wpctl isn't installed"
        if action == "volume":
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
                return "volume needs a level from 0 to 1"
            level = max(0.0, min(mc.MAX_VOLUME, float(value)))
            return self._check(self.runner("wpctl", "set-volume", mc.DEFAULT_SINK, f"{level:.2f}"))
        if value is None:
            arg = "toggle"
        elif isinstance(value, bool):
            arg = "1" if value else "0"
        else:
            return "mute takes true, false, or nothing to toggle"
        return self._check(self.runner("wpctl", "set-mute", mc.DEFAULT_SINK, arg))

    @staticmethod
    def _check(r: subprocess.CompletedProcess | None) -> str | None:
        if r is None:
            return "the command didn't run"
        if r.returncode != 0:
            return (r.stderr or r.stdout or "").strip() or f"exit status {r.returncode}"
        return None


class MediaPublisher:
    """Polls the media state and publishes it when it changes: at most once a
    second while something plays, and within two seconds otherwise."""

    def __init__(self, media: Media, publish, stop: threading.Event):
        self.media = media
        self.publish = publish
        self.stop = stop
        self.last: dict | None = None
        self._force = threading.Event()

    def force(self):
        """Publish on the next poll even if nothing changed (after a reconnect)."""
        self._force.set()

    def start(self):
        threading.Thread(target=self._loop, name="media", daemon=True).start()

    def poll_once(self) -> bool:
        """One poll; returns whether something is playing."""
        snap = self.media.snapshot()
        if snap != self.last or self._force.is_set():
            self._force.clear()
            if self.publish(snap):
                self.last = snap
        return any(p["status"] == "Playing" for p in snap["players"])

    def _loop(self):
        while not self.stop.is_set():
            started = time.monotonic()
            try:
                playing = self.poll_once()
            except Exception:
                log.exception("reading media state failed")
                playing = False
            wait = (1.0 if playing else 2.0) - (time.monotonic() - started)
            # a forced publish (reconnect) wakes it early
            if self._force.wait(max(0.05, wait)):
                continue
