"""Finding droplet hubs on the LAN over mDNS (DNS-SD), like KDE Connect does.

The hub announces `_droplet._tcp.local.` on its LAN HTTPS port, with TXT
records (docs/local-first.md §1):

    id    the hub's permanent id
    fp    SHA-256 of its LAN certificate (DER), lowercase hex
    name  the hub machine's name
    http  its plain-HTTP port
    ts    its tailnet URL, or empty

An announcement proves nothing by itself: anyone on the Wi-Fi can send one.
The agent only uses it to find an address, then connects with the pinned
fingerprint it already trusts (or, on first contact, the one in `fp`, which
the pairing approval then confirms).
"""

from __future__ import annotations

import ipaddress
import logging
import queue
import re
import socket
import time
from dataclasses import dataclass, field

from .pinning import normalize_fingerprint

log = logging.getLogger("droplet_agent.discovery")

SERVICE = "_droplet._tcp.local."
HUB_ID = re.compile(r"^[0-9a-f]{8,64}$")


@dataclass
class Found:
    """A hub that answered on the LAN."""

    id: str
    fingerprint: str
    name: str
    https_port: int
    http_port: int | None
    tailnet: str
    addresses: list[str] = field(default_factory=list)
    service: str = ""


def _text(props: dict, key: str) -> str | None:
    v = props.get(key.encode(), props.get(key))
    if v is None:
        return None
    if isinstance(v, bytes):
        try:
            v = v.decode("utf-8")
        except UnicodeDecodeError:
            return None
    return str(v)


def parse_txt(props: dict) -> dict | None:
    """The hub's TXT records → {"id","fingerprint","name","http_port","tailnet"}.

    zeroconf hands them over as bytes (a key without a value maps to None).
    Returns None when the id or fingerprint is missing or malformed: that
    isn't a droplet hub the agent could trust.
    """
    hub_id = (_text(props, "id") or "").strip().lower()
    fp = normalize_fingerprint(_text(props, "fp"))
    if not HUB_ID.match(hub_id) or fp is None:
        return None
    http = _text(props, "http") or ""
    http_port = int(http) if http.isdigit() and 0 < int(http) < 65536 else None
    ts = (_text(props, "ts") or "").strip()
    if not re.fullmatch(r"https://[A-Za-z0-9.\-]+(:\d{1,5})?/?", ts):
        ts = ""
    name = " ".join((_text(props, "name") or "").split())[:64]
    return {"id": hub_id, "fingerprint": fp, "name": name, "http_port": http_port, "tailnet": ts.rstrip("/")}


def usable_addresses(addresses) -> list[str]:
    """IPv4 first, then routable IPv6. Link-local IPv6 needs an interface to go with it; skipped."""
    v4, v6 = [], []
    for a in addresses:
        try:
            ip = ipaddress.ip_address(str(a).split("%")[0])
        except ValueError:
            continue
        if ip.is_unspecified or ip.is_multicast:
            continue
        if ip.version == 4:
            v4.append(str(ip))
        elif not ip.is_link_local:
            v6.append(str(ip))
    return list(dict.fromkeys(v4 + v6))


def from_service(name: str, port: int | None, props: dict, addresses) -> Found | None:
    txt = parse_txt(props or {})
    addrs = usable_addresses(addresses or [])
    if txt is None or not addrs or not port:
        return None
    return Found(id=txt["id"], fingerprint=txt["fingerprint"], name=txt["name"] or name.split(".")[0],
                 https_port=int(port), http_port=txt["http_port"], tailnet=txt["tailnet"],
                 addresses=addrs, service=name)


def browse(timeout: float = 1.5, want_id: str | None = None) -> list[Found]:
    """Hubs announcing themselves on the LAN, gathered for up to `timeout` seconds.

    With `want_id`, returns as soon as that hub has answered (only it).
    Never raises: no zeroconf, no network or a busy port just means nothing
    was found.
    """
    try:
        from zeroconf import ServiceBrowser, ServiceInfo, ServiceStateChange, Zeroconf
    except ImportError:
        log.warning("zeroconf isn't installed, so the agent can't look for the hub on the LAN")
        return []
    deadline = time.monotonic() + timeout
    names: queue.Queue = queue.Queue()

    def on_change(zeroconf, service_type, name, state_change):
        if state_change in (ServiceStateChange.Added, ServiceStateChange.Updated):
            names.put(name)

    found: dict[str, Found] = {}
    try:
        zc = Zeroconf()
    except OSError as e:
        log.info("mDNS isn't available here (%s)", e)
        return []
    try:
        browser = ServiceBrowser(zc, SERVICE, handlers=[on_change])
        try:
            while True:
                left = deadline - time.monotonic()
                if left <= 0:
                    break
                try:
                    name = names.get(timeout=left)
                except queue.Empty:
                    break
                info = ServiceInfo(SERVICE, name)
                left = max(deadline - time.monotonic(), 0.25)
                if not info.request(zc, timeout=left * 1000):
                    continue
                hub = from_service(name, info.port, info.properties, info.parsed_addresses())
                if hub is None:
                    continue
                if want_id is not None:
                    if hub.id == want_id:
                        return [hub]
                    continue
                found[name] = hub
        finally:
            browser.cancel()
    except Exception as e:  # zeroconf on an odd network setup; never fatal
        log.info("looking for hubs over mDNS failed: %s", e)
    finally:
        zc.close()
    return sorted(found.values(), key=lambda h: (h.name.lower(), h.id))


def local_addresses() -> set[str]:
    """This machine's own IP addresses (loopback and link-local IPv6 left out)."""
    out: set[str] = set()
    try:
        import ifaddr  # comes with zeroconf

        for adapter in ifaddr.get_adapters():
            for ip in adapter.ips:
                out.add(ip.ip if isinstance(ip.ip, str) else ip.ip[0])
    except Exception:
        try:
            for fam, *_, sa in socket.getaddrinfo(socket.gethostname(), None):
                out.add(sa[0])
        except OSError:
            pass
    clean = set()
    for a in out:
        try:
            ip = ipaddress.ip_address(a.split("%")[0])
        except ValueError:
            continue
        # link-local IPv6 comes and goes with every container's veth; it never
        # reaches the hub, and would look like a network change
        if not ip.is_loopback and not (ip.version == 6 and ip.is_link_local):
            clean.add(str(ip))
    return clean
