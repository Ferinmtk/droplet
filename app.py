"""droplet — LAN file drop. Any browser on the network can send/fetch files."""

import atexit
import json
import os
import re
import secrets
import signal
import socket
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

from flask import (
    Flask,
    abort,
    g,
    jsonify,
    redirect,
    render_template,
    request,
    send_file,
    send_from_directory,
    session,
    url_for,
)
from werkzeug.utils import secure_filename

from devices import Chats, DeviceStore, Pusher

# --- config (env-driven so any machine can be the hub) -----------------------

BASE_DIR = Path(os.environ.get("DROPLET_HOME", Path(__file__).parent)).resolve()
RECEIVED_DIR = BASE_DIR / "received"
SHARED_DIR = BASE_DIR / "shared"
CERT_DIR = BASE_DIR / "certs"

HOST = os.environ.get("DROPLET_HOST", "0.0.0.0")
PORT = int(os.environ.get("DROPLET_PORT", "8000"))
ADVERTISED_IP = os.environ.get("DROPLET_LAN_IP", "")
PIN = os.environ.get("DROPLET_PIN", "")
USE_HTTPS = os.environ.get("DROPLET_HTTPS", "") not in ("", "0", "false")
MDNS_NAME = os.environ.get("DROPLET_NAME", "droplet")
MAX_UPLOAD_MB = int(os.environ.get("DROPLET_MAX_MB", "1024"))
USE_TAILSCALE = os.environ.get("DROPLET_TAILSCALE", "") not in ("", "0", "false")
# tailnet devices are already approved by the tailnet admin, so by default
# they skip the PIN; set to 0 to make them enter it like LAN guests
TAILNET_TRUST = os.environ.get("DROPLET_TAILNET_TRUST", "1") not in ("", "0", "false")
TAILNET_URL: str | None = None  # set at startup once `tailscale serve` is confirmed
# 0 = no push notifications (fully local; devices only see new items while open)
USE_PUSH = os.environ.get("DROPLET_PUSH", "1") not in ("", "0", "false")
# what a device on the LAN may do before one of yours allows it in (or it
# enters the PIN): "drop" = send files to the hub, "none" = nothing
LAN_GUESTS = os.environ.get("DROPLET_LAN_GUESTS", "drop")
# HTTPS for native apps on the LAN, with a self-signed certificate they pin
# (0 turns it off). Browsers keep using PORT, or the tailnet URL.
LAN_TLS_PORT = int(os.environ.get("DROPLET_LAN_TLS_PORT", "8443"))
LAN_IP = ""        # set at startup
LAN_FP = ""        # sha256 of the LAN certificate, set at startup

FOLDERS = {"received": RECEIVED_DIR, "shared": SHARED_DIR}

# shown as thumbnails in the listing rather than as a filename.
# raster only — /raw serves inline, and an inline SVG can carry script.
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".avif"}

STATIC_DIR = Path(__file__).parent / "static"

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = MAX_UPLOAD_MB * 1024 * 1024
# an installed app drops browser-session cookies whenever it's closed, which
# would mean typing the PIN on every launch
app.config["PERMANENT_SESSION_LIFETIME"] = 30 * 24 * 3600


def _secret_key() -> bytes:
    # persisted so PIN logins survive restarts
    f = BASE_DIR / ".secret_key"
    if not f.exists():
        f.write_bytes(secrets.token_bytes(32))
        f.chmod(0o600)
    return f.read_bytes()


app.secret_key = _secret_key()


def _hub_id() -> str:
    # stable across restarts and IP changes, so apps can tell it's the same hub
    f = BASE_DIR / ".hub_id"
    if not f.exists():
        f.write_text(secrets.token_hex(8))
    return f.read_text().strip()


HUB_ID = _hub_id()

DEVICE_COOKIE = "droplet_device"
devices = DeviceStore(BASE_DIR)
pusher = Pusher(BASE_DIR, devices, USE_PUSH)
chats = Chats(BASE_DIR)
# features that know more about devices (e.g. live connections) add to the listing
PRESENCE_HOOKS: list = []
URL_ONLY = re.compile(r"^https?://\S+$")


# --- helpers -----------------------------------------------------------------

