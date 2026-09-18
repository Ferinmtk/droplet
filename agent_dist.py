"""Serves the Linux agent from the hub, so installing it needs nothing but the hub.

    curl -fsSL https://<hub>/agent/install.sh | sh -s -- --code 123456

- GET /agent/install.sh: agent/install.sh with this hub's URL filled in,
  taken from the request (the address the script was fetched from).
- GET /agent/<wheel>.whl: the agent as a wheel, which pip installs without
  building anything. Its dependencies still come from PyPI.
- GET /agent/droplet-agent.tar.gz: the same as a source archive.

Both packages are built in memory from the agent/ directory, and rebuilt
when a file there changes.
"""

import base64
import hashlib
import io
import re
import tarfile
import threading
import time
import zipfile
from pathlib import Path

from flask import Response, abort, request

AGENT_DIR = Path(__file__).parent / "agent"
PACKAGE = AGENT_DIR / "droplet_agent"
HUB_PLACEHOLDER = "DROPLET_HUB=''  # filled in by the hub that serves this script"
WHEEL_PLACEHOLDER = "DROPLET_WHEEL=''  # likewise"
# what may be written into a shell script: a scheme and a plain host[:port]
SAFE_HUB = re.compile(r"^https?://(?:[A-Za-z0-9.-]+|\[[0-9A-Fa-f:.]+\])(?::\d{1,5})?$")
FIXED_TIME = (1980, 1, 1, 0, 0, 0)  # reproducible archives: same files, same bytes


def version() -> str:
    m = re.search(r'^__version__ = "([^"]+)"', (PACKAGE / "__init__.py").read_text(), re.M)
    return m.group(1) if m else "0"


def wheel_name() -> str:
    return f"droplet_agent-{version()}-py3-none-any.whl"


def _dependencies() -> list[str]:
    text = (AGENT_DIR / "pyproject.toml").read_text()
    m = re.search(r"^dependencies = \[(.*?)\]", text, re.M | re.S)
    return re.findall(r'"([^"]+)"', m.group(1)) if m else []


def _requires_python() -> str:
    m = re.search(r'^requires-python = "([^"]+)"', (AGENT_DIR / "pyproject.toml").read_text(), re.M)
    return m.group(1) if m else ">=3.9"


def package_files() -> list[tuple[str, Path]]:
    """(path inside the package, file on disk) for every module. Symlinks (mediactl.py) are followed."""
    out = []
    for p in sorted(PACKAGE.rglob("*.py")):
        if "__pycache__" in p.parts:
            continue
        out.append((p.relative_to(AGENT_DIR).as_posix(), p))
    return out


def build_wheel() -> bytes:
    ver = version()
    dist_info = f"droplet_agent-{ver}.dist-info"
    readme = (AGENT_DIR / "README.md").read_text() if (AGENT_DIR / "README.md").exists() else ""
    metadata = "\n".join([
        "Metadata-Version: 2.1",
        "Name: droplet-agent",
        f"Version: {ver}",
        "Summary: Lets your other devices control this Linux computer through droplet",
        f"Requires-Python: {_requires_python()}",
        *[f"Requires-Dist: {d}" for d in _dependencies()],
        "Description-Content-Type: text/markdown",
        "",
        readme,
    ])
    files: list[tuple[str, bytes]] = [(name, path.read_bytes()) for name, path in package_files()]
    files += [
        (f"{dist_info}/METADATA", metadata.encode()),
        (f"{dist_info}/WHEEL", b"Wheel-Version: 1.0\nGenerator: droplet-hub\nRoot-Is-Purelib: true\nTag: py3-none-any\n"),
        (f"{dist_info}/entry_points.txt", b"[console_scripts]\ndroplet-agent = droplet_agent.cli:main\n"),
    ]
    record = []
    for name, data in files:
        digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode()
        record.append(f"{name},sha256={digest},{len(data)}")
    record.append(f"{dist_info}/RECORD,,")
    files.append((f"{dist_info}/RECORD", ("\n".join(record) + "\n").encode()))

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, data in files:
            info = zipfile.ZipInfo(name, FIXED_TIME)
            info.external_attr = 0o644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            zf.writestr(info, data)
    return buf.getvalue()


def build_sdist() -> bytes:
    root = f"droplet-agent-{version()}"
    entries = [(name, path) for name, path in package_files()]
    for extra in ("pyproject.toml", "README.md", "install.sh"):
        if (AGENT_DIR / extra).exists():
            entries.append((extra, AGENT_DIR / extra))
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz", format=tarfile.PAX_FORMAT) as tar:
        for name, path in sorted(entries):
            data = path.read_bytes()  # follows the mediactl.py symlink
            info = tarfile.TarInfo(f"{root}/{name}")
            info.size = len(data)
            info.mode = 0o755 if name.endswith(".sh") else 0o644
            info.mtime = 315532800  # 1980-01-01
            tar.addfile(info, io.BytesIO(data))
    return buf.getvalue()


_cache: dict = {"key": None, "wheel": b"", "sdist": b""}
_lock = threading.Lock()


def _built() -> dict:
    """The current wheel and archive, rebuilt when anything under agent/ changed."""
    watched = [p for _, p in package_files()] + [AGENT_DIR / "pyproject.toml", AGENT_DIR / "README.md"]
    key = tuple((str(p), p.stat().st_mtime_ns) for p in watched if p.exists())
    with _lock:
        if _cache["key"] != key:
            _cache.update(key=key, wheel=build_wheel(), sdist=build_sdist(), built=time.time())
        return dict(_cache)


def hub_url() -> str:
    """This hub's public URL, as the client reached it.

    Behind `tailscale serve`, requests arrive over loopback with
    X-Forwarded-Proto: https and the tailnet name as Host.
    """
    scheme = request.scheme
    if request.remote_addr in ("127.0.0.1", "::1") and request.headers.get("X-Forwarded-Proto") in ("http", "https"):
        scheme = request.headers["X-Forwarded-Proto"]
    return f"{scheme}://{request.host}"


def render_install_script() -> str:
    script = (AGENT_DIR / "install.sh").read_text()
    url = hub_url()
    if not SAFE_HUB.match(url):
        # a Host header with anything odd in it never reaches a shell script;
        # the script then asks for --hub
        url = ""
    script = script.replace(HUB_PLACEHOLDER, f"DROPLET_HUB='{url}'", 1)
    return script.replace(WHEEL_PLACEHOLDER, f"DROPLET_WHEEL='{wheel_name()}'", 1)


def register(ctx):
    app = ctx.app

    @app.route("/agent/install.sh")
    def agent_install_script():
        if not (AGENT_DIR / "install.sh").exists():
            abort(404)
        resp = Response(render_install_script(), mimetype="text/x-shellscript")
        resp.headers["Cache-Control"] = "no-store"
        return resp

    @app.route("/agent/<name>")
    def agent_package(name):
        if not PACKAGE.is_dir():
            abort(404)  # e.g. a Docker image built without agent/
        if name == wheel_name():
            data, mime = _built()["wheel"], "application/zip"
        elif name == "droplet-agent.tar.gz":
            data, mime = _built()["sdist"], "application/gzip"
        else:
            abort(404)
        resp = Response(data, mimetype=mime)
        resp.headers["Content-Disposition"] = f'attachment; filename="{name}"'
        resp.headers["Cache-Control"] = "no-cache"
        return resp
