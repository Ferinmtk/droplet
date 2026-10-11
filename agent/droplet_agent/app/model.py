"""What the desktop app shows, worked out from the agent's answers. No Qt here, so it's
tested without PySide6, and the windows stay thin."""

from __future__ import annotations

import time
from dataclasses import dataclass, field

# the state of a paired device, as the cards say it
CONNECTED, NEARBY, AWAY = "connected", "nearby", "away"

OS_NAMES = {"android": "Android", "ios": "iPhone", "windows": "Windows", "linux": "Linux", "macos": "Mac"}
PHONES = ("android", "ios")


# --- your device, or someone else's (mesh/perms.py) -------------------------------------------

RELATION_CHOICES = [
    ("own", "My device", "Everything on: files, messages, clipboard, notifications, remote control and ring."),
    ("other", "Someone else's", "A deskmate's laptop, a friend's phone: files, messages and ring only. "
                                "No clipboard, notifications or remote control."),
]


def display_name(p: dict | None) -> str:
    """What this computer calls a device: the nickname given here, else its own name."""
    p = p or {}
    return str(p.get("nickname") or p.get("name") or p.get("id") or "the device")


def own_name_note(p: dict | None) -> str:
    """Under a nickname, the device's own name, small: "Its own name: maryanne"."""
    p = p or {}
    return f"Its own name: {p.get('name')}" if p.get("nickname") and p.get("name") != p.get("nickname") else ""


def is_other(p: dict) -> bool:
    return p.get("relation") == "other"


def is_paused(p: dict) -> bool:
    return bool(p.get("paused"))


def paused_by_it(p: dict) -> bool:
    """It paused sharing with this computer (what it said; a hint)."""
    return bool((p.get("remote") or {}).get("paused"))


def allows(p: dict, cap: str) -> bool:
    """This computer shares `cap` with it, and it said it takes it (as far as it said)."""
    if not (p.get("allow") or {}).get(cap, True):
        return False
    return (p.get("remote") or {}).get("allow", {}).get(cap, True) is not False


def can_send(p: dict, cap: str, paused_all: bool = False) -> bool:
    return not (paused_all or is_paused(p) or paused_by_it(p)) and allows(p, cap)


def perm_summary(p: dict) -> str:
    """"Clipboard, notifications and remote control off": what's switched off for it, in a line."""
    from ..mesh.perms import NOUNS
    off = [c for c, on in (p.get("allow") or {}).items() if not on]
    if not off:
        return "Everything allowed"
    names = [NOUNS.get(c, c) for c in off]
    names[0] = names[0][:1].upper() + names[0][1:]
    if len(names) == 1:
        return f"{names[0]} off"
    return f"{', '.join(names[:-1])} and {names[-1]} off"


def device_state(p: dict) -> str:
    if p.get("link"):
        return CONNECTED
    if p.get("on_lan"):
        return NEARBY
    return AWAY


def state_text(p: dict) -> str:
    """How a device is reached now, in a few words (the Android home screen's wording)."""
    if is_paused(p):
        return "Paused: nothing is shared with it"
    if paused_by_it(p):
        return f"Paused by {display_name(p)}"
    link = str(p.get("link") or "")
    if link.startswith("tailnet"):
        return "Connected via Tailscale"
    if link:
        return "Connected on this network"
    if p.get("on_lan"):
        return "On this network"
    if p.get("hub"):
        return "Not reachable directly: through the hub"
    return "Not reachable: what you send waits for it"


def os_label(os_name: str | None) -> str:
    return OS_NAMES.get((os_name or "").lower(), "")


def is_phone(os_name: str | None) -> bool:
    return (os_name or "").lower() in PHONES


def peers(status: dict | None) -> list[dict]:
    """Paired devices: connected first, then nearby, then the rest, each by name."""
    order = {CONNECTED: 0, NEARBY: 1, AWAY: 2}
    found = [p for p in (status or {}).get("peers") or [] if isinstance(p, dict) and p.get("fp")]
    return sorted(found, key=lambda p: (order[device_state(p)], display_name(p).casefold()))


def summary(status: dict | None) -> str:
    """One line for the sidebar: how many devices, how many connected."""
    if status is None:
        return "The agent isn't running"
    ps = peers(status)
    if not ps:
        return "No paired devices yet"
    connected = sum(1 for p in ps if device_state(p) == CONNECTED)
    n = len(ps)
    return f"{n} device{'s' if n != 1 else ''} · {connected} connected"


def incoming(status: dict | None) -> list[dict]:
    return [r for r in (status or {}).get("incoming") or [] if isinstance(r, dict) and r.get("request")]