def get_lan_ip() -> str:
    if ADVERTISED_IP:  # multi-homed hosts: pick which network to advertise on
        return ADVERTISED_IP
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))  # no traffic sent; just picks the LAN interface
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def unique_path(directory: Path, name: str) -> Path:
    p = directory / name
    stem, suffix = p.stem, p.suffix
    i = 1
    while p.exists():
        p = directory / f"{stem}-{i}{suffix}"
        i += 1
    return p


def meta_path(p: Path) -> Path:
    # inbox items carry a hidden sidecar saying who sent them
    return p.with_name(f".{p.name}.json")


def list_files(directory: Path) -> list[dict]:
    items = []
    if not directory.is_dir():
        return items
    for p in directory.iterdir():
        if p.is_file() and not p.name.startswith("."):
            st = p.stat()
            item = {
                "name": p.name,
                "size": st.st_size,
                "mtime": int(st.st_mtime),
                "image": p.suffix.lower() in IMAGE_SUFFIXES,
            }
            try:
                item["from"] = json.loads(meta_path(p).read_text())["from"]
            except (OSError, ValueError, KeyError):
                pass
            items.append(item)
    items.sort(key=lambda f: f["mtime"], reverse=True)
    return items


def resolve_in(directory: Path, name: str) -> Path:
    # send_from_directory guards itself, but delete/zip/raw need the same
    # check before touching the path at all
    p = (directory / name).resolve()
    if not p.is_file() or directory.resolve() not in p.parents:
        abort(404)
    return p


def tailnet_user() -> str | None:
    """Tailnet login of the visitor, when the request came through `tailscale serve`.

    serve proxies from loopback and sets Tailscale-User-Login itself, dropping
    any copy the client sent. LAN clients never arrive from loopback, so they
    can't forge it. Tagged devices carry no user and get None.
    """
    if not TAILNET_URL or request.remote_addr not in ("127.0.0.1", "::1"):
        return None
    return request.headers.get("Tailscale-User-Login") or None


def via_tailnet() -> bool:
    return bool(TAILNET_URL) and request.remote_addr in ("127.0.0.1", "::1")


# --- devices -----------------------------------------------------------------

def current_device() -> dict | None:
    if "device" not in g:
        # browsers send the cookie; native helpers may use a bearer token instead
        # (a cross-site page can't set Authorization without a CORS preflight)
        auth = request.headers.get("Authorization", "")
        token = auth[7:].strip() if auth.startswith("Bearer ") else request.cookies.get(DEVICE_COOKIE)
        g.device = devices.by_token(token)
        if g.device:
            devices.touch(g.device["id"])
            if not g.device.get("node"):
                # devices named before this was recorded, or first seen over the LAN
                node = tailnet_node()
                if node:
                    devices.update(g.device["id"], node=node)
                    g.device["node"] = node
    return g.device


def sender_name() -> str:
    dev = current_device()
    if dev:
        return dev["name"]
    return tailnet_user() or "someone"


_whois_cache: dict[str, str] = {}


def tailnet_node() -> str | None:
    """The visitor's tailnet machine name (e.g. "redmi-note-11e-pro").

    `tailscale serve` passes the visitor's tailnet IP in X-Forwarded-For;
    only trusted on requests that came through serve (see via_tailnet).
    """
    if not via_tailnet():
        return None
    ip = request.headers.get("X-Forwarded-For", "").split(",")[0].strip()
    if ip and ip not in _whois_cache:
        r = _tailscale("whois", "--json", ip)
        try:
            _whois_cache[ip] = json.loads(r.stdout)["Node"]["ComputedName"] if r else ""
        except (ValueError, KeyError, TypeError):
            _whois_cache[ip] = ""
    return _whois_cache.get(ip) or None


_peers: tuple[float, list[dict]] = (0.0, [])


def tailnet_peers() -> list[dict]:
    """Machines on the tailnet, refreshed at most every 15 s (the page polls every 5)."""
    global _peers
    if not TAILNET_URL:
        return []
    if time.time() - _peers[0] > 15:
        found = _peers[1]
        r = _tailscale("status", "--json")
        try:
            found = [
                {
                    "node": (p.get("DNSName") or "").split(".")[0] or p.get("HostName", ""),
                    "os": p.get("OS", ""),
                    "online": bool(p.get("Online")),
                }
                for p in (json.loads(r.stdout).get("Peer") or {}).values()
                if not p.get("Tags")  # tagged nodes are servers, not someone's device
            ]
        except (AttributeError, ValueError, TypeError):
            pass
        _peers = (time.time(), found)
    return _peers[1]


