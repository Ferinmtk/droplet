"""install.sh, run for real into a throwaway home, against a fake GitHub release
(and a fake hub) served from a temporary directory. Never touches the real
~/.local or ~/.config, and never installs or starts the service.

PIP_NO_DEPS keeps it offline: only the agent's own wheel is installed.
"""

from __future__ import annotations

import functools
import hashlib
import os
import shutil
import subprocess
import sys
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

AGENT = Path(__file__).resolve().parents[1]
REPO = AGENT.parent
INSTALL = AGENT / "install.sh"

sys.path.insert(0, str(REPO))
import agent_dist  # noqa: E402

pytestmark = pytest.mark.skipif(
    not shutil.which("curl") or not shutil.which("python3"), reason="needs curl and python3")
linux_only = pytest.mark.skipif(sys.platform == "darwin", reason="Linux's desktop entries and systemd")
mac_only = pytest.mark.skipif(sys.platform != "darwin", reason="needs a Mac")


class Quiet(SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass


@pytest.fixture
def server(tmp_path):
    """An http server for tmp_path/www; yields (root dir, base URL)."""
    root = tmp_path / "www"
    root.mkdir()
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Quiet, directory=str(root)))
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()
    yield root, f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()
    httpd.server_close()


@pytest.fixture(scope="module")
def wheel():
    return agent_dist.build_wheel()


def publish(root: Path, where: str, wheel: bytes, sums: str | None = "good"):
    """A release's assets, as GitHub serves them at <releases>/<where>/."""
    d = root / where
    d.mkdir(parents=True)
    (d / "droplet-agent.whl").write_bytes(wheel)
    digest = hashlib.sha256(wheel).hexdigest() if sums == "good" else "0" * 64
    if sums is not None:
        (d / "SHA256SUMS.txt").write_text(f"{'1' * 64}  droplet-android.apk\n{digest}  droplet-agent.whl\n")


def run(home: Path, releases: str, *args, script: Path = INSTALL, env_extra: dict | None = None):
    env = {
        "HOME": str(home),
        "PATH": os.environ["PATH"],
        "LANG": "C.UTF-8",
        "DROPLET_RELEASES_URL": releases,
        "PIP_NO_DEPS": "1",
        "PIP_NO_INDEX": "1",
        "PIP_NO_CACHE_DIR": "1",
        **(env_extra or {}),
    }
    # no WAYLAND_DISPLAY/DISPLAY: the tray isn't started; no session bus either
    return subprocess.run(["sh", str(script), *args], env=env, capture_output=True, text=True,
                          timeout=300, stdin=subprocess.DEVNULL)


@linux_only
def test_installs_from_the_latest_release_without_a_hub(tmp_path, server, wheel):
    root, url = server
    publish(root, "latest/download", wheel)
    home = tmp_path / "home"
    home.mkdir()
    r = run(home, url, "--no-service")
    out = r.stdout + r.stderr
    assert r.returncode == 0, out
    assert f"Downloading the agent from {url}/latest/download" in out
    assert "Checked its SHA-256" in out
    assert "No hub: this computer pairs with your devices directly." in out
    assert "Pair with your phone" in out and "droplet-agent pair" in out
    assert "Service not installed" in out

    data = home / ".local/share/droplet-agent"
    agent = data / "bin/droplet-agent"
    assert agent.exists()
    assert os.readlink(home / ".local/bin/droplet-agent") == str(agent)
    assert (home / ".local/share/applications/io.github.ferinmtk.DropletAgent.desktop").exists()
    assert (home / ".config/autostart/io.github.ferinmtk.DropletAgent.Tray.desktop").exists()
    assert (home / ".config/systemd/user/droplet-agent.service").exists()
    # the hub setup never ran: nothing linked, nothing saved
    assert not (home / ".config/droplet-agent/config.json").exists()
    # the package data came along
    icon = subprocess.run([str(data / "bin/python"), "-c",
                           "import droplet_agent, pathlib; "
                           "print((pathlib.Path(droplet_agent.__file__).parent / 'tray_icon.bin').stat().st_size)"],
                          capture_output=True, text=True)
    assert icon.returncode == 0 and int(icon.stdout) > 0, icon.stderr

    # again: an upgrade, in place
    r = run(home, url, "--no-service", "--no-tray")
    assert r.returncode == 0, r.stdout + r.stderr
    assert "Creating" not in r.stdout
    assert not (home / ".config/autostart/io.github.ferinmtk.DropletAgent.Tray.desktop").exists()


@linux_only
def test_the_window_is_installed_only_from_a_desktop_session(tmp_path, server, wheel):
    root, url = server
    publish(root, "latest/download", wheel)
    home = tmp_path / "home"
    home.mkdir()
    # no desktop (the test above): not even tried
    r = run(home, url, "--no-service", "--no-tray")
    assert r.returncode == 0, r.stdout + r.stderr
    assert "Droplet's window" not in r.stdout
    # a desktop session: it's tried. Offline, PySide6 can't come, and that's not fatal
    desktop = {"WAYLAND_DISPLAY": "wayland-test"}
    r = run(home, url, "--no-service", "--no-tray", env_extra=desktop)
    assert r.returncode == 0, r.stdout + r.stderr
    assert "Installing Droplet's window" in r.stdout
    assert "Couldn't install Droplet's window; the tray and the commands work without it." in r.stdout
    assert (home / ".local/share/droplet-agent/bin/droplet-agent").exists()
    # and --no-app leaves it out
    r = run(home, url, "--no-service", "--no-tray", "--no-app", env_extra=desktop)
    assert r.returncode == 0, r.stdout + r.stderr
    assert "Droplet's window" not in r.stdout


