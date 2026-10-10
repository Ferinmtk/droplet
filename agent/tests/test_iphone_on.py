"""The iPhone link is on by default, and a computer without its parts runs normally and says so.

(The link itself is test_webrtc.py; installing its parts, test_install.py.)
"""

import importlib.util
import json
import logging
import os
import shutil
import subprocess
import sys
import tempfile
import time
import types
from pathlib import Path

import pytest

from droplet_agent import cli, config
from droplet_agent.webrtc import bridge, deps

AGENT = Path(__file__).resolve().parents[1]

# makes aiortc (and so the iPhone link) look not installed, in a fresh Python
BLOCK_AIORTC = """
import importlib.abc, sys
class Block(importlib.abc.MetaPathFinder):
    def find_spec(self, name, path=None, target=None):
        if name == "aiortc" or name.startswith("aiortc."):
            raise ModuleNotFoundError(f"No module named {name!r}", name=name)
        return None
sys.meta_path.insert(0, Block())
"""


def test_it_is_on_by_default_and_can_be_switched_off(isolated_home):
    assert config.DEFAULTS["iphone"]["enabled"] is True
    assert config.load()["iphone"]["enabled"] is True
    p = config.config_path()
    p.parent.mkdir(parents=True)
    p.write_text('{"iphone": {"enabled": false}}')
    cfg = config.load()
    assert cfg["iphone"]["enabled"] is False and cfg["iphone"]["app_url"].startswith("https://")


def _missing(monkeypatch, names):
    monkeypatch.setattr(deps, "_have", lambda mod: mod not in names)


def test_the_fix_is_one_command_and_leaves_pyav_out(monkeypatch):
    _missing(monkeypatch, {"aiortc"})
    assert deps.missing() == ["aiortc"]
    fix = deps.fix_command("/venv/bin/python")
    assert fix == "/venv/bin/python -m pip install --no-deps 'aiortc>=1.9'"
    _missing(monkeypatch, {"aiortc", "aioice", "OpenSSL"})
    fix = deps.fix_command("/venv/bin/python")
    assert fix == ("/venv/bin/python -m pip install aioice pyopenssl && "
                   "/venv/bin/python -m pip install --no-deps 'aiortc>=1.9'")
    ok, why = deps.available()
    assert not ok and "isn't installed" in why and "--no-deps" in why


class FakeNode:
    def __init__(self, tmp_path):
        self.identity = types.SimpleNamespace(cert_path=tmp_path / "cert.pem", key_path=tmp_path / "key.pem")
        self.port = 0
        self.webrtc = None
        self.webrtc_off = None
        self.control_ext = {}


def test_without_its_parts_the_link_stays_off_and_says_why_once(tmp_path, monkeypatch, caplog):
    _missing(monkeypatch, {"aiortc"})
    node = FakeNode(tmp_path)
    with caplog.at_level(logging.DEBUG, logger="droplet_agent"):
        assert bridge.start_bridge(node, config.load()) is None
    assert node.webrtc is None and "qr" not in node.control_ext
    assert node.webrtc_off["why"] == "missing" and "--no-deps" in node.webrtc_off["text"]
    lines = [r for r in caplog.records if "iphone" in r.getMessage()]
    assert len(lines) == 1 and lines[0].levelno == logging.WARNING


def test_an_aiortc_that_wont_load_is_reported_not_raised(tmp_path, monkeypatch):
    monkeypatch.setattr(deps, "missing", lambda: [])

    def load():
        raise ModuleNotFoundError("No module named 'av'")
    monkeypatch.setattr(deps, "load", load)
    node = FakeNode(tmp_path)
    assert bridge.start_bridge(node, config.load()) is None
    assert node.webrtc_off["why"] == "broken" and "--force-reinstall" in node.webrtc_off["text"]


def test_switched_off_it_doesnt_even_look(tmp_path, monkeypatch):
    monkeypatch.setattr(deps, "available", lambda: pytest.fail("looked for aiortc"))
    node = FakeNode(tmp_path)
    assert bridge.start_bridge(node, {"iphone": {"enabled": False}}) is None
    assert node.webrtc_off["why"] == "off"


# --- status and doctor -----------------------------------------------------------------------

def test_status_says_what_to_do(monkeypatch):
    cfg = config.load()
    _missing(monkeypatch, {"aiortc"})
    line = cli._iphone_line(cfg, None)
    assert line.startswith("not installed, so iPhones can't pair") and "pip install --no-deps" in line
    monkeypatch.setattr(deps, "missing", lambda: [])
    assert cli._iphone_line(cfg, None).startswith("ready")
    assert cli._iphone_line(cfg, {"webrtc": {"port": 1740, "connected": []}}).startswith(
        "listening on UDP port 1740")
    # installed after the agent started
    assert "restart it" in cli._iphone_line(cfg, {"webrtc": None, "webrtc_off": {"why": "missing", "text": ""}})
    assert cli._iphone_line({"iphone": {"enabled": False}}, None).startswith("off")