def suggest_name() -> str:
    """A starting name for a new device: its tailnet machine name if we can see it."""
    node = tailnet_node()
    if node:
        return node
    ua = request.user_agent.string
    for needle, name in (("Android", "Android phone"), ("iPhone", "iPhone"), ("iPad", "iPad"),
                         ("Windows", "Windows PC"), ("Macintosh", "Mac"), ("Linux", "Linux PC")):
        if needle in ua:
            return name
    return "This device"


def clean_name(raw) -> str:
    name = " ".join(str(raw or "").split())[:40]
    if not name:
        abort(400)
    return name


def destination(to: str | None) -> tuple[Path, dict | None]:
    """Where an upload goes: the hub's received/ folder, or a device's inbox."""
    if not to or to == "hub":
        return RECEIVED_DIR, None
    dev = devices.get(to)
    if dev is None:
        abort(404)
    inbox = devices.inbox(dev["id"])
    inbox.mkdir(parents=True, exist_ok=True)
    return inbox, dev


def deliver(dev: dict | None, paths: list[Path]):
    """Label files sent to a device with their sender, then notify the device."""
    if dev is None or not paths:
        return
    sender = sender_name()
    for p in paths:
        meta_path(p).write_text(json.dumps({"from": sender, "sent": int(time.time())}))
    names = [p.name for p in paths]
    pusher.send(dev["id"], {
        "title": f"{sender} sent {len(names)} file{'s' if len(names) != 1 else ''}",
        "body": ", ".join(names[:3]) + (f" +{len(names) - 3} more" if len(names) > 3 else ""),
        "url": "/#inbox",
        "tag": f"droplet-{int(time.time() * 1000)}",
    })


def send_message(me: dict, dev: dict, text: str) -> dict:
    msg = chats.add(me, dev, text)
    pusher.send(dev["id"], {
        "title": me["name"],
        "body": text[:200],
        # a bare link opens straight away when tapped; anything else opens the chat
        "url": text if URL_ONLY.match(text) else f"/#chat-{me['id']}",
        "tag": f"chat-{me['id']}",  # one notification per sender, updated as messages arrive
    })
    return msg


def save_files(directory: Path) -> list[Path]:
    saved = []
    for f in request.files.getlist("files"):
        name = secure_filename(f.filename or "")
        if not name:
            continue
        dest = unique_path(directory, name)
        f.save(dest)
        saved.append(dest)
    return saved


def save_text(directory: Path, text: str) -> Path:
    dest = unique_path(directory, f"text-{time.strftime('%Y%m%d-%H%M%S')}.txt")
    dest.write_text(text, encoding="utf-8")
    return dest


def folder_dir(folder: str) -> Path:
    if folder == "inbox":
        dev = current_device()
        if dev is None:
            abort(404)
        return devices.inbox(dev["id"])
    directory = FOLDERS.get(folder)
    if directory is None:
        abort(404)
    return directory


# --- cross-site guard -------------------------------------------------------

@app.before_request
def same_origin_writes():
    """Refuse state-changing requests that another website started.

    Tailnet visitors are trusted by network, not by cookie, so a page on any
    site could otherwise make the visitor's browser POST here (delete files,
    run hub commands…). Browsers always send Origin on cross-site POSTs;
    curl and the native apps send none and aren't affected.
    """
    if request.method in ("GET", "HEAD", "OPTIONS") or request.endpoint == "share":
        return None  # /share: Android's share sheet may post with Origin: null
    origin = request.headers.get("Origin")
    if origin is None:
        return None
    from urllib.parse import urlsplit
    if urlsplit(origin).netloc != request.host:
        abort(403)
    return None


# --- who gets in --------------------------------------------------------------
#
# droplet is local-first, like KDE Connect: devices on the same Wi-Fi talk to
# the hub directly. So the LAN can't be trusted by itself. A request is
# trusted when it comes
#   - from a process on the hub itself,
#   - through `tailscale serve` (your tailnet; DROPLET_TAILNET_TRUST=0 turns
#     this off),
#   - with a PIN login, or
#   - from a device you've already allowed in.
# A new device on the LAN names itself and waits: your devices get "X wants
# to join, code 1234: Allow / Deny", or it can type the PIN.

