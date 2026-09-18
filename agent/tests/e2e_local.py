"""End-to-end check of the agent against a real local hub. Not collected by pytest.

Start a throwaway hub first, then run this with a Python that has the
agent's dependencies plus simple-websocket:

    DROPLET_HOME=$(mktemp -d) DROPLET_PORT=8822 DROPLET_HOST=127.0.0.1 DROPLET_PUSH=0 python app.py &
    python agent/tests/e2e_local.py http://127.0.0.1:8822

It links an agent with a real link code, runs it with the dry-run backends
(nothing on this computer is moved, typed, locked or copied; media state is
read for real, read-only), and drives it from a fake controller over the
hub. Then it runs the real `droplet-agent run --dry-run` command briefly.
"""

import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from http.cookies import SimpleCookie
from pathlib import Path

AGENT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AGENT_DIR))

HUB = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8822"
WS = HUB.replace("http", "ws", 1) + "/ws"
TMP = Path(tempfile.mkdtemp(prefix="droplet-e2e-"))
os.environ["XDG_CONFIG_HOME"] = str(TMP / "config")
os.environ["XDG_DATA_HOME"] = str(TMP / "data")

import simple_websocket  # noqa: E402

from droplet_agent import config, mediastate, routes  # noqa: E402
from droplet_agent.agent import Agent  # noqa: E402
from droplet_agent.connection import Connection  # noqa: E402

checks = []


def check(name, ok, detail=""):
    checks.append((name, bool(ok)))
    print(f"  {'PASS' if ok else 'FAIL'}  {name}{'  ' + str(detail) if detail and not ok else ''}")


