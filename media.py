"""Hub media remote: what's playing on the hub, playback, volume and audio output.

Reads MPRIS players with playerctl and PipeWire with wpctl, both over the
session bus the droplet service runs on. Nothing a client sends is ever run:
player names must be ones `playerctl -l` lists, sink ids ones `wpctl status`
lists, and numbers are clamped.

The reading and parsing lives in mediactl.py, which the Linux agent shares.
"""

import hashlib
import shutil
import socket
import subprocess
from urllib.parse import quote

from flask import abort, jsonify, request, send_file

# re-exported so media.<name> keeps working for anything that used it
from mediactl import (  # noqa: F401
    ART_MAX_BYTES,
    CAPS,
    DEFAULT_SINK,
    FIELDS,
    FORMAT,
    IMAGE_MAGIC,
    MAX_VOLUME,
    SEP,
    SINK_RE,
    TIMEOUT,
    VOLUME_RE,
    _art,
    _art_roots,
    _caps,
    _dedupe_names,
    _identities,
    _identity,
    _micros,
    _ok,
    _run,
    _section,
    friendly_name,
    list_players,
    read_player,
    read_sinks,
    read_volume,
    safe_art,
)
from mediactl import pick_active as _pick_active

def _art_version(path: str) -> str:
    # changes with the track, so the page's <img> reloads without cache games
    return hashlib.sha1(path.encode()).hexdigest()[:10]


# --- routes ------------------------------------------------------------------

def register(ctx):
    app = ctx.app
    state = {"last": None}  # the player most recently playing or controlled

    def tools() -> tuple[bool, bool]:
        return bool(shutil.which("playerctl")), bool(shutil.which("wpctl"))

    def pick_active(players: list[dict]) -> str | None:
        # whatever is playing; otherwise the one last playing or controlled
        return _pick_active(players, state)

    def snapshot() -> dict:
        has_pc, has_wp = tools()
        players = [read_player(p) for p in list_players()] if has_pc else []
        _dedupe_names(players)
        active = pick_active(players)
        for p in players:
            art_file = p.pop("_art_file", None)
            if art_file and safe_art(art_file):
                p["art"] = f"/api/hub/media/art?player={quote(p['id'])}&v={_art_version(art_file)}"
        return {
            "available": has_pc or has_wp,
            "hub": socket.gethostname().split(".")[0],
            "players": players,
            "active": active,
            "volume": read_volume() if has_wp else None,
            "sinks": read_sinks() if has_wp else [],
        }

    def body() -> dict:
        data = request.get_json(silent=True)
        if not isinstance(data, dict):
            abort(400)
        return data

    def known_player(name) -> str:
        if not isinstance(name, str) or name not in list_players():
            abort(400)
        return name

    def number(value, lo: float, hi: float) -> float:
        # bool is an int in Python; reject it rather than read true as 1
        if isinstance(value, bool) or not isinstance(value, (int, float)) or value != value:
            abort(400)
        return max(lo, min(hi, float(value)))

    def done(r: subprocess.CompletedProcess | None):
        if not _ok(r):
            err = (r.stderr or r.stdout).strip() if r else "the hub didn't answer"
            return jsonify({"error": err or "failed"}), 502
        return jsonify(snapshot())

    @app.route("/api/hub/media")
    def hub_media():
        return jsonify(snapshot())

    @app.route("/api/hub/media", methods=["POST"])
    def hub_media_action():
        data = body()
        action = data.get("action")
        if action not in ("play-pause", "next", "previous", "seek"):
            abort(400)
        player = known_player(data.get("player"))
        state["last"] = player
        if action != "seek":
            return done(_run("playerctl", "-p", player, action))
        if "position" in data:  # tap on the progress bar: absolute seconds
            pos = number(data["position"], 0, 24 * 3600)
            return done(_run("playerctl", "-p", player, "position", f"{pos:.2f}"))
        offset = number(data.get("offset"), -3600, 3600)
        # playerctl reads "10+" / "10-" as relative to the current position
        return done(_run("playerctl", "-p", player, "position", f"{abs(offset):.2f}{'-' if offset < 0 else '+'}"))

    @app.route("/api/hub/volume", methods=["POST"])
    def hub_volume():
        data = body()
        if not any(k in data for k in ("level", "delta", "mute", "sink")):
            abort(400)
        sink = None
        if "sink" in data:
            # validate before changing anything, so a bad request changes nothing
            if isinstance(data["sink"], bool) or not isinstance(data["sink"], int):
                abort(400)
            if data["sink"] not in {s["id"] for s in read_sinks()}:
                abort(400)
            sink = data["sink"]
        mute = data.get("mute")
        if "mute" in data and mute not in ("toggle", True, False):
            abort(400)
        level = None
        if "level" in data:
            level = number(data["level"], 0, MAX_VOLUME)
        elif "delta" in data:
            delta = number(data["delta"], -MAX_VOLUME, MAX_VOLUME)
            now = read_volume()
            if now is None:
                return jsonify({"error": "can't read the hub's volume"}), 502
            level = max(0.0, min(MAX_VOLUME, now["level"] + delta))

        if sink is not None:
            r = _run("wpctl", "set-default", str(sink))
            if not _ok(r):
                return done(r)
        if level is not None:
            r = _run("wpctl", "set-volume", DEFAULT_SINK, f"{level:.2f}")
            if not _ok(r):
                return done(r)
        if "mute" in data:
            arg = "toggle" if mute == "toggle" else ("1" if mute else "0")
            r = _run("wpctl", "set-mute", DEFAULT_SINK, arg)
            if not _ok(r):
                return done(r)
        return jsonify(snapshot())

    @app.route("/api/hub/media/art")
    def hub_media_art():
        # the path always comes from the player's own metadata, never the request
        names = list_players()
        player = request.args.get("player")
        if player is None:
            player = pick_active([read_player(p) for p in names])
        if player not in names:
            abort(404)
        found = safe_art(read_player(player).get("_art_file"))
        if not found:
            abort(404)
        path, mime = found
        resp = send_file(path, mimetype=mime, max_age=3600)
        resp.headers["X-Content-Type-Options"] = "nosniff"
        return resp
