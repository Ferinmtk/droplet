"""HTTP calls to the droplet hub: its identity, joining, linking, uploading.

Plain urllib, and never an Origin header: the hub refuses writes whose
Origin isn't itself (docs/remote.md §1).

Every call goes over a `Route`: the hub's tailnet URL (ordinary verified
TLS), its LAN HTTPS address (the certificate pinned by fingerprint, see
pinning.py), or loopback when the agent runs on the hub machine itself. A
plain URL string still works and is treated like the tailnet: normal TLS.
"""

from __future__ import annotations

import http.client
import ipaddress
import json
import re
import secrets
import socket
import ssl
import threading
import urllib.error
import urllib.request
from dataclasses import dataclass
from http.cookies import SimpleCookie
from urllib.parse import quote, urlencode, urlsplit

from . import __version__
from .pinning import HubError, PinMismatch, normalize_fingerprint, pinned_context

__all__ = ["HubError", "PinMismatch", "Route"]

USER_AGENT = f"droplet-agent/{__version__}"
COOKIE = "droplet_device"
HUB_ID = re.compile(r"^[0-9a-f]{8,64}$")


@dataclass(frozen=True)
class Route:
    """One way to reach the hub.

    kind: "hub-local" (plain HTTP over loopback, on the hub machine),
    "lan" (HTTPS to a LAN address, certificate pinned), "tailnet" (the
    tailnet URL, verified TLS) or "direct" (the URL setup was given, used
    as is).
    """

    kind: str
    url: str
    pin: str | None = None

    def describe(self) -> str:
        if self.kind == "lan":
            return f"LAN {self.url} (pinned)"
        if self.kind in ("hub-local", "tailnet"):
            return f"{self.kind} {self.url}"
        return self.url

    @property
    def ws(self) -> str:
        return ws_url(self.url)


def as_route(hub) -> Route:
    return hub if isinstance(hub, Route) else Route("direct", str(hub))


def normalize(url: str) -> str:
    """"t15.tail….ts.net" or "https://t15…/" → "https://t15…" (no trailing slash)."""
    url = (url or "").strip()
    if not url:
        raise HubError("no hub URL given")
    if "://" not in url:
        url = "https://" + url
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise HubError(f"{url!r} isn't a hub URL (expected https://<hub>)")
    try:
        parts.port  # raises on a malformed port
    except ValueError as e:
        raise HubError(f"{url!r} has a bad port") from e
    if not re.fullmatch(r"[A-Za-z0-9.\-:]+", parts.hostname) or "@" in parts.netloc:
        raise HubError(f"{url!r} has an odd host name")
    return f"{parts.scheme}://{parts.netloc}"


def ws_url(hub: str) -> str:
    parts = urlsplit(hub)
    return f"{'wss' if parts.scheme == 'https' else 'ws'}://{parts.netloc}/ws"


def host_url(scheme: str, host: str, port: int) -> str:
    """http(s)://host:port, with IPv6 addresses in brackets."""
    if ":" in host and not host.startswith("["):
        host = f"[{host}]"
    return f"{scheme}://{host}:{int(port)}"


def is_loopback(host: str | None) -> bool:
    if not host:
        return False
    if host == "localhost":
        return True
    try:
        return ipaddress.ip_address(host.strip("[]")).is_loopback
    except ValueError:
        return False


def client_label() -> str:
    return f"droplet-agent on {socket.gethostname().split('.')[0]}"


# --- the HTTP layer ----------------------------------------------------------

class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None  # hand the 30x back to the caller as an HTTPError


_openers: dict = {}
_openers_lock = threading.Lock()


def _opener(route: Route, follow: bool):
    """urllib opener for a route. LAN and loopback go direct, never via a proxy."""
    key = (route.kind, route.pin, follow)
    with _openers_lock:
        op = _openers.get(key)
        if op is None:
            handlers = []
            if route.kind in ("lan", "hub-local"):
                handlers.append(urllib.request.ProxyHandler({}))
            if route.pin:
                handlers.append(urllib.request.HTTPSHandler(context=pinned_context(route.pin)))
            if not follow:
                handlers.append(_NoRedirect())
            op = _openers[key] = urllib.request.build_opener(*handlers)
        return op


def _check_scheme(route: Route):
    parts = urlsplit(route.url)
    if route.pin and parts.scheme != "https":
        raise HubError(f"a pinned route must be https, not {route.url}")
    if route.kind == "lan" and not route.pin:
        raise HubError("a LAN route needs the hub's certificate fingerprint")