# reachable before being trusted: the app shell (browsers fetch the manifest
# and service worker without cookies), asking to join, the hub's identity,
# and the Linux agent's installer and package (public code, the same as in
# the repository), so a new computer on the LAN can install it and then ask
# to join
PUBLIC_ENDPOINTS = {"login", "static", "manifest", "service_worker", "home",
                    "api_me", "api_device", "api_link", "hub_info",
                    "agent_install_script", "agent_package"}
GUEST_DROP_ENDPOINTS = {"upload", "share"}


def trusted() -> bool:
    if "trusted" not in g:
        if request.remote_addr in ("127.0.0.1", "::1") and not (TAILNET_URL and request.headers.get("X-Forwarded-For")):
            g.trusted = True  # a process on the hub machine
        elif via_tailnet() and TAILNET_TRUST and request.headers.get("X-Forwarded-For"):
            g.trusted = True  # a tailnet member, through tailscale serve
        else:
            g.trusted = bool(session.get("authed")) or devices.is_approved(current_device())
    return g.trusted


@app.before_request
def access_gate():
    ep = request.endpoint
    if ep in PUBLIC_ENDPOINTS or trusted():
        return None
    if (ep in GUEST_DROP_ENDPOINTS and LAN_GUESTS == "drop"
            and request.args.get("to") in (None, "", "hub")):
        return None  # a guest may still drop files on the hub, like before
    return jsonify({"error": "This device hasn't been allowed in yet.", "pair": True}), 403


@app.route("/login", methods=["GET", "POST"])
def login():
    if not PIN:
        return redirect(url_for("home"))
    error = None
    if request.method == "POST":
        if secrets.compare_digest(request.form.get("pin", ""), PIN):
            session.permanent = True
            session["authed"] = True
            me = current_device()
            if me and not devices.is_approved(me):
                devices.approve(me["id"])  # the PIN is as good as being allowed in
            return redirect(url_for("home"))
        error = "Wrong PIN"
    return render_template("login.html", error=error)


# --- routes ------------------------------------------------------------------

@app.route("/")
def home():
    return render_template("index.html", host=socket.gethostname(), tailnet_user=tailnet_user())


@app.route("/api/files")
def api_files():
    # polled every few seconds, which is also how devices show as online
    me = current_device()
    out = {name: list_files(d) for name, d in FOLDERS.items()}
    out["inbox"] = list_files(devices.inbox(me["id"])) if me else []
    out["unread"] = chats.unread(me["id"], me.get("read") or {}) if me else {}
    out["pending"] = devices.pending()  # join requests waiting for an answer
    out["devices"] = devices.listing(me["id"] if me else None)
    for hook in PRESENCE_HOOKS:
        extra = hook()
        for d in out["devices"]:
            d.update(extra.get(d["id"]) or {"caps": [], "apps": []})
    # tailnet machines that haven't opened droplet yet, so people know what's missing
    known = devices.nodes()
    out["tailnet"] = [p for p in tailnet_peers() if p["node"] and p["node"] not in known]
    return jsonify(out)


@app.route("/api/me")
def api_me():
    me = current_device()
    device = None
    if me:
        device = {"id": me["id"], "name": me["name"], "push": bool(me.get("push"))}
        if not devices.is_approved(me):
            device.update(pending=True, code=devices.pair_code(me))
    return jsonify({
        "device": device,
        "trusted": trusted(),
        "pin": bool(PIN),          # whether "enter the PIN" is a way in
        "guests": LAN_GUESTS,
        "suggested": None if me else suggest_name(),
        "push_key": pusher.public_key if pusher.enabled else None,
        "hub_url": TAILNET_URL,
    })


@app.route("/api/device", methods=["POST"])
def api_device():
    """Name this browser (registering it as a device), or rename it."""
    name = clean_name((request.get_json(silent=True) or {}).get("name"))
    me = current_device()
    if devices.name_taken(name, except_id=me["id"] if me else None):
        return jsonify({"error": f"{name} is already a device here. Pick another name, "
                                 "or remove the old one under Devices."}), 409
    if me:
        devices.update(me["id"], name=name)
        return jsonify({"id": me["id"], "name": name})
    approved = trusted()
    if not approved and len(devices.pending()) >= devices.PENDING_MAX:
        return jsonify({"error": "Too many devices are waiting to join. Answer those first."}), 429
    dev, token = devices.create(name, node=tailnet_node(), approved=approved)
    body = {"id": dev["id"], "name": name}
    if not approved:
        body.update(pending=True, code=devices.pair_code(dev))
        ask_to_join(dev)
    resp = jsonify(body)
    secure = request.is_secure or (via_tailnet() and request.headers.get("X-Forwarded-Proto") == "https")
    resp.set_cookie(DEVICE_COOKIE, token, max_age=5 * 365 * 24 * 3600,
                    httponly=True, samesite="Lax", secure=secure)
    return resp


