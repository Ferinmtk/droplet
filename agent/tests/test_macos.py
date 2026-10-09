"""macOS support. The pure parts (plists, the icon, parsing pmset and route, the Quartz
backend against a fake CoreGraphics) run everywhere; the parts that call the real
system run on a Mac only (CI's macos-latest)."""

import ctypes
import os
import plistlib
import socket
import struct
import sys
import threading
import time
from pathlib import Path

import pytest

from droplet_agent import macos, tray
from droplet_agent.inject import InputHandler, quartz
from droplet_agent.mesh.node import mac_gateway_from_route

mac_only = pytest.mark.skipif(sys.platform != "darwin", reason="needs a Mac")


# --- launchd and Droplet.app -----------------------------------------------------------

def test_the_agents_launch_agent():
    d = plistlib.loads(macos.launch_agent(macos.AGENT_LABEL, ["/x/droplet-agent", "run"], keep_alive=True))
    assert d["Label"] == "io.github.ferinmtk.DropletAgent"
    assert d["ProgramArguments"] == ["/x/droplet-agent", "run"]
    assert d["RunAtLoad"] is True and d["KeepAlive"] is True
    assert d["LimitLoadToSessionType"] == "Aqua"
    assert d["EnvironmentVariables"]["LANG"] == "en_US.UTF-8"
    assert d["StandardErrorPath"].endswith("Library/Logs/droplet-agent.log")


def test_the_menu_stays_closed_when_quit():
    d = plistlib.loads(macos.launch_agent(macos.MENU_LABEL, ["/x/droplet-agent", "tray"], keep_alive=False))
    assert d["Label"] == "io.github.ferinmtk.DropletAgent.Menu"
    assert d["KeepAlive"] == {"SuccessfulExit": False}
    assert d["StandardOutPath"].endswith("droplet-menu.log")


def test_launch_agents_live_in_the_users_library(isolated_home, monkeypatch):
    monkeypatch.setenv("HOME", str(isolated_home))
    path = macos.write_launch_agent(macos.AGENT_LABEL, ["a", "run"], keep_alive=True)
    assert path == isolated_home / "Library/LaunchAgents/io.github.ferinmtk.DropletAgent.plist"
    assert plistlib.loads(path.read_bytes())["ProgramArguments"] == ["a", "run"]


def test_the_app_bundle(isolated_home, monkeypatch):
    monkeypatch.setenv("HOME", str(isolated_home))
    app = macos.install_app_bundle(["/opt/my agent/droplet-agent"])
    assert app == isolated_home / "Applications/Droplet.app"
    info = plistlib.loads((app / "Contents/Info.plist").read_bytes())
    assert info["CFBundleExecutable"] == "Droplet" and info["CFBundleIdentifier"] == "io.github.ferinmtk.Droplet"
    exe = app / "Contents/MacOS/Droplet"
    assert os.access(exe, os.X_OK)
    assert "exec '/opt/my agent/droplet-agent' open" in exe.read_text()
    icns = (app / "Contents/Resources/Droplet.icns").read_bytes()
    assert icns[:4] == b"icns" and struct.unpack(">I", icns[4:8])[0] == len(icns)
    kinds, pos = [], 8
    while pos < len(icns):
        kind, size = icns[pos:pos + 4], struct.unpack(">I", icns[pos + 4:pos + 8])[0]
        assert icns[pos + 8:pos + 16] == b"\x89PNG\r\n\x1a\n"
        kinds.append(kind)
        pos += size
    assert pos == len(icns)
    assert b"ic09" in kinds and b"icp5" in kinds   # 512 px for the Dock and Finder, 32 for lists
    assert macos.remove_app_bundle() and not app.exists()


def test_the_512px_icon_ships_with_the_package():
    assert macos.ICON_FILE.read_bytes()[16:24] == struct.pack(">II", 512, 512)


def test_on_a_mac_the_tray_functions_mean_the_menu_bar(isolated_home, monkeypatch):
    monkeypatch.setenv("HOME", str(isolated_home))
    monkeypatch.setattr(tray, "MAC", True)
    assert tray.autostart_path() == isolated_home / "Library/LaunchAgents/io.github.ferinmtk.DropletAgent.Menu.plist"
    assert "launchctl kickstart gui/" in tray.start_hint()
    title, body = tray.where_text(None)
    assert "menu bar" in title and "launchctl" in body


# --- parsing the system's tools ---------------------------------------------------------

PMSET_CHARGING = """Now drawing from 'AC Power'
 -InternalBattery-0 (id=4653155)\t87%; charging; 0:42 remaining present: true
"""
PMSET_BATTERY = """Now drawing from 'Battery Power'
 -InternalBattery-0 (id=4653155)\t54%; discharging; 3:10 remaining present: true
"""
PMSET_DESKTOP = "Now drawing from 'AC Power'\n"


