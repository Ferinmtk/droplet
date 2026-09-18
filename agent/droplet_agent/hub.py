"""HTTP calls to the droplet hub: linking, registering, uploading.

Plain urllib, and never an Origin header: the hub refuses writes whose
Origin isn't itself (docs/remote.md §1).
"""

from __future__ import annotations

import json
import re
import secrets
import socket
import urllib.error
import urllib.request
from http.cookies import SimpleCookie
from urllib.parse import quote, urlsplit

from . import __version__

USER_AGENT = f"droplet-agent/{__version__}"
COOKIE = "droplet_device"


class HubError(Exception):
    pass


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


def client_label() -> str:
    return f"droplet-agent on {socket.gethostname().split('.')[0]}"


def _request(method: str, url: str, *, body: bytes | None = None, headers: dict | None = None,
             timeout: float = 30):
    h = {"User-Agent": USER_AGENT, **(headers or {})}
    req = urllib.request.Request(url, data=body, method=method, headers=h)
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
    except urllib.error.HTTPError as e:
        detail = e.read(4000).decode(errors="replace")
        try:
            detail = json.loads(detail).get("error") or detail
        except (ValueError, AttributeError):
            pass
        if e.code in (302, 303) or "/login" in (e.headers.get("Location") or ""):
            detail = "the hub wants a PIN; connect over the tailnet instead"
        raise HubError(f"{e.code}: {detail.strip()[:300]}") from e
    except (urllib.error.URLError, OSError) as e:
        raise HubError(f"can't reach {urlsplit(url).netloc}: {getattr(e, 'reason', e)}") from e
    with resp:
        if urlsplit(resp.geturl()).path.startswith("/login"):
            raise HubError("the hub wants a PIN; connect over the tailnet instead")
        return resp.status, resp.headers, resp.read()


def _json(data: bytes) -> dict:
    try:
        out = json.loads(data)
    except ValueError as e:
        raise HubError("the hub's answer wasn't JSON (is that a droplet hub?)") from e
    if not isinstance(out, dict):
        raise HubError("unexpected answer from the hub")
    return out


def link(hub: str, code: str) -> dict:
    """Trade a six-digit link code for {"id","name","token"}."""
    _, _, data = _request("POST", f"{hub}/api/device/link",
                          body=json.dumps({"code": code, "client": client_label()}).encode(),
                          headers={"Content-Type": "application/json"})
    out = _json(data)
    if not out.get("token") or not out.get("id"):
        raise HubError("the hub didn't send a token")
    return out


def register(hub: str, name: str) -> dict:
    """Register a new device; returns {"id","name","token"} (token from the cookie)."""
    status, headers, data = _request("POST", f"{hub}/api/device",
                                     body=json.dumps({"name": name}).encode(),
                                     headers={"Content-Type": "application/json"})
    out = _json(data)
    token = None
    for raw in headers.get_all("Set-Cookie") or []:
        c = SimpleCookie()
        c.load(raw)
        if COOKIE in c:
            token = c[COOKIE].value
    if not token or not out.get("id"):
        raise HubError("the hub didn't hand out a device token")
    return {"id": out["id"], "name": out.get("name", name), "token": token}


def me(hub: str, token: str) -> dict | None:
    """Who the hub thinks this token is ({"id","name",…}), or None if it's unknown."""
    _, _, data = _request("GET", f"{hub}/api/me", headers={"Authorization": f"Bearer {token}"}, timeout=10)
    return _json(data).get("device")


def upload(hub: str, token: str, to: str, name: str, data: bytes, mime: str = "image/png") -> dict:
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
    _, _, resp = _request("POST", f"{hub}/upload?to={quote(to, safe='')}", body=body, timeout=120,
                          headers={"Authorization": f"Bearer {token}",
                                   "Content-Type": f"multipart/form-data; boundary={boundary}"})
    return _json(resp)