def ask_to_join(dev: dict):
    """Tell every device that can answer that a new one wants in."""
    code = devices.pair_code(dev)
    for d in devices.listing(None):
        if d["push"]:
            pusher.send(d["id"], {"title": "A new device wants to join",
                                  "body": f"{dev['name']} · code {code}. Tap to allow or deny.",
                                  "url": "/#pair", "tag": "pair"})


@app.route("/api/hub/info")
def hub_info():
    """Who this hub is and how to reach it, for apps pairing or finding it on the LAN."""
    return jsonify({
        "id": HUB_ID,
        "name": socket.gethostname().split(".")[0],
        "fingerprint": LAN_FP or None,   # sha256 of the LAN HTTPS certificate, to pin
        "lan": {"addresses": [LAN_IP] if LAN_IP else [], "http_port": PORT,
                "https_port": LAN_TLS_PORT if LAN_FP else None},
        "tailnet": TAILNET_URL,
        "pin": bool(PIN),
    })


@app.route("/api/pair")
def api_pair():
    return jsonify({"pending": devices.pending()})


@app.route("/api/device/<device_id>/approve", methods=["POST"])
def api_device_approve(device_id):
    if not devices.approve(device_id):
        abort(404)
    return jsonify({"approved": device_id})


@app.route("/api/device/link-code", methods=["POST"])
def api_link_code():
    """A one-time code for linking a native helper (agent, app) to this device."""
    me = current_device()
    if me is None:
        abort(400)
    return jsonify({"code": devices.link_code(me["id"]), "expires_in": devices.LINK_TTL,
                    "device": {"id": me["id"], "name": me["name"]}})


@app.route("/api/device/link", methods=["POST"])
def api_link():
    body = request.get_json(silent=True) or {}
    got = devices.redeem(body.get("code", ""), str(body.get("client") or "app"))
    if got is None:
        return jsonify({"error": "That code is wrong or has expired. Make a new one."}), 403
    dev, token = got
    resp = jsonify({"id": dev["id"], "name": dev["name"], "token": token})
    secure = request.is_secure or (via_tailnet() and request.headers.get("X-Forwarded-Proto") == "https")
    resp.set_cookie(DEVICE_COOKIE, token, max_age=5 * 365 * 24 * 3600,
                    httponly=True, samesite="Lax", secure=secure)
    return resp


@app.route("/api/device/<device_id>/remove", methods=["POST"])
def api_device_remove(device_id):
    # any device can remove any other (e.g. a lost phone, an old browser);
    # the PIN / tailnet is the trust boundary, as for everything else here
    if devices.get(device_id) is None:
        abort(404)
    devices.remove(device_id)
    chats.forget(device_id)
    resp = jsonify({"removed": device_id})
    me = current_device()
    if me and me["id"] == device_id:
        resp.delete_cookie(DEVICE_COOKIE)
    return resp


@app.route("/api/push/subscribe", methods=["POST"])
def api_push_subscribe():
    me = current_device()
    sub = request.get_json(silent=True) or {}
    keys = sub.get("keys") or {}
    if me is None or not str(sub.get("endpoint", "")).startswith("https://") or not (keys.get("p256dh") and keys.get("auth")):
        abort(400)
    devices.update(me["id"], push={"endpoint": sub["endpoint"], "keys": {"p256dh": keys["p256dh"], "auth": keys["auth"]}})
    return jsonify({"ok": True})


@app.route("/api/push/test", methods=["POST"])
def api_push_test():
    me = current_device()
    if me is None:
        abort(400)
    pusher.send(me["id"], {"title": "droplet", "body": f"Notifications work on {me['name']} 👋", "url": "/", "tag": "droplet-test"})
    return jsonify({"ok": True})


@app.route("/upload", methods=["POST"])
def upload():
    directory, dev = destination(request.args.get("to"))
    saved = save_files(directory)
    deliver(dev, saved)
    return jsonify({"saved": [p.name for p in saved]})


