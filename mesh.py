"""The mesh roster: the hub vouching for its devices (docs/mesh.md §3).

Devices with the droplet app talk to each other directly, over mutual TLS
with self-signed certificates. Each device announces its mesh identity here,
and every approved device gets the roster: all the other approved devices'
identities. That's how devices on the same hub trust each other without
pairing one by one. Removing (or denying) a device on the hub drops it from
the roster, and each device drops it from its trust list on the next fetch.

- POST /api/mesh/announce {"fp","cert_pem","port","lan":[…],"os","caps"}
- GET  /api/mesh/roster → {"v":1,"hub":<hub id>,"peers":[{"id","name","fp","cert_pem","port","lan","tailnet_ip","caps","os"}]}

Both need a named, approved device (bearer token or cookie). The roster
holds no tokens: certificates and addresses only. Whenever it changes, every
open /ws connection gets {"t":"roster"}, and fetches it again.
"""

import hashlib
import ipaddress
import json
import re
import threading
import time

from flask import jsonify, request

MAX_CERT = 8 * 1024
MAX_LAN = 6
TAILNET_V4 = ipaddress.ip_network("100.64.0.0/10")
TAILNET_V6 = ipaddress.ip_network("fd7a:115c:a1e0::/48")
CAP = re.compile(r"^[a-z][a-z0-9-]{0,19}$")
OSES = {"android", "windows", "linux"}
# endpoints that change who is on the roster, or what it says about them
CHANGES = {"api_device", "api_device_remove", "api_device_approve", "login"}


def is_tailnet(ip: str) -> bool:
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return False
    return a in (TAILNET_V4 if a.version == 4 else TAILNET_V6)


def check_cert(pem, fp) -> tuple[str, str] | None:
    """(pem, fingerprint) if `pem` is one certificate whose SHA-256 (DER) is `fp`."""
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization

    if not isinstance(pem, str) or len(pem) > MAX_CERT or pem.count("-----BEGIN CERTIFICATE-----") != 1:
        return None
    try:
        cert = x509.load_pem_x509_certificate(pem.encode())
    except ValueError:
        return None
    der = cert.public_bytes(serialization.Encoding.DER)
    real = hashlib.sha256(der).hexdigest()
    if not isinstance(fp, str) or fp.replace(":", "").lower() != real:
        return None
    return cert.public_bytes(serialization.Encoding.PEM).decode(), real


def clean_lan(items) -> list[str]:
    out = []
    for a in items if isinstance(items, list) else []:
        try:
            ip = ipaddress.ip_address(str(a))
        except ValueError:
            continue
        if ip.is_loopback or ip.is_unspecified or ip.is_multicast or ip.is_link_local or is_tailnet(str(ip)):
            continue
        if str(ip) not in out:
            out.append(str(ip))
    return out[:MAX_LAN]


def register(ctx):
    app = ctx.app
    devices = ctx.devices
    status_cache = {"at": 0.0, "ips": {}}
    status_lock = threading.Lock()

    def broadcast():
        import remote
        remote.hub.broadcast({"t": "roster"})

    def me_approved():
        me = ctx.current_device()
        if me is None or not devices.is_approved(me):
            return None
        return me

    def tailnet_ips() -> dict:
        """tailnet machine name → its first tailnet IP, from `tailscale status` (cached 30 s)."""
        if not ctx.tailnet_url():
            return {}
        with status_lock:
            if time.time() - status_cache["at"] > 30:
                r = ctx.tailscale("status", "--json")
                ips = {}
                try:
                    st = json.loads(r.stdout) if r else {}
                    for p in [st.get("Self") or {}] + list((st.get("Peer") or {}).values()):
                        node = (p.get("DNSName") or "").split(".")[0]
                        addrs = [a for a in p.get("TailscaleIPs") or [] if is_tailnet(a)]
                        if node and addrs:
                            ips[node] = sorted(addrs, key=lambda a: ":" in a)[0]
                except (ValueError, AttributeError, TypeError):
                    pass
                status_cache.update(at=time.time(), ips=ips)
            return status_cache["ips"]

    @app.route("/api/mesh/announce", methods=["POST"])
    def mesh_announce():
        me = me_approved()
        if me is None:
            return jsonify({"error": "Only a named device that's been let in can join the mesh."}), 403
        body = request.get_json(silent=True) or {}
        got = check_cert(body.get("cert_pem"), body.get("fp"))
        if got is None:
            return jsonify({"error": "cert_pem must be one PEM certificate whose SHA-256 is fp"}), 400
        pem, fp = got
        port = body.get("port")
        if not isinstance(port, int) or isinstance(port, bool) or not 0 < port < 65536:
            return jsonify({"error": "bad port"}), 400
        # a certificate names one device: another device can't claim it
        for d in devices.listing(None):
            other = devices.get(d["id"]) or {}
            if d["id"] != me["id"] and (other.get("mesh") or {}).get("fp") == fp:
                return jsonify({"error": "another device already announced that certificate"}), 409
        os_name = body.get("os") if body.get("os") in OSES else ""
        caps = sorted({c for c in body.get("caps") or [] if isinstance(c, str) and CAP.match(c)})[:16]
        old = me.get("mesh") or {}
        tailnet_ip = old.get("tailnet_ip")
        if ctx.via_tailnet():
            # tailscale serve puts the visitor's tailnet IP here; only trusted through serve
            ip = request.headers.get("X-Forwarded-For", "").split(",")[0].strip()
            if is_tailnet(ip):
                tailnet_ip = str(ipaddress.ip_address(ip))
        mesh = {"fp": fp, "cert_pem": pem, "port": port, "lan": clean_lan(body.get("lan")), "os": os_name,
                "caps": caps, "tailnet_ip": tailnet_ip, "announced": int(time.time())}
        devices.update(me["id"], mesh=mesh)
        changed = {k: v for k, v in mesh.items() if k != "announced"} != \
            {k: v for k, v in old.items() if k != "announced"}
        if changed:
            broadcast()
        return jsonify({"ok": True, "changed": changed})

    @app.route("/api/mesh/roster")
    def mesh_roster():
        me = me_approved()
        if me is None:
            return jsonify({"error": "Only a named device that's been let in can see the roster."}), 403
        ips = None
        peers = []
        for d in devices.listing(None):          # approved devices only
            dev = devices.get(d["id"])
            m = (dev or {}).get("mesh")
            if not dev or not m or dev["id"] == me["id"] or not devices.is_approved(dev):
                continue
            tip = m.get("tailnet_ip")
            if not tip and dev.get("node"):
                ips = tailnet_ips() if ips is None else ips
                tip = ips.get(dev["node"])
            peers.append({"id": dev["id"], "name": dev["name"], "fp": m["fp"], "cert_pem": m["cert_pem"],
                          "port": m["port"], "lan": m.get("lan") or [], "tailnet_ip": tip,
                          "caps": m.get("caps") or [], "os": m.get("os") or ""})
        return jsonify({"v": 1, "hub": ctx.hub_id, "peers": peers})

    @app.after_request
    def roster_changes(resp):
        if request.endpoint in CHANGES and request.method == "POST" and resp.status_code < 400:
            broadcast()
        return resp