def test_doctor_offers_the_install(monkeypatch, capsys):
    from droplet_agent.mesh import control
    _missing(monkeypatch, {"aiortc"})
    monkeypatch.setattr(cli, "_interactive", lambda: False)
    assert cli._doctor_iphone(config.load()) == 1
    out = capsys.readouterr().out
    assert "Pairing an iPhone needs the iPhone link" in out
    assert f"{sys.executable} -m pip install --no-deps 'aiortc>=1.9'" in out

    # asked, and said yes: it runs pip into the agent's own Python, aiortc without its dependencies
    ran = []
    monkeypatch.setattr(cli, "_interactive", lambda: True)
    monkeypatch.setattr("builtins.input", lambda _prompt: "")
    monkeypatch.setattr(subprocess, "run", lambda argv, **kw: ran.append(argv) or
                        types.SimpleNamespace(returncode=0))
    cli._doctor_iphone(config.load())
    assert ran and ran[0][:3] == [sys.executable, "-m", "pip"] and "--no-deps" in ran[0] and ran[0][-1] == "aiortc>=1.9"

    # installed and running: how to pair, and nothing to fix
    monkeypatch.setattr(deps, "missing", lambda: [])
    monkeypatch.setattr(control, "call", lambda req, timeout=5: {"webrtc": {"port": 1739}})
    monkeypatch.setattr(cli, "_iphone_firewall_fix", lambda port: None)
    capsys.readouterr()
    assert cli._doctor_iphone(config.load()) == 0
    assert "Pair an iPhone" in capsys.readouterr().out


# --- the firewall ---------------------------------------------------------------------------

def test_firewalld_ports():
    fedora = "1025-65535/udp 1025-65535/tcp"
    assert cli.ports_cover(fedora, 1739, "udp") and cli.ports_cover(fedora, 1749, "tcp")
    assert not cli.ports_cover("1739-1749/tcp", 1739, "udp")
    assert cli.ports_cover("22/tcp 1739/udp", 1739, "udp") and not cli.ports_cover("1739/udp", 1740, "udp")
    assert not cli.ports_cover("", 1739, "udp")


def test_firewalld_says_the_command_only_when_closed(monkeypatch):
    listed = {"ports": ""}

    def run(argv, timeout=5):
        out = b"running" if argv[1] == "--state" else listed["ports"].encode()
        return types.SimpleNamespace(returncode=0, stdout=out)
    monkeypatch.setattr(cli.env, "which", lambda name: "/usr/bin/" + name)
    monkeypatch.setattr(cli.env, "run", run)
    assert cli._firewalld_fix("udp", 1739) == ("sudo firewall-cmd --permanent --add-port=1739-1749/udp && "
                                               "sudo firewall-cmd --reload")
    listed["ports"] = "1025-65535/udp 1025-65535/tcp"      # Fedora Workstation's zone
    assert cli._firewalld_fix("udp", 1739) is None and cli._firewall_blocks_mesh() is None
    listed["ports"] = "1739-1749/tcp"
    assert cli._firewalld_fix("udp", 1745) is not None and cli._firewall_blocks_mesh() is None
    monkeypatch.setattr(cli.env, "which", lambda name: None)
    assert cli._firewalld_fix("udp", 1739) is None


def test_ufw(tmp_path, monkeypatch):
    ufw = tmp_path / "ufw"
    ufw.mkdir()
    monkeypatch.setattr(cli, "UFW_DIR", ufw)
    monkeypatch.setattr(cli, "UFW_DEFAULTS", tmp_path / "default-ufw")
    assert cli._ufw_fix("udp", 1739) is None                       # not installed
    (ufw / "ufw.conf").write_text("ENABLED=no\n")
    assert cli._ufw_fix("udp", 1739) is None                       # off
    (ufw / "ufw.conf").write_text("ENABLED=yes\n")
    (tmp_path / "default-ufw").write_text('DEFAULT_INPUT_POLICY="DROP"\n')
    # its rules unreadable (root only, as usual): say what would open the port, in case
    assert cli._ufw_fix("udp", 1739) == "sudo ufw allow 1739:1749/udp"
    (ufw / "user.rules").write_text("### tuple ### allow tcp 1739:1749 0.0.0.0/0 any 0.0.0.0/0 in\n")
    assert cli._ufw_fix("udp", 1739) == "sudo ufw allow 1739:1749/udp"
    (ufw / "user.rules").write_text("### tuple ### allow udp 1739:1749 0.0.0.0/0 any 0.0.0.0/0 in\n")
    assert cli._ufw_fix("udp", 1741) is None
    (tmp_path / "default-ufw").write_text('DEFAULT_INPUT_POLICY="ACCEPT"\n')
    (ufw / "user.rules").write_text("")
    assert cli._ufw_fix("udp", 1739) is None


