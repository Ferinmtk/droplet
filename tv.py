"""TV remote: control an Android TV / Google TV from any droplet device.

The hub talks to the TV itself, over Google's Android TV Remote protocol v2
(the one the Google TV phone app uses), through the androidtvremote2 library:
TLS on port 6466 for commands, 6467 for pairing. Pairing makes the TV show a
6-character code; once it's typed into droplet, the TV trusts the hub's client
certificate for good.

The library is asyncio-based and Flask is threaded, so one asyncio loop runs in
a background thread and requests hand it work with run_coroutine_threadsafe,
always with a timeout. Every paired TV gets a connection that's kept open and
re-opened with backoff, so the page can show what the TV is doing (on/off, the
app in front, volume) and keys go out without a handshake first.

Nothing a client sends reaches the TV unchecked: keys come from a fixed
allowlist, apps from a fixed catalogue (or a plain https:// link), and the TV
to pair with must be on a private network.

Files, all under BASE_DIR/tv/ (git-ignored, owner-only):
  cert.pem, key.pem   the hub's client certificate; deleting it un-pairs every TV
  tvs.json            paired TVs: id, name, host, ports, MACs
"""

import asyncio
import atexit
import ipaddress
import json
import os
import re
import secrets
import socket
import threading
import time
from concurrent.futures import TimeoutError as FutureTimeout
from pathlib import Path
from urllib.parse import urlsplit

from flask import abort, jsonify, request

try:
    import androidtvremote2 as _atv
except ImportError:  # the rest of droplet works without it; the card says what's missing
    _atv = None

SERVICE = "_androidtvremote2._tcp.local."
API_PORT = 6466            # the pairing port is always the next one up
CLIENT_NAME = "droplet"    # what the TV calls the hub under "paired devices"

CALL_TIMEOUT = 10          # seconds a request waits on the TV before giving up
CONNECT_TIMEOUT = 8        # an unreachable host would otherwise hang for minutes
READY_WAIT = 4             # how long a key press waits for a dropped connection to come back
WAKE_WAIT = 8              # ...and a power-on after a Wake-on-LAN packet
PAIR_TTL = 300             # a code on screen goes stale; start again after this
LONG_PRESS = 0.9           # seconds a "long" press is held down
BACKOFF_MAX = 60
CONNECTING_GRACE = 12      # seconds a lost connection shows as "connecting" rather than "can't reach"

# name the page sends -> the library's key code (KEYCODE_ prefix dropped).
# MUTE is the speaker mute (KEYCODE_VOLUME_MUTE); Android's KEYCODE_MUTE is the
# microphone. DEL and ENTER back up the keyboard field: fix a typo, submit a search.
KEYS = {k: k for k in (
    "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER",
    "BACK", "HOME", "MENU", "POWER",
    "VOLUME_UP", "VOLUME_DOWN", "CHANNEL_UP", "CHANNEL_DOWN",
    "MEDIA_PLAY_PAUSE", "MEDIA_NEXT", "MEDIA_PREVIOUS", "MEDIA_REWIND",
    "MEDIA_FAST_FORWARD", "MEDIA_STOP",
    "SETTINGS", "INFO", "GUIDE", "SEARCH", "TV_INPUT", "DEL", "ENTER",
    *"0123456789",
)}
KEYS["MUTE"] = "VOLUME_MUTE"

# Apps by package name: the library turns a bare package into
# market://launch?id=<package>, which opens the app when it's installed and its
# Play Store page when it isn't. Sturdier than web links, which each app may or
# may not claim on a TV.
APPS = {
    "youtube": {"name": "YouTube", "package": "com.google.android.youtube.tv"},
    "netflix": {"name": "Netflix", "package": "com.netflix.ninja"},
    "prime": {"name": "Prime Video", "package": "com.amazon.amazonvideo.livingroom"},
    "spotify": {"name": "Spotify", "package": "com.spotify.tv.android"},
    "showmax": {"name": "Showmax", "package": "com.showmax.app"},
    "disney": {"name": "Disney+", "package": "com.disney.disneyplus"},
    "plex": {"name": "Plex", "package": "com.plexapp.android"},
    "home": {"name": "Google TV home", "key": "HOME"},
}
# friendlier names for what's on screen
APP_NAMES = {a["package"]: a["name"] for a in APPS.values() if "package" in a} | {
    "com.google.android.apps.tv.launcherx": "Home",
    "com.google.android.tvlauncher": "Home",
    "com.google.android.leanbacklauncher": "Home",
    "com.android.tv.settings": "Settings",
    "com.google.android.youtube.tvmusic": "YouTube Music",
    "com.google.android.videos": "Google TV",
    "com.android.vending": "Play Store",
    "com.google.android.katniss": "Assistant",
    "com.google.android.tv": "Live TV",
    "com.tcl.tv": "TV",
}

