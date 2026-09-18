"""Tests for tv.py with the androidtvremote2 library replaced by a fake TV.

Run from the repo root:  python -m unittest discover tests
"""

import asyncio
import json
import os
import stat
import sys
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from flask import Flask  # noqa: E402

import tv  # noqa: E402


def fake_lib(state: dict):
    """A stand-in for the androidtvremote2 module, driven by `state`:
    reachable, hang, code (the one "on screen"), trusted, sent, remotes."""

    class CannotConnect(Exception):
        pass

    class ConnectionClosed(Exception):
        pass

    class InvalidAuth(Exception):
        pass

    class Remote:
        def __init__(self, client_name, certfile, keyfile, host, api_port=6466, pair_port=6467, loop=None):
            self.certfile, self.keyfile, self.host = certfile, keyfile, host
            self.pairing = False
            self.up = False
            self.cbs = {"on": [], "app": [], "vol": [], "avail": []}
            self.invalid_auth_cb = None
            state.setdefault("remotes", []).append(self)

        async def _gate(self):
            if state.get("hang"):
                await asyncio.sleep(3600)
            if not state.get("reachable", True):
                raise CannotConnect("no route")

        async def async_generate_cert_if_missing(self):
            if os.path.exists(self.certfile):
                return False
            Path(self.certfile).write_text("CERT")
            Path(self.keyfile).write_text("KEY")
            return True

        async def async_get_name_and_mac(self):
            await self._gate()
            return "Fake TV", "AA:BB:CC:DD:EE:FF"

        async def async_start_pairing(self):
            await self._gate()
            if state.get("refuse_pairing"):
                raise ConnectionClosed("closed")
            self.pairing = True
            state["pairings"] = state.get("pairings", 0) + 1

        async def async_finish_pairing(self, code):
            if not self.pairing:
                raise ConnectionClosed("after disconnect")
            if state.get("cancelled_on_tv"):
                raise ConnectionClosed("user pressed cancel")
            if code != state["code"]:
                raise InvalidAuth("hash")
            state["trusted"] = True
            self.disconnect()

        async def async_connect(self):
            await self._gate()
            if not state.get("trusted"):
                raise InvalidAuth("need to pair")
            self.up = True
            state["connects"] = state.get("connects", 0) + 1

        def keep_reconnecting(self, cb=None):
            self.invalid_auth_cb = cb

        def disconnect(self):
            self.up = False
            self.pairing = False

        def send_key_command(self, key, direction="SHORT"):
            if not self.up:
                raise ConnectionClosed("down")
            state.setdefault("sent", []).append(("key", key, direction))

        def send_text(self, text):
            if not self.up:
                raise ConnectionClosed("down")
            state.setdefault("sent", []).append(("text", text))

        def send_launch_app_command(self, link):
            if not self.up:
                raise ConnectionClosed("down")
            state.setdefault("sent", []).append(("launch", link))

        is_on = property(lambda self: True if self.up else None)
        current_app = property(lambda self: "com.netflix.ninja" if self.up else None)
        volume_info = property(lambda self: {"level": 12, "max": 100, "muted": False} if self.up else None)
        device_info = property(lambda self: {"manufacturer": "TCL", "model": "43P", "sw_version": "1"} if self.up else None)

        def add_is_on_updated_callback(self, cb): self.cbs["on"].append(cb)
        def add_current_app_updated_callback(self, cb): self.cbs["app"].append(cb)
        def add_volume_info_updated_callback(self, cb): self.cbs["vol"].append(cb)
        def add_is_available_updated_callback(self, cb): self.cbs["avail"].append(cb)

    return SimpleNamespace(AndroidTVRemote=Remote, CannotConnect=CannotConnect,
                           ConnectionClosed=ConnectionClosed, InvalidAuth=InvalidAuth)


def wait_for(pred, timeout=3.0):
    end = time.time() + timeout
    while time.time() < end:
        if pred():
            return True
        time.sleep(0.02)
    return False


