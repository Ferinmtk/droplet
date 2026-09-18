"""Named devices, per-device inboxes, and Web Push notifications."""

import base64
import hashlib
import json
import secrets
import shutil
import threading
import time
from pathlib import Path

ONLINE_WINDOW = 30  # seconds since a device's last poll that still counts as online
PUSH_TTL = 3 * 24 * 3600  # how long the push service holds a notification for a device that's off


class DeviceStore:
    """devices.json on disk, plus in-memory "last seen" times.

    A device is a browser that has named itself. It holds a random token in a
    long-lived cookie; only the token's hash is stored here. Each device gets
    an inbox directory for items sent to it.
    """

    def __init__(self, home: Path):
        self.file = home / "devices.json"
        self.inbox_root = home / "inbox"
        self._lock = threading.Lock()
        self._seen: dict[str, float] = {}
        try:
            self._devices: dict[str, dict] = json.loads(self.file.read_text())
        except FileNotFoundError:
            self._devices = {}

    def _save(self):
        # holds push endpoints and token hashes: owner-only, written atomically
        tmp = self.file.with_suffix(".tmp")
        tmp.write_text(json.dumps(self._devices, indent=1))
        tmp.chmod(0o600)
        tmp.replace(self.file)

    @staticmethod
    def _hash(token: str) -> str:
        return hashlib.sha256(token.encode()).hexdigest()

    def create(self, name: str, node: str | None = None) -> tuple[dict, str]:
        token = secrets.token_urlsafe(32)
        dev = {
            "id": secrets.token_hex(6),
            "name": name,
            "token": self._hash(token),
            "created": int(time.time()),
            "push": None,
            "node": node,  # tailnet machine name, when it registered over the tailnet
        }
        with self._lock:
            self._devices[dev["id"]] = dev
            self._save()
        self.inbox(dev["id"]).mkdir(parents=True, exist_ok=True)
        return dict(dev), token

    def by_token(self, token: str | None) -> dict | None:
        if not token:
            return None
        h = self._hash(token)
        with self._lock:
            for dev in self._devices.values():
                if secrets.compare_digest(dev["token"], h):
                    return dict(dev)
        return None

    def get(self, device_id: str) -> dict | None:
        with self._lock:
            dev = self._devices.get(device_id)
            return dict(dev) if dev else None

    def update(self, device_id: str, **fields):
        with self._lock:
            if device_id in self._devices:
                self._devices[device_id].update(fields)
                self._save()

    def remove(self, device_id: str):
        with self._lock:
            if self._devices.pop(device_id, None) is None:
                return
            self._save()
        self._seen.pop(device_id, None)
        shutil.rmtree(self.inbox(device_id), ignore_errors=True)

    def name_taken(self, name: str, except_id: str | None = None) -> bool:
        # names are how people pick a target, so two "slim"s would be ambiguous
        with self._lock:
            return any(d["name"].casefold() == name.casefold() and d["id"] != except_id
                       for d in self._devices.values())

    def nodes(self) -> set[str]:
        with self._lock:
            return {d["node"] for d in self._devices.values() if d.get("node")}

    def touch(self, device_id: str):
        self._seen[device_id] = time.time()

    def inbox(self, device_id: str) -> Path:
        # only ever called with ids that came out of the store (hex), never raw input
        return self.inbox_root / device_id

    def listing(self, self_id: str | None) -> list[dict]:
        now = time.time()
        with self._lock:
            devices = list(self._devices.values())
        out = [
            {
                "id": d["id"],
                "name": d["name"],
                "online": now - self._seen.get(d["id"], 0) < ONLINE_WINDOW,
                "push": bool(d.get("push")),
                "self": d["id"] == self_id,
            }
            for d in devices
        ]
        out.sort(key=lambda d: d["name"].lower())
        return out


