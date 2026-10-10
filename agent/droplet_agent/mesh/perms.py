"""Per-device permissions and Pause (docs/mesh.md §9.9).

Each trusted peer has a **relation**, the owner's answer to "Is this your
device, or someone else's?", and a switch per **capability**. A peer can
also be **paused**: nothing goes to it and nothing from it is taken, except
the few messages that keep the link and its status working. A **global
pause** does that for every peer at once.

Enforcement is local and both ways: this device checks what it sends and
what it accepts, whatever the peer says. What a peer tells us about its own
switches (`perm`, in its hello and as a message) is only a hint for the UI,
and for not sending what it would refuse anyway.
"""

from __future__ import annotations

RELATIONS = ("own", "other")

# capability: (what it covers, as a label in the UI and the CLI)
CAPABILITIES = {
    "files": "Send and receive files",
    "chat": "Messages",
    "clipboard": "Shared clipboard",
    "notify": "Notifications",
    "control": "Remote control",
    "ring": "Ring",
    "access": "SMS, files on the device and commands",
}

# the words in "<name> doesn't allow <noun> from you"
NOUNS = {
    "files": "files", "chat": "messages", "clipboard": "the clipboard", "notify": "notifications",
    "control": "remote control", "ring": "ringing", "access": "SMS, files and commands",
}

# one line under each switch
EXPLAIN = {
    "files": "Files both ways.",
    "chat": "Messages both ways.",
    "clipboard": "Clipboard sync, and a clipboard sent on purpose, both ways.",
    "notify": "Notifications mirrored between it and this device, both ways.",
    "control": "It may use this device's mouse and keyboard, media, lock, screenshots and presentation remote.",
    "ring": "It may ring this device to find it.",
    "access": "It may read this device's SMS, browse its files and run commands, where it offers them.",
}

# about this device only: the switch says what the peer may do here. What this device may do
# to the peer is the peer's own switch, which it enforces (and tells us, as a hint).
INBOUND_ONLY = frozenset({"control", "ring", "access"})

OWN = {c: True for c in CAPABILITIES}
OTHER = {"files": True, "chat": True, "clipboard": False, "notify": False, "control": False, "ring": True,
         "access": False}

# always allowed, even paused: what keeps the link up, says why something was refused,
# and lets either side unpair. ack and nack answer what was sent before the pause.
ALWAYS = frozenset({"hello", "welcome", "ping", "pong", "perm", "unpair", "ack", "nack", "refused", "error",
                    "rpc-result"})


def defaults(relation: str) -> dict:
    return dict(OTHER if relation == "other" else OWN)


def clean_relation(v) -> str:
    return v if v in RELATIONS else "own"


def clean_allow(v, relation: str = "own") -> dict:
    """Every capability, as a bool: the relation's default where `v` doesn't say."""
    out = defaults(relation)
    if isinstance(v, dict):
        for c in CAPABILITIES:
            if isinstance(v.get(c), bool):
                out[c] = v[c]
    return out


def capability(msg: dict) -> str | None:
    """The capability a message needs, either way. None: it needs none (but still stops while paused,
    unless it's in ALWAYS)."""
    t = msg.get("t")
    if t == "text":
        return "chat"
    if t in ("offer", "file", "file-end"):
        return "files"
    if t == "clip":
        return "clipboard"
    if t in ("notify", "notify-removed"):
        return "notify"
    if t in ("input", "media", "cmd"):
        return "control"
    if t in ("ring", "ring-stop"):
        return "ring"
    if t == "rpc":
        method = str(msg.get("method") or "")
        return "control" if method.startswith("media.") else "access"
    if t == "state":
        # what's playing is part of remote control; the battery level is just there
        return "control" if msg.get("kind") == "media" else None
    return None


def check(entry: dict | None, msg: dict, paused_all: bool = False, direction: str = "in") -> tuple[str, str] | None:
    """Whether a message may come from (`direction` "in") or go to ("out") the peer `entry`, by
    this device's own settings. None if it may; else (why, capability): why is "paused" or "denied"."""
    t = msg.get("t")
    if t in ALWAYS:
        return None
    cap = capability(msg)
    if paused_all or (entry or {}).get("paused"):
        return "paused", cap or ""
    if direction == "out" and cap in INBOUND_ONLY and t != "state":
        return None       # the peer decides what this device may do to it
    if cap and not allowed(entry, cap):
        return "denied", cap
    return None


def allowed(entry: dict | None, cap: str) -> bool:
    if entry is None:
        return False
    allow = entry.get("allow")
    if not isinstance(allow, dict):
        return True       # an entry from before permissions: your own device, as it was
    return bool(allow.get(cap, defaults(entry.get("relation") or "own").get(cap, True)))


def remote_view(entry: dict | None, paused_all: bool) -> dict:
    """What this device tells a peer about how it treats it: `perm` (a hint for its UI)."""
    entry = entry or {}
    return {"paused": bool(paused_all or entry.get("paused")),
            "allow": clean_allow(entry.get("allow"), entry.get("relation") or "own")}


def parse_remote(v) -> dict | None:
    """A peer's `perm`, cleaned: {"paused": bool, "allow": {cap: bool}}. None if it sent none
    (an older peer: everything as before)."""
    if not isinstance(v, dict):
        return None
    allow = {c: v["allow"][c] for c in CAPABILITIES
             if isinstance(v.get("allow"), dict) and isinstance(v["allow"].get(c), bool)}
    return {"paused": v.get("paused") is True, "allow": allow}


def remote_refuses(remote: dict | None, cap: str | None) -> str | None:
    """What the peer said it would do with `cap` from us: "paused", "denied" or None."""
    if not remote:
        return None
    if remote.get("paused"):
        return "paused"
    if cap and (remote.get("allow") or {}).get(cap) is False:
        return "denied"
    return None


def refusal_text(name: str, why: str, cap: str | None) -> str:
    """What the sender shows: "Brian's laptop doesn't allow the clipboard from you"."""
    if why == "paused":
        return f"{name} paused sharing with you"
    return f"{name} doesn't allow {NOUNS.get(cap or '', cap or 'that')} from you"


def local_text(name: str, why: str, cap: str | None, paused_all: bool = False) -> str:
    """Why this device itself won't send it."""
    if why == "paused":
        if paused_all:
            return "everything is paused on this device: resume to send"
        return f"{name} is paused: resume it to send"
    return f"{NOUNS.get(cap or '', cap or 'that').capitalize()} with {name} is switched off here"
