"""Pairing on a phone's hotspot, where mDNS doesn't reach (docs/mesh.md §9.10).

A device serving a hotspot (an Android phone above all) doesn't announce
itself to the devices that joined it, and may not hear them either, so
neither side's Pair screen would list the other. But the device serving the
hotspot is always the joined device's default gateway. So, while a Pair
screen is open, the joined device asks its gateway who it is:

    POST https://<gateway>:1739/mesh/pair/hello      (no client certificate, like all of /mesh/pair*)
    {"v":1, "id", "name", "os", "fp", "port"}         the asker: who it is, and its mesh port
    → 200 {"v":1, "id", "name", "os", "fp", "port"}   the answerer, the same

The asker checks `fp` is the certificate the answerer presented in TLS, and
lists it for pairing like a device found over mDNS. The answerer lists the
asker too, at the address the request came from, for a minute ("knocked"),
so whichever side the people start from works.

Both are hints, exactly as an mDNS announcement is: they say where a device
is and what it's called, and prove nothing. Pairing itself (§9.3) is
unchanged, and the 4-digit code is still what proves who's at the other end.
Nothing is ever paired by itself.
"""

from __future__ import annotations

import http.client
import json
import re
import threading
import time

from . import DEFAULT_PORT, OS_NAME, PROTOCOL_VERSION
from .discovery import OSES, Seen
from .identity import PEER_ID
from .tlsctx import client_context, peer_fingerprint
from .trust import clean_name, clean_port

FP = re.compile(r"^[0-9a-f]{64}$")
HELLO_PATH = "/mesh/pair/hello"
KNOCK_TTL = 60          # seconds a device that asked stays listed on the gateway
MAX_KNOCKS = 8          # devices listed that way at once
MAX_HELLOS = 60         # hellos answered a minute
FOUND_TTL = 30          # seconds the gateway stays listed after its last answer
HELLO_EVERY = 10        # while a Pair screen is open, ask a gateway that answered this often
QUIET_FIRST, QUIET_MAX = 5, 60   # and one that didn't: after 5 s, then twice as long each time, up to a minute
TIMEOUT = 3


def parse_hello(body) -> dict | None:
    """A hello's {"id","name","os","fp","port"}, or None if it isn't a usable one."""
    if not isinstance(body, dict):
        return None
    peer_id, fp = body.get("id"), body.get("fp")
    if not isinstance(peer_id, str) or not PEER_ID.match(peer_id) or not isinstance(fp, str) or not FP.match(fp):
        return None
    os_name = body.get("os") if body.get("os") in OSES else ""
    return {"id": peer_id, "fp": fp, "name": clean_name(body.get("name"), peer_id)[:64], "os": os_name,
            "port": clean_port(body.get("port")) or DEFAULT_PORT}


def me(peer_id: str, fp: str, name: str, port: int) -> dict:
    return {"v": PROTOCOL_VERSION, "id": peer_id, "name": clean_name(name, peer_id)[:63], "os": OS_NAME,
            "fp": fp, "port": port}


class Knocks:
    """The answering side: devices that asked who this one is, listed for a minute."""

    def __init__(self, own_fp: str, *, clock=time.monotonic):
        self.own_fp, self.clock = own_fp, clock
        self._lock = threading.Lock()
        self._seen: dict[str, tuple[Seen, float]] = {}     # fp → (who, when it asked)
        self._answered: list[float] = []

    def answer(self, body, address: str | None, mine: dict) -> tuple[int, dict]:
        now = self.clock()
        with self._lock:
            self._answered = [t for t in self._answered if now - t < 60]
            if len(self._answered) >= MAX_HELLOS:
                return 429, {"error": "too many requests; wait a minute"}
            self._answered.append(now)
            self._prune(now)
            who = parse_hello(body)
            if who is not None and address and who["fp"] != self.own_fp \
                    and (who["fp"] in self._seen or len(self._seen) < MAX_KNOCKS):
                self._seen[who["fp"]] = (Seen(fp=who["fp"], id=who["id"], name=who["name"], os=who["os"], caps=[],
                                              hub="", port=who["port"], addresses=[address], seen=time.time()), now)
        return 200, mine

    def _prune(self, now: float):
        for fp, (_s, at) in list(self._seen.items()):
            if now - at > KNOCK_TTL:
                del self._seen[fp]

    def peers(self) -> list[Seen]:
        with self._lock:
            self._prune(self.clock())
            return [s for s, _at in self._seen.values()]


def ask(host: str, port: int, mine: dict, timeout: float = TIMEOUT) -> Seen | None:
    """Ask the device at host:port who it is (and say who this one is). None: no droplet
    device there, or one too old to answer (404)."""
    conn = http.client.HTTPSConnection(host, port, context=client_context(None, None), timeout=timeout)
    try:
        conn.connect()
        tls_fp = peer_fingerprint(conn.sock)
        data = json.dumps(mine).encode()
        conn.request("POST", HELLO_PATH, body=data, headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        raw = resp.read(65536)
        if resp.status != 200:
            return None
        try:
            who = parse_hello(json.loads(raw))
        except ValueError:
            return None
    finally:
        conn.close()
    if who is None or who["fp"] != tls_fp:
        return None     # the answer doesn't match the certificate it presented
    return Seen(fp=who["fp"], id=who["id"], name=who["name"], os=who["os"], caps=[], hub="", port=who["port"],
                addresses=[host], seen=time.time())


class GatewayScan:
    """The asking side: which gateways answered, and when to ask each again."""

    def __init__(self, *, clock=time.monotonic):
        self.clock = clock
        self._lock = threading.Lock()
        self._found: dict[str, tuple[Seen, float]] = {}       # gateway → (who, until)
        self._next: dict[str, tuple[float, float]] = {}       # gateway → (when to ask again, the wait)

    def due(self, gateways: list[str], force: bool = False) -> list[str]:
        now = self.clock()
        with self._lock:
            for d in (self._found, self._next):
                for gw in [g for g in d if g not in gateways]:
                    del d[gw]
            return [gw for gw in gateways if force or self._next.get(gw, (0.0, 0.0))[0] <= now]

    def result(self, gateway: str, seen: Seen | None):
        now = self.clock()
        with self._lock:
            if seen is not None:
                self._found[gateway] = (seen, now + FOUND_TTL)
                self._next[gateway] = (now + HELLO_EVERY, 0.0)
                return
            self._found.pop(gateway, None)
            wait = min(max(self._next.get(gateway, (0.0, 0.0))[1] * 2, QUIET_FIRST), QUIET_MAX)
            self._next[gateway] = (now + wait, wait)

    def peers(self) -> list[Seen]:
        now = self.clock()
        with self._lock:
            return [s for s, until in self._found.values() if until > now]