class Validation(unittest.TestCase):
    def test_keys_allowlist(self):
        self.assertEqual(tv.check_key("DPAD_UP"), "DPAD_UP")
        self.assertEqual(tv.check_key("home"), "HOME")
        self.assertEqual(tv.check_key("7"), "7")
        self.assertEqual(tv.check_key("MUTE"), "VOLUME_MUTE")  # speaker, not microphone
        for bad in ("KEYCODE_POWER", "text:hello", "CALL", "ENDCALL", "", None, 26, "DPAD_UP ", "POWER\n"):
            if bad in ("DPAD_UP ", "POWER\n"):
                self.assertIn(tv.check_key(bad), tv.KEYS.values())  # surrounding space is trimmed
                continue
            with self.assertRaises(tv.TVError, msg=repr(bad)) as cm:
                tv.check_key(bad)
            self.assertEqual(cm.exception.status, 400)

    def test_every_allowed_key_exists_in_the_library(self):
        try:
            from androidtvremote2.remotemessage_pb2 import RemoteKeyCode
        except ImportError:
            self.skipTest("androidtvremote2 not installed")
        names = set(RemoteKeyCode.keys())
        for name, code in tv.KEYS.items():
            self.assertIn("KEYCODE_" + code, names, name)

    def test_pairing_code(self):
        self.assertEqual(tv.check_code("a1b2c3"), "A1B2C3")
        self.assertEqual(tv.check_code(" A1 B2-C3 "), "A1B2C3")
        for bad in ("A1B2C", "A1B2C3D", "G1B2C3", "", None, "12345%"):
            with self.assertRaises(tv.TVError):
                tv.check_code(bad)

    def test_text(self):
        self.assertEqual(tv.check_text("hello world"), "hello world")
        self.assertEqual(tv.check_text("a\x00b\nc"), "abc")
        for bad in ("", "\n\t", None, 5, "x" * (tv.MAX_TEXT + 1)):
            with self.assertRaises(tv.TVError):
                tv.check_text(bad)

    def test_apps_and_links(self):
        self.assertEqual(tv.check_app("youtube"), ("link", "com.google.android.youtube.tv"))
        self.assertEqual(tv.check_app("Netflix"), ("link", "com.netflix.ninja"))
        self.assertEqual(tv.check_app("home"), ("key", "HOME"))
        url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        self.assertEqual(tv.check_app(url), ("link", url))
        for bad in ("intent://x#Intent;component=a/b;end", "market://launch?id=evil", "file:///etc/passwd",
                    "http://example.com", "content://x", "com.evil.app", "https://", "https://user@host/",
                    "https://a b.com", "https://[::1", "javascript:alert(1)", "", None):
            with self.assertRaises(tv.TVError, msg=repr(bad)):
                tv.check_app(bad)

    def test_host(self):
        self.assertEqual(tv.check_host("192.168.100.40"), "192.168.100.40")
        self.assertEqual(tv.check_host("127.0.0.1"), "127.0.0.1")
        self.assertEqual(tv.check_host("100.101.1.2"), "100.101.1.2")   # tailnet
        self.assertEqual(tv.check_host("[fe80::1]"), "fe80::1")
        for bad in ("8.8.8.8", "1.1.1.1", "", None, "a b", "tv;rm -rf", "http://192.168.1.2"):
            with self.assertRaises(tv.TVError, msg=repr(bad)):
                tv.check_host(bad)

    def test_mac_and_magic_packet(self):
        self.assertEqual(tv.norm_mac("AA-BB-CC-DD-EE-FF"), "aa:bb:cc:dd:ee:ff")
        self.assertEqual(tv.norm_mac("aabbccddeeff"), "aa:bb:cc:dd:ee:ff")
        self.assertIsNone(tv.norm_mac("00:00:00:00:00:00"))
        self.assertIsNone(tv.norm_mac("nope"))
        pkt = tv.magic_packet("aa:bb:cc:dd:ee:ff")
        self.assertEqual(len(pkt), 102)
        self.assertEqual(pkt[:6], b"\xff" * 6)
        self.assertEqual(pkt[6:12], bytes.fromhex("aabbccddeeff"))


class ManagerCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name) / "tv"
        self.state = {"code": "A1B2C3", "reachable": True}
        # short waits, so the unhappy paths don't slow the suite down
        patches = {"CONNECT_TIMEOUT": 0.5, "READY_WAIT": 0.6, "WAKE_WAIT": 0.6, "LONG_PRESS": 0.05}
        self.patchers = [mock.patch.object(tv, k, v) for k, v in patches.items()]
        for p in self.patchers:
            p.start()
        self.m = self.make()

    def make(self, lib="fake"):
        m = tv.TVManager(self.dir, lib=fake_lib(self.state) if lib == "fake" else lib, hub="t15", discovery=False)
        m.start()
        return m

    def tearDown(self):
        self.m.stop()
        for p in self.patchers:
            p.stop()
        self.tmp.cleanup()

    def pair(self):
        self.m.pair_start("192.168.100.40")
        return self.m.pair_finish("a1b2c3")

    def only_tv(self):
        return self.m.snapshot()["tvs"][0]


class Pairing(ManagerCase):
    def test_happy_path(self):
        started = self.m.pair_start("192.168.100.40")
        self.assertEqual(started["name"], "Fake TV")
        self.assertEqual(started["host"], "192.168.100.40")
        self.assertIsNotNone(self.m.snapshot()["pairing"])
        got = self.m.pair_finish("a1b2c3")
        self.assertTrue(got["paired"])
        self.assertIsNone(self.m.snapshot()["pairing"])
        self.assertTrue(wait_for(lambda: self.only_tv()["connected"]))
        t = self.only_tv()
        self.assertEqual((t["on"], t["app"], t["app_name"]), (True, "com.netflix.ninja", "Netflix"))
        self.assertEqual(t["volume"], {"level": 12, "max": 100, "muted": False})
        self.assertEqual(t["model"], "TCL 43P")

    def test_files_are_private(self):
        self.pair()
        for name in ("cert.pem", "key.pem", "tvs.json"):
            mode = stat.S_IMODE((self.dir / name).stat().st_mode)
            self.assertEqual(mode, 0o600, name)
        self.assertEqual(stat.S_IMODE(self.dir.stat().st_mode), 0o700)
        saved = json.loads((self.dir / "tvs.json").read_text())["tvs"]
        self.assertEqual(saved[0]["host"], "192.168.100.40")
        self.assertEqual(saved[0]["mac"], "aa:bb:cc:dd:ee:ff")

    def test_wrong_code_can_be_retyped(self):
        self.m.pair_start("192.168.100.40")
        with self.assertRaises(tv.TVError) as cm:
            self.m.pair_finish("FFFFFF")
        self.assertEqual(cm.exception.status, 400)
        self.assertIsNotNone(self.m.snapshot()["pairing"])  # still waiting for the right one
        self.assertTrue(self.m.pair_finish("A1B2C3")["paired"])

    def test_too_many_wrong_codes_ends_it(self):
        self.m.pair_start("192.168.100.40")
        for _ in range(4):
            with self.assertRaises(tv.TVError):
                self.m.pair_finish("FFFFFF")
        with self.assertRaises(tv.TVError) as cm:
            self.m.pair_finish("FFFFFF")
        self.assertEqual(cm.exception.status, 409)
        self.assertIsNone(self.m.snapshot()["pairing"])

    def test_bad_format_never_reaches_the_tv(self):
        self.m.pair_start("192.168.100.40")
        with self.assertRaises(tv.TVError) as cm:
            self.m.pair_finish("12345")
        self.assertEqual(cm.exception.status, 400)
        self.assertIsNotNone(self.m.snapshot()["pairing"])

    def test_finish_without_start(self):
        with self.assertRaises(tv.TVError) as cm:
            self.m.pair_finish("A1B2C3")
        self.assertEqual(cm.exception.status, 409)

    def test_cancelled_on_the_tv(self):
        self.m.pair_start("192.168.100.40")
        self.state["cancelled_on_tv"] = True
        with self.assertRaises(tv.TVError) as cm:
            self.m.pair_finish("A1B2C3")
        self.assertEqual(cm.exception.status, 409)
        self.assertIsNone(self.m.snapshot()["pairing"])
        self.assertEqual(self.m.snapshot()["tvs"], [])

    def test_code_goes_stale(self):
        self.m.pair_start("192.168.100.40")
        with mock.patch.object(tv, "PAIR_TTL", 0):
            with self.assertRaises(tv.TVError) as cm:
                self.m.pair_finish("A1B2C3")
        self.assertEqual(cm.exception.status, 409)

    def test_cancel(self):
        self.m.pair_start("192.168.100.40")
        remote = self.state["remotes"][-1]
        self.m.pair_cancel()
        self.assertFalse(remote.pairing)  # connection closed: the TV drops the code screen
        self.assertIsNone(self.m.snapshot()["pairing"])

    def test_starting_again_replaces_the_old_session(self):
        self.m.pair_start("192.168.100.40")
        first = self.state["remotes"][-1]
        self.m.pair_start("192.168.100.41")
        self.assertFalse(first.pairing)
        self.assertEqual(self.m.snapshot()["pairing"]["host"], "192.168.100.41")

    def test_tv_off(self):
        self.state["reachable"] = False
        with self.assertRaises(tv.TVError) as cm:
            self.m.pair_start("192.168.100.40")
        self.assertEqual(cm.exception.status, 502)
        self.assertIn("same network as t15", str(cm.exception))

    def test_tv_hangs(self):
        self.state["hang"] = True
        t0 = time.time()
        with self.assertRaises(tv.TVError) as cm:
            self.m.pair_start("192.168.100.40")
        self.assertEqual(cm.exception.status, 502)
        self.assertLess(time.time() - t0, 3)

    def test_public_address_refused(self):
        with self.assertRaises(tv.TVError) as cm:
            self.m.pair_start("8.8.8.8")
        self.assertEqual(cm.exception.status, 400)
        self.assertNotIn("remotes", self.state)

    def test_repairing_the_same_tv_keeps_one_entry(self):
        self.pair()
        first_id = self.only_tv()["id"]
        self.pair()
        tvs = self.m.snapshot()["tvs"]
        self.assertEqual(len(tvs), 1)
        self.assertEqual(tvs[0]["id"], first_id)
        self.assertTrue(wait_for(lambda: self.only_tv()["connected"]))


