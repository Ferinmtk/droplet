"""The desktop side of mesh messages: notifications, and ringing to find this computer.

Notifications go to org.freedesktop.Notifications over D-Bus (jeepney), so
a mirrored notification can be replaced and closed by its key. The ring
plays the freedesktop incoming-call sound a few times, at the current volume.
In a dry run both are only logged.
"""

from __future__ import annotations

import logging
import shutil
import subprocess
import threading
from pathlib import Path

log = logging.getLogger("droplet_agent.mesh.desktop")

RING_SOUND = Path("/usr/share/sounds/freedesktop/stereo/phone-incoming-call.oga")
RING_REPEATS = 6
PLAYERS = (("pw-play", []), ("paplay", []), ("canberra-gtk-play", ["-f"]))


class Desktop:
    def __init__(self, dry_run: bool, session_env=None):
        self.dry_run = dry_run
        self.session_env = session_env
        self._ids: dict[str, int] = {}      # notification key → id the desktop gave it
        self._lock = threading.Lock()
        self._ring_stop = threading.Event()
        self._ring_thread: threading.Thread | None = None
        self._proc: subprocess.Popen | None = None

    # --- notifications ---------------------------------------------------------

    def notify(self, title: str, body: str, key: str | None = None, app: str = "droplet") -> None:
        title, body = str(title)[:200], str(body)[:1000]
        if self.dry_run:
            log.info("notification (dry run): %s: %s", title, body[:120])
            return
        try:
            from jeepney import DBusAddress, new_method_call
            from jeepney.io.blocking import open_dbus_connection
        except ImportError:
            return
        addr = DBusAddress("/org/freedesktop/Notifications", bus_name="org.freedesktop.Notifications",
                           interface="org.freedesktop.Notifications")
        with self._lock:
            replaces = self._ids.get(key, 0) if key else 0
        try:
            with open_dbus_connection(bus="SESSION") as conn:
                msg = new_method_call(addr, "Notify", "susssasa{sv}i",
                                      (app[:40], replaces, "", title, body, [], {}, -1))
                reply = conn.send_and_get_reply(msg, timeout=5)
            nid = reply.body[0]
        except Exception as e:
            log.debug("couldn't show a notification: %s", e)
            return
        if key:
            with self._lock:
                self._ids[key] = nid
                if len(self._ids) > 500:
                    self._ids.pop(next(iter(self._ids)))

    def close_notification(self, key: str) -> None:
        with self._lock:
            nid = self._ids.pop(key, None)
        if nid is None or self.dry_run:
            return
        try:
            from jeepney import DBusAddress, new_method_call
            from jeepney.io.blocking import open_dbus_connection
            addr = DBusAddress("/org/freedesktop/Notifications", bus_name="org.freedesktop.Notifications",
                               interface="org.freedesktop.Notifications")
            with open_dbus_connection(bus="SESSION") as conn:
                conn.send_and_get_reply(new_method_call(addr, "CloseNotification", "u", (nid,)), timeout=5)
        except Exception as e:
            log.debug("couldn't close a notification: %s", e)

    # --- ringing ---------------------------------------------------------------

    def ring(self, who: str):
        log.info("%s is ringing this computer", who)
        self.notify(f"{who} is looking for this computer", "droplet is ringing it.", key="droplet-ring")
        if self.dry_run:
            log.info("ring (dry run): would play %s", RING_SOUND)
            return
        if self._ring_thread and self._ring_thread.is_alive():
            return
        player = next(((n, a) for n, a in PLAYERS if shutil.which(n)), None)
        if player is None or not RING_SOUND.exists():
            log.warning("can't ring: no sound player (pw-play, paplay) or no sound file")
            return
        self._ring_stop.clear()
        self._ring_thread = threading.Thread(target=self._ring, args=(player,), name="ring", daemon=True)
        self._ring_thread.start()

    def _ring(self, player):
        name, extra = player
        for _ in range(RING_REPEATS):
            if self._ring_stop.is_set():
                return
            try:
                self._proc = subprocess.Popen([name, *extra, str(RING_SOUND)], env=self.session_env,
                                              stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                              stderr=subprocess.DEVNULL)
                self._proc.wait(timeout=30)
            except (OSError, subprocess.TimeoutExpired):
                return

    def stop_ring(self):
        self._ring_stop.set()
        p = self._proc
        if p and p.poll() is None:
            p.terminate()
        self.close_notification("droplet-ring")
        if self.dry_run:
            log.info("ring stopped (dry run)")
