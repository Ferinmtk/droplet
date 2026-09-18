"""Screenshots: capture the screen as PNG.

Tried in order: the xdg-desktop-portal Screenshot interface (non-interactive;
KDE and GNOME may still ask once), then the desktop's own tool (spectacle on
KDE, gnome-screenshot on GNOME, niri's on niri, grim on wlroots), then the
other tools found, and ImageMagick's import on X11. The first that gives a
PNG wins, and a method that failed isn't tried again until the agent restarts.
"""

from __future__ import annotations

import logging
import os
import struct
import tempfile
import threading
import time
import zlib
from pathlib import Path
from urllib.parse import unquote, urlparse

from . import env
from . import portal as xdp

log = logging.getLogger("droplet_agent.screenshot")

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
PORTAL_IFACE = "org.freedesktop.portal.Screenshot"
PORTAL_TIMEOUT = 30  # seconds to wait for the portal, which may be showing a dialog
MAX_BYTES = 200 * 1024 * 1024


def _tool_commands(out: str) -> list[tuple[str, list[str]]]:
    """(name, argv) for each capture tool, writing to `out`."""
    cmds = [
        ("spectacle", ["spectacle", "-b", "-n", "-f", "-o", out]),
        ("gnome-screenshot", ["gnome-screenshot", "-f", out]),
        ("grim", ["grim", out]),
        # niri also copies the picture to the clipboard (as an image, which
        # clipboard sync ignores)
        ("niri", ["niri", "msg", "action", "screenshot-screen", "--write-to-disk", "true",
                  "--show-pointer", "false", "--path", out]),
    ]
    if not env.is_wayland():
        cmds.append(("import", ["import", "-window", "root", out]))
    return cmds


def _niri_running() -> bool:
    return "niri" in env.desktops()


def methods(configured=None) -> list[str]:
    """Names of the methods that could work here, in the order they're tried. Read-only."""
    if configured:
        return ["configured"] if env.which(str(configured[0])) else []
    out = []
    if env.is_wayland() and xdp.read_property(PORTAL_IFACE, "version") is not None:
        out.append("portal")
    desks = env.desktops()
    native = {"kde": "spectacle", "gnome": "gnome-screenshot", "niri": "niri", "sway": "grim",
              "hyprland": "grim", "river": "grim", "labwc": "grim", "wayfire": "grim"}
    mine = {native[d] for d in desks if d in native}
    tools = [name for name, argv in _tool_commands("x.png")
             if env.which(argv[0]) and (name != "niri" or _niri_running())]
    # the desktop's own tool first: spectacle under niri, say, is a poor guess
    out += sorted(tools, key=lambda n: n not in mine)
    return out


def fake_png(width: int = 4, height: int = 3) -> bytes:
    """A small valid PNG (droplet blue), for the dry run and the tests."""
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    row = b"\x00" + bytes((0x3B, 0x82, 0xF6)) * width
    return (PNG_MAGIC + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(row * height)) + chunk(b"IEND", b""))


class Screenshotter:
    def __init__(self, configured=None, fake: bool = False):
        self.configured = configured
        self.fake = fake
        self.broken: set[str] = set()
        self._lock = threading.Lock()  # one capture at a time

    def capture(self) -> tuple[bytes | None, str]:
        """(PNG bytes, which method or why it failed)."""
        if self.fake:
            return fake_png(), "fake capture (dry run)"
        with self._lock:
            reasons = []
            for name in methods(self.configured):
                if name in self.broken:
                    continue
                try:
                    data = self._portal() if name == "portal" else self._tool(name)
                except Exception as e:
                    data, why = None, str(e)
                else:
                    why = "no PNG came back"
                if data:
                    return data, name
                reasons.append(f"{name}: {why}")
                log.warning("screenshot with %s failed: %s", name, why)
                self.broken.add(name)
            return None, "; ".join(reasons) or "no screenshot tool found"

    def _tool(self, name: str) -> bytes | None:
        with tempfile.TemporaryDirectory(prefix="droplet-shot-") as d:
            out = os.path.join(d, "shot.png")
            if name == "configured":
                argv = [a.replace("{out}", out) for a in self.configured]
            else:
                argv = dict(_tool_commands(out))[name]
            r = env.run(argv, timeout=30)
            if r is None:
                raise RuntimeError(f"{argv[0]} didn't finish")
            if r.returncode != 0:
                raise RuntimeError(r.stderr.decode(errors="replace").strip() or f"exit status {r.returncode}")
            # niri answers before the file is written; wait until it's whole
            last = -1
            for _ in range(50):
                size = os.path.getsize(out) if os.path.exists(out) else 0
                if size and size == last:
                    break
                last = size
                time.sleep(0.1)
            return _read_png(Path(out))

    def _portal(self) -> bytes | None:
        started = time.time()
        p = xdp.Portal()  # no app id: unsandboxed callers aren't asked, registered ones may be
        try:
            res = p.request(PORTAL_IFACE, "Screenshot", "sa{sv}", ("",),
                            {"interactive": ("b", False)}, timeout=PORTAL_TIMEOUT)
        finally:
            p.close()
        uri = res.get("uri") or ""
        if not uri.startswith("file://"):
            raise RuntimeError(f"unexpected answer {uri!r}")
        path = Path(unquote(urlparse(uri).path))
        try:
            return _read_png(path)
        finally:
            # the portal saved it (often into ~/Pictures) just for us; don't
            # leave a pile of them behind. Only a file made by this request.
            try:
                if path.is_file() and path.stat().st_mtime >= started - 2:
                    path.unlink()
            except OSError:
                pass


def _read_png(path: Path) -> bytes | None:
    try:
        if path.stat().st_size > MAX_BYTES:
            return None
        data = path.read_bytes()
    except OSError:
        return None
    return data if data.startswith(PNG_MAGIC) else None
