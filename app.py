"""droplet — LAN file drop. Any browser on the network can send/fetch files."""

import atexit
import os
import secrets
import signal
import socket
import sys
from pathlib import Path

from flask import (
    Flask,
    abort,
    jsonify,
    redirect,
    render_template,
    request,
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
PIN = os.environ.get("DROPLET_PIN", "")
USE_HTTPS = os.environ.get("DROPLET_HTTPS", "") not in ("", "0", "false")
MDNS_NAME = os.environ.get("DROPLET_NAME", "droplet")
MAX_UPLOAD_MB = int(os.environ.get("DROPLET_MAX_MB", "1024"))

FOLDERS = {"received": RECEIVED_DIR, "shared": SHARED_DIR}

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
            items.append({"name": p.name, "size": st.st_size, "mtime": int(st.st_mtime)})
    items.sort(key=lambda f: f["mtime"], reverse=True)
    return items


# --- PIN gate ----------------------------------------------------------------

@app.before_request
def require_pin():
    if not PIN or session.get("authed") or request.endpoint in ("login", "static"):
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
    return render_template("index.html", host=socket.gethostname())


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


@app.route("/d/<folder>/<path:name>")
def download(folder, name):
    directory = FOLDERS.get(folder)
    if directory is None:
        abort(404)
    return send_from_directory(directory, name, as_attachment=True)


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

def banner(url: str):
    print()
    print("  💧 droplet — LAN file drop")
    print(f"     {url}")
    print(f"     http{'s' if USE_HTTPS else ''}://{MDNS_NAME}.local:{PORT}  (mDNS-capable devices)")
    print(f"     PIN: {'required' if PIN else 'off (set DROPLET_PIN to enable)'}")
    print(f"     folders: {RECEIVED_DIR}  |  {SHARED_DIR}")
    print()
    try:
        import qrcode

        qr = qrcode.QRCode(border=1)
        qr.add_data(url)
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
    banner(f"{scheme}://{lan_ip}:{PORT}")
    register_mdns(lan_ip)

    ssl_context = ensure_cert(lan_ip) if USE_HTTPS else None
    app.run(host=HOST, port=PORT, ssl_context=ssl_context)