MAX_TEXT = 500
MAX_URL = 2048
HEX6 = re.compile(r"^[0-9A-F]{6}$")
HOSTNAME = re.compile(r"^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\.?$")
MAC = re.compile(r"^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$")
TAILNET = ipaddress.ip_network("100.64.0.0/10")


class TVError(Exception):
    """Something the page should show the user, with the HTTP status to send."""

    def __init__(self, message: str, status: int = 503):
        super().__init__(message)
        self.status = status


# --- validation (plain functions, so they're easy to test) -------------------

def norm_mac(value) -> str | None:
    s = str(value or "").strip().lower().replace("-", ":")
    if re.fullmatch(r"[0-9a-f]{12}", s):
        s = ":".join(s[i:i + 2] for i in range(0, 12, 2))
    return s if MAC.match(s) and s not in ("00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff") else None


def check_key(value) -> str:
    key = str(value or "").strip().upper()
    if key not in KEYS:
        raise TVError("That isn't a key droplet sends.", 400)
    return KEYS[key]


def check_code(value) -> str:
    code = re.sub(r"[\s-]", "", str(value or "")).upper()
    if not HEX6.match(code):
        raise TVError("The code is 6 characters: digits 0-9 and letters A-F.", 400)
    return code


def check_text(value) -> str:
    if not isinstance(value, str):
        raise TVError("Send some text.", 400)
    # control characters would just confuse the TV's text field
    text = "".join(ch for ch in value if ch.isprintable() or ch == " ")
    if not text:
        raise TVError("Send some text.", 400)
    if len(text) > MAX_TEXT:
        raise TVError(f"That's more than {MAX_TEXT} characters.", 400)
    return text


def check_url(value) -> str:
    """A web link the TV may open. Only https: other schemes (intent:, file:,
    content:, market:…) could point the TV at anything installed on it."""
    url = str(value or "").strip()
    if len(url) > MAX_URL or any(ch.isspace() or not ch.isprintable() for ch in url):
        raise TVError("That link isn't one droplet can open on the TV.", 400)
    try:
        parts = urlsplit(url)
        scheme, host, netloc = parts.scheme, parts.hostname, parts.netloc
    except ValueError:  # e.g. a malformed [IPv6] host
        scheme = host = netloc = None
    if scheme != "https" or not host or "@" in netloc:
        raise TVError("Only https:// links can be opened on the TV.", 400)
    return url


def check_app(value) -> tuple[str, str]:
    """('key', key) or ('link', package-or-url) for a catalogue id or an https link."""
    s = str(value or "").strip()
    app = APPS.get(s.lower())
    if app:
        return ("key", app["key"]) if "key" in app else ("link", app["package"])
    if s.lower().startswith("https://"):
        return "link", check_url(s)
    raise TVError("Pick an app from the list, or give an https:// link.", 400)


def check_host(value) -> str:
    """An address on this network: a private/loopback/link-local/tailnet IP, or a
    name that resolves to one. Keeps the hub from being pointed at the internet."""
    host = str(value or "").strip().strip("[]")
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        if not HOSTNAME.match(host):
            raise TVError("That doesn't look like an IP address.", 400) from None
        try:
            infos = socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
        except (OSError, UnicodeError):
            raise TVError(f"Can't find {host} on the network. Try its IP address.", 400) from None
        ips = [ipaddress.ip_address(i[4][0].split("%")[0]) for i in infos]
        if not ips or not all(_local(ip) for ip in ips):
            raise TVError("The TV has to be on your own network.", 400)
        return host.rstrip(".")
    if not _local(ip):
        raise TVError("The TV has to be on your own network.", 400)
    return str(ip)


def _local(ip) -> bool:
    return ip.is_private or ip.is_loopback or ip.is_link_local or (ip.version == 4 and ip in TAILNET)


def app_name(package: str | None) -> str | None:
    if not package:
        return None
    return APP_NAMES.get(package) or package


# --- Wake-on-LAN --------------------------------------------------------------

def magic_packet(mac: str) -> bytes:
    return b"\xff" * 6 + bytes.fromhex(mac.replace(":", "")) * 16