@app.route("/text", methods=["POST"])
def share_text():
    text = (request.form.get("text") or "").strip()
    if not text:
        return jsonify({"error": "empty"}), 400
    directory, dev = destination(request.form.get("to"))
    if dev is not None:
        # text to a device is a chat message; it needs a named sender to reply to
        me = current_device()
        if me is None:
            return jsonify({"error": "Name this device first, so replies have somewhere to go."}), 400
        if me["id"] == dev["id"]:
            abort(400)
        return jsonify({"message": send_message(me, dev, text)})
    dest = save_text(directory, text)
    return jsonify({"saved": dest.name})


@app.route("/api/chat/<device_id>")
def api_chat(device_id):
    me = current_device()
    other = devices.get(device_id)
    if me is None or other is None or other["id"] == me["id"]:
        abort(404)
    msgs = chats.thread(me["id"], other["id"])
    # opening the thread marks it read (only written when something new arrived)
    read = me.get("read") or {}
    if msgs and msgs[-1]["ts"] > read.get(other["id"], 0):
        devices.update(me["id"], read={**read, other["id"]: msgs[-1]["ts"]})
    return jsonify({"with": {"id": other["id"], "name": other["name"]}, "messages": msgs})


@app.route("/share", methods=["POST"])
def share():
    # Android's share sheet posts here (see share_target in the manifest).
    # Normally the service worker catches it first; this is the fallback for
    # when it isn't running yet.
    saved = save_files(RECEIVED_DIR)
    parts = []
    for key in ("title", "text", "url"):
        v = (request.form.get(key) or "").strip()
        if v and not any(v in p for p in parts):
            parts.append(v)
    if parts:
        saved.append(save_text(RECEIVED_DIR, "\n".join(parts)))
    return redirect(url_for("home", shared=len(saved)), code=303)


@app.route("/manifest.webmanifest")
def manifest():
    return send_from_directory(STATIC_DIR, "manifest.webmanifest", mimetype="application/manifest+json")


@app.route("/sw.js")
def service_worker():
    # served from the root so its scope covers the whole app;
    # no-cache so a new version is picked up on the next visit
    resp = send_from_directory(STATIC_DIR, "sw.js", mimetype="text/javascript")
    resp.headers["Cache-Control"] = "no-cache"
    return resp


@app.route("/d/<folder>/<path:name>")
def download(folder, name):
    directory = folder_dir(folder)
    return send_from_directory(directory, name, as_attachment=True)


@app.route("/raw/<folder>/<path:name>")
def raw(folder, name):
    # inline rather than attachment, so thumbnails can render
    directory = folder_dir(folder)
    resolve_in(directory, name)
    return send_from_directory(directory, name)


@app.route("/delete/<folder>/<path:name>", methods=["POST"])
def delete(folder, name):
    p = resolve_in(folder_dir(folder), name)
    p.unlink()
    meta_path(p).unlink(missing_ok=True)
    return jsonify({"deleted": name})


@app.route("/zip", methods=["POST"])
def zip_selected():
    wanted = (request.get_json(silent=True) or {}).get("files") or []
    if not wanted:
        abort(400)
    # spills to disk past the threshold so a big selection can't exhaust RAM
    spool = tempfile.SpooledTemporaryFile(max_size=32 * 1024 * 1024)
    with zipfile.ZipFile(spool, "w", zipfile.ZIP_DEFLATED) as zf:
        for item in wanted:
            folder, _, name = str(item).partition("/")
            p = resolve_in(folder_dir(folder), name)
            zf.write(p, arcname=f"{folder}/{p.name}")
    spool.seek(0)
    return send_file(
        spool,
        mimetype="application/zip",
        as_attachment=True,
        download_name=f"droplet-{time.strftime('%Y%m%d-%H%M%S')}.zip",
    )


# --- HTTPS (persistent self-signed cert) -------------------------------------

def ensure_cert(lan_ip: str) -> tuple[str, str]:
    cert_file = CERT_DIR / "cert.pem"
    key_file = CERT_DIR / "key.pem"
    if not (cert_file.exists() and key_file.exists()):
        import datetime
        import ipaddress

        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.x509.oid import NameOID

        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, f"{MDNS_NAME}.local")])
        now = datetime.datetime.now(datetime.timezone.utc)
        cert = (
            x509.CertificateBuilder()
            .subject_name(subject)
            .issuer_name(subject)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now)
            .not_valid_after(now + datetime.timedelta(days=3650))
            .add_extension(
                x509.SubjectAlternativeName(
                    [
                        x509.DNSName(f"{MDNS_NAME}.local"),
                        x509.DNSName("localhost"),
                        x509.IPAddress(ipaddress.ip_address(lan_ip)),
                    ]
                ),
                critical=False,
            )
            .sign(key, hashes.SHA256())
        )
        key_file.write_bytes(
            key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.TraditionalOpenSSL,
                serialization.NoEncryption(),
            )
        )
        key_file.chmod(0o600)
        cert_file.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    return str(cert_file), str(key_file)