def spaced_code(code: str) -> str:
    """"4817" → "4 8 1 7", easier to compare at a glance."""
    return " ".join(str(code or ""))


def grouped_fp(fp: str, group: int = 4, per_line: int = 0) -> str:
    """A fingerprint in groups of four, easier to read out; `per_line` groups a line if given."""
    fp = (fp or "").lower()
    groups = [fp[i:i + group] for i in range(0, len(fp), group)]
    if not per_line:
        return " ".join(groups)
    return "\n".join(" ".join(groups[i:i + per_line]) for i in range(0, len(groups), per_line))


ROUTES = {"lan": "directly, over the network", "tailnet": "directly, over Tailscale",
          "webrtc": "directly, to its web app",
          "hub": "through the hub", "hub-mailbox": "to the hub's mailbox (it's offline)"}


def route_text(route: str | None) -> str:
    return ROUTES.get(route or "", route or "")


# --- sending files ------------------------------------------------------------------

@dataclass
class Transfer:
    """Files sent to one device from one pick or drop: what the card says about them."""
    peer: str
    name: str
    files: dict = field(default_factory=dict)   # job id or "!<file>" → [file name, state, why]

    def add(self, file_name: str, answer: dict):
        """An answer to send-file (a job), or one with an error (the file wasn't taken)."""
        if answer.get("error") or not answer.get("id"):
            self.files[f"!{file_name}"] = [file_name, "failed", answer.get("error") or "it wasn't sent"]
        else:
            self.update(answer, file_name)

    def update(self, job: dict, file_name: str | None = None):
        jid = job.get("id")
        if not jid:
            return
        row = self.files.setdefault(jid, [file_name or job.get("name") or "a file", "queued", None])
        state = job.get("state")
        if state == "done":
            row[1], row[2] = "done", route_text(job.get("route"))
        elif state == "failed":
            row[1], row[2] = "failed", job.get("why") or "failed"
        elif state == "cancelled":
            row[1], row[2] = "cancelled", job.get("why") or "cancelled"
        elif state == "queued" and job.get("attempts") and not job.get("retry"):
            row[1], row[2] = "waiting", job.get("why")
        else:
            row[1] = "sending"

    def pending(self) -> list[str]:
        """Job ids still worth asking about."""
        return [jid for jid, (_n, st, _w) in self.files.items() if st in ("queued", "sending")
                and not jid.startswith("!")]

    def finished(self) -> bool:
        return not self.pending()

    def text(self) -> str:
        rows = list(self.files.values())
        if not rows:
            return ""
        def what(names):
            return names[0] if len(names) == 1 else f"{len(names)} files"
        done = [n for n, st, _ in rows if st == "done"]
        failed = [(n, w) for n, st, w in rows if st == "failed"]
        waiting = [n for n, st, _ in rows if st == "waiting"]
        cancelled = [n for n, st, _ in rows if st == "cancelled"]
        going = [n for n, st, _ in rows if st in ("queued", "sending")]
        parts = []
        if going:
            parts.append(f"Sending {what(going)}…")
        if done:
            routes = {w for n, st, w in rows if st == "done"}
            how = f" {routes.pop()}" if len(routes) == 1 and not going else ""
            parts.append(f"Sent {what(done)}{how}.")
        if waiting:
            parts.append(f"{what(waiting)} {'waits' if len(waiting) == 1 else 'wait'} until "
                         f"{self.name} can be reached.")
        if cancelled:
            parts.append(f"Cancelled {what(cancelled)}.")
        if failed:
            if len(failed) == 1:
                parts.append(f"Couldn't send {failed[0][0]}: {failed[0][1]}")
            else:
                parts.append(f"Couldn't send {len(failed)} files: {failed[0][1]}")
        return " ".join(parts)

    def tone(self) -> str:
        """"busy", "ok", "warn" or "bad", for the colour of the line."""
        states = {st for _n, st, _w in self.files.values()}
        if states & {"queued", "sending"}:
            return "busy"
        if "failed" in states:
            return "bad"
        if "waiting" in states:
            return "warn"
        return "ok"


# --- transfers: how far each file has got (the agent's `transfers`) --------------------------------

def transfers_for(transfers: list | None, fp: str) -> list[dict]:
    """A device's transfers, either way: going ones first, then the ones just finished."""
    return [t for t in transfers or [] if isinstance(t, dict) and t.get("fp") == fp]


def any_active(transfers: list | None) -> bool:
    return any(isinstance(t, dict) and t.get("state") == "active" for t in transfers or [])


