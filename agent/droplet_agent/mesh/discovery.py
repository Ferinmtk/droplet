"""Finding peers on the LAN: `_droplet-peer._tcp.local.` over mDNS (docs/mesh.md §2).

TXT records:

    id    peer id            fp    certificate fingerprint (SHA-256 of the DER)
    name  device name        os    android | windows | linux
    caps  comma-separated    hub   the id of its hub, or empty
    v     protocol version, 1

An announcement proves nothing: anyone on the Wi-Fi can send one. It's only a
hint where a peer might be. The connection is then made with mutual TLS and
the fingerprint in the trust list, so a false announcement just fails.
"""

from __future__ import annotations

import ipaddress
import logging
import re
import socket
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field

from . import PROTOCOL_VERSION, SERVICE
from .identity import PEER_ID, normalize_fingerprint
from .trust import clean_caps, clean_name

log = logging.getLogger("droplet_agent.mesh.discovery")

OSES = ("android", "windows", "linux")


@dataclass
class Seen:
    fp: str
    id: str
    name: str
    os: str
    caps: list[str]
    hub: str
    port: int
    addresses: list[str] = field(default_factory=list)
    service: str = ""
    seen: float = 0.0


def txt_records(*, peer_id: str, fp: str, name: str, caps, hub_id: str | None) -> dict[str, str]:
    return {"id": peer_id, "fp": fp, "name": clean_name(name)[:63], "os": "linux",
            "caps": ",".join(clean_caps(list(caps))), "hub": hub_id or "", "v": str(PROTOCOL_VERSION)}


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
    """TXT records → {"id","fp","name","os","caps","hub","v"}, or None if it isn't a usable peer."""
    peer_id = (_text(props, "id") or "").strip().lower()
    fp = normalize_fingerprint(_text(props, "fp"))
    v = (_text(props, "v") or "").strip()
    if not PEER_ID.match(peer_id) or fp is None or not v.isdigit():
        return None
    os_name = (_text(props, "os") or "").strip().lower()
    hub = (_text(props, "hub") or "").strip().lower()
    return {"id": peer_id, "fp": fp, "name": clean_name(_text(props, "name"), peer_id),
            "os": os_name if os_name in OSES else "", "caps": clean_caps(_text(props, "caps") or ""),
            "hub": hub if re.fullmatch(r"[0-9a-f]{8,64}", hub) else "", "v": int(v)}


def usable_addresses(addresses) -> list[str]:
    v4, v6 = [], []
    for a in addresses:
        try:
            ip = ipaddress.ip_address(str(a).split("%")[0])
        except ValueError:
            continue
        if ip.is_unspecified or ip.is_multicast or (ip.version == 6 and ip.is_link_local):
            continue
        (v4 if ip.version == 4 else v6).append(str(ip))
    return list(dict.fromkeys(v4 + v6))


