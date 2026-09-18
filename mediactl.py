"""Reading and driving media players and audio, shared by the hub and the Linux agent.

Reads MPRIS players with playerctl and PipeWire with wpctl, over the session
bus. No Flask here: the hub's media card (media.py) and the agent
(agent/droplet_agent, which links to this file) both import it, so a fix to
the parsing reaches both.
"""

from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path
from urllib.parse import unquote, urlparse

TIMEOUT = 3  # seconds; a wedged player must not hang the page's poll
SEP = "\x1f"  # ASCII unit separator: can't turn up in a title, unlike | or tab
FIELDS = ("status", "title", "artist", "album", "mpris:artUrl", "position", "mpris:length")
FORMAT = SEP.join("{{%s}}" % f for f in FIELDS)
CAPS = ("CanGoNext", "CanGoPrevious", "CanPlay", "CanPause", "CanSeek")
MAX_VOLUME = 1.0  # louder than 100% distorts laptop speakers; wpctl can go to 1.5
ART_MAX_BYTES = 5 * 1024 * 1024
DEFAULT_SINK = "@DEFAULT_AUDIO_SINK@"

# the art file's type is decided by its first bytes, not its name: Chrome
# writes art to /tmp/.org.chromium.Chromium.<random>, which has no real suffix
IMAGE_MAGIC = (
    (b"\x89PNG\r\n\x1a\n", "image/png"),
    (b"\xff\xd8\xff", "image/jpeg"),
    (b"GIF87a", "image/gif"),
    (b"GIF89a", "image/gif"),
)


def _run(*argv: str) -> subprocess.CompletedProcess | None:
    """Run a hub tool; None when it's missing or hung, so callers just degrade."""
    try:
        return subprocess.run(list(argv), capture_output=True, text=True, timeout=TIMEOUT)
    except (FileNotFoundError, PermissionError, subprocess.TimeoutExpired):
        return None


def _ok(r: subprocess.CompletedProcess | None) -> bool:
    return r is not None and r.returncode == 0


# --- players (playerctl) -----------------------------------------------------

def list_players() -> list[str]:
    r = _run("playerctl", "-l")
    if not _ok(r):
        return []  # "No players found" exits 1
    # playerctld is a proxy for whichever player is newest; listing it too
    # would show the same track twice
    return [p.strip() for p in r.stdout.splitlines() if p.strip() and p.strip() != "playerctld"]


def _micros(s: str) -> float | None:
    try:
        return max(0.0, int(s) / 1_000_000)
    except ValueError:
        return None


def _art(url: str) -> tuple[str | None, str | None]:
    """(url the page can load, local file path) for a player's mpris:artUrl."""
    if url.startswith(("https://", "http://")):
        return url, None
    if url.startswith("file://"):
        return None, unquote(urlparse(url).path)
    return None, None  # data: URIs and anything else: skip rather than guess


_identities: dict[str, str | None] = {}


def _identity(player: str) -> str | None:
    """The player's own display name, e.g. "Chrome - fedora" for a KDE Connect proxy.

    playerctl can't read it, so ask D-Bus directly. It never changes for a
    given bus name, so it's cached.
    """
    if player not in _identities:
        r = _run("busctl", "--user", "--json=short", "get-property", f"org.mpris.MediaPlayer2.{player}",
                 "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2", "Identity")
        ident = None
        if _ok(r):
            try:
                ident = str(json.loads(r.stdout)["data"]).strip() or None
            except (ValueError, KeyError, TypeError):
                pass
        _identities[player] = ident
    return _identities[player]


def _caps(player: str) -> dict[str, bool]:
    """What the player allows right now. Everything is allowed when busctl can't say."""
    out = {f"can_{c[3:].lower()}": True for c in CAPS}
    r = _run("busctl", "--user", "--json=short", "get-property", f"org.mpris.MediaPlayer2.{player}",
             "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", *CAPS)
    if not _ok(r):
        return out
    try:
        values = [bool(json.loads(line)["data"]) for line in r.stdout.splitlines() if line.strip()]
    except (ValueError, KeyError, TypeError):
        return out
    if len(values) == len(CAPS):
        out = {f"can_{c[3:].lower()}": v for c, v in zip(CAPS, values)}
    return out


def friendly_name(player: str) -> str:
    base = player.split(".")[0]  # "chromium.instance56633" -> "chromium"
    ident = _identity(player)
    if base == "kdeconnect":
        # KDE Connect relays a paired device's player as "<player> - <device>"
        if ident and " - " in ident:
            what, _, where = ident.rpartition(" - ")
            return f"{what} on {where}"
        return ident or "KDE Connect"
    return ident or base.replace("_", " ").replace("-", " ").title()