def _request(method: str, hub, path: str, *, body: bytes | None = None, headers: dict | None = None,
             timeout: float = 30, follow: bool = True):
    route = as_route(hub)
    _check_scheme(route)
    url = route.url + path
    h = {"User-Agent": USER_AGENT, **(headers or {})}
    req = urllib.request.Request(url, data=body, method=method, headers=h)
    try:
        with _opener(route, follow).open(req, timeout=timeout) as resp:
            return resp.status, resp.headers, resp.read(), resp.geturl()
    except urllib.error.HTTPError as e:
        raw = e.read(4000).decode(errors="replace")
        detail, pair = raw, False
        try:
            j = json.loads(raw)
            detail, pair = j.get("error") or raw, bool(j.get("pair"))
        except (ValueError, AttributeError):
            pass
        if e.code == 403 and pair:
            raise HubError("the hub hasn't let this device in (it's waiting to be allowed, was denied, "
                           "or was removed). Run: droplet-agent setup", pair=True) from e
        if e.code in (302, 303) and "/login" in (e.headers.get("Location") or ""):
            detail = "the hub wants its PIN: run droplet-agent setup --pin"
        raise HubError(f"{e.code}: {detail.strip()[:300]}") from e
    except (urllib.error.URLError, OSError, http.client.HTTPException) as e:
        # urllib wraps what went wrong while connecting in a URLError
        reason = getattr(e, "reason", e)
        if isinstance(reason, ssl.SSLCertVerificationError):
            raise HubError(f"{urlsplit(url).netloc}'s certificate isn't valid ({reason.verify_message}). "
                           "For the hub's LAN address, run droplet-agent setup without --hub, or "
                           "give its plain http://<address>:<port>") from e
        raise HubError(f"can't reach {urlsplit(url).netloc}: {reason}") from e


def _json(data: bytes) -> dict:
    try:
        out = json.loads(data)
    except ValueError as e:
        raise HubError("the hub's answer wasn't JSON (is that a droplet hub?)") from e
    if not isinstance(out, dict):
        raise HubError("unexpected answer from the hub")
    return out


def _token_from(headers) -> str | None:
    token = None
    for raw in headers.get_all("Set-Cookie") or []:
        c = SimpleCookie()
        c.load(raw)
        if COOKIE in c:
            token = c[COOKIE].value
    return token


# --- the calls -----------------------------------------------------------------

def info(hub, timeout: float = 10) -> dict:
    """GET /api/hub/info, checked and tidied:

    {"id", "name", "fingerprint" (or None), "addresses", "http_port",
     "https_port" (or None), "tailnet" (or None), "pin"}
    """
    _, _, data, _ = _request("GET", hub, "/api/hub/info", timeout=timeout)
    return parse_info(_json(data))


def parse_info(raw: dict) -> dict:
    hub_id = raw.get("id")
    if not isinstance(hub_id, str) or not HUB_ID.match(hub_id):
        raise HubError("the hub didn't say who it is (is it an older droplet?)")
    lan = raw.get("lan") if isinstance(raw.get("lan"), dict) else {}

    def port(v):
        return v if isinstance(v, int) and not isinstance(v, bool) and 0 < v < 65536 else None
    addresses = []
    for a in lan.get("addresses") or []:
        try:
            addresses.append(str(ipaddress.ip_address(str(a))))
        except ValueError:
            continue
    tailnet = raw.get("tailnet")
    if isinstance(tailnet, str) and tailnet:
        try:
            tailnet = normalize(tailnet)
            if not tailnet.startswith("https://"):
                tailnet = None
        except HubError:
            tailnet = None
    else:
        tailnet = None
    fp = normalize_fingerprint(raw.get("fingerprint"))
    https_port = port(lan.get("https_port"))
    return {
        "id": hub_id,
        "name": str(raw.get("name") or "")[:64],
        "fingerprint": fp,
        "addresses": addresses,
        "http_port": port(lan.get("http_port")),
        "https_port": https_port if fp else None,
        "tailnet": tailnet,
        "pin": bool(raw.get("pin")),
    }


def link(hub, code: str) -> dict:
    """Trade a six-digit link code for {"id","name","token"}."""
    _, _, data, _ = _request("POST", hub, "/api/device/link",
                             body=json.dumps({"code": code, "client": client_label()}).encode(),
                             headers={"Content-Type": "application/json"})
    out = _json(data)
    if not out.get("token") or not out.get("id"):
        raise HubError("the hub didn't send a token")
    return out


def register(hub, name: str) -> dict:
    """Register a new device: {"id","name","token"} (token from the cookie).

    Over the tailnet, or from the hub machine, the device is in straight
    away. From the LAN it comes back with "pending": True and a four-digit
    "code", and waits for one of your devices to allow it.
    """
    _, headers, data, _ = _request("POST", hub, "/api/device",
                                   body=json.dumps({"name": name}).encode(),
                                   headers={"Content-Type": "application/json"})
    out = _json(data)
    token = _token_from(headers)
    if not token or not out.get("id"):
        raise HubError("the hub didn't hand out a device token")
    got = {"id": out["id"], "name": out.get("name", name), "token": token}
    if out.get("pending"):
        got.update(pending=True, code=str(out.get("code") or ""))
    return got