# --- tailnet: real HTTPS via `tailscale serve` -------------------------------

def _tailscale(*args: str) -> subprocess.CompletedProcess | None:
    try:
        return subprocess.run(["tailscale", *args], capture_output=True, text=True, timeout=20)
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return None


def setup_tailnet() -> str | None:
    """Put droplet behind `tailscale serve` on :443 and return its https URL.

    Tailscale terminates TLS with a real Let's Encrypt cert for
    <machine>.<tailnet>.ts.net and renews it itself. Returns None (LAN-only)
    when anything is missing, after saying what to fix.
    """
    r = _tailscale("status", "--json")
    if r is None:
        print("  tailnet: tailscale CLI not found — LAN only")
        return None
    try:
        status = json.loads(r.stdout)
    except ValueError:
        print(f"  tailnet: can't read tailscale status ({r.stderr.strip()}) — LAN only")
        return None
    if status.get("BackendState") != "Running":
        print("  tailnet: tailscale isn't connected (run `tailscale up`) — LAN only")
        return None

    domain = (status.get("Self") or {}).get("DNSName", "").rstrip(".")
    if not domain or domain not in (status.get("CertDomains") or []):
        print("  tailnet: HTTPS certificates are off for this tailnet — LAN only.")
        print("           Turn on MagicDNS + HTTPS Certificates at")
        print("           https://login.tailscale.com/admin/dns, then restart droplet.")
        return None

    target = f"{'https+insecure' if USE_HTTPS else 'http'}://127.0.0.1:{PORT}"
    r = _tailscale("serve", "status", "--json")
    config = json.loads(r.stdout or "{}") if r and r.returncode == 0 else {}
    handlers = ((config.get("Web") or {}).get(f"{domain}:443") or {}).get("Handlers") or {}
    current = (handlers.get("/") or {}).get("Proxy")
    if current and current != target:
        # :443 already serves something else on this machine; don't clobber it
        print(f"  tailnet: https://{domain} already proxies to {current} — leaving it alone.")
        print(f"           Free it with `tailscale serve --https=443 off`, then restart droplet.")
        return None
    if current != target:
        # --bg persists in tailscaled, so the URL survives droplet restarts
        r = _tailscale("serve", "--bg", "--yes", "--https=443", target)
        if r is None or r.returncode != 0:
            err = (r.stderr or r.stdout).strip() if r else "timed out"
            print(f"  tailnet: `tailscale serve` failed: {err}")
            if "access denied" in err.lower() or "permission" in err.lower():
                print(f"           Allow your user once: sudo tailscale set --operator=$USER")
            return None
    return f"https://{domain}"


# --- mDNS: announce this machine as <name>.local -----------------------------

def register_mdns(lan_ip: str):
    try:
        from zeroconf import NonUniqueNameException, ServiceInfo, Zeroconf
    except ImportError:
        print("  (zeroconf not installed — skipping mDNS)")
        return
    infos = [ServiceInfo(
        "_http._tcp.local.",
        f"{MDNS_NAME}._http._tcp.local.",
        addresses=[socket.inet_aton(lan_ip)],
        port=PORT,
        server=f"{MDNS_NAME}.local.",
    )]
    if LAN_FP:
        # how the native apps find the hub on the Wi-Fi without Tailscale;
        # the fingerprint lets them check it's the hub they paired with
        infos.append(ServiceInfo(
            "_droplet._tcp.local.",
            f"{MDNS_NAME}-{HUB_ID[:6]}._droplet._tcp.local.",
            addresses=[socket.inet_aton(lan_ip)],
            port=LAN_TLS_PORT,
            server=f"{MDNS_NAME}.local.",
            properties={"id": HUB_ID, "fp": LAN_FP, "name": socket.gethostname().split(".")[0],
                        "http": str(PORT), "ts": TAILNET_URL or ""},
        ))
    zc = Zeroconf()
    registered = []
    for info in infos:
        try:
            zc.register_service(info, allow_name_change=True)
            registered.append(info)
        except NonUniqueNameException:
            # stale announcement from a previous run still cached on the LAN;
            # the app works fine via IP — the name frees up when the TTL expires
            print(f"  ({info.name} is taken/stale — skipping it this run)")
    if not registered:
        zc.close()
        return

    def _goodbye():
        for info in registered:
            zc.unregister_service(info)
        zc.close()

    atexit.register(_goodbye)


