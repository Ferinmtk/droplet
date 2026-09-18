"""droplet — LAN file drop. Any browser on the network can send/fetch files."""

import atexit
import json
import os
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

FOLDERS = {"received": RECEIVED_DIR, "shared": SHARED_DIR}

# shown as thumbnails in the listing rather than as a filename.
# raster only — /raw serves inline, and an inline SVG can carry script.
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".avif"}

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = MAX_UPLOAD_MB * 1024 * 1024


def _secret_key() -> bytes:
    # persisted so PIN logins survive restarts
    f = BASE_DIR / ".secret_key"
    if not f.exists():
        f.write_bytes(secrets.token_bytes(32))
        f.chmod(0o600)
    return f.read_bytes()


app.secret_key = _secret_key()


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


def list_files(directory: Path) -> list[dict]:
    items = []
    for p in directory.iterdir():
        if p.is_file() and not p.name.startswith("."):
            st = p.stat()
            items.append(
                {
                    "name": p.name,
                    "size": st.st_size,
                    "mtime": int(st.st_mtime),
                    "image": p.suffix.lower() in IMAGE_SUFFIXES,
                }
            )
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


# --- PIN gate ----------------------------------------------------------------

@app.before_request
def require_pin():
    if not PIN or session.get("authed") or request.endpoint in ("login", "static"):
        return None
    if TAILNET_TRUST and tailnet_user():
        return None
    return redirect(url_for("login"))


@app.route("/login", methods=["GET", "POST"])
def login():
    if not PIN:
        return redirect(url_for("home"))
    error = None
    if request.method == "POST":
        if secrets.compare_digest(request.form.get("pin", ""), PIN):
            session["authed"] = True
            return redirect(url_for("home"))
        error = "Wrong PIN"
    return render_template("login.html", error=error)


# --- routes ------------------------------------------------------------------

@app.route("/")
def home():
    return render_template("index.html", host=socket.gethostname(), tailnet_user=tailnet_user())


@app.route("/api/files")
def api_files():
    return jsonify({name: list_files(d) for name, d in FOLDERS.items()})


@app.route("/upload", methods=["POST"])
def upload():
    saved = []
    for f in request.files.getlist("files"):
        name = secure_filename(f.filename or "")
        if not name:
            continue
        dest = unique_path(RECEIVED_DIR, name)
        f.save(dest)
        saved.append(dest.name)
    return jsonify({"saved": saved})


@app.route("/text", methods=["POST"])
def share_text():
    text = (request.form.get("text") or "").strip()
    if not text:
        return jsonify({"error": "empty"}), 400
    dest = unique_path(RECEIVED_DIR, f"text-{time.strftime('%Y%m%d-%H%M%S')}.txt")
    dest.write_text(text, encoding="utf-8")
    return jsonify({"saved": dest.name})


@app.route("/d/<folder>/<path:name>")
def download(folder, name):
    directory = FOLDERS.get(folder)
    if directory is None:
        abort(404)
    return send_from_directory(directory, name, as_attachment=True)


@app.route("/raw/<folder>/<path:name>")
def raw(folder, name):
    # inline rather than attachment, so thumbnails can render
    directory = FOLDERS.get(folder)
    if directory is None:
        abort(404)
    resolve_in(directory, name)
    return send_from_directory(directory, name)


@app.route("/delete/<folder>/<path:name>", methods=["POST"])
def delete(folder, name):
    directory = FOLDERS.get(folder)
    if directory is None:
        abort(404)
    resolve_in(directory, name).unlink()
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
            directory = FOLDERS.get(folder)
            if directory is None:
                abort(404)
            p = resolve_in(directory, name)
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
    info = ServiceInfo(
        "_http._tcp.local.",
        f"{MDNS_NAME}._http._tcp.local.",
        addresses=[socket.inet_aton(lan_ip)],
        port=PORT,
        server=f"{MDNS_NAME}.local.",
    )
    zc = Zeroconf()
    try:
        zc.register_service(info, allow_name_change=True)
    except NonUniqueNameException:
        # stale announcement from a previous run still cached on the LAN;
        # the app works fine via IP — the name frees up when the TTL expires
        print(f"  ({MDNS_NAME}.local is taken/stale — skipping mDNS this run)")
        zc.close()
        return

    def _goodbye():
        zc.unregister_service(info)
        zc.close()

    atexit.register(_goodbye)


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


if __name__ == "__main__":
    # make pkill/SIGTERM exit cleanly so the mDNS goodbye packet goes out
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))

    for d in (RECEIVED_DIR, SHARED_DIR, CERT_DIR):
        d.mkdir(parents=True, exist_ok=True)

    lan_ip = get_lan_ip()
    scheme = "https" if USE_HTTPS else "http"
    TAILNET_URL = setup_tailnet() if USE_TAILSCALE else None
    banner(f"{scheme}://{lan_ip}:{PORT}", TAILNET_URL)
    register_mdns(lan_ip)

    ssl_context = ensure_cert(lan_ip) if USE_HTTPS else None
    app.run(host=HOST, port=PORT, ssl_context=ssl_context)