class Control(ManagerCase):
    def setUp(self):
        super().setUp()
        self.pair()
        self.id = self.only_tv()["id"]
        self.assertTrue(wait_for(lambda: self.only_tv()["connected"]))

    def test_keys(self):
        self.m.key(self.id, "DPAD_UP")
        self.m.key(self.id, "mute")
        self.assertEqual(self.state["sent"], [("key", "DPAD_UP", "SHORT"), ("key", "VOLUME_MUTE", "SHORT")])

    def test_long_press(self):
        self.m.key(self.id, "DPAD_CENTER", long=True)
        self.assertTrue(wait_for(lambda: len(self.state.get("sent", [])) == 2))
        self.assertEqual(self.state["sent"], [("key", "DPAD_CENTER", "START_LONG"), ("key", "DPAD_CENTER", "END_LONG")])

    def test_key_not_allowed(self):
        with self.assertRaises(tv.TVError) as cm:
            self.m.key(self.id, "text:rm")
        self.assertEqual(cm.exception.status, 400)
        self.assertNotIn("sent", self.state)

    def test_unknown_tv(self):
        with self.assertRaises(tv.TVError) as cm:
            self.m.key("nope", "HOME")
        self.assertEqual(cm.exception.status, 404)

    def test_text_and_launch(self):
        self.m.text(self.id, "breaking bad")
        self.m.launch(self.id, "youtube")
        self.m.launch(self.id, "home")
        self.m.launch(self.id, "https://www.youtube.com/watch?v=x")
        self.assertEqual(self.state["sent"], [
            ("text", "breaking bad"),
            ("launch", "com.google.android.youtube.tv"),
            ("key", "HOME", "SHORT"),
            ("launch", "https://www.youtube.com/watch?v=x"),
        ])

    def test_power_toggles(self):
        self.m.power(self.id)
        self.assertEqual(self.state["sent"], [("key", "POWER", "SHORT")])
        self.m.power(self.id, "on")  # already on: nothing to do
        self.assertEqual(len(self.state["sent"]), 1)

    def drop(self):
        remote = self.state["remotes"][-1]
        remote.up = False
        self.state["reachable"] = False
        for cb in remote.cbs["avail"]:
            self.m.loop.call_soon_threadsafe(cb, False)
        self.assertTrue(wait_for(lambda: not self.only_tv()["connected"]))

    def test_tv_turned_off(self):
        self.drop()
        t = self.only_tv()
        self.assertTrue(t["connecting"])      # a moment's grace before it counts as gone
        with mock.patch.object(tv, "CONNECTING_GRACE", 0):
            self.assertFalse(self.only_tv()["connecting"])
        self.assertIsNone(t["on"])
        self.assertIsNone(t["volume"])
        t0 = time.time()
        with self.assertRaises(tv.TVError) as cm:
            self.m.key(self.id, "HOME")
        self.assertEqual(cm.exception.status, 503)
        self.assertLess(time.time() - t0, 3)

    def test_key_reconnects_when_it_can(self):
        self.drop()
        self.state["reachable"] = True
        self.m.key(self.id, "HOME")   # the press kicks a reconnect and waits for it
        self.assertEqual(self.state["sent"][-1], ("key", "HOME", "SHORT"))

    def test_power_on_sends_wake_on_lan(self):
        self.drop()
        with mock.patch.object(tv, "send_wol", return_value=True) as wol:
            got = self.m.power(self.id, "on")
        wol.assert_called_once()
        self.assertEqual(wol.call_args.args[0], "aa:bb:cc:dd:ee:ff")
        self.assertTrue(got["woke"])

    def test_power_off_when_unreachable_is_a_no_op(self):
        self.drop()
        self.assertEqual(self.m.power(self.id, "off")["on"], False)

    def test_tv_forgot_us(self):
        remote = self.state["remotes"][-1]
        self.m.loop.call_soon_threadsafe(remote.invalid_auth_cb)
        self.assertTrue(wait_for(lambda: not self.only_tv()["paired"]))
        self.assertIn("Pair it again", self.only_tv()["error"])
        with self.assertRaises(tv.TVError) as cm:
            self.m.key(self.id, "HOME")
        self.assertEqual(cm.exception.status, 409)
        saved = json.loads((self.dir / "tvs.json").read_text())["tvs"][0]
        self.assertFalse(saved["paired"])

    def test_restart_reconnects_saved_tvs(self):
        self.m.stop()
        self.m = self.make()
        self.assertTrue(wait_for(lambda: self.m.snapshot()["tvs"] and self.only_tv()["connected"]))

    def test_forget(self):
        self.m.forget(self.id)
        self.assertEqual(self.m.snapshot()["tvs"], [])
        self.assertEqual(json.loads((self.dir / "tvs.json").read_text())["tvs"], [])
        self.assertFalse(self.state["remotes"][-1].up)

    def test_follows_a_new_address(self):
        found = {"name": "Living Room TV", "host": "192.168.100.77", "port": 6466,
                 "mac": "aa:bb:cc:dd:ee:ff", "seen": time.time()}
        self.m.loop.call_soon_threadsafe(self.m._follow, "Living Room TV." + tv.SERVICE, found)
        self.assertTrue(wait_for(lambda: self.only_tv()["host"] == "192.168.100.77"))
        self.assertEqual(self.state["remotes"][-1].host, "192.168.100.77")