def transfer_title(t: dict) -> str:
    """"↑ video.mp4" (going to it) or "↓ photo.jpg" (coming from it)."""
    return f"{'↑' if t.get('dir') == 'out' else '↓'} {t.get('name') or 'a file'}"


def transfer_detail(t: dict) -> str:
    """"45% · 12 s left · 3.2 MB/s · 80 MB", or how it ended."""
    from ..mesh.transfers import progress_text, size_text
    text = progress_text(t)
    if t.get("state") == "active" and t.get("size"):
        text = f"{text} · {size_text(t['size'])}"
    return text


# --- several devices at once ---------------------------------------------------------------

def outcome(answer: dict | None, error: str | None = None) -> tuple[str, str]:
    """One device's result of a send: ("sent" | "waiting" | "refused", why)."""
    if error or not answer:
        return "refused", error or "it wasn't sent"
    st, why = answer.get("state"), answer.get("why") or ""
    if answer.get("how") == "link" or st == "done" or (st is None and answer.get("route")):
        return "sent", route_text(answer.get("route"))
    if st in ("failed", "cancelled"):
        return "refused", why or st
    return "waiting", why[len("waiting: "):] if why.startswith("waiting: ") else (why or "it can't be reached now")


def outcomes_text(what: str, results: list) -> str:
    """"Sent the clipboard to 2 of 3: sheffield: not reachable." `results` is
    [(device name, "sent" | "waiting" | "refused", why)]."""
    sent = [n for n, st, _ in results if st == "sent"]
    others = [(n, st, w) for n, st, w in results if st != "sent"]
    if not others:
        return f"Sent {what} to {', '.join(sent)}." if len(sent) <= 3 else f"Sent {what} to all {len(sent)}."
    head = f"Sent {what} to {len(sent)} of {len(results)}" if sent else f"Didn't send {what}"
    bits = [f"{n} waits ({w})" if st == "waiting" else f"{n}: {w}" for n, st, w in others]
    return f"{head}. " + "; ".join(bits) + "."


# --- messages -----------------------------------------------------------------------

def conversations(messages: list[dict], status: dict | None) -> list[dict]:
    """One row per paired device for the Messages list: the device, its last message,
    newest conversation first, then devices with none yet by name."""
    last: dict[str, dict] = {}
    for m in messages:
        if isinstance(m, dict) and m.get("fp"):
            last[m["fp"]] = m
    rows = []
    for p in peers(status):
        rows.append({"peer": p, "last": last.get(p["fp"])})
    rows.sort(key=lambda r: (r["last"] is None, -(r["last"] or {}).get("ts", 0) if r["last"] else 0,
                             str(r["peer"].get("name") or "").casefold()))
    return rows


def for_peer(messages: list[dict], fp: str) -> list[dict]:
    return [m for m in messages if isinstance(m, dict) and m.get("fp") == fp]


def message_url(m: dict) -> str | None:
    """The web link a message is, for its Open button: a link sent as one, or a message that's
    nothing but a link. Only http and https."""
    from ..mesh.links import only_url
    return only_url(m.get("body"))


def message_note(m: dict) -> str:
    """The small line under a message: when, and what happened to a sent one."""
    when = when_text(m.get("ts"))
    st = m.get("state")
    if st in ("queued", "sending"):
        return f"{when} · waiting to send" if st == "queued" else f"{when} · sending…"
    if st == "failed":
        return f"{when} · not sent: {m.get('why') or 'failed'}"
    return when


def when_text(ts, now: float | None = None) -> str:
    if not isinstance(ts, (int, float)):
        return ""
    now = time.time() if now is None else now
    t, today = time.localtime(ts), time.localtime(now)
    if t[:3] == today[:3]:
        return time.strftime("%H:%M", t)
    if now - ts < 6 * 86400 and ts < now:
        return time.strftime("%a %H:%M", t)
    if t.tm_year == today.tm_year:
        return time.strftime("%-d %b %H:%M", t)
    return time.strftime("%-d %b %Y", t)


def preview(m: dict | None, width: int = 48) -> str:
    if not m:
        return "No messages yet"
    body = " ".join(str(m.get("body") or "").split())
    if len(body) > width:
        body = body[:width - 1] + "…"
    return ("You: " if m.get("dir") == "out" else "") + ("Link: " if m.get("kind") == "link" else "") + body


# --- received files -------------------------------------------------------------------

def size_text(n) -> str:
    from ..mesh.transfers import size_text as text
    return text(n) if isinstance(n, int) else ""