# --- a real agent -------------------------------------------------------------------------------

class RealAgent:
    """`droplet-agent run --dry-run` in a throwaway home, and droplet-agent commands beside it.
    `prelude`: Python run first in each (to make aiortc look not installed, say)."""

    def __init__(self, home: Path, prelude: str = ""):
        cfg_dir = home / "config" / "droplet-agent"
        cfg_dir.mkdir(parents=True, exist_ok=True)
        (cfg_dir / "config.json").write_text(json.dumps({"mesh": {"announce": False}}))
        self.runtime = tempfile.mkdtemp(prefix="dr", dir="/tmp")   # a short path, for the control socket
        self.env = {**os.environ, "XDG_RUNTIME_DIR": self.runtime, "PYTHONPATH": str(AGENT)}
        self.code = "import sys\n" + prelude + "\nfrom droplet_agent.cli import main\nsys.exit(main())\n"
        self.proc = subprocess.Popen([sys.executable, "-c", self.code, "run", "--dry-run", "-v"], cwd=str(AGENT),
                                     env=self.env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        self.log = ""

    def cli(self, *args) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, "-c", self.code, *args], cwd=str(AGENT), env=self.env,
                              capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL)

    def status(self, until: str = "listening on port") -> str:
        """`droplet-agent status`, once it says `until`."""
        out = ""
        for _ in range(60):
            assert self.proc.poll() is None, self.stop()
            out = self.cli("status").stdout
            if until in out:
                return out
            time.sleep(0.5)
        raise AssertionError(f"the status never said {until!r}:\n{out}\n{self.stop()}")

    def stop(self) -> str:
        if self.proc.poll() is None:
            self.proc.terminate()
        self.log = self.proc.communicate(timeout=30)[0]
        shutil.rmtree(self.runtime, ignore_errors=True)
        return self.log


def test_the_agent_runs_without_the_parts_and_says_so(isolated_home):
    """`droplet-agent run` with aiortc not importable: it starts, the mesh works, the
    status says the iPhone link isn't installed and how to fix it, and the log says it once."""
    agent = RealAgent(isolated_home, prelude=BLOCK_AIORTC)
    try:
        status = agent.status()
        assert "iphone:   not installed, so iPhones can't pair" in status
        assert "pip install --no-deps 'aiortc>=1.9'" in status
        for _ in range(20):
            qr = agent.cli("pair", "--qr")
            if "is starting" not in qr.stderr:
                break
            time.sleep(0.5)
        assert qr.returncode != 0 and "isn't installed" in qr.stdout + qr.stderr
    finally:
        out = agent.stop()
    assert agent.proc.returncode == 0, out
    assert out.count("iphone:") == 1 and "the agent runs without it" in out
    assert "Traceback" not in out


@pytest.mark.skipif(bool(deps.missing()), reason="the iPhone link's parts aren't installed here")
def test_with_its_parts_the_agent_listens_for_iphones(isolated_home):
    agent = RealAgent(isolated_home)
    try:
        status = agent.status(until="iphone:   listening on UDP port")
        assert "iphone:   listening on UDP port 17" in status, status
    finally:
        out = agent.stop()
    assert "webrtc: listening on UDP port" in out and "Traceback" not in out


@pytest.mark.skipif(bool(deps.missing()), reason="the iPhone link's parts aren't installed here")
def test_droplet_never_imports_pyav():
    """Through deps.load() (and the modules that use it), aiortc loads with the stand-in, and no
    real PyAV is imported. (Where PyAV is installed, the real one is used: this is the light install.)"""
    if importlib.util.find_spec("av") is not None:
        pytest.skip("PyAV is installed here")
    code = ("import sys\nfrom droplet_agent.webrtc import deps, transport, bridge\nrtc = deps.load()\n"
            "import av\nprint(getattr(av, 'DROPLET_STUB', False), av.__file__ if hasattr(av, '__file__') else None)\n")
    r = subprocess.run([sys.executable, "-c", code], cwd=str(AGENT), capture_output=True, text=True, timeout=60,
                       env={**os.environ, "PYTHONPATH": str(AGENT)})
    assert r.returncode == 0, r.stderr
    assert r.stdout.split() == ["True", "None"]