class Directory:
    """Announces this peer and keeps a live map of the peers announcing themselves.

    `local_addresses()` gives the addresses to announce; `on_seen(Seen)` is
    called when a peer appears or changes.
    """

    def __init__(self, own_fp: str, local_addresses, on_seen=None):
        self.own_fp = own_fp
        self.local_addresses = local_addresses
        self.on_seen = on_seen
        self.zc = None
        self.info = None
        self.browser = None
        self._seen: dict[str, Seen] = {}       # service name → Seen
        self._lock = threading.Lock()
        self._pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="mesh-mdns")
        self._stop = threading.Event()
        self._txt: dict = {}
        self._port = 0
        self._addrs: list[str] = []

    def start(self, port: int, txt: dict[str, str]):
        try:
            from zeroconf import IPVersion, ServiceBrowser, ServiceInfo, Zeroconf  # noqa: F401
        except ImportError:
            log.warning("zeroconf isn't installed: peers can't find this computer on the LAN")
            return
        try:
            from zeroconf import Zeroconf
            self.zc = Zeroconf()
        except OSError as e:
            log.warning("mDNS isn't available (%s): peers can't find this computer on the LAN", e)
            return
        self._port, self._txt = port, dict(txt)
        self._register()
        from zeroconf import ServiceBrowser
        self.browser = ServiceBrowser(self.zc, SERVICE, handlers=[self._on_change])
        threading.Thread(target=self._watch_addresses, name="mesh-mdns-addrs", daemon=True).start()

    def _addresses(self) -> list[str]:
        try:
            return list(self.local_addresses())
        except Exception:
            return []

    def _service_info(self):
        from zeroconf import ServiceInfo
        name = self._txt.get("name") or "droplet"
        label = re.sub(r"[^\w\- ]", "", name)[:40].strip() or "droplet"
        instance = f"{label} {self._txt['id'][:6]}.{SERVICE}"
        packed = [socket.inet_pton(socket.AF_INET6 if ":" in a else socket.AF_INET, a) for a in self._addrs]
        return ServiceInfo(SERVICE, instance, addresses=packed, port=self._port, properties=self._txt,
                           server=f"droplet-{self._txt['id']}.local.")

    def _register(self):
        self._addrs = self._addresses()
        if not self._addrs or self.zc is None:
            return
        try:
            self.info = self._service_info()
            self.zc.register_service(self.info, allow_name_change=True)
            log.info("announcing on the LAN as %s (port %d)", self.info.name, self._port)
        except Exception as e:
            self.info = None
            log.warning("couldn't announce over mDNS: %s", e)

    def update(self, txt: dict[str, str] | None = None):
        """Announce again, with new TXT records or new addresses."""
        if self.zc is None:
            return
        if txt is not None:
            self._txt = dict(txt)
        old = self.info
        try:
            if old is not None:
                self.zc.unregister_service(old)
        except Exception:
            pass
        self.info = None
        self._register()

    def _watch_addresses(self):
        while not self._stop.wait(15):
            if self._addresses() != self._addrs:
                log.info("this computer's addresses changed; announcing again")
                self.update()

    def _on_change(self, zeroconf, service_type, name, state_change):
        from zeroconf import ServiceStateChange
        if state_change is ServiceStateChange.Removed:
            with self._lock:
                self._seen.pop(name, None)
            return
        # zeroconf's handlers must not block: resolve elsewhere
        try:
            self._pool.submit(self._resolve, name)
        except RuntimeError:
            pass

    def _resolve(self, name: str):
        from zeroconf import ServiceInfo
        if self._stop.is_set() or self.zc is None:
            return
        info = ServiceInfo(SERVICE, name)
        if not info.request(self.zc, 3000):
            return
        txt = parse_txt(info.properties or {})
        addrs = usable_addresses(info.parsed_addresses())
        if txt is None or not addrs or not info.port or txt["fp"] == self.own_fp:
            return
        seen = Seen(fp=txt["fp"], id=txt["id"], name=txt["name"], os=txt["os"], caps=txt["caps"],
                    hub=txt["hub"], port=int(info.port), addresses=addrs, service=name, seen=time.time())
        with self._lock:
            self._seen[name] = seen
        if self.on_seen:
            try:
                self.on_seen(seen)
            except Exception:
                log.exception("handling a peer seen on the LAN")

    def peers(self) -> list[Seen]:
        with self._lock:
            return list(self._seen.values())

    def by_fp(self, fp: str) -> list[Seen]:
        return [s for s in self.peers() if s.fp == fp]

    def close(self):
        self._stop.set()
        self._pool.shutdown(wait=False, cancel_futures=True)
        if self.zc is not None:
            try:
                if self.browser is not None:
                    self.browser.cancel()
                if self.info is not None:
                    self.zc.unregister_service(self.info)
            except Exception:
                pass
            self.zc.close()
            self.zc = None


# interfaces whose addresses no other device on the LAN can reach: containers,
# VMs, VPNs. Tailscale addresses reach peers through the roster instead (route 2).
VIRTUAL = ("docker", "br-", "veth", "virbr", "vnet", "podman", "cni", "flannel", "kube", "lxc", "lxd",
           "tailscale", "tun", "wg", "zt", "vboxnet", "vmnet", "lo")


def _primary_ipv4() -> str | None:
    """The address this machine would use to reach the internet: its main LAN address."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))   # nothing is sent; TEST-NET, just picks the interface
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def lan_addresses() -> list[str]:
    """This machine's LAN addresses, the main one first; no container, VM, VPN or tailnet ones."""
    out = []
    try:
        import ifaddr
        for adapter in ifaddr.get_adapters():
            if adapter.nice_name.startswith(VIRTUAL) or adapter.name.startswith(VIRTUAL):
                continue
            for ip in adapter.ips:
                out.append(ip.ip if isinstance(ip.ip, str) else ip.ip[0])
    except Exception:
        pass
    clean = []
    for a in out:
        try:
            ip = ipaddress.ip_address(a.split("%")[0])
        except ValueError:
            continue
        if ip.is_loopback or ip.is_link_local or ip.is_multicast or ip.is_unspecified:
            continue
        if ip in ipaddress.ip_network("100.64.0.0/10") or (ip.version == 6 and ip in ipaddress.ip_network("fd7a:115c:a1e0::/48")):
            continue
        clean.append(str(ip))
    first = _primary_ipv4()
    return sorted(dict.fromkeys(clean), key=lambda a: (a != first, ":" in a, a))