def received_line(f: dict, now: float | None = None) -> str:
    parts = []
    if f.get("from"):
        parts.append(f"From {f['from']}")
    if f.get("ts"):
        parts.append(when_text(f["ts"], now))
    if f.get("exists") is False:
        parts.append("moved or deleted")
    elif f.get("size") is not None:
        parts.append(size_text(f["size"]))
    return " · ".join(parts)


# --- pairing ---------------------------------------------------------------------------

class PairFlow:
    """Pairing with another device, from this side: the Pair page's state machine.

    Each step returns the control request to make (or None), and each `on_*`
    takes the agent's answer. States: "pick" (choose a device), "asking"
    (pair-start sent), "code" (compare the four digits), "waiting" (the
    other device has to accept), and the ends "paired", "declined",
    "expired", "cancelled", or "error" (`error` says why).
    """

    ENDS = ("paired", "declined", "expired", "cancelled", "error")

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = "pick"
        self.target = ""
        self.request = None
        self.code = ""
        self.peer: dict = {}
        self.address = ""
        self.error = ""
        self.relation = "own"

    @property
    def name(self) -> str:
        return str(self.peer.get("name") or self.target or "the other device")

    def start(self, target: str, name: str | None = None) -> dict | None:
        """Ask `target` (a name, fingerprint or address) to pair; `name` is what to call it meanwhile."""
        target = (target or "").strip()
        if not target or self.state not in ("pick", *self.ENDS):
            return None
        self.reset()
        self.state, self.target = "asking", target
        if name:
            self.peer = {"name": name}
        return {"cmd": "pair-start", "target": target}

    def on_started(self, answer: dict | None, error: str | None = None):
        if self.state != "asking":
            return
        if error or not answer or answer.get("error"):
            self.state, self.error = "error", error or (answer or {}).get("error") or "it didn't answer"
            return
        self.request = answer.get("request")
        self.code = str(answer.get("code") or "")
        self.peer = answer.get("peer") or {}
        self.address = str(answer.get("address") or "")
        self.state = "code"

    def confirm(self, yes: bool) -> dict | None:
        """The codes match (then: is it yours? see choose), or cancel."""
        if self.state not in ("code", "relation", "waiting") or not self.request:
            return None
        if not yes:
            self.state = "cancelled"
            return {"cmd": "pair-confirm", "request": self.request, "yes": False}
        if self.state == "code":
            self.state = "relation"
        return None

    def choose(self, relation: str) -> dict | None:
        """"own" or "other": the answer to "Is it your device, or someone else's?"."""
        if self.state != "relation" or relation not in ("own", "other"):
            return None
        self.relation = relation
        self.state = "waiting"
        return {"cmd": "pair-confirm", "request": self.request, "yes": True, "relation": relation}

    def on_confirmed(self, answer: dict | None, error: str | None = None):
        if self.state == "waiting" and (error or not answer or answer.get("error")):
            self.state, self.error = "error", error or (answer or {}).get("error") or "it didn't answer"

    def poll(self) -> dict | None:
        if self.state != "waiting":
            return None
        return {"cmd": "pair-status", "request": self.request}

    def on_status(self, answer: dict | None, error: str | None = None):
        if self.state != "waiting" or error or not answer:
            return    # a missed poll: ask again
        st = answer.get("state")
        if st == "accepted":
            self.state = "paired"
        elif st == "denied":
            self.state = "declined"
        elif st in ("expired", "cancelled"):
            self.state = st

    def headline(self) -> str:
        n = self.name
        return {
            "pick": "Pair a device",
            "asking": f"Asking {n}…",
            "code": f"Pairing with {n}",
            "relation": f"Is {n} your device, or someone else's?",
            "waiting": f"Waiting for {n} to accept",
            "paired": f"Paired with {n}",
            "declined": f"{n} said no",
            "expired": "The request expired",
            "cancelled": "Cancelled: nothing was paired",
            "error": "Couldn't pair",
        }[self.state]

    def detail(self) -> str:
        n = self.name
        return {
            "pick": "",
            "asking": "",
            "code": f"Does {n} show the same code?",
            "relation": "It decides what it may do here. You can change it later, under Permissions.",
            "waiting": f"On {n}, accept the request if it shows this code.",
            "paired": f"You can send to {n} now. It's in Devices" + (
                ", as someone else's device." if self.relation == "other" else "."),
            "declined": "Nothing was paired. You can ask again.",
            "expired": f"{n} didn't answer in time. Nothing was paired; you can ask again.",
            "cancelled": "",
            "error": self.error,
        }[self.state]
