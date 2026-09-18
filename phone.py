"""Phone companion: notifications mirrored from the droplet Android app, and its battery.

The app (android/ in this repo) posts what shows up on the phone; other
devices read it back for the Phone card (static/phone.js). Kept in memory,
newest first, capped per phone, and saved to phone.json so a restart
doesn't blank the card. Notification text is personal, so the file is
owner-only, like devices.json.
"""

import json
import threading
import time

from flask import abort, jsonify, request

KEEP = 50  # notifications kept per phone
LIMITS = {"key": 200, "package": 200, "app": 80, "title": 200, "text": 1000}


def _clean(item) -> dict | None:
    if not isinstance(item, dict) or not isinstance(item.get("key"), str) or not item["key"]:
        return None
    out = {k: str(item.get(k) or "")[:n] for k, n in LIMITS.items()}
    try:
        t = float(item.get("time") or 0)
    except (TypeError, ValueError):
        t = 0
    if t > 1e12:  # milliseconds
        t /= 1000
    out["time"] = t or time.time()
    return out


class PhoneStore:
    def __init__(self, path):
        self.file = path
        self._lock = threading.Lock()
        try:
            self._phones: dict[str, dict] = json.loads(self.file.read_text())
        except (FileNotFoundError, ValueError):
            self._phones = {}

    def _save(self):
        tmp = self.file.with_suffix(".tmp")
        tmp.write_text(json.dumps(self._phones))
        tmp.chmod(0o600)
        tmp.replace(self.file)

    def _phone(self, device_id: str) -> dict:
        return self._phones.setdefault(device_id, {"notifications": [], "battery": None, "charging": False,
                                                    "status_ts": 0, "ts": 0})

    def notifications(self, device_id: str, posted: list, removed: list, sync: bool):
        with self._lock:
            p = self._phone(device_id)
            items = [] if sync else p["notifications"]
            gone = {k for k in removed if isinstance(k, str)}
            fresh = [c for c in map(_clean, posted) if c]
            gone |= {c["key"] for c in fresh}  # an update replaces the old copy
            items = [n for n in items if n["key"] not in gone]
            items = sorted(fresh + items, key=lambda n: n["time"], reverse=True)
            p["notifications"] = items[:KEEP]
            p["ts"] = time.time()
            self._save()

    def status(self, device_id: str, battery, charging: bool):
        with self._lock:
            p = self._phone(device_id)
            p["battery"] = battery
            p["charging"] = charging
            p["status_ts"] = p["ts"] = time.time()
            self._save()

    def clear(self, device_id: str):
        with self._lock:
            if device_id in self._phones:
                self._phones[device_id]["notifications"] = []
                self._save()

    def snapshot(self) -> dict[str, dict]:
        with self._lock:
            return json.loads(json.dumps(self._phones))


def register(ctx):
    app = ctx.app
    store = PhoneStore(ctx.base_dir / "phone.json")

    @app.route("/api/phone/notifications", methods=["POST"])
    def phone_notifications_post():
        me = ctx.current_device()
        if me is None:
            return jsonify({"error": "Name this device in droplet first."}), 400
        body = request.get_json(silent=True) or {}
        posted, removed = body.get("posted") or [], body.get("removed") or []
        if not isinstance(posted, list) or not isinstance(removed, list):
            abort(400)
        store.notifications(me["id"], posted, removed, bool(body.get("sync")))
        return jsonify({"ok": True})

    @app.route("/api/phone/notifications", methods=["GET"])
    def phone_notifications_get():
        me = ctx.current_device()
        phones = []
        for device_id, p in store.snapshot().items():
            dev = ctx.devices.get(device_id)
            if dev is None:  # removed under Devices
                continue
            if not p["notifications"] and p["battery"] is None:
                continue
            phones.append({
                "id": device_id,
                "name": dev["name"],
                "self": bool(me and me["id"] == device_id),
                "battery": p["battery"],
                "charging": p["charging"],
                "status_ts": p["status_ts"],
                "ts": p["ts"],
                "notifications": p["notifications"],
            })
        phones.sort(key=lambda p: p["ts"], reverse=True)
        return jsonify({"phones": phones})

    @app.route("/api/phone/status", methods=["POST"])
    def phone_status():
        me = ctx.current_device()
        if me is None:
            return jsonify({"error": "Name this device in droplet first."}), 400
        body = request.get_json(silent=True) or {}
        battery = body.get("battery")
        if isinstance(battery, bool) or not isinstance(battery, (int, float)) or not 0 <= battery <= 100:
            abort(400)
        store.status(me["id"], int(battery), bool(body.get("charging")))
        return jsonify({"ok": True})

    @app.route("/api/phone/<device_id>/clear", methods=["POST"])
    def phone_clear(device_id):
        # like removing a device, any device may tidy the list; the PIN / tailnet is the boundary
        if ctx.devices.get(device_id) is None:
            abort(404)
        store.clear(device_id)
        return jsonify({"ok": True})