def test_battery_from_pmset():
    assert macos.battery_from_pmset(PMSET_CHARGING) == {"level": 87, "charging": True}
    assert macos.battery_from_pmset(PMSET_BATTERY) == {"level": 54, "charging": False}
    held = PMSET_CHARGING.replace("charging; 0:42 remaining", "AC attached; not charging")
    assert macos.battery_from_pmset(held) == {"level": 87, "charging": True}
    assert macos.battery_from_pmset(PMSET_DESKTOP) is None   # a Mac mini


def test_volume_from_osascript():
    assert macos.volume_from_osascript("50,false\n") == {"level": 0.5, "muted": False}
    assert macos.volume_from_osascript("100,true") == {"level": 1.0, "muted": True}
    assert macos.volume_from_osascript("missing value,missing value") is None
    assert macos.volume_command(level=0.37) == ["osascript", "-e", "set volume output volume 37"]
    assert macos.volume_command(muted=True) == ["osascript", "-e", "set volume output muted true"]


ROUTE = """   route to: default
destination: default
       mask: default
    gateway: 192.168.43.1
  interface: en0
      flags: <UP,GATEWAY,DONE,STATIC,PRCLONING>
"""


def test_the_gateway_from_route():
    assert mac_gateway_from_route(ROUTE) == ["192.168.43.1"]
    assert mac_gateway_from_route(ROUTE.replace("en0", "utun4")) == []   # a VPN's
    assert mac_gateway_from_route("route: writing to routing socket: not in table") == []


# --- media on a Mac: volume through osascript, the rest through the media keys -------------

@pytest.fixture
def mac_media(monkeypatch):
    from droplet_agent import mediastate
    monkeypatch.setattr(mediastate, "MAC", True)
    monkeypatch.setattr(mediastate, "_mac_keys_allowed", lambda: True)
    monkeypatch.setattr(macos, "read_volume", lambda: {"level": 0.25, "muted": False})
    calls = []

    def runner(*argv):
        import subprocess
        calls.append(list(argv))
        return subprocess.CompletedProcess(argv, 0, "", "")
    return mediastate.Media(runner=runner), calls


def test_a_macs_media_state(mac_media):
    media, _ = mac_media
    snap = media.snapshot()
    assert snap["volume"] == {"level": 0.25, "muted": False}
    assert snap["active"] == "media-keys" and snap["players"][0]["can_seek"] is False


def test_a_macs_media_actions(mac_media):
    media, calls = mac_media
    assert media.act("volume", value=0.5) is None
    assert media.act("mute") is None              # toggles: it wasn't muted
    assert media.act("mute", value=False) is None
    assert media.act("play-pause") is None
    assert media.act("next", player="anything") is None
    assert calls == [["osascript", "-e", "set volume output volume 50"],
                     ["osascript", "-e", "set volume output muted true"],
                     ["osascript", "-e", "set volume output muted false"],
                     ["media-key", "MediaPlayPause"], ["media-key", "MediaNext"]]
    assert "isn't possible on a Mac" in media.act("seek", value=10)
    assert media.act("volume", value="loud") and media.act("mute", value=3)
    assert len(calls) == 5


# --- the Quartz backend, against a fake CoreGraphics -------------------------------------

class FakeCG:
    source = "src"

    def __init__(self):
        self.pos = (100.0, 100.0)
        self.posted = []
        self.media = []

    def location(self):
        return self.pos

    def screen(self):
        return (0.0, 0.0, 1440.0, 900.0)

    def mouse_event(self, source, kind, point, button):
        return {"type": "mouse", "kind": kind, "x": point.x, "y": point.y, "button": button, "fields": {}}

    def key_event(self, source, code, down):
        return {"type": "key", "code": code, "down": down, "flags": None, "text": None}

    def scroll_event(self, source, unit, count, w1, w2, w3):
        return {"type": "scroll", "unit": unit, "v": w1, "h": w2}

    def set_field(self, ev, field, value):
        ev["fields"][field] = value

    def set_flags(self, ev, flags):
        ev["flags"] = flags

    def set_unicode(self, ev, n, buf):
        ev["text"] = bytes(bytearray(b for u in buf[:n] for b in struct.pack("<H", u))).decode("utf-16-le")

    def send(self, ev):
        self.posted.append(ev)
        if ev["type"] == "mouse":
            self.pos = (ev["x"], ev["y"])

    def media_key(self, key):
        self.media.append(key)


def backend(clock=None):
    cg = FakeCG()
    t = [0.0]
    b = quartz.QuartzBackend(cg=cg, clock=clock or (lambda: t[0]))
    return b, cg, t