def send_wol(mac: str, host: str | None = None) -> bool:
    """Best effort: a TV in deep standby may answer it; many Google TVs keep the
    network up in standby anyway and never need it."""
    packet = magic_packet(mac)
    targets = {"255.255.255.255"}
    try:  # the directed broadcast of a /24 gets through more routers' Wi-Fi bridges
        ip = ipaddress.ip_address(host or "")
        if ip.version == 4 and ip.is_private:
            targets.add(str(ipaddress.ip_network(f"{ip}/24", strict=False).broadcast_address))
    except ValueError:
        pass
    sent = False
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        for target in targets:
            for port in (9, 7):
                try:
                    s.sendto(packet, (target, port))
                    sent = True
                except OSError:
                    pass
    return sent


def arp_mac(host: str) -> str | None:
    """The TV's LAN MAC from the hub's neighbour table, learnt while we can reach it.
    It's the one Wake-on-LAN needs; the MAC in the TV's certificate may not be."""
    try:
        for line in Path("/proc/net/arp").read_text().splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 4 and parts[0] == host:
                return norm_mac(parts[3])
    except OSError:
        pass
    return None


# --- state --------------------------------------------------------------------

class TV:
    """A paired TV and its live connection. Mutated on the loop thread only."""

    def __init__(self, rec: dict):
        self.id = rec["id"]
        self.name = rec.get("name") or "TV"
        self.host = rec["host"]
        self.api_port = int(rec.get("api_port") or API_PORT)
        self.mac = rec.get("mac")          # from the TV's certificate
        self.lan_mac = rec.get("lan_mac")  # from the hub's ARP table
        self.mdns = rec.get("mdns")        # service name, to follow the TV to a new IP
        self.added = rec.get("added") or int(time.time())
        self.paired = rec.get("paired", True)
        self.remote = None
        self.connected = False
        self.on: bool | None = None
        self.app: str | None = None
        self.volume: dict | None = None
        self.model: str | None = None
        self.error: str | None = None
        self.task: asyncio.Task | None = None
        self.kick: asyncio.Event | None = None     # wakes the connection loop early
        self.ready: asyncio.Event | None = None    # set while connected
        self.stopped = False
        self.down_at = time.time()   # when the connection was last lost (or first tried)
        self.long_tasks: set[asyncio.Task] = set()

    def record(self) -> dict:
        return {"id": self.id, "name": self.name, "host": self.host, "api_port": self.api_port,
                "mac": self.mac, "lan_mac": self.lan_mac, "mdns": self.mdns,
                "added": self.added, "paired": self.paired}

    def public(self) -> dict:
        return {
            "id": self.id,
            "name": self.name,
            "host": self.host,
            "paired": self.paired,
            "connected": self.connected,
            # just paired, or a dropped connection being retried: not worth an alarm yet
            "connecting": self.paired and not self.connected and not self.stopped
                          and time.time() - self.down_at < CONNECTING_GRACE,
            "on": self.on if self.connected else None,
            "app": self.app if self.connected else None,
            "app_name": app_name(self.app) if self.connected else None,
            "volume": dict(self.volume) if self.connected and self.volume else None,
            "model": self.model,
            "can_wake": bool(self.lan_mac or self.mac),
            "error": self.error,
        }


