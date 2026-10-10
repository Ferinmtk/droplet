"""The trust list: the peers this device talks to (docs/mesh.md §3).

Kept in the config directory, owner-only, as JSON, keyed by fingerprint:

    {"<fp>": {"id", "name", "fp", "cert_pem", "source": "roster"|"paired",
              "lan": [...], "port", "tailnet_ip", "os", "caps", "added"}}

- "roster": vouched for by the hub. Replaced wholesale by each roster fetch,
  so a device removed on the hub is dropped here on the next fetch. Kept
  while the hub is down, so peers keep working without it.
- "paired": paired directly by the owner. Survives roster changes, and only
  goes when unpaired.
- "browser": the iPhone web app, paired directly over WebRTC (docs/iphone.md).
  A bare public key (`key`, base64 SPKI) instead of a certificate
  (`cert_pem` is null): it never takes part in TLS, and is never dialled; it
  connects when it's open.

A certificate is stored in full: the server loads it as a trust anchor.

Each entry also holds what the owner decided about it (perms.py, docs/mesh.md §9.9):
`relation` ("own" or "other": your device, or someone else's), `allow` (a switch per
capability) and `paused`. An entry from before these existed is your own device with
everything on, as it was; so is every roster peer until the owner changes it. A roster
fetch keeps them.
"""

from __future__ import annotations

import ipaddress
import json
import os
import threading
import time
from pathlib import Path

from .identity import PEER_ID, fingerprint, key_fingerprint, normalize_fingerprint, p256_spki, pem_to_der
from .perms import RELATIONS, clean_allow, clean_relation, defaults

SOURCES = ("roster", "paired", "browser")
MAX_LAN = 6


def clean_addresses(items, limit: int = MAX_LAN) -> list[str]:
    out = []
    for a in items or []:
        try:
            ip = ipaddress.ip_address(str(a).split("%")[0])
        except ValueError:
            continue
        if ip.is_unspecified or ip.is_multicast or (ip.version == 6 and ip.is_link_local):
            continue
        s = str(ip)
        if s not in out:
            out.append(s)
    return out[:limit]


def clean_port(v) -> int | None:
    return v if isinstance(v, int) and not isinstance(v, bool) and 0 < v < 65536 else None


def clean_name(v, fallback: str = "") -> str:
    name = " ".join(str(v or "").split())
    name = "".join(ch for ch in name if ch.isprintable())[:64]
    return name or fallback


def clean_caps(v) -> list[str]:
    if isinstance(v, str):
        v = v.split(",")
    if not isinstance(v, list):
        return []
    return sorted({c.strip() for c in v if isinstance(c, str) and c.strip().isascii()
                   and c.strip().replace("-", "").isalnum() and len(c.strip()) <= 20})


def with_perms(entry: dict, relation=None, allow=None, paused=False) -> dict:
    """Add the owner's choices to an entry: missing ones are "own", with everything on."""
    entry["relation"] = clean_relation(relation)
    entry["allow"] = clean_allow(allow, entry["relation"])
    entry["paused"] = paused is True
    return entry


def make_entry(*, peer_id, name, cert_pem, source, lan=(), port=None, tailnet_ip=None, os_name="",
               caps=(), fp=None, hub=None, relation=None, allow=None, paused=False) -> dict:
    """A checked entry. Raises ValueError when the id, certificate or fingerprint is wrong."""
    if source not in SOURCES:
        raise ValueError(f"bad source {source!r}")
    if not isinstance(peer_id, str) or not PEER_ID.match(peer_id):
        raise ValueError("bad peer id")
    der = pem_to_der(cert_pem)
    real = fingerprint(der)
    if fp is not None and normalize_fingerprint(fp) != real:
        raise ValueError("the fingerprint doesn't match the certificate")
    tip = clean_addresses([tailnet_ip] if tailnet_ip else [], 1)
    return with_perms({
        "id": peer_id, "name": clean_name(name, peer_id), "fp": real,
        "cert_pem": pem_to_pem(der), "source": source,
        "lan": clean_addresses(lan), "port": clean_port(port),
        "tailnet_ip": tip[0] if tip else None,
        "os": clean_name(os_name)[:20], "caps": clean_caps(list(caps)),
        # the hub whose roster lists it: messages to it may go through that hub
        "hub": hub if isinstance(hub, str) and PEER_ID.match(hub) else "",
        "added": int(time.time()),
    }, relation, allow, paused)