def read_player(player: str) -> dict:
    info = {
        "id": player, "name": friendly_name(player), "status": "Stopped",
        "title": "", "artist": "", "album": "", "art": None, "position": None, "length": None,
    }
    r = _run("playerctl", "-p", player, "metadata", "--format", FORMAT)
    parts = r.stdout.rstrip("\n").split(SEP) if _ok(r) else []
    if len(parts) == len(FIELDS):
        status, title, artist, album, art_url, position, length = parts
        info.update(status=status or "Stopped", title=title, artist=artist, album=album,
                    position=_micros(position), length=_micros(length))
        info["art"], info["_art_file"] = _art(art_url)
    else:
        # a player with no track (e.g. KDE Connect's proxy while the phone is
        # idle) fails `metadata` with "No player could handle this command"
        s = _run("playerctl", "-p", player, "status")
        if _ok(s) and s.stdout.strip():
            info["status"] = s.stdout.strip()
    info.update(_caps(player))
    return info


def _dedupe_names(players: list[dict]):
    # two Chrome windows are both "Chrome"; number them so the picker can tell
    seen: dict[str, int] = {}
    for p in players:
        n = seen[p["name"]] = seen.get(p["name"], 0) + 1
        if n > 1:
            p["name"] = f"{p['name']} ({n})"


# --- audio (wpctl) -----------------------------------------------------------

VOLUME_RE = re.compile(r"Volume:\s*([\d.]+)(\s*\[MUTED\])?")
SINK_RE = re.compile(r"^(\*)?\s*(\d+)\.\s+(.*?)\s*(?:\[vol:[^\]]*\])?\s*$")


def read_volume() -> dict | None:
    r = _run("wpctl", "get-volume", DEFAULT_SINK)
    m = VOLUME_RE.search(r.stdout) if _ok(r) else None
    if not m:
        return None
    return {"level": min(float(m.group(1)), 1.5), "muted": bool(m.group(2))}


def _section(lines: list[str], start: int, name: str) -> list[str]:
    """The entries of one "├─ Name:" subsection of a top-level block of `wpctl status`."""
    out, inside = [], False
    for line in lines[start:]:
        if line and not line[0].isspace() and line[0] not in "│├└":
            break  # next top-level block (Video, Settings)
        if "├─" in line or "└─" in line:
            if inside:
                break
            inside = line.split("─", 1)[1].strip() == f"{name}:"
            continue
        if inside:
            entry = line.replace("│", " ").strip()
            if entry:
                out.append(entry)
    return out


def read_sinks() -> list[dict]:
    """Audio outputs from `wpctl status`, with the hardware name trimmed off.

    "400 Series Chipset Family On-Package HD Audio Speaker" reads as just
    "Speaker" when the device is "400 Series Chipset Family On-Package HD Audio".
    """
    r = _run("wpctl", "status")
    if not _ok(r):
        return []
    lines = r.stdout.splitlines()
    try:
        audio = next(i for i, line in enumerate(lines) if line.strip() == "Audio")
    except StopIteration:
        return []
    devices = []
    for entry in _section(lines, audio + 1, "Devices"):
        m = re.match(r"^\*?\s*\d+\.\s+(.*?)\s*\[[^\]]*\]\s*$", entry)
        if m:
            devices.append(m.group(1))
    sinks = []
    for entry in _section(lines, audio + 1, "Sinks"):
        m = SINK_RE.match(entry)
        if not m:
            continue
        name = full = m.group(3)
        for dev in sorted(devices, key=len, reverse=True):
            if full.startswith(dev + " ") and full[len(dev):].strip():
                name = full[len(dev):].strip()
                break
        sinks.append({"id": int(m.group(2)), "name": name, "full": full, "default": bool(m.group(1))})
    return sinks


# --- art ---------------------------------------------------------------------

def _art_roots() -> list[Path]:
    return [Path.home().resolve(), Path("/tmp").resolve()]


def safe_art(path: str | None) -> tuple[Path, str] | None:
    """(file, mimetype) when a player's local art is safe to hand out, else None.

    The path comes from the player's metadata, never from the client, but a
    player could still name any file, so: it has to be a real file under
    home or /tmp once symlinks are resolved, at most 5 MB, and actually an
    image by its content.
    """
    if not path:
        return None
    try:
        p = Path(path).resolve(strict=True)
        if not p.is_file() or not any(root in p.parents for root in _art_roots()):
            return None
        if p.stat().st_size > ART_MAX_BYTES:
            return None
        with p.open("rb") as f:
            head = f.read(16)
    except (OSError, RuntimeError, ValueError):
        return None
    for magic, mime in IMAGE_MAGIC:
        if head.startswith(magic):
            return p, mime
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return p, "image/webp"
    return None


def pick_active(players: list[dict], state: dict) -> str | None:
    """Whatever is playing; otherwise the player last playing or controlled.

    `state["last"]` remembers that player between calls.
    """
    playing = next((p for p in players if p["status"] == "Playing"), None)
    if playing:
        state["last"] = playing["id"]
        return playing["id"]
    ids = [p["id"] for p in players]
    return state.get("last") if state.get("last") in ids else (ids[0] if ids else None)