def test_the_wheel_offers_the_window_as_an_extra(wheel):
    import io
    import zipfile
    z = zipfile.ZipFile(io.BytesIO(wheel))
    meta = z.read(f"droplet_agent-{agent_dist.version()}.dist-info/METADATA").decode()
    assert "Provides-Extra: app" in meta
    assert 'Requires-Dist: PySide6-Essentials>=6.6; extra == "app"' in meta
    assert "droplet_agent/app/window.py" in z.namelist()


def test_refuses_a_wheel_that_doesnt_match_the_sums(tmp_path, server, wheel):
    root, url = server
    publish(root, "latest/download", wheel, sums="bad")
    home = tmp_path / "home"
    home.mkdir()
    r = run(home, url, "--no-service")
    assert r.returncode != 0
    assert "doesn't match the release's SHA256SUMS.txt" in r.stderr
    assert not (home / ".local/share/droplet-agent").exists()


def test_a_given_release_and_no_sums(tmp_path, server, wheel):
    root, url = server
    publish(root, "download/v9.9.9", wheel, sums=None)
    home = tmp_path / "home"
    home.mkdir()
    r = run(home, url, "--no-service", "--no-tray", "--release", "v9.9.9")
    assert r.returncode == 0, r.stdout + r.stderr
    assert f"{url}/download/v9.9.9" in r.stdout
    assert "no SHA256SUMS.txt" in r.stdout


def test_a_release_without_the_agent(tmp_path, server):
    root, url = server
    (root / "latest/download").mkdir(parents=True)
    r = run(tmp_path, url, "--no-service")
    assert r.returncode != 0
    assert "does that release have the Linux agent?" in r.stderr


def test_hub_options_need_a_hub(tmp_path, server):
    _, url = server
    for opt in (["--code", "123456"], ["--name", "slim"], ["--pin", "1234"]):
        r = run(tmp_path, url, *opt)
        assert r.returncode != 0
        assert "link to a hub" in r.stderr


def test_a_hub_still_serves_the_agent(tmp_path, server, wheel):
    """The hub path as before: an unserved copy fetches the hub's own script,
    which downloads the hub's wheel and then links to the hub."""
    root, url = server
    (root / "agent").mkdir()
    script = INSTALL.read_text()
    assert agent_dist.HUB_PLACEHOLDER in script and agent_dist.WHEEL_PLACEHOLDER in script
    script = script.replace(agent_dist.HUB_PLACEHOLDER, f"DROPLET_HUB='{url}'")
    script = script.replace(agent_dist.WHEEL_PLACEHOLDER, f"DROPLET_WHEEL='{agent_dist.wheel_name()}'")
    (root / "agent/install.sh").write_text(script)
    (root / "agent" / agent_dist.wheel_name()).write_bytes(wheel)
    home = tmp_path / "home"
    home.mkdir()
    # nothing at the release URL: it must not be used
    r = run(home, url + "/nothing", "--hub", url, "--code", "123456", "--no-service", "--no-tray")
    out = r.stdout + r.stderr
    assert f"Downloading the agent from {url}\n" in out
    assert (home / ".local/share/droplet-agent/bin/droplet-agent").exists()
    # then it ran `droplet-agent setup` against the hub, which this fake one can't answer
    assert r.returncode != 0
    assert "Pair with your phone" not in out


@mac_only
def test_installs_on_a_mac(tmp_path, server, wheel):
    import plistlib
    root, url = server
    publish(root, "latest/download", wheel)
    home = tmp_path / "home"
    home.mkdir()
    r = run(home, url, "--no-service")
    out = r.stdout + r.stderr
    assert r.returncode == 0, out
    assert "Checked its SHA-256" in out
    # offline, PySide6 can't come: not fatal, and said
    assert "Installing Droplet's window and menu bar icon" in out
    assert "Couldn't install Droplet's window" in out
    assert "Service not loaded" in out
    assert "this Mac, then accept here" in out and "Accessibility" in out

    agent = home / ".local/share/droplet-agent/bin/droplet-agent"
    assert agent.exists()
    assert os.readlink(home / ".local/bin/droplet-agent") == str(agent)
    assert ".local/bin" in (home / ".zprofile").read_text()
    plist = plistlib.loads((home / "Library/LaunchAgents/io.github.ferinmtk.DropletAgent.plist").read_bytes())
    assert plist["ProgramArguments"] == [str(agent), "run"]
    exe = home / "Applications/Droplet.app/Contents/MacOS/Droplet"
    assert os.access(exe, os.X_OK) and str(agent) in exe.read_text()
    # nothing of Linux's
    assert not (home / ".config/systemd").exists()
    assert not (home / ".local/share/applications").exists()
    assert not (home / ".config/autostart").exists()
    # no menu bar icon without PySide6
    assert not (home / "Library/LaunchAgents/io.github.ferinmtk.DropletAgent.Menu.plist").exists()

    # again, without the menu bar: an upgrade in place, and Droplet.app goes
    r = run(home, url, "--no-service", "--no-tray")
    assert r.returncode == 0, r.stdout + r.stderr
    assert "Creating" not in r.stdout
    assert not (home / "Applications/Droplet.app").exists()
    assert (home / ".zprofile").read_text().count(".local/bin") == 1