def make_browser_entry(*, name, key, os_name="ios", fp=None, relation=None, allow=None, paused=False) -> dict:
    """A checked entry for a browser peer (the iPhone web app, docs/iphone.md): a bare P-256
    public key, no certificate, so never a TLS trust anchor. Its fingerprint is the SHA-256 of
    the key's SubjectPublicKeyInfo, and its id the first 16 hex of that. Raises ValueError."""
    import base64
    spki = p256_spki(key)
    real = key_fingerprint(spki)
    if fp is not None and normalize_fingerprint(fp) != real:
        raise ValueError("the fingerprint doesn't match the key")
    return with_perms({
        "id": real[:16], "name": clean_name(name, "iPhone"), "fp": real,
        "key": base64.b64encode(spki).decode(), "cert_pem": None, "source": "browser",
        "lan": [], "port": None, "tailnet_ip": None,
        "os": clean_name(os_name)[:20], "caps": [], "hub": "", "added": int(time.time()),
    }, relation, allow, paused)


def pem_to_pem(der: bytes) -> str:
    from .identity import der_to_pem
    return der_to_pem(der)


class TrustList:
    def __init__(self, path: Path, own_fp: str):
        self.path = path
        self.own_fp = own_fp
        self._lock = threading.RLock()
        self._peers: dict[str, dict] = {}
        self.on_change = None   # called (no arguments) after the set of trusted certificates changes
        self._pending_change = False
        self._load()

    def _load(self):
        try:
            raw = json.loads(self.path.read_text())
        except FileNotFoundError:
            raw = {}
        except ValueError:
            raw = {}
        peers = {}
        for fp, e in (raw.get("peers") or {}).items() if isinstance(raw, dict) else []:
            try:
                if isinstance(e, dict) and e.get("source") == "browser":
                    entry = make_browser_entry(name=e.get("name"), key=e.get("key"), os_name=e.get("os") or "",
                                               fp=fp, relation=e.get("relation"), allow=e.get("allow"),
                                               paused=e.get("paused"))
                    entry["added"] = e.get("added") or entry["added"]
                    if entry["fp"] != self.own_fp:
                        peers[entry["fp"]] = entry
                    continue
                entry = make_entry(peer_id=e.get("id"), name=e.get("name"), cert_pem=e.get("cert_pem"),
                                   source=e.get("source"), lan=e.get("lan"), port=e.get("port"),
                                   tailnet_ip=e.get("tailnet_ip"), os_name=e.get("os"), caps=e.get("caps") or [],
                                   fp=fp, hub=e.get("hub"), relation=e.get("relation"), allow=e.get("allow"),
                                   paused=e.get("paused"))
            except (ValueError, AttributeError, TypeError):
                continue  # a damaged entry is dropped, never trusted
            entry["added"] = e.get("added") or entry["added"]
            if entry["fp"] != self.own_fp:
                peers[entry["fp"]] = entry
        self._peers = peers

    def _save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump({"v": 1, "peers": self._peers}, f, indent=1)
            f.write("\n")
        os.chmod(tmp, 0o600)
        tmp.replace(self.path)

    def _changed(self, certs_changed: bool):
        self._save()
        if certs_changed:
            self._pending_change = True

    def _fire(self):
        """Tell the listener, outside the lock (it closes links, which takes other locks)."""
        with self._lock:
            fire, self._pending_change = self._pending_change, False
        if fire and self.on_change:
            self.on_change()

    # --- reading -------------------------------------------------------------

    def get(self, fp: str | None) -> dict | None:
        with self._lock:
            e = self._peers.get(fp or "")
            return dict(e, allow=dict(e["allow"])) if e else None

    def all(self) -> list[dict]:
        with self._lock:
            return [dict(e, allow=dict(e["allow"]))
                    for e in sorted(self._peers.values(), key=lambda e: (e["name"].lower(), e["fp"]))]

    def pems(self) -> list[str]:
        with self._lock:
            return [e["cert_pem"] for e in self._peers.values() if e.get("cert_pem")]

    def find(self, query: str) -> list[dict]:
        """Peers matching a name (case-insensitive), an id, or a fingerprint prefix of 8+ hex."""
        q = (query or "").strip()
        ql = q.lower()
        with self._lock:
            peers = list(self._peers.values())
        exact = [e for e in peers if e["id"] == ql or e["fp"] == ql or e["name"].casefold() == q.casefold()]
        if exact:
            return [dict(e) for e in exact]
        if len(ql) >= 8:
            return [dict(e) for e in peers if e["fp"].startswith(ql.replace(":", ""))]
        return []

    # --- changing --------------------------------------------------------------

    def add_paired(self, entry: dict):
        """Trust a directly paired peer. A re-pair of the same device id replaces its old certificate."""
        assert entry["source"] == "paired"
        with self._lock:
            if entry["fp"] == self.own_fp:
                raise ValueError("that's this device")
            for fp, e in list(self._peers.items()):
                if e["id"] == entry["id"] and e["source"] == "paired" and fp != entry["fp"]:
                    del self._peers[fp]
            old = self._peers.get(entry["fp"])
            if old:
                entry["lan"] = clean_addresses(entry["lan"] + old.get("lan", []))
                entry["tailnet_ip"] = entry.get("tailnet_ip") or old.get("tailnet_ip")
            self._peers[entry["fp"]] = entry
            self._changed(True)
        self._fire()

    def add_browser(self, entry: dict):
        """Trust a browser peer paired over WebRTC. Not a TLS trust anchor, so no TLS change."""
        assert entry["source"] == "browser"
        with self._lock:
            old = self._peers.get(entry["fp"])
            if old is not None and old["source"] != "browser":
                raise ValueError("that key belongs to another peer")
            self._peers[entry["fp"]] = entry
            self._save()

    def set_perms(self, fp: str, *, relation=None, allow=None, paused=None) -> dict:
        """Change what the owner decided about a peer. A new `relation` starts from its defaults;
        `allow` then changes only the capabilities it names. Returns the entry."""
        with self._lock:
            e = self._peers.get(fp)
            if e is None:
                raise ValueError("that peer isn't trusted")
            if relation is not None:
                if relation not in RELATIONS:
                    raise ValueError(f"relation must be one of {', '.join(RELATIONS)}")
                e["relation"] = relation
                e["allow"] = defaults(relation)
            if allow is not None:
                if not isinstance(allow, dict):
                    raise ValueError("allow must be {capability: true|false}")
                unknown = [c for c in allow if c not in e["allow"]]
                if unknown:
                    raise ValueError(f"no capability called {unknown[0]!r}; they are: {', '.join(e['allow'])}")
                for c, on in allow.items():
                    if not isinstance(on, bool):
                        raise ValueError(f"{c} must be true or false")
                    e["allow"][c] = on
            if paused is not None:
                e["paused"] = bool(paused)
            self._save()
            return dict(e, allow=dict(e["allow"]))

    def remove(self, fp: str) -> dict | None:
        with self._lock:
            e = self._peers.pop(fp, None)
            if e is not None:
                self._changed(True)
        self._fire()
        return e

    def sync_roster(self, entries: list[dict], hub_id: str) -> tuple[int, int]:
        """Make the roster peers exactly `entries` (from hub `hub_id`). Paired peers stay,
        and get fresher addresses from it.

        Returns (added, removed).
        """
        with self._lock:
            new = {e["fp"]: e for e in entries if e["fp"] != self.own_fp}
            before = {fp: e["cert_pem"] for fp, e in self._peers.items()}
            removed = 0
            for fp, e in list(self._peers.items()):
                if e["source"] == "roster" and fp not in new:
                    del self._peers[fp]
                    removed += 1
                elif e["source"] == "paired" and fp not in new and e.get("hub") == hub_id:
                    e["hub"] = ""   # still paired, but that hub no longer knows it
            added = 0
            for fp, e in new.items():
                old = self._peers.get(fp)
                if old is None:
                    self._peers[fp] = e
                    added += 1
                elif old["source"] == "paired":
                    # direct pairing is the owner's own decision: it stays "paired",
                    # and learns where the peer is from the hub
                    old.update(lan=clean_addresses(e["lan"] + old["lan"]), port=e["port"] or old["port"],
                               tailnet_ip=e["tailnet_ip"] or old["tailnet_ip"], os=e["os"] or old["os"],
                               caps=e["caps"] or old["caps"], hub=e["hub"])
                else:
                    e["added"] = old["added"]
                    e["lan"] = clean_addresses(e["lan"] + old["lan"])
                    # the owner's switches and pause are theirs, not the hub's
                    e.update(relation=old["relation"], allow=old["allow"], paused=old["paused"])
                    self._peers[fp] = e
            after = {fp: e["cert_pem"] for fp, e in self._peers.items()}
            self._changed(after != before)
        self._fire()
        return added, removed

    def learn(self, fp: str, *, address: str | None = None, port=None, name=None, peer_id=None,
              os_name=None, caps=None, tailnet: bool = False):
        """Record what an authenticated peer said about itself, or where it answered."""
        with self._lock:
            e = self._peers.get(fp)
            if e is None:
                return
            before = json.dumps(e, sort_keys=True)
            if address:
                if tailnet:
                    e["tailnet_ip"] = clean_addresses([address], 1)[0] if clean_addresses([address], 1) else e["tailnet_ip"]
                else:
                    e["lan"] = clean_addresses([address] + e["lan"])
            if clean_port(port):
                e["port"] = port
            if e["source"] == "paired":
                # the hub names roster peers; a paired peer names itself
                if name:
                    e["name"] = clean_name(name, e["name"])
                if isinstance(peer_id, str) and PEER_ID.match(peer_id):
                    e["id"] = peer_id
            if os_name:
                e["os"] = clean_name(os_name)[:20]
            if caps is not None:
                e["caps"] = clean_caps(caps)
            if json.dumps(e, sort_keys=True) != before:
                self._save()