class NoLibrary(ManagerCase):
    def test_degrades(self):
        self.m.stop()
        self.m = self.make(lib=None)
        snap = self.m.snapshot()
        self.assertFalse(snap["available"])
        self.assertIn("pip install", snap["error"])
        for fn, args in ((self.m.pair_start, ("192.168.1.2",)), (self.m.key, ("x", "HOME")),
                         (self.m.power, ("x",)), (self.m.launch, ("x", "youtube"))):
            with self.assertRaises(tv.TVError) as cm:
                fn(*args)
            self.assertEqual(cm.exception.status, 503)

    def test_import_failure_is_handled(self):
        # the module itself imports with the library missing
        import importlib
        with mock.patch.dict(sys.modules, {"androidtvremote2": None}):
            mod = importlib.reload(tv)
            self.assertIsNone(mod._atv)
        importlib.reload(tv)


class Routes(ManagerCase):
    def setUp(self):
        super().setUp()
        app = Flask(__name__)
        tv.register(SimpleNamespace(app=app, base_dir=Path(self.tmp.name)), manager=self.m)
        self.c = app.test_client()

    def test_flow(self):
        r = self.c.get("/api/tv")
        self.assertEqual(r.status_code, 200)
        self.assertTrue(r.json["available"])
        self.assertEqual(r.json["tvs"], [])
        self.assertIn({"id": "youtube", "name": "YouTube", "package": "com.google.android.youtube.tv"}, r.json["apps"])

        r = self.c.post("/api/tv/pair/start", json={"host": "192.168.100.40"})
        self.assertEqual(r.status_code, 200, r.json)
        r = self.c.post("/api/tv/pair/finish", json={"code": "zzzzzz"})
        self.assertEqual(r.status_code, 400)
        self.assertIn("A-F", r.json["error"])
        r = self.c.post("/api/tv/pair/finish", json={"code": "A1B2C3"})
        self.assertEqual(r.status_code, 200)
        tid = r.json["id"]
        self.assertTrue(wait_for(lambda: self.c.get("/api/tv").json["tvs"][0]["connected"]))

        self.assertEqual(self.c.post(f"/api/tv/{tid}/key", json={"key": "BACK"}).status_code, 200)
        self.assertEqual(self.c.post(f"/api/tv/{tid}/key", json={"key": "BACK", "action": "long"}).status_code, 200)
        self.assertEqual(self.c.post(f"/api/tv/{tid}/key", json={"key": "BACK", "action": "hold"}).status_code, 400)
        self.assertEqual(self.c.post(f"/api/tv/{tid}/key", json={"key": "SYSRQ"}).status_code, 400)
        self.assertEqual(self.c.post(f"/api/tv/{tid}/key", data="not json").status_code, 400)
        self.assertEqual(self.c.post(f"/api/tv/{tid}/text", json={"text": "hi"}).status_code, 200)
        self.assertEqual(self.c.post(f"/api/tv/{tid}/launch", json={"app": "netflix"}).status_code, 200)
        self.assertEqual(self.c.post(f"/api/tv/{tid}/launch", json={"app": "intent:#Intent;end"}).status_code, 400)
        self.assertEqual(self.c.post(f"/api/tv/{tid}/power").status_code, 200)
        self.assertEqual(self.c.post(f"/api/tv/{tid}/power", json={"state": "sideways"}).status_code, 400)
        self.assertEqual(self.c.post("/api/tv/nope/key", json={"key": "HOME"}).status_code, 404)
        self.assertEqual(self.c.delete(f"/api/tv/{tid}").status_code, 200)
        self.assertEqual(self.c.get("/api/tv").json["tvs"], [])

    def test_unreachable_is_an_error_not_a_crash(self):
        self.state["reachable"] = False
        r = self.c.post("/api/tv/pair/start", json={"host": "192.168.100.40"})
        self.assertEqual(r.status_code, 502)
        self.assertFalse(r.json["ok"])


if __name__ == "__main__":
    unittest.main()
