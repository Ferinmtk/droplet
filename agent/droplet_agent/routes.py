"""Choosing how to reach the hub, local first (docs/local-first.md §3).

In order, first that works:

1. **hub-local**: the agent runs on the hub machine itself (the T15). Plain
   HTTP to 127.0.0.1 on the hub's port: the fastest, needs no Tailscale, and
   the hub trusts loopback. Used only when the hub there says it has the
   paired hub's id.
2. **LAN**: HTTPS to the hub's LAN address with its certificate pinned.
   Addresses come from mDNS (an announcement with the paired hub's id), then
   from the addresses that worked before. An address is only a hint: the
   pinned certificate is what makes it the hub. About 1.5 s.
3. **tailnet**: the hub's tailnet URL, with ordinary verified TLS.
4. **direct**: the URL setup was given, when it's none of the above (say, an
   https hub with a real certificate somewhere else).

A LAN address whose certificate isn't the pinned one is never used. If no
other route works, that's a clear error telling you to re-pair.
"""

from __future__ import annotations

import ipaddress
import json
import logging
import threading
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlsplit

from . import config, discovery, hub
from .hub import HubError, PinMismatch, Route

log = logging.getLogger("droplet_agent.routes")

LAN_TIMEOUT = 1.5      # seconds for one LAN attempt, and for the mDNS search
LOCAL_TIMEOUT = 1.0    # loopback answers at once or not at all
REMOTE_TIMEOUT = 8     # the tailnet may have to wake up
MAX_LAN_HINTS = 4
DEFAULT_HTTP_PORT = 8000


def _host(url: str) -> str:
    return urlsplit(url).hostname or ""


def is_ip(host: str) -> bool:
    try:
        ipaddress.ip_address(host)
        return True
    except ValueError:
        return False


def dedupe(items) -> list:
    return list(dict.fromkeys(i for i in items if i))


def identity_from(info: dict, route: Route | None = None) -> dict:
    """A fresh stored identity from /api/hub/info (and the route it came over)."""
    ident = json.loads(json.dumps(config.IDENTITY_DEFAULTS))
    ident.update(id=info["id"], name=info.get("name") or "", fingerprint=info.get("fingerprint") or "",
                 https_port=info.get("https_port"), http_port=info.get("http_port"),
                 tailnet=info.get("tailnet") or "")
    first = [_host(route.url)] if route is not None and route.kind == "lan" else []
    ident["lan"] = dedupe(first + list(info.get("addresses") or []))[:MAX_LAN_HINTS]
    return ident


# --- first contact: setup, and configs from before local-first ------------------

def establish(url: str, get_info=hub.info) -> tuple[Route, dict, str]:
    """Reach the hub at `url` and learn who it is: (route to use, its info, how it's trusted).

    How it's trusted:
    - "verified": https with a valid certificate (the tailnet), or loopback.
    - "first-use": a plain http:// LAN address. /api/hub/info is read over
      it, then the agent switches to the hub's LAN HTTPS with that
      fingerprint pinned, before sending anything that matters. Only the
      pairing approval (the owner comparing the code) confirms it's your hub.
    - "plain": a LAN address of a hub with no LAN HTTPS; the token then goes
      unencrypted. Only when the hub offers nothing better.
    """
    url = hub.normalize(url)
    parts = urlsplit(url)
    if parts.scheme == "https":
        route = Route("direct", url)
        info = get_info(route, timeout=REMOTE_TIMEOUT)
        if info["tailnet"] == url:
            route = Route("tailnet", url)
        return route, info, "verified"
    if hub.is_loopback(parts.hostname):
        route = Route("hub-local", url)
        return route, get_info(route, timeout=REMOTE_TIMEOUT), "verified"
    plain = Route("direct", url)
    first = get_info(plain, timeout=REMOTE_TIMEOUT)
    if not first["fingerprint"] or not first["https_port"]:
        return plain, first, "plain"
    lan = Route("lan", hub.host_url("https", parts.hostname, first["https_port"]), first["fingerprint"])
    info = get_info(lan, timeout=REMOTE_TIMEOUT)
    if info["id"] != first["id"] or info["fingerprint"] != first["fingerprint"]:
        raise HubError(f"{url} and {lan.url} don't agree on who the hub is; not pairing with it")
    return lan, info, "first-use"


def establish_found(found: discovery.Found, get_info=hub.info) -> tuple[Route, dict, str]:
    """Reach a hub found over mDNS, pinning the fingerprint it announced."""
    last: HubError | None = None
    for addr in found.addresses:
        route = Route("lan", hub.host_url("https", addr, found.https_port), found.fingerprint)
        try:
            info = get_info(route, timeout=REMOTE_TIMEOUT)
        except PinMismatch as e:
            raise HubError(f"{found.name} announced one certificate but presented another at {addr}; "
                           "not pairing with it") from e
        except HubError as e:
            last = e
            continue
        if info["id"] != found.id:
            last = HubError(f"{addr} isn't the hub that announced itself there")
            continue
        return route, info, "first-use"
    raise last or HubError(f"{found.name} announced no usable address")