def http(method, path, body=None, cookie=None, bearer=None):
    headers = {"Content-Type": "application/json"}
    if cookie:
        headers["Cookie"] = f"droplet_device={cookie}"
    if bearer:
        headers["Authorization"] = f"Bearer {bearer}"
    req = urllib.request.Request(HUB + path, method=method, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    with urllib.request.urlopen(req, timeout=10) as r:
        token = None
        for raw in r.headers.get_all("Set-Cookie") or []:
            c = SimpleCookie()
            c.load(raw)
            if "droplet_device" in c:
                token = c["droplet_device"].value
        return json.loads(r.read()), token


def new_device(name):
    dev, token = http("POST", "/api/device", {"name": name})
    return dev, token


class Peer:
    """A WebSocket client of the hub, collecting what it receives."""

    def __init__(self, token, caps):
        self.ws = simple_websocket.Client.connect(WS, headers={"Cookie": f"droplet_device={token}"})
        self.ws.send(json.dumps({"t": "hello", "caps": caps, "platform": "web", "app": "e2e"}))
        self.got = []
        self.welcome = json.loads(self.ws.receive(timeout=5))
        threading.Thread(target=self._recv, daemon=True).start()

    def _recv(self):
        try:
            while True:
                raw = self.ws.receive()
                if raw is None:
                    return
                self.got.append(json.loads(raw))
        except Exception:
            return

    def send(self, msg):
        self.ws.send(json.dumps(msg))

    def close(self):
        self.ws.close()

    def wait(self, pred, timeout=8):
        end = time.time() + timeout
        while time.time() < end:
            for m in list(self.got):
                if pred(m):
                    return m
            time.sleep(0.05)
        return None


def main():
    print(f"hub {HUB}, scratch {TMP}")
    suffix = str(int(time.time()))[-5:]
    browser, browser_token = new_device(f"slim-e2e-{suffix}")    # this computer's browser
    phone, phone_token = new_device(f"phone-e2e-{suffix}")       # the controller
    other, other_token = new_device(f"other-e2e-{suffix}")       # another clipboard helper

    # --- setup with a real link code, through the CLI ---
    code = http("POST", "/api/device/link-code", {}, cookie=browser_token)[0]["code"]
    r = subprocess.run([sys.executable, "-m", "droplet_agent", "setup", "--hub", HUB, "--code", code],
                       cwd=AGENT_DIR, capture_output=True, text=True, env=os.environ)
    check("setup with a link code", r.returncode == 0, r.stderr)
    cfg = config.load()
    check("config holds hub, token and the browser's device id",
          cfg["hub"] == HUB and cfg["token"] and cfg["device"]["id"] == browser["id"])
    mode = oct(config.config_path().stat().st_mode & 0o777)
    check("config is chmod 600", mode == "0o600", mode)
    r = subprocess.run([sys.executable, "-m", "droplet_agent", "setup", "--hub", HUB, "--code", code],
                       cwd=AGENT_DIR, capture_output=True, text=True, env=os.environ)
    check("a used link code is refused", r.returncode != 0 and "wrong or has expired" in r.stderr, r.stderr)
    me, _ = http("GET", "/api/me", bearer=cfg["token"])
    check("the hub knows the agent's bearer token as the browser's device",
          me["device"]["id"] == browser["id"])

    # --- the agent, in-process, dry run ---
    agent = Agent(cfg, dry_run=True)
    media_calls = []

    def media_runner(*argv):
        media_calls.append(list(argv))
        return subprocess.CompletedProcess(argv, 0, "", "")
    agent.media.runner = media_runner
    conn = Connection(agent, routes.Router(cfg), cfg["token"])
    agent.start()
    threading.Thread(target=conn.run, args=(agent.stop,), daemon=True).start()

    ctl = Peer(phone_token, [])
    pres = ctl.wait(lambda m: m.get("t") == "presence"
                    and "input" in (m["devices"].get(browser["id"]) or {}).get("caps", []))
    caps = (pres or {}).get("devices", {}).get(browser["id"], {}).get("caps", [])
    check("agent's hello reached the hub with its caps", pres is not None, caps)
    print(f"        advertised: {caps}")
    apps = (pres or {}).get("devices", {}).get(browser["id"], {}).get("apps", [])
    check("it shows as a linux droplet-agent app", apps and apps[0]["platform"] == "linux"
          and apps[0]["app"].startswith("droplet-agent/"), apps)

    # input
    ctl.send({"t": "input", "to": browser["id"], "ev": [
        {"k": "move", "dx": 4.5, "dy": -2}, {"k": "move", "dx": 0.5, "dy": 0},
        {"k": "scroll", "dx": 0, "dy": 1.5}, {"k": "click", "b": "left", "n": 2},
        {"k": "key", "key": "ArrowRight"}, {"k": "key", "key": "c", "mods": ["ctrl"]},
        {"k": "text", "s": "héllo 👋"}]})
    calls = agent.input.backend.calls
    end = time.time() + 5
    while time.time() < end and len(calls) < 9:
        time.sleep(0.05)
    expect = [("move", 4, -2), ("move", 1, 0), ("scroll", 0, 1),
              ("button", "left", "down"), ("button", "left", "up"),
              ("button", "left", "down"), ("button", "left", "up"),
              ("key", "ArrowRight"), ("key", "ctrl+c"), ("text", "héllo 👋")]
    check("input events applied in order, fractions carried", calls == expect, calls)

    # media action + state
    ctl.send({"t": "media", "to": browser["id"], "action": "volume", "value": 0.5})
    ctl.send({"t": "media", "to": browser["id"], "action": "mute"})
    time.sleep(1)
    check("media actions became the right wpctl calls (logged, not run)", media_calls == [
        ["wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "0.50"],
        ["wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"]], media_calls)
    st = ctl.wait(lambda m: m.get("t") == "state" and m.get("kind") == "media" and m.get("device") == browser["id"])
    data = (st or {}).get("data") or {}
    check("media state published with the protocol's shape",
          st and isinstance(data.get("players"), list) and "active" in data and "volume" in data, st)
    real = mediastate.Media().snapshot()
    check("published players match playerctl on this machine",
          [p["id"] for p in data.get("players", [])] == [p["id"] for p in real["players"]])
    print(f"        players: {[(p['id'], p['status']) for p in data.get('players', [])]}, volume {data.get('volume')}")
    arts = [p["art"] for p in data.get("players", []) if p.get("art")]
    check("art is small (≤ 64 KB) or absent", all(len(a) <= 65536 for a in arts))
    bat = ctl.wait(lambda m: m.get("t") == "state" and m.get("kind") == "battery", timeout=2)
    print(f"        battery state: {(bat or {}).get('data')}")

    # lock (dry run) and an unknown command
    ctl.send({"t": "cmd", "to": browser["id"], "cmd": "lock"})
    ctl.send({"t": "cmd", "to": browser["id"], "cmd": "reboot"})
    err = ctl.wait(lambda m: m.get("t") == "error" and m.get("re") == "cmd")
    check("the hub refuses unknown commands before they reach the agent", err and "unknown" in err["error"], err)

    # screenshot: fake capture, real upload to the requester's inbox
    ctl.send({"t": "cmd", "to": browser["id"], "cmd": "screenshot"})
    found = None
    end = time.time() + 8
    while time.time() < end and not found:
        inbox = http("GET", "/api/files", cookie=phone_token)[0]["inbox"]
        found = next((f for f in inbox if f["name"].startswith("screenshot-")), None)
        time.sleep(0.2)
    check("screenshot uploaded to the requester's inbox", found, found)
    if found:
        with urllib.request.urlopen(urllib.request.Request(
                f"{HUB}/raw/inbox/{found['name']}", headers={"Cookie": f"droplet_device={phone_token}"})) as r:
            body = r.read()
        check("it's the PNG, labelled as from this device",
              body.startswith(b"\x89PNG") and found.get("from") == browser["name"], found)

    # rpc: answered at once
    ctl.send({"t": "rpc", "id": "r1", "to": browser["id"], "method": "media.whatever", "params": {}})
    res = ctl.wait(lambda m: m.get("t") == "rpc-result" and m.get("id") == "r1")
    check("rpc gets an immediate 'not supported' reply", res and "error" in res, res)

    # clipboard both ways, with echo suppression
    helper = Peer(other_token, ["clipboard"])
    written = []
    agent.clip.writer = lambda t: written.append(t)
    helper.send({"t": "clip", "text": "from the other device"})
    end = time.time() + 5
    while time.time() < end and not written:
        time.sleep(0.05)
    check("incoming clip applied (logged in the dry run)", written == ["from the other device"], written)
    agent.clip.local_change("from the other device")   # the echo of our own write
    agent.clip.local_change("copied on slim")
    got = helper.wait(lambda m: m.get("t") == "clip" and m.get("text") == "copied on slim")
    time.sleep(1.2)
    echoes = [m for m in helper.got if m.get("t") == "clip"]
    check("local change sent once, echo suppressed", got and len(echoes) == 1, echoes)

    # caps change → reconnect with a new hello
    agent.cfg["caps"]["lock"] = False
    agent.check_caps()
    pres = ctl.wait(lambda m: m.get("t") == "presence"
                    and "lock" not in (m["devices"].get(browser["id"]) or {}).get("caps", ["lock"])
                    and "input" in (m["devices"].get(browser["id"]) or {}).get("caps", []))
    check("switching a cap off reconnects without it", pres is not None)
    ctl.got.clear()
    ctl.send({"t": "cmd", "to": browser["id"], "cmd": "lock"})
    err = ctl.wait(lambda m: m.get("t") == "error" and m.get("re") == "cmd")
    check("…and the hub no longer routes it", err is not None)

    agent.stop.set()
    conn.reconnect()
    agent.close()
    time.sleep(1)

    # --- the real CLI, dry run ---
    proc = subprocess.Popen([sys.executable, "-m", "droplet_agent", "run", "--dry-run"], cwd=AGENT_DIR,
                            env=os.environ, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    pres = ctl.wait(lambda m: m.get("t") == "presence"
                    and "input" in (m["devices"].get(browser["id"]) or {}).get("caps", []), timeout=15)
    ctl.send({"t": "input", "to": browser["id"], "ev": [{"k": "key", "key": "F5"}]})
    time.sleep(1.5)
    proc.terminate()
    out = proc.communicate(timeout=10)[0]
    check("`droplet-agent run --dry-run` connects and applies input",
          "connected to the hub" in out and "input (dry run): key F5" in out, out[-800:])
    check("…and exits cleanly on SIGTERM", proc.returncode == 0, proc.returncode)

    ctl.close()
    helper.close()
    failed = [n for n, ok in checks if not ok]
    print(f"\n{len(checks) - len(failed)}/{len(checks)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