class TVManager:
    """Owns the asyncio loop, discovery, pairing and every TV's connection."""

    def __init__(self, directory: Path, lib=_atv, hub: str = "", discovery: bool = True,
                 client_name: str = CLIENT_NAME):
        self.dir = Path(directory)
        self.lib = lib
        self.hub = hub or socket.gethostname().split(".")[0]
        self.client_name = client_name
        self.discovery_on = discovery
        self.loop: asyncio.AbstractEventLoop | None = None
        self.thread: threading.Thread | None = None
        self.tvs: dict[str, TV] = {}
        self.discovered: dict[str, dict] = {}   # mDNS service name -> {name, host, port, mac}
        self.pairing: dict | None = None        # the one pairing in progress
        self._lock = threading.Lock()           # guards tvs / discovered / pairing for readers
        self._zc = None
        self._browser = None
        self.discovery_error: str | None = None

    # --- lifecycle ---

    @property
    def available(self) -> bool:
        return self.lib is not None

    @property
    def certfile(self) -> str:
        return str(self.dir / "cert.pem")

    @property
    def keyfile(self) -> str:
        return str(self.dir / "key.pem")

    def start(self):
        if self.thread:
            return
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self.loop.run_forever, name="droplet-tv", daemon=True)
        self.thread.start()
        for rec in self._load():
            try:
                tv = TV(rec)
            except (KeyError, TypeError, ValueError):
                continue
            self.tvs[tv.id] = tv
        self.call(self._boot(), timeout=5)

    def stop(self):
        if not self.loop:
            return
        try:
            self.call(self._shutdown(), timeout=4)
        except TVError:
            pass
        self.loop.call_soon_threadsafe(self.loop.stop)
        self.thread.join(timeout=2)
        if not self.thread.is_alive():
            self.loop.close()
        self.loop = self.thread = None

    async def _boot(self):
        if self.available:
            for tv in self.tvs.values():
                if tv.paired:
                    self._run(tv)
        if self.discovery_on:
            await self._start_discovery()

    async def _shutdown(self):
        await self._end_pairing()
        for tv in list(self.tvs.values()):
            await self._stop_tv(tv)
        if self._browser:
            await self._browser.async_cancel()
        if self._zc:
            await self._zc.async_close()

    def call(self, coro, timeout: float = CALL_TIMEOUT):
        """Run a coroutine on the TV loop from a Flask thread and wait for it."""
        if not self.loop:
            coro.close()
            raise TVError("The TV remote isn't running on the hub.")
        fut = asyncio.run_coroutine_threadsafe(coro, self.loop)
        try:
            return fut.result(timeout)
        except FutureTimeout:
            fut.cancel()
            raise TVError("The TV took too long to answer. Is it on?", 504) from None

    # --- storage ---

    def _load(self) -> list[dict]:
        try:
            data = json.loads((self.dir / "tvs.json").read_text())
            return [r for r in data.get("tvs", []) if isinstance(r, dict)]
        except (OSError, ValueError, AttributeError):
            return []

    def _save(self):
        self._ensure_dir()
        f = self.dir / "tvs.json"
        tmp = self.dir / "tvs.json.tmp"
        with self._lock:
            data = {"tvs": [tv.record() for tv in self.tvs.values()]}
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as out:
            json.dump(data, out, indent=2)
        os.replace(tmp, f)

    def _ensure_dir(self):
        self.dir.mkdir(parents=True, exist_ok=True)
        os.chmod(self.dir, 0o700)

    async def _ensure_cert(self, remote):
        self._ensure_dir()
        await remote.async_generate_cert_if_missing()
        for f in (self.certfile, self.keyfile):
            if os.path.exists(f):
                os.chmod(f, 0o600)

    def _remote(self, host: str, api_port: int = API_PORT):
        return self.lib.AndroidTVRemote(self.client_name, self.certfile, self.keyfile, host,
                                        api_port=api_port, pair_port=api_port + 1, loop=self.loop)

    # --- discovery (zeroconf, on the same loop) ---

    async def _start_discovery(self):
        try:
            from zeroconf import ServiceStateChange
            from zeroconf.asyncio import AsyncServiceBrowser, AsyncZeroconf
        except ImportError:
            self.discovery_error = "zeroconf isn't installed on the hub"
            return
        try:
            self._zc = AsyncZeroconf()
        except OSError as e:
            self.discovery_error = f"can't listen for mDNS: {e}"
            return

        def changed(zeroconf, service_type, name, state_change):
            if state_change is ServiceStateChange.Removed:
                with self._lock:
                    self.discovered.pop(name, None)
            else:
                asyncio.ensure_future(self._resolve(name))

        self._browser = AsyncServiceBrowser(self._zc.zeroconf, SERVICE, handlers=[changed])

    async def _resolve(self, name: str):
        from zeroconf.asyncio import AsyncServiceInfo
        info = AsyncServiceInfo(SERVICE, name)
        if not await info.async_request(self._zc.zeroconf, 3000):
            return
        addrs = info.parsed_addresses(version=_ipv4())
        if not addrs:
            return
        props = {(k or b"").decode(errors="replace"): (v or b"").decode(errors="replace")
                 for k, v in (info.properties or {}).items()}
        found = {
            "name": name.removesuffix("." + SERVICE),
            "host": addrs[0],
            "port": info.port or API_PORT,
            "mac": norm_mac(props.get("bt")),
            "seen": time.time(),
        }
        with self._lock:
            self.discovered[name] = found
        self._follow(name, found)

    def _follow(self, service: str, found: dict):
        """A paired TV turned up at a new address (DHCP moved it): follow it there."""
        for tv in self.tvs.values():
            same = (tv.mdns and tv.mdns == service) or (found["mac"] and found["mac"] in (tv.mac, tv.lan_mac))
            if not same:
                continue
            changed = tv.host != found["host"] or tv.api_port != found["port"] or tv.mdns != service
            if changed:
                tv.host, tv.api_port, tv.mdns = found["host"], found["port"], service
                if tv.remote is not None:
                    tv.remote.host = tv.host
                self._save()
                if tv.kick:
                    tv.kick.set()
            elif not tv.connected and tv.kick:
                tv.kick.set()  # it's announcing itself, so it's awake: try now
            return

    # --- the connection to each paired TV ---

    def _run(self, tv: TV):
        tv.stopped = False
        tv.down_at = time.time()
        tv.kick = asyncio.Event()
        tv.ready = asyncio.Event()
        tv.task = asyncio.ensure_future(self._connection(tv))

    async def _connection(self, tv: TV):
        """Connect, then let the library keep reconnecting; a kick (a key press
        while it's down, a new address) restarts the attempt without the wait."""
        lib = self.lib
        delay = 2
        while not tv.stopped:
            if tv.remote is None:
                tv.remote = self._remote(tv.host, tv.api_port)
                self._watch(tv)
            tv.remote.host = tv.host
            try:
                await asyncio.wait_for(tv.remote.async_connect(), CONNECT_TIMEOUT)
            except lib.InvalidAuth:
                self._lost_auth(tv)
                return
            except (lib.CannotConnect, lib.ConnectionClosed, asyncio.TimeoutError, OSError):
                tv.remote.disconnect()
                self._set_connected(tv, False)
                tv.error = "Can't reach the TV. Is it on?"
                tv.kick.clear()
                try:
                    await asyncio.wait_for(tv.kick.wait(), delay)
                except asyncio.TimeoutError:
                    pass
                delay = min(delay * 2, BACKOFF_MAX)
                continue
            delay = 2
            tv.error = None
            self._set_connected(tv, True)
            self._read_state(tv)
            lan = arp_mac(tv.host)
            if lan and lan != tv.lan_mac:
                tv.lan_mac = lan
                self._save()
            tv.remote.keep_reconnecting(lambda t=tv: self._lost_auth(t))
            # while the library looks after the connection, only a kick matters
            while not tv.stopped:
                tv.kick.clear()
                await tv.kick.wait()
                if tv.stopped or not tv.connected:
                    break
            if not tv.stopped:
                tv.remote.disconnect()  # stop the library's slow retry; ours starts now

    def _watch(self, tv: TV):
        r = tv.remote

        def is_on(v):
            tv.on = bool(v)

        def current_app(v):
            tv.app = v or None

        def volume(v):
            tv.volume = {"level": int(v["level"]), "max": int(v["max"]), "muted": bool(v["muted"])}

        def available(v):
            self._set_connected(tv, bool(v))
            if v:
                tv.error = None
                self._read_state(tv)

        r.add_is_on_updated_callback(is_on)
        r.add_current_app_updated_callback(current_app)
        r.add_volume_info_updated_callback(volume)
        r.add_is_available_updated_callback(available)

    def _read_state(self, tv: TV):
        r = tv.remote
        tv.on = r.is_on
        tv.app = r.current_app or None
        v = r.volume_info
        tv.volume = {"level": int(v["level"]), "max": int(v["max"]), "muted": bool(v["muted"])} if v else None
        info = r.device_info or {}
        model = " ".join(x for x in (info.get("manufacturer"), info.get("model")) if x)
        tv.model = model or tv.model

    def _set_connected(self, tv: TV, up: bool):
        if tv.connected and not up:
            tv.down_at = time.time()
        tv.connected = up
        if tv.ready:
            (tv.ready.set if up else tv.ready.clear)()

    def _lost_auth(self, tv: TV):
        """The TV no longer trusts the hub (reset, or droplet removed on the TV)."""
        if tv.remote:
            tv.remote.disconnect()
        self._set_connected(tv, False)
        tv.paired = False
        tv.stopped = True
        tv.error = "The TV forgot droplet. Pair it again."
        if tv.kick:
            tv.kick.set()  # lets the connection loop finish
        self._save()

    async def _stop_tv(self, tv: TV):
        tv.stopped = True
        if tv.kick:
            tv.kick.set()
        for t in list(tv.long_tasks):
            t.cancel()
        if tv.remote:
            tv.remote.disconnect()
        if tv.task and not tv.task.done():
            tv.task.cancel()
            try:
                await tv.task
            except (asyncio.CancelledError, Exception):
                pass
        self._set_connected(tv, False)

    async def _ready(self, tv: TV, wait: float | None = None) -> bool:
        if tv.connected:
            return True
        if tv.stopped or not tv.paired:
            return False
        tv.kick.set()
        try:
            await asyncio.wait_for(tv.ready.wait(), READY_WAIT if wait is None else wait)
        except asyncio.TimeoutError:
            return False
        return tv.connected

    # --- pairing ---

    async def _start_pairing(self, host: str) -> dict:
        lib = self.lib
        await self._end_pairing()
        with self._lock:
            hint = next((d for d in self.discovered.values() if d["host"] == host), None)
        api_port = hint["port"] if hint else API_PORT
        remote = self._remote(host, api_port)
        await self._ensure_cert(remote)
        unreachable = TVError(f"Can't reach a TV at {host}. Turn it on, and make sure it's on "
                              f"the same network as {self.hub}.", 502)
        try:
            name, mac = await asyncio.wait_for(remote.async_get_name_and_mac(), CONNECT_TIMEOUT)
        except (lib.CannotConnect, lib.ConnectionClosed, asyncio.TimeoutError, OSError):
            remote.disconnect()
            raise unreachable from None
        try:
            await asyncio.wait_for(remote.async_start_pairing(), CONNECT_TIMEOUT + 4)
        except (lib.CannotConnect, lib.ConnectionClosed, asyncio.TimeoutError, OSError):
            remote.disconnect()
            raise TVError("The TV didn't start pairing. Wake it up (press a button on its remote) "
                          "and try again.", 502) from None
        session = {
            "id": secrets.token_hex(6),
            "host": host,
            "api_port": api_port,
            "name": (hint and hint["name"]) or name or "TV",
            "mac": norm_mac(mac) or (hint and hint["mac"]),
            "mdns": hint and (hint["name"] + "." + SERVICE),
            "remote": remote,
            "started": time.time(),
            "tries": 0,
        }
        with self._lock:
            self.pairing = session
        return self._pairing_public()

    async def _finish_pairing(self, code: str) -> dict:
        lib = self.lib
        p = self.pairing
        if not p or time.time() - p["started"] > PAIR_TTL:
            await self._end_pairing()
            raise TVError("Pairing has timed out. Start again, and a new code shows on the TV.", 409)
        try:
            await asyncio.wait_for(p["remote"].async_finish_pairing(code), CONNECT_TIMEOUT + 4)
        except lib.InvalidAuth:
            # checked against the TV's certificate before anything is sent, so the
            # TV's screen stays up and the user can simply try again
            p["tries"] += 1
            if p["tries"] >= 5:
                await self._end_pairing()
                raise TVError("That code didn't match 5 times. Start again for a new code.", 409) from None
            raise TVError("That code doesn't match. Check the TV and type it again.", 400) from None
        except (lib.ConnectionClosed, lib.CannotConnect, asyncio.TimeoutError, OSError):
            await self._end_pairing()
            # the TV turned the code down, or someone pressed Cancel on it
            raise TVError("The TV didn't accept that code, or its pairing screen closed. "
                          "Start again for a new code.", 409) from None
        await self._end_pairing()
        tv = self._adopt(p)
        return tv.public()

    def _adopt(self, p: dict) -> TV:
        """Save a freshly paired TV (or refresh one paired before) and connect to it."""
        existing = next((t for t in self.tvs.values()
                         if (p["mac"] and p["mac"] in (t.mac, t.lan_mac)) or t.host == p["host"]), None)
        if existing:
            existing.task and existing.task.cancel()
            if existing.remote:
                existing.remote.disconnect()
            existing.remote = None
            existing.host, existing.api_port, existing.name = p["host"], p["api_port"], p["name"]
            existing.mac = p["mac"] or existing.mac
            existing.mdns = p["mdns"] or existing.mdns
            existing.paired, existing.error = True, None
            tv = existing
        else:
            tv = TV({"id": secrets.token_hex(4), "name": p["name"], "host": p["host"], "api_port": p["api_port"],
                     "mac": p["mac"], "mdns": p["mdns"]})
            with self._lock:
                self.tvs[tv.id] = tv
        tv.lan_mac = arp_mac(tv.host) or tv.lan_mac
        self._save()
        self._run(tv)
        return tv

    async def _end_pairing(self):
        with self._lock:
            p, self.pairing = self.pairing, None
        if p:
            p["remote"].disconnect()  # closes the code screen on the TV

    def _pairing_public(self) -> dict | None:
        p = self.pairing
        if not p:
            return None
        return {"id": p["id"], "host": p["host"], "name": p["name"],
                "expires_in": max(0, int(PAIR_TTL - (time.time() - p["started"])))}

    # --- commands ---

    def _get(self, tv_id: str) -> TV:
        tv = self.tvs.get(tv_id)
        if tv is None:
            raise TVError("No TV with that id.", 404)
        return tv

    async def _connected(self, tv_id: str, wait: float | None = None) -> TV:
        tv = self._get(tv_id)
        if not tv.paired:
            raise TVError("The TV needs pairing again.", 409)
        if not await self._ready(tv, wait):
            raise TVError(f"Can't reach {tv.name}. Is it on?", 503)
        return tv

    async def _key(self, tv_id: str, key: str, long: bool) -> dict:
        tv = await self._connected(tv_id)
        try:
            if not long:
                tv.remote.send_key_command(key)
            else:
                tv.remote.send_key_command(key, "START_LONG")
                task = asyncio.ensure_future(self._release(tv, key))
                tv.long_tasks.add(task)
                task.add_done_callback(tv.long_tasks.discard)
        except self.lib.ConnectionClosed:
            self._set_connected(tv, False)
            raise TVError(f"Lost the connection to {tv.name}. Try again.", 503) from None
        return {"ok": True}

    async def _release(self, tv: TV, key: str):
        await asyncio.sleep(LONG_PRESS)
        try:
            tv.remote.send_key_command(key, "END_LONG")
        except Exception:  # the connection dropped mid-press; the TV lets go by itself
            pass

    async def _text(self, tv_id: str, text: str) -> dict:
        tv = await self._connected(tv_id)
        try:
            tv.remote.send_text(text)
        except self.lib.ConnectionClosed:
            self._set_connected(tv, False)
            raise TVError(f"Lost the connection to {tv.name}. Try again.", 503) from None
        return {"ok": True}

    async def _launch(self, tv_id: str, kind: str, target: str) -> dict:
        tv = await self._connected(tv_id)
        try:
            if kind == "key":
                tv.remote.send_key_command(target)
            else:
                tv.remote.send_launch_app_command(target)
        except self.lib.ConnectionClosed:
            self._set_connected(tv, False)
            raise TVError(f"Lost the connection to {tv.name}. Try again.", 503) from None
        return {"ok": True}

    async def _power(self, tv_id: str, state: str) -> dict:
        tv = self._get(tv_id)
        if not tv.paired:
            raise TVError("The TV needs pairing again.", 409)
        woke = False
        if not await self._ready(tv, 1.5):
            if state == "off":
                return {"ok": True, "on": False, "connected": False}
            mac = tv.lan_mac or tv.mac
            if mac:
                woke = send_wol(mac, tv.host)
            if not await self._ready(tv, WAKE_WAIT):
                if woke:
                    return {"ok": True, "woke": True, "connected": False}
                raise TVError(f"Can't reach {tv.name}. It may be fully off: turn it on with its own "
                              "remote once, and turn on network standby in its settings.", 503)
            # it came up: a TV that woke from deep standby is already on
            if tv.on:
                return {"ok": True, "woke": woke, "on": True, "connected": True}
        want = {"on": True, "off": False}.get(state)
        if want is None or tv.on is None or tv.on != want:
            try:
                tv.remote.send_key_command("POWER")
            except self.lib.ConnectionClosed:
                self._set_connected(tv, False)
                raise TVError(f"Lost the connection to {tv.name}. Try again.", 503) from None
        return {"ok": True, "woke": woke, "connected": True}

    async def _forget(self, tv_id: str) -> dict:
        tv = self._get(tv_id)
        await self._stop_tv(tv)
        with self._lock:
            self.tvs.pop(tv.id, None)
        self._save()
        return {"ok": True, "removed": tv.id}

    async def _retry(self, tv_id: str) -> dict:
        """Poke a dropped connection now rather than at the next backoff step."""
        tv = self._get(tv_id)
        if tv.paired and not tv.stopped and tv.kick:
            tv.kick.set()
        return {"ok": True}

    # --- the page's view ---

    def snapshot(self) -> dict:
        with self._lock:
            tvs = list(self.tvs.values())
            found = list(self.discovered.values())
            pairing = self._pairing_public()
        paired_macs = {m for t in tvs for m in (t.mac, t.lan_mac) if m}
        paired_hosts = {t.host for t in tvs}
        return {
            "available": self.available,
            "error": None if self.available else
                     "The TV library isn't installed on the hub. Run: pip install -r requirements.txt",
            "hub": self.hub,
            "discovery": self.discovery_error is None and self.discovery_on,
            "discovered": sorted(({
                "name": d["name"], "host": d["host"],
                "paired": bool((d["mac"] and d["mac"] in paired_macs) or d["host"] in paired_hosts),
            } for d in found), key=lambda d: d["name"].lower()),
            "pairing": pairing,
            "tvs": sorted((t.public() for t in tvs), key=lambda t: t["name"].lower()),
            "apps": [{"id": k, "name": v["name"], "package": v.get("package")} for k, v in APPS.items()],
        }

    # --- thread-safe entry points for Flask ---

    def _need_lib(self):
        if not self.available:
            raise TVError("The TV library isn't installed on the hub. Run: pip install -r requirements.txt")

    def pair_start(self, host: str) -> dict:
        self._need_lib()
        return self.call(self._start_pairing(check_host(host)), timeout=CONNECT_TIMEOUT * 2 + 6)

    def pair_finish(self, code: str) -> dict:
        self._need_lib()
        return self.call(self._finish_pairing(check_code(code)), timeout=CONNECT_TIMEOUT + 8)

    def pair_cancel(self) -> dict:
        self.call(self._end_pairing())
        return {"ok": True}

    def key(self, tv_id: str, key: str, long: bool = False) -> dict:
        self._need_lib()
        return self.call(self._key(tv_id, check_key(key), long))

    def text(self, tv_id: str, text: str) -> dict:
        self._need_lib()
        return self.call(self._text(tv_id, check_text(text)))

    def launch(self, tv_id: str, app: str) -> dict:
        self._need_lib()
        kind, target = check_app(app)
        return self.call(self._launch(tv_id, kind, target))

    def power(self, tv_id: str, state: str = "toggle") -> dict:
        self._need_lib()
        if state not in ("toggle", "on", "off"):
            raise TVError("Power is on, off or toggle.", 400)
        return self.call(self._power(tv_id, state), timeout=WAKE_WAIT + 6)

    def forget(self, tv_id: str) -> dict:
        return self.call(self._forget(tv_id))

    def retry(self, tv_id: str) -> dict:
        return self.call(self._retry(tv_id))


