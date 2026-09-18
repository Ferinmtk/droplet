"""Ring a device to find it, or ring the hub itself.

Rings live in memory only: a ring is worth nothing after a minute, so a
restart forgetting them is fine. A device learns it's ringing from a push
notification (closed app) or by polling GET /api/ring (open app).
"""

import secrets
import shutil
import subprocess
import threading
import time
from pathlib import Path

from flask import abort, jsonify, request

RING_SECONDS = 60  # a ring nobody answers stops by itself
HUB_SOUND = Path("/usr/share/sounds/freedesktop/stereo/phone-incoming-call.oga")
HUB_REPEATS = 4
# first one found wins: pw-play talks to PipeWire directly, paplay goes through
# its PulseAudio shim, canberra-gtk-play is the last resort on older desktops
PLAYERS = (
    ("pw-play", lambda f: ["pw-play", str(f)]),
    ("paplay", lambda f: ["paplay", str(f)]),
    ("canberra-gtk-play", lambda f: ["canberra-gtk-play", "-f", str(f)]),
)


class Rings:
    """Pending rings, one per device."""

    def __init__(self):
        self._lock = threading.Lock()
        self._rings: dict[str, dict] = {}

    def start(self, device_id: str, sender: str) -> dict:
        ring = {"id": secrets.token_hex(6), "from": sender, "ts": time.time()}
        with self._lock:
            self._rings[device_id] = ring
        return dict(ring)

    def get(self, device_id: str) -> dict | None:
        with self._lock:
            ring = self._rings.get(device_id)
            # expired on read, so nothing needs a timer thread
            if ring and time.time() - ring["ts"] > RING_SECONDS:
                del self._rings[device_id]
                ring = None
            return dict(ring) if ring else None

    def stop(self, device_id: str) -> bool:
        with self._lock:
            return self._rings.pop(device_id, None) is not None


class HubRinger:
    """Plays the incoming-call sound on the hub a few times, one ring at a time.

    Uses whatever volume the hub is at: turning it up behind the user's back
    would be a nasty surprise at 3 a.m.
    """

    def __init__(self, sound: Path = HUB_SOUND, repeats: int = HUB_REPEATS):
        self.sound = sound
        self.repeats = repeats
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._proc: subprocess.Popen | None = None

    def player(self) -> tuple[str, list[str]] | None:
        for name, cmd in PLAYERS:
            if shutil.which(name):
                return name, cmd(self.sound)
        return None

    def ringing(self) -> bool:
        return bool(self._thread and self._thread.is_alive())

    def start(self) -> tuple[bool, str]:
        """(True, player name) once ringing, or (False, why it can't)."""
        if not self.sound.is_file():
            return False, f"{self.sound} is missing on the hub"
        found = self.player()
        if found is None:
            return False, "no sound player on the hub (tried pw-play, paplay, canberra-gtk-play)"
        name, cmd = found
        old = self._thread
        if old and old.is_alive() and self._stop.is_set():
            old.join(timeout=2)  # just stopped: let it wind down rather than refuse the new ring
        with self._lock:
            if self.ringing():
                return True, name  # already ringing; a second one would just overlap
            self._stop.clear()
            self._thread = threading.Thread(target=self._run, args=(cmd,), daemon=True)
            self._thread.start()
        return True, name

    def _run(self, cmd: list[str]):
        for _ in range(self.repeats):
            if self._stop.is_set():
                break
            try:
                proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except OSError as e:
                print(f"  hub ring: {cmd[0]} failed: {e}")
                break
            with self._lock:
                self._proc = proc
                if self._stop.is_set():  # stop() landed while this was starting
                    proc.terminate()
            proc.wait()
        with self._lock:
            self._proc = None

    def stop(self) -> bool:
        with self._lock:
            was = self.ringing()
            self._stop.set()
            if self._proc and self._proc.poll() is None:
                self._proc.terminate()
        return was


def register(ctx):
    app = ctx.app
    rings = Rings()
    hub = HubRinger()

    def target(device_id: str) -> dict:
        dev = ctx.devices.get(device_id)
        if dev is None:
            abort(404)
        return dev

    def named_caller() -> dict:
        me = ctx.current_device()
        if me is None:
            abort(400)  # only named devices can be rung, so there's nothing to check
        return me

    @app.route("/api/device/<device_id>/ring", methods=["POST"])
    def api_ring_device(device_id):
        dev = target(device_id)
        me = ctx.current_device()
        if me and me["id"] == dev["id"]:
            return jsonify({"error": "You can't ring the device you're on."}), 400
        ring = rings.start(dev["id"], ctx.sender_name())
        ctx.pusher.send(dev["id"], {
            "title": f"📣 {ring['from']} is ringing this device",
            "body": "Tap to stop",
            "tag": "ring",
            "url": "/#ring",
            "ring": True,
        })
        return jsonify({"ok": True, "ring": ring})

    @app.route("/api/device/<device_id>/ring")
    def api_ring_device_status(device_id):
        # lets the sender's page see the ring end when the other side answers
        return jsonify({"ring": rings.get(target(device_id)["id"])})

    @app.route("/api/device/<device_id>/ring/stop", methods=["POST"])
    def api_ring_device_stop(device_id):
        return jsonify({"ok": True, "stopped": rings.stop(target(device_id)["id"])})

    @app.route("/api/ring")
    def api_ring():
        return jsonify({"ring": rings.get(named_caller()["id"])})

    @app.route("/api/ring/stop", methods=["POST"])
    def api_ring_stop():
        return jsonify({"ok": True, "stopped": rings.stop(named_caller()["id"])})

    @app.route("/api/hub/ring", methods=["GET", "POST"])
    def api_hub_ring():
        if request.method == "GET":
            return jsonify({"ringing": hub.ringing()})
        ok, info = hub.start()
        if not ok:
            return jsonify({"ok": False, "error": info}), 503
        return jsonify({"ok": True, "player": info})

    @app.route("/api/hub/ring/stop", methods=["POST"])
    def api_hub_ring_stop():
        return jsonify({"ok": True, "stopped": hub.stop()})