# --- the route manager ----------------------------------------------------------

class Router:
    """Picks the best route to the paired hub, and keeps its identity up to date."""

    def __init__(self, cfg: dict, *, persist: bool = True, discover=discovery.browse,
                 local_addresses=discovery.local_addresses, get_info=hub.info,
                 lan_timeout: float = LAN_TIMEOUT):
        self.cfg = cfg
        self.persist = persist
        self.discover = discover
        self.local_addresses = local_addresses
        self.get_info = get_info
        self.lan_timeout = lan_timeout
        self.route: Route | None = None
        self.warning: str | None = None     # a problem worth showing, even though a route works
        self.found: list[discovery.Found] = []  # the paired hub, as last seen over mDNS
        self._lock = threading.RLock()

    @property
    def identity(self) -> dict:
        return self.cfg.setdefault("hub_identity", json.loads(json.dumps(config.IDENTITY_DEFAULTS)))

    # --- probing ------------------------------------------------------------

    def _check(self, route: Route, timeout: float) -> dict:
        """/api/hub/info over `route`, which must be the paired hub."""
        info = self.get_info(route, timeout=timeout)
        want = self.identity.get("id")
        if want and info["id"] != want:
            raise HubError(f"{route.url} is a different droplet hub ({info.get('name') or info['id']})")
        return info

    def _try(self, route: Route, timeout: float, mismatches: list | None = None):
        try:
            return route, self._check(route, timeout)
        except PinMismatch as e:
            log.warning("%s", e)
            if mismatches is not None:
                mismatches.append(e)
        except HubError as e:
            log.debug("%s: %s", route.describe(), e)
        return None

    def _local_route(self, port) -> Route:
        return Route("hub-local", f"http://127.0.0.1:{int(port)}")

    def on_hub_machine(self, found=None) -> bool:
        """Whether the hub, as announced over mDNS, has one of this machine's addresses."""
        mine = self.local_addresses()
        return any(a in mine for f in (found if found is not None else self.found) for a in f.addresses)

    def _discover(self) -> list:
        want = self.identity.get("id")
        try:
            return [f for f in self.discover(timeout=self.lan_timeout, want_id=want) if f.id == want]
        except Exception as e:  # discovery is only ever a hint
            log.debug("mDNS search failed: %s", e)
            return []

    def _near(self, mismatches: list):
        """hub-local or LAN: (route, info), or None.

        Loopback first, then mDNS and the remembered LAN addresses side by side.
        """
        ident = self.identity
        if not ident.get("id"):
            return None  # without the hub's id, nothing nearby can be told apart from another hub
        pin = ident.get("fingerprint") or None
        port = ident.get("http_port") or DEFAULT_HTTP_PORT
        # loopback first: it answers (or refuses) at once, and on the hub
        # machine nothing else needs trying
        got = self._try(self._local_route(port), LOCAL_TIMEOUT)
        if got:
            return got
        pool = ThreadPoolExecutor(max_workers=8, thread_name_prefix="route")
        try:
            disc = pool.submit(self._discover)
            lan: dict = {}

            def probe(addr, https_port):
                key = (addr, https_port)
                if key not in lan:
                    route = Route("lan", hub.host_url("https", addr, https_port), pin)
                    lan[key] = pool.submit(self._try, route, self.lan_timeout, mismatches)
                return lan[key]

            hints = list(ident.get("lan") or [])
            if pin and ident.get("https_port"):
                for addr in hints:
                    probe(addr, ident["https_port"])

            found = disc.result()
            self.found = found
            # the hub announces itself at one of our addresses, but not on the
            # port we knew: try loopback on the port it announced
            if self.on_hub_machine(found):
                for f in found:
                    if f.http_port and f.http_port != port:
                        got = self._try(self._local_route(f.http_port), LOCAL_TIMEOUT)
                        if got:
                            return got
            if not pin:
                return None
            order = [(a, f.https_port) for f in found for a in f.addresses]
            order += [(a, ident["https_port"]) for a in hints if ident.get("https_port")]
            for addr, https_port in dedupe(order):
                got = probe(addr, https_port).result()
                if got:
                    return got
            return None
        finally:
            pool.shutdown(wait=False, cancel_futures=True)

    def _remote_routes(self) -> list[Route]:
        ident = self.identity
        out = []
        if ident.get("tailnet"):
            out.append(Route("tailnet", ident["tailnet"]))
        try:
            url = hub.normalize(self.cfg.get("hub") or "")
        except HubError:
            return out
        parts = urlsplit(url)
        if url == ident.get("tailnet"):
            pass
        elif parts.scheme == "https":
            # an https IP address is the LAN listener, reached above with the pin;
            # normal verification of its self-signed certificate would only fail
            if not is_ip(parts.hostname or ""):
                out.append(Route("direct", url))
        elif hub.is_loopback(parts.hostname):
            out.append(Route("hub-local", url))
        elif not ident.get("fingerprint"):
            # a hub without LAN HTTPS, reached as before; the token is sent unencrypted
            out.append(Route("direct", url))
        return out

    # --- the public side ------------------------------------------------------

    def select(self) -> Route:
        """The best working route right now. Raises HubError (PinMismatch included) if none works."""
        with self._lock:
            self.warning = None
            if not self.identity.get("id"):
                legacy = self._migrate()
                if legacy is not None:
                    return legacy
            mismatches: list = []
            got = self._near(mismatches)
            errors = []
            if got is None:
                for route in self._remote_routes():
                    try:
                        got = route, self._check(route, REMOTE_TIMEOUT)
                        break
                    except PinMismatch as e:
                        mismatches.append(e)
                    except HubError as e:
                        errors.append(f"{route.describe()}: {e}")
            if got is None:
                if mismatches:
                    raise mismatches[0]
                name = self.identity.get("name") or "the hub"
                where = "not found on this network"
                if not self.identity.get("fingerprint"):
                    where = "it has no LAN address to use"
                raise HubError(f"can't reach {name}: {where}"
                               + (f"; {'; '.join(errors)}" if errors else "; and it has no tailnet URL"))
            route, info = got
            if mismatches and route.kind != "lan":
                self.warning = str(mismatches[0])
            self._learn(route, info)
            self.route = route
            return route

    def nearer(self, current: Route) -> Route | None:
        """A hub-local or LAN route, when `current` is the tailnet (or direct) and one works now."""
        if current.kind in ("hub-local", "lan"):
            return None
        with self._lock:
            got = self._near([])
            if got is None:
                return None
            self._learn(*got)
            return got[0]

    def still_works(self, route: Route) -> bool:
        return self._try(route, self.lan_timeout * 2) is not None

    # --- learning ---------------------------------------------------------------

    def _migrate(self) -> Route | None:
        """A config from before local-first has only a URL: ask that hub who it is.

        Returns None when it worked (routing goes on as normal), or the
        configured URL to use as before when the hub couldn't say.
        """
        url = self.cfg.get("hub") or ""
        try:
            route, info, how = establish(url, self.get_info)
        except PinMismatch:
            raise
        except HubError as e:
            self.warning = f"couldn't read the hub's identity yet ({e}); using {url} as configured"
            log.warning("%s", self.warning)
            return Route("direct", hub.normalize(url))
        ident = identity_from(info, route)
        log.info("the hub is %s (id %s); pinned its LAN certificate %s (%s)", ident["name"] or "?",
                 ident["id"], ident["fingerprint"] or "(it has none)", how)
        self._store(ident)
        return None

    def _learn(self, route: Route, info: dict):
        """Keep the stored identity current with what a verified route says."""
        ident = self.identity
        new = dict(ident)
        # verified: loopback, the pin, or a CA-checked certificate. A plain
        # http LAN answer could come from anyone on the Wi-Fi.
        verified = route.kind == "hub-local" or route.url.startswith("https://")
        if not verified:
            return
        new["name"] = info.get("name") or ident.get("name") or ""
        new["http_port"] = info.get("http_port") or ident.get("http_port")
        if info.get("tailnet"):
            new["tailnet"] = info["tailnet"]
        fp = info.get("fingerprint")
        if fp and fp == ident.get("fingerprint"):
            new["https_port"] = info.get("https_port") or ident.get("https_port")
        elif fp and not ident.get("fingerprint"):
            # the hub turned on LAN HTTPS since: pinning it from a verified route
            new["fingerprint"], new["https_port"] = fp, info.get("https_port")
            log.info("pinned the hub's LAN certificate %s", fp)
        elif fp:
            self.warning = (f"the hub's identity changed: it now presents LAN certificate {fp}, not the "
                            f"pinned {ident['fingerprint']}. The LAN route stays off until you re-pair "
                            "with: droplet-agent setup")
            log.error("%s", self.warning)
        first = [_host(route.url)] if route.kind == "lan" else []
        new["lan"] = dedupe(first + list(info.get("addresses") or []) + list(ident.get("lan") or []))[:MAX_LAN_HINTS]
        if new != ident:
            self._store(new)

    def _store(self, ident: dict):
        self.cfg["hub_identity"] = ident
        if self.persist:
            try:
                config.save_identity(ident)
            except OSError as e:
                log.warning("couldn't save what the agent learnt about the hub: %s", e)