class Pusher:
    """Sends Web Push notifications to a device's browser.

    The browser vendor's push service (Google for Chrome, Mozilla for Firefox)
    relays them, which is what lets a closed phone app still ring. Payloads are
    end-to-end encrypted to the browser, so the relay sees only that a message
    arrived. Files never go through it.
    """

    def __init__(self, home: Path, store: DeviceStore, enabled: bool):
        self.store = store
        self.key_file = home / ".vapid_private.pem"
        self.public_key = ""
        self.contact = "mailto:droplet@example.com"
        try:
            import pywebpush  # noqa: F401
        except ImportError:
            if enabled:
                print("  (pywebpush not installed — push notifications off)")
            enabled = False
        self.enabled = enabled
        if enabled:
            self._load_key()

    def _load_key(self):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec

        # persisted: browsers subscribe against this key, so changing it
        # would silently break every existing subscription
        if not self.key_file.exists():
            key = ec.generate_private_key(ec.SECP256R1())
            self.key_file.write_bytes(
                key.private_bytes(
                    serialization.Encoding.PEM,
                    serialization.PrivateFormat.PKCS8,
                    serialization.NoEncryption(),
                )
            )
            self.key_file.chmod(0o600)
        key = serialization.load_pem_private_key(self.key_file.read_bytes(), password=None)
        raw = key.public_key().public_bytes(
            serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint
        )
        self.public_key = base64.urlsafe_b64encode(raw).rstrip(b"=").decode()

    def send(self, device_id: str, payload: dict):
        if self.enabled:
            # the push service can take a second or two; don't hold up the upload
            threading.Thread(target=self._send, args=(device_id, payload), daemon=True).start()

    def _send(self, device_id: str, payload: dict):
        from pywebpush import WebPushException, webpush

        dev = self.store.get(device_id)
        if not dev or not dev.get("push"):
            return
        try:
            webpush(
                dev["push"],
                json.dumps(payload),
                vapid_private_key=str(self.key_file),
                vapid_claims={"sub": self.contact},
                ttl=PUSH_TTL,
                timeout=15,
            )
        except WebPushException as e:
            status = e.response.status_code if e.response is not None else None
            if status in (404, 410):
                # the browser dropped the subscription; it re-subscribes next visit
                self.store.update(device_id, push=None)
            print(f"  push to {dev['name']} failed: {status or e}")
        except Exception as e:  # network down, push service hiccup
            print(f"  push to {dev['name']} failed: {e}")


class Chats:
    """Text messages between pairs of devices: one append-only JSON-lines file per pair."""

    KEEP = 500  # messages returned per thread; older ones stay on disk

    def __init__(self, home: Path):
        self.root = home / "chats"
        self._lock = threading.Lock()

    def _file(self, a: str, b: str) -> Path:
        # ids are hex from DeviceStore, so they're safe in a filename
        x, y = sorted((a, b))
        return self.root / f"{x}__{y}.jsonl"

    @staticmethod
    def _read(path: Path) -> list[dict]:
        out = []
        try:
            with path.open(encoding="utf-8") as f:
                for line in f:
                    try:
                        out.append(json.loads(line))
                    except ValueError:
                        pass  # a torn last line after a crash
        except FileNotFoundError:
            pass
        return out

    def add(self, sender: dict, recipient: dict, text: str) -> dict:
        msg = {
            "id": secrets.token_hex(6),
            "from": sender["id"],
            "to": recipient["id"],
            "text": text,
            "ts": time.time(),
        }
        with self._lock:
            self.root.mkdir(parents=True, exist_ok=True)
            with self._file(sender["id"], recipient["id"]).open("a", encoding="utf-8") as f:
                f.write(json.dumps(msg) + "\n")
        return msg

    def thread(self, a: str, b: str) -> list[dict]:
        return self._read(self._file(a, b))[-self.KEEP:]

    def unread(self, me: str, read: dict[str, float]) -> dict[str, int]:
        """Messages to `me` newer than when each thread was last opened, per sender."""
        counts: dict[str, int] = {}
        if not self.root.is_dir():
            return counts
        for path in self.root.glob(f"*{me}*.jsonl"):
            for m in self._read(path):
                if m.get("to") == me and m.get("ts", 0) > read.get(m.get("from"), 0):
                    counts[m["from"]] = counts.get(m["from"], 0) + 1
        return counts

    def forget(self, device_id: str):
        with self._lock:
            for path in self.root.glob(f"*{device_id}*.jsonl"):
                path.unlink(missing_ok=True)