def _ipv4():
    from zeroconf import IPVersion
    return IPVersion.V4Only


# --- routes -----------------------------------------------------------------

def register(ctx, manager: TVManager | None = None):
    app = ctx.app
    if manager is None:
        if os.environ.get("DROPLET_TV", "1") in ("0", "false"):
            return
        manager = TVManager(ctx.base_dir / "tv", hub=socket.gethostname().split(".")[0])
        manager.start()
        atexit.register(manager.stop)
    app.extensions["droplet_tv"] = manager

    def body() -> dict:
        data = request.get_json(silent=True)
        if not isinstance(data, dict):
            abort(400)
        return data

    def answer(fn, *args):
        try:
            return jsonify(fn(*args))
        except TVError as e:
            return jsonify({"ok": False, "error": str(e)}), e.status

    @app.route("/api/tv")
    def api_tv():
        return jsonify(manager.snapshot())

    @app.route("/api/tv/pair/start", methods=["POST"])
    def api_tv_pair_start():
        return answer(manager.pair_start, body().get("host"))

    @app.route("/api/tv/pair/finish", methods=["POST"])
    def api_tv_pair_finish():
        return answer(manager.pair_finish, body().get("code"))

    @app.route("/api/tv/pair/cancel", methods=["POST"])
    def api_tv_pair_cancel():
        return answer(manager.pair_cancel)

    @app.route("/api/tv/<tv_id>/key", methods=["POST"])
    def api_tv_key(tv_id):
        b = body()
        action = str(b.get("action") or "short")
        if action not in ("short", "long"):
            abort(400)
        return answer(manager.key, tv_id, b.get("key"), action == "long")

    @app.route("/api/tv/<tv_id>/text", methods=["POST"])
    def api_tv_text(tv_id):
        return answer(manager.text, tv_id, body().get("text"))

    @app.route("/api/tv/<tv_id>/launch", methods=["POST"])
    def api_tv_launch(tv_id):
        return answer(manager.launch, tv_id, body().get("app"))

    @app.route("/api/tv/<tv_id>/power", methods=["POST"])
    def api_tv_power(tv_id):
        data = request.get_json(silent=True)
        if data is None:
            data = {}  # a bare POST toggles
        if not isinstance(data, dict):
            abort(400)
        return answer(manager.power, tv_id, str(data.get("state") or "toggle"))

    @app.route("/api/tv/<tv_id>/retry", methods=["POST"])
    def api_tv_retry(tv_id):
        return answer(manager.retry, tv_id)

    @app.route("/api/tv/<tv_id>", methods=["DELETE"])
    def api_tv_forget(tv_id):
        return answer(manager.forget, tv_id)