def test_moves_are_relative_and_stay_on_screen():
    b, cg, _ = backend()
    b.move(10, -20)
    ev = cg.posted[-1]
    assert (ev["kind"], ev["x"], ev["y"]) == (quartz.MOVED, 110.0, 80.0)
    assert ev["fields"] == {quartz.DELTA_X: 10, quartz.DELTA_Y: -20}
    b.move(-5000, 5000)
    assert (cg.posted[-1]["x"], cg.posted[-1]["y"]) == (0.0, 899.0)


def test_a_drag_is_a_dragged_move():
    b, cg, _ = backend()
    b.button("left", True)
    b.move(5, 5)
    b.button("left", False)
    kinds = [e["kind"] for e in cg.posted]
    assert kinds == [quartz.LEFT_DOWN, quartz.LEFT_DRAGGED, quartz.LEFT_UP]


def test_two_quick_clicks_are_a_double_click():
    b, cg, t = backend()
    h = InputHandler(b)
    h.apply([{"k": "click", "b": "left", "n": 2}])
    states = [e["fields"][quartz.CLICK_STATE] for e in cg.posted]
    assert states == [1, 1, 2, 2]
    t[0] += 2   # much later: a single click again
    h.apply([{"k": "click", "b": "right"}])
    assert [e["fields"][quartz.CLICK_STATE] for e in cg.posted[4:]] == [1, 1]
    assert [e["kind"] for e in cg.posted[4:]] == [quartz.RIGHT_DOWN, quartz.RIGHT_UP]


def test_scrolling_goes_the_protocols_way():
    b, cg, _ = backend()
    b.scroll(0, 3)    # down three lines
    b.scroll(-1, 0)   # left one
    assert [(e["v"], e["h"]) for e in cg.posted] == [(-3, 0), (0, 1)]


def test_keys_and_shortcuts():
    b, cg, _ = backend()
    assert b.key("c", ["meta"])
    assert [(e["code"], e["down"], e["flags"]) for e in cg.posted] == [(0x08, True, 0x100000),
                                                                        (0x08, False, 0x100000)]
    assert b.key("ArrowLeft", ["shift", "alt"])
    assert cg.posted[-1]["code"] == 0x7B and cg.posted[-1]["flags"] == 0x20000 | 0x80000
    assert b.key("MediaPlayPause", [])
    assert cg.media == [16]
    assert not b.key("ContextMenu", [])
    assert not b.key("NoSuchKey", [])


def test_text_is_unicode_and_newlines_are_keys():
    b, cg, _ = backend()
    b.text("héllo 👋\nnext\tline")
    typed = [(e["code"], e["text"]) for e in cg.posted if e["down"]]
    assert typed == [(0, "héllo 👋"), (quartz.RETURN, None), (0, "next"), (quartz.TAB, None), (0, "line")]
    assert all(e["flags"] == 0 for e in cg.posted)


def test_long_text_is_cut_without_splitting_a_pair():
    chunks = quartz.utf16_chunks("a" * 19 + "👋" + "b")
    assert [len(c) for c in chunks] == [19, 3]
    assert chunks[1][0] == 0xD83D


def test_the_quartz_backend_is_offered_only_on_a_mac():
    ok, why = quartz.usable()
    if sys.platform != "darwin":
        assert not ok and why == "not a Mac"


# --- the menu bar -------------------------------------------------------------------------

def test_the_menu_bar_shows_the_trays_menu(monkeypatch):
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
    pytest.importorskip("PySide6.QtWidgets")
    from PySide6.QtWidgets import QApplication
    from droplet_agent import macmenu
    from droplet_agent.app import main as appmain
    QApplication.instance() or appmain.make_app(["droplet-test"])   # the same app the window's tests use
    status = {"id": "me", "name": "mac", "fp": "", "port": 1739, "nearby": [], "outbox": [],
              "peers": [{"id": "p1", "name": "phone", "link": "lan", "on_lan": True, "os": "android"}],
              "incoming": [{"request": "r1", "name": "friend", "code": "1234"}]}
    calls, notes = [], []

    def call(req, timeout=30):
        calls.append(req)
        if req["cmd"] == "status":
            return status
        return {"route": "lan", "id": "j1", "state": "done", "name": "friend"}
    bar = macmenu.MenuBar(call=call, notify=lambda t, b: notes.append((t, b)), sync=True)
    bar.kick()
    top = [a.text() for a in bar.menu.actions() if not a.isSeparator()]
    assert top[0] == "Open Droplet"
    assert "friend wants to pair (code 1234)" in top
    assert "phone — connected" in top
    assert top[-1] == "Quit droplet's menu bar icon"
    peer = next(a for a in bar.menu.actions() if a.text() == "phone — connected").menu()
    assert [a.text() for a in peer.actions()] == ["Send files…", "Send clipboard", "Ring"]

    # files are picked on the GUI thread, then sent by the tray's Actions
    sent = []
    monkeypatch.setattr(bar, "pick_files", lambda name: [Path("/tmp/a.txt")])
    monkeypatch.setattr(bar.actions, "run", lambda action: sent.append(action))
    peer.actions()[0].trigger()
    assert sent == [("send-files", "p1", "phone", [Path("/tmp/a.txt")])]
    bar.show(None)
    assert [a.text() for a in bar.menu.actions() if not a.isSeparator()][1] == "droplet agent isn't running"