def cert_fingerprint(cert_file: str) -> str:
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes

    cert = x509.load_pem_x509_certificate(Path(cert_file).read_bytes())
    return cert.fingerprint(hashes.SHA256()).hex()


def serve_lan_tls(cert_file: str, key_file: str):
    """A second listener with HTTPS for native apps on the LAN."""
    import threading

    from werkzeug.serving import make_server

    server = make_server(HOST, LAN_TLS_PORT, app, threaded=True, ssl_context=(cert_file, key_file))
    threading.Thread(target=server.serve_forever, name="lan-tls", daemon=True).start()


# --- startup banner ----------------------------------------------------------

def banner(url: str, tailnet_url: str | None = None):
    print()
    print("  💧 droplet — LAN file drop")
    if tailnet_url:
        print(f"     {tailnet_url}  (any device on your tailnet, from anywhere)")
    print(f"     {url}  (LAN)")
    print(f"     http{'s' if USE_HTTPS else ''}://{MDNS_NAME}.local:{PORT}  (mDNS-capable devices)")
    pin = "required" if PIN else "off (set DROPLET_PIN to enable)"
    if PIN and tailnet_url and TAILNET_TRUST:
        pin += " — tailnet devices skip it"
    print(f"     PIN: {pin}")
    print(f"     push notifications: {'on' if pusher.enabled else 'off'}")
    print(f"     folders: {RECEIVED_DIR}  |  {SHARED_DIR}")
    print()
    try:
        import qrcode

        qr = qrcode.QRCode(border=1)
        qr.add_data(tailnet_url or url)
        qr.print_ascii(invert=True)
    except ImportError:
        print("  (qrcode not installed — skipping QR)")
    print()


# --- feature modules ---------------------------------------------------------
# Each module exposes register(ctx) and adds its own routes and page script,
# so features stay out of this file. The PIN gate above covers their routes too.

from types import SimpleNamespace  # noqa: E402

import agent_dist  # noqa: E402
import clipboard  # noqa: E402
import commands  # noqa: E402
import media  # noqa: E402
import phone  # noqa: E402
import remote  # noqa: E402
import ring  # noqa: E402
import tv  # noqa: E402

FEATURES: list = [agent_dist, clipboard, commands, media, phone, remote, ring, tv]





feature_ctx = SimpleNamespace(
    app=app,
    base_dir=BASE_DIR,
    devices=devices,
    pusher=pusher,
    chats=chats,
    current_device=current_device,
    sender_name=sender_name,
    presence_hooks=PRESENCE_HOOKS,
)
for _feature in FEATURES:
    _feature.register(feature_ctx)


if __name__ == "__main__":
    # make pkill/SIGTERM exit cleanly so the mDNS goodbye packet goes out
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))

    for d in (RECEIVED_DIR, SHARED_DIR, CERT_DIR, devices.inbox_root):
        d.mkdir(parents=True, exist_ok=True)

    lan_ip = get_lan_ip()
    scheme = "https" if USE_HTTPS else "http"
    LAN_IP = lan_ip
    if LAN_TLS_PORT:
        cert_file, key_file = ensure_cert(lan_ip)
        LAN_FP = cert_fingerprint(cert_file)
        try:
            serve_lan_tls(cert_file, key_file)
        except OSError as e:
            print(f"  LAN HTTPS on :{LAN_TLS_PORT} failed ({e}); apps will use the tailnet")
            LAN_FP = ""
    TAILNET_URL = setup_tailnet() if USE_TAILSCALE else None
    if TAILNET_URL:
        pusher.contact = TAILNET_URL  # push services want a way to reach the sender
    banner(f"{scheme}://{lan_ip}:{PORT}", TAILNET_URL)
    register_mdns(lan_ip)

    ssl_context = ensure_cert(lan_ip) if USE_HTTPS else None
    app.run(host=HOST, port=PORT, ssl_context=ssl_context)
