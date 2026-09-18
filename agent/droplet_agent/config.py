"""The agent's settings: ~/.config/droplet-agent/config.json, owner-only.

It holds the device token, so it's written atomically with mode 600, and
unknown keys are kept so a newer agent's settings survive an older one.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

CAPS = ("input", "media", "lock", "screenshot", "clipboard")
INPUT_BACKENDS = ("auto", "portal", "uinput", "x11", "log")
UINPUT_TEXT_MODES = ("auto", "wtype", "ascii")

# who the hub is, so the agent can find it on any network and know it's the
# same one (docs/local-first.md). Written by setup, or read from the hub the
# first time a newer agent runs with an older config.
IDENTITY_DEFAULTS: dict = {
    "id": "",            # the hub's permanent id
    "name": "",          # the hub machine's name
    "fingerprint": "",   # SHA-256 of its LAN certificate: the pin
    "lan": [],           # LAN addresses that worked last, newest first: hints, never trusted alone
    "https_port": None,  # its LAN HTTPS port
    "http_port": None,   # its plain-HTTP port (loopback, on the hub machine)
    "tailnet": "",       # its tailnet URL, if it has one
}

DEFAULTS: dict = {
    "hub": "",
    "token": "",
    "device": {"id": "", "name": ""},
    "hub_identity": IDENTITY_DEFAULTS,
    # true while a new device waits for one of yours to let it in
    "pending": False,
    # each capability can be switched off here; the agent then neither
    # advertises it nor acts on it
    "caps": {c: True for c in CAPS},
    # "auto" picks the first that works: portal, then uinput, then x11
    "input_backend": "auto",
    # how the uinput backend types characters a US keyboard can't:
    # "auto" uses wtype when it's installed, "ascii" drops accents and skips the rest
    "uinput_text": "auto",
    # null: loginctl on KDE/GNOME, else the first locker found (gtklock,
    # swaylock, hyprlock…). Or a command as a list: ["swaylock", "-f"]
    "lock_command": None,
    # null: portal, then spectacle, gnome-screenshot, grim, niri, import
    "screenshot_command": None,
    "clipboard_max_bytes": 256 * 1024,
}


def config_dir() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config")
    return Path(base) / "droplet-agent"


def config_path() -> Path:
    return config_dir() / "config.json"


def portal_token_path() -> Path:
    # kept apart from config.json: the agent rewrites it on every start,
    # and must never clobber settings the user is editing
    return config_dir() / "portal-restore-token"


def _merge(defaults: dict, found: dict) -> dict:
    out = dict(found)
    for k, v in defaults.items():
        if k not in out:
            out[k] = json.loads(json.dumps(v))  # a copy, so callers can't edit DEFAULTS
        elif isinstance(v, dict) and isinstance(out[k], dict):
            out[k] = _merge(v, out[k])
    return out


def load(path: Path | None = None) -> dict:
    """The config with defaults filled in. Missing file → defaults (not set up)."""
    path = path or config_path()
    try:
        found = json.loads(path.read_text())
    except FileNotFoundError:
        found = {}
    if not isinstance(found, dict):
        raise ValueError(f"{path} isn't a JSON object")
    return _merge(DEFAULTS, found)


def save(cfg: dict, path: Path | None = None) -> Path:
    path = path or config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    os.chmod(path.parent, 0o700)
    tmp = path.with_suffix(".tmp")
    # create it 600 from the start, so the token is never readable, even briefly
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(cfg, f, indent=2)
        f.write("\n")
    os.chmod(tmp, 0o600)
    tmp.replace(path)
    return path


def is_set_up(cfg: dict) -> bool:
    return bool(cfg.get("hub") and cfg.get("token")) and not cfg.get("pending")


def save_identity(identity: dict, path: Path | None = None) -> Path:
    """Store what the agent learnt about the hub, and nothing else.

    Read fresh and written back at once, so settings changed by hand while
    the agent runs survive.
    """
    cfg = load(path)
    cfg["hub_identity"] = identity
    return save(cfg, path)


def enabled(cfg: dict, cap: str) -> bool:
    return bool((cfg.get("caps") or {}).get(cap, False))


def read_secret(path: Path) -> str | None:
    try:
        return path.read_text().strip() or None
    except OSError:
        return None


def write_secret(path: Path, value: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(value)
    os.chmod(path, 0o600)