# --- on a real Mac ------------------------------------------------------------------------

@mac_only
def test_quartz_events_are_made_and_read_back():
    cg = quartz.CG()
    x, y = cg.location()
    left, top, right, bottom = cg.screen()
    assert right > left and bottom > top
    ev = cg.mouse_event(cg.source, quartz.LEFT_DOWN, quartz.CGPoint(x, y), 0)
    cg.set_field(ev, quartz.CLICK_STATE, 2)
    assert cg.get_type(ev) == quartz.LEFT_DOWN and cg.get_field(ev, quartz.CLICK_STATE) == 2
    cg.release(ev)
    ev = cg.key_event(cg.source, 0, True)
    cg.set_flags(ev, quartz.FLAGS["meta"])
    text = "héllo 👋"
    chunk = quartz.utf16_chunks(text)[0]
    buf = (ctypes.c_uint16 * len(chunk))(*chunk)
    cg.set_unicode(ev, len(chunk), buf)
    out = (ctypes.c_uint16 * 32)()
    n = ctypes.c_ulong(0)
    cg.get_unicode(ev, 32, ctypes.byref(n), out)
    assert bytes(bytearray(b for u in out[:n.value] for b in struct.pack("<H", u))).decode("utf-16-le") == text
    assert cg.get_flags(ev) & quartz.FLAGS["meta"]
    cg.release(ev)
    ev = cg.scroll_event(cg.source, quartz.SCROLL_LINE, 2, -3, 0, 0)
    assert ev and cg.get_type(ev) == 22   # kCGEventScrollWheel
    cg.release(ev)
    # a media key's event is made (not posted: that would start the Music app on the runner)
    with macos.autorelease():
        ev = cg.media_event(quartz.MEDIA["MediaPlayPause"], True)
        assert cg.get_type(ev) == 14   # NSEventTypeSystemDefined
    # posting: harmless (a zero move), and dropped anyway without Accessibility
    cg.send(cg.mouse_event(cg.source, quartz.MOVED, quartz.CGPoint(x, y), 0))


@mac_only
def test_the_permission_checks_answer():
    assert isinstance(macos.accessibility_allowed(), bool)
    assert macos.screen_capture_allowed() in (True, False, None)
    ok, why = quartz.usable()
    assert ok or "Accessibility" in why
    assert macos.set_app_name("Droplet") in (True, False)   # answers, and AppKit doesn't throw


@mac_only
def test_the_clipboard_round_trip():
    from droplet_agent import clip
    mode, why = clip.detect()
    assert mode == "macos", why
    before = macos.pasteboard_change_count()
    assert isinstance(before, int)
    sync = clip.ClipboardSync(mode, lambda _t: True)
    text = f"droplet test ✓ {time.time()}"
    assert sync._write(text) is None
    assert macos.pasteboard_change_count() != before
    assert sync._read() == text
    assert macos.pasteboard_concealed() is False


@mac_only
def test_the_control_socket_checks_who_connects(tmp_path):
    from droplet_agent.mesh import control
    path = Path(f"/tmp/dt-{os.getpid()}/c.sock")
    server = control.ControlServer(lambda req: {"ok": req.get("cmd")}, path)
    server.start()
    try:
        assert control.call({"cmd": "status"}, timeout=5, path=path) == {"ok": "status"}
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.connect(str(path))
        assert control._peer_uid(s) == os.getuid()
        s.close()
    finally:
        server.close()


@mac_only
def test_what_a_mac_offers():
    from droplet_agent import battery, env, lock, mediastate, screenshot
    from droplet_agent.mesh import OS_NAME
    from droplet_agent.mesh.node import default_gateways
    assert OS_NAME == "macos"
    assert env.desktops() == {"macos"}
    cmd, why = lock.detect()
    assert cmd == [lock.MAC_LOCK], why   # the login framework, from the dyld cache (not locked here)
    assert screenshot.methods() == ["screencapture"]
    assert mediastate.available()[0]
    b = battery.read()
    assert b is None or 0 <= b["level"] <= 100
    v = mediastate.Media().snapshot()
    assert set(v) == {"players", "active", "volume"}
    assert isinstance(default_gateways(), list)
    assert macos.battery_from_pmset(os.popen("pmset -g batt").read()) == b


@mac_only
def test_launchctl_answers_for_an_unknown_job():
    assert macos.service_state("io.github.ferinmtk.NoSuchJob") == "not installed"
    assert not macos.loaded("io.github.ferinmtk.NoSuchJob")