def me(hub, token: str) -> dict | None:
    """Who the hub thinks this token is ({"id","name",…}, with "pending" and "code"
    while it waits to be let in), or None if it's unknown (denied, expired, removed)."""
    _, _, data, _ = _request("GET", hub, "/api/me", headers={"Authorization": f"Bearer {token}"}, timeout=10)
    dev = _json(data).get("device")
    return dev if isinstance(dev, dict) else None


def login_pin(hub, token: str, pin: str) -> bool:
    """Enter the hub's PIN for this device (POST /login). True if the hub took it.

    The hub then lets the device in. It answers a right PIN with a redirect
    to the app and a wrong one with the login page again.
    """
    body = urlencode({"pin": pin}).encode()
    try:
        _, _, _, final = _request("POST", hub, "/login", body=body, follow=False, timeout=15,
                                  headers={"Authorization": f"Bearer {token}",
                                           "Content-Type": "application/x-www-form-urlencoded"})
    except HubError as e:
        cause = e.__cause__
        if isinstance(cause, urllib.error.HTTPError) and cause.code in (301, 302, 303, 307, 308):
            return "/login" not in (cause.headers.get("Location") or "")
        raise
    return not urlsplit(final).path.startswith("/login")


def upload(hub, token: str, to: str, name: str, data: bytes, mime: str = "image/png") -> dict:
    """POST /upload?to=<device id> as multipart field "files"."""
    boundary = "droplet" + secrets.token_hex(12)
    safe_name = name.replace('"', "").replace("\r", "").replace("\n", "")
    body = b"".join([
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="files"; filename="{safe_name}"\r\n'.encode(),
        f"Content-Type: {mime}\r\n\r\n".encode(),
        data,
        f"\r\n--{boundary}--\r\n".encode(),
    ])
    _, _, resp, _ = _request("POST", hub, f"/upload?to={quote(to, safe='')}", body=body, timeout=120,
                             headers={"Authorization": f"Bearer {token}",
                                      "Content-Type": f"multipart/form-data; boundary={boundary}"})
    return _json(resp)


# --- the mesh's routes 3 and 4: through the hub, and its mailbox ------------------

def send_text(hub, token: str, to: str, text: str) -> dict:
    """POST /text to=<device id>: a chat message, kept by the hub for a device that's off."""
    body = urlencode({"text": text, "to": to}).encode()
    _, _, resp, _ = _request("POST", hub, "/text", body=body, timeout=30,
                             headers={"Authorization": f"Bearer {token}",
                                      "Content-Type": "application/x-www-form-urlencoded"})
    return _json(resp)


class _Multipart:
    """A multipart body streamed from a file, with its length known up front."""

    def __init__(self, path, name: str, mime: str):
        self.boundary = "droplet" + secrets.token_hex(12)
        safe = name.replace('"', "").replace("\r", "").replace("\n", "")
        self.head = (f"--{self.boundary}\r\nContent-Disposition: form-data; name=\"files\"; "
                     f"filename=\"{safe}\"\r\nContent-Type: {mime}\r\n\r\n").encode()
        self.tail = f"\r\n--{self.boundary}--\r\n".encode()
        self.path = path
        import os
        self.length = len(self.head) + os.path.getsize(path) + len(self.tail)

    def __iter__(self):
        yield self.head
        with open(self.path, "rb") as f:
            while True:
                chunk = f.read(256 * 1024)
                if not chunk:
                    break
                yield chunk
        yield self.tail


def upload_file(hub, token: str, to: str, path, name: str, mime: str = "application/octet-stream") -> dict:
    """POST /upload?to=<device id>, streaming the file rather than reading it into memory."""
    body = _Multipart(path, name, mime)
    _, _, resp, _ = _request("POST", hub, f"/upload?to={quote(to, safe='')}", body=body, timeout=600,
                             headers={"Authorization": f"Bearer {token}",
                                      "Content-Type": f"multipart/form-data; boundary={body.boundary}",
                                      "Content-Length": str(body.length)})
    return _json(resp)


def ring(hub, token: str, to: str, stop: bool = False) -> dict:
    path = f"/api/device/{quote(to, safe='')}/ring" + ("/stop" if stop else "")
    _, _, resp, _ = _request("POST", hub, path, body=b"{}", timeout=15,
                             headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    return _json(resp)


def mesh_announce(hub, token: str, body: dict) -> dict:
    """POST /api/mesh/announce: this device's mesh identity, for the hub's roster."""
    _, _, resp, _ = _request("POST", hub, "/api/mesh/announce", body=json.dumps(body).encode(), timeout=15,
                             headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    return _json(resp)


def mesh_roster(hub, token: str) -> dict:
    """GET /api/mesh/roster: every approved device's mesh identity."""
    _, _, resp, _ = _request("GET", hub, "/api/mesh/roster", timeout=15,
                             headers={"Authorization": f"Bearer {token}"})
    return _json(resp)
