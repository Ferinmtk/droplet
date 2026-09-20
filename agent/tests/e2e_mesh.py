"""End-to-end check of the mesh with real agent processes. Not collected by pytest.

Each agent is a separate `droplet-agent run --dry-run` process with its own
HOME and XDG directories (nothing on this computer is moved, typed, locked,
copied or played), talking over this machine's LAN address.

    python agent/tests/e2e_mesh.py direct
        two agents with no hub: pair directly, text, a 50 MB file (interrupted
        and resumed), input, media, ring, clip; a third, untrusted agent is
        refused; unpair.

    python agent/tests/e2e_mesh.py hub http://127.0.0.1:8861 <hub pid>
        against a throwaway hub (DROPLET_HOME=$(mktemp -d) DROPLET_PORT=8861
        DROPLET_LAN_TLS_PORT=8862 DROPLET_PUSH=0 python app.py). Links two agents,
        which trust each other through the roster; the hub is stopped (by its
        pid) and they keep working directly; the mailbox and the outbox;
        removing a device on the hub removes the trust. The hub is started
        again by this script, from the same DROPLET_HOME, with `restart_hub`.

Unix sockets have a short path limit, so instances live under $E2E_TMP (default /tmp).
"""

import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

AGENT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AGENT_DIR))

from droplet_agent.mesh import identity, tlsctx  # noqa: E402

ROOT = Path(tempfile.mkdtemp(prefix="dm-", dir=os.environ.get("E2E_TMP", "/tmp")))
checks = []


def check(name, ok, detail=""):
    checks.append((name, bool(ok)))
    print(f"  {'PASS' if ok else 'FAIL'}  {name}{'  ' + str(detail)[:400] if detail and not ok else ''}", flush=True)
    return ok


def wait_until(cond, timeout=20, every=0.2):
    end = time.time() + timeout
    while time.time() < end:
        try:
            v = cond()
        except Exception:
            v = None
        if v:
            return v
        time.sleep(every)
    return None


class Instance:
    def __init__(self, name, **mesh):
        self.name = name
        self.dir = ROOT / name
        self.home = self.dir / "h"
        self.env = dict(os.environ, HOME=str(self.home), XDG_CONFIG_HOME=str(self.home / ".config"),
                        XDG_DATA_HOME=str(self.home / ".local/share"), XDG_RUNTIME_DIR=str(self.dir / "r"),
                        PYTHONPATH=str(AGENT_DIR))
        for k in ("DBUS_SESSION_BUS_ADDRESS", "WAYLAND_DISPLAY", "DISPLAY"):
            self.env.pop(k, None)   # nothing reaches the real desktop, even by mistake
        (self.dir / "r").mkdir(parents=True, exist_ok=True)
        os.chmod(self.dir / "r", 0o700)
        cfg = self.home / ".config/droplet-agent"
        cfg.mkdir(parents=True, exist_ok=True)
        self.cfg_file = cfg / "config.json"
        if not self.cfg_file.exists():
            self.cfg_file.write_text(json.dumps({"device": {"id": "", "name": name}, "mesh": mesh}))
        self.proc = None
        self.log = self.dir / "agent.log"

    def cli(self, *args, input=None, timeout=120):
        return subprocess.run([sys.executable, "-m", "droplet_agent", *args], env=self.env, cwd=AGENT_DIR,
                              capture_output=True, text=True, input=input, timeout=timeout)

    def start(self):
        f = open(self.log, "a")
        self.proc = subprocess.Popen([sys.executable, "-m", "droplet_agent", "run", "--dry-run", "-v"],
                                     env=self.env, cwd=AGENT_DIR, stdout=f, stderr=subprocess.STDOUT)
        return wait_until(lambda: self.status(), 20)

    def stop(self, sig=signal.SIGTERM):
        if self.proc and self.proc.poll() is None:
            self.proc.send_signal(sig)
            try:
                self.proc.wait(10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait()
        self.proc = None

    def status(self):
        r = self.cli("peers", timeout=15)
        if r.returncode != 0:
            return None
        from droplet_agent.mesh import control
        old = os.environ.get("XDG_RUNTIME_DIR")
        os.environ["XDG_RUNTIME_DIR"] = self.env["XDG_RUNTIME_DIR"]
        try:
            return control.call({"cmd": "status"}, timeout=10)
        finally:
            if old is None:
                os.environ.pop("XDG_RUNTIME_DIR", None)
            else:
                os.environ["XDG_RUNTIME_DIR"] = old

    def logtext(self):
        return self.log.read_text(errors="replace") if self.log.exists() else ""

    def identity(self):
        return identity.load_or_create(self.home / ".config/droplet-agent/mesh")

    def downloads(self):
        return self.home / "Downloads/droplet"

    def chat(self):
        p = self.home / ".local/share/droplet-agent/mesh/chat.jsonl"
        return [json.loads(line) for line in p.read_text().splitlines()] if p.exists() else []

    def trust(self):
        p = self.home / ".config/droplet-agent/mesh/trust.json"
        return json.loads(p.read_text()).get("peers", {}) if p.exists() else {}


def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def lan_ip():
    from droplet_agent.mesh.discovery import lan_addresses
    return lan_addresses()[0]


def raw_request(port, ident, path, method="GET", body=None):
    """One HTTPS request to a mesh port, as `ident` (None: no client certificate)."""
    import socket
    ctx = tlsctx.client_context(ident, None)
    s = ctx.wrap_socket(socket.create_connection((lan_ip(), port), timeout=5))
    data = json.dumps(body).encode() if body is not None else b""
    s.sendall(f"{method} {path} HTTP/1.1\r\nHost: x\r\nContent-Length: {len(data)}\r\n"
              f"Connection: close\r\n\r\n".encode() + data)
    out = b""
    while True:
        chunk = s.recv(65536)
        if not chunk:
            break
        out += chunk
    return int(out.split(b" ", 2)[1])


# --- no hub ------------------------------------------------------------------------------

def pair(a, b, b_id):
    p = subprocess.Popen([sys.executable, "-m", "droplet_agent", "pair", b_id], env=a.env, cwd=AGENT_DIR,
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    incoming = wait_until(lambda: b.status()["incoming"], 20)
    check("the other side shows the request", incoming, b.status())
    p.stdin.write("y\n")
    p.stdin.flush()
    r = b.cli("pair", "--accept")
    check("accepting it on the other side", r.returncode == 0, r.stderr)
    out, _ = p.communicate(timeout=30)
    codes = re.findall(r"^\s+(\d{4})\s*$", out, re.M)
    check("both sides showed the same 4-digit code", incoming and codes and codes[0] == incoming[0]["code"],
          f"{codes} vs {incoming}")
    check("pairing finished on the initiator", p.returncode == 0 and "Paired with" in out, out)


def direct():
    print(f"scratch {ROOT}")
    a = Instance("alpha", max_rate=8 * 1024 * 1024)
    b = Instance("beta")
    c = Instance("gamma")
    try:
        check("alpha starts (mesh only, no hub)", a.start())
        check("beta starts", b.start())
        b_id, a_id = b.status()["id"], a.status()["id"]
        check("they find each other over mDNS",
              wait_until(lambda: any(p["id"] == b_id for p in a.status()["nearby"]), 20), a.status())
        # nothing works before pairing
        r = a.cli("text", b_id, "too early")
        check("no messages before pairing", r.returncode != 0 and "no trusted peer" in r.stderr, r.stderr)

        pair(a, b, b_id)
        check("each trusts the other", b.identity().fp in a.trust() and a.identity().fp in b.trust())

        r = a.cli("text", b_id, "hello from alpha")
        check("text: sent directly", r.returncode == 0 and "directly, over the LAN" in r.stdout, r.stdout + r.stderr)
        check("text: received and stored", any(m["body"] == "hello from alpha" and m["dir"] == "in" for m in b.chat()))
        r = b.cli("text", "alpha", "and back from beta")
        check("text the other way, by name", r.returncode == 0 and any(
            m["body"] == "and back from beta" for m in a.chat()), r.stdout + r.stderr)

        # --- a 50 MB file, interrupted and resumed
        big = ROOT / "big.bin"
        with open(big, "wb") as f:
            for _ in range(50):
                f.write(os.urandom(1024 * 1024))
        sender = subprocess.Popen([sys.executable, "-m", "droplet_agent", "send-file", b_id, str(big)],
                                  env=a.env, cwd=AGENT_DIR, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True)

        def part_size():
            parts = list(b.downloads().glob(".droplet-*.part"))
            return parts[0].stat().st_size if parts else 0
        got = wait_until(lambda: part_size() > 15 * 1024 * 1024, 30, 0.05)
        check("file: the transfer is under way", got)
        b.stop(signal.SIGKILL)   # the receiver dies mid-transfer
        interrupted_at = part_size()
        check("file: interrupted part-way", 0 < interrupted_at < 50 * 1024 * 1024, interrupted_at)
        check("beta comes back", b.start())
        out, _ = sender.communicate(timeout=180)
        saved = b.downloads() / "big.bin"
        # while beta restarts it may find no route for a moment: then it waits in the outbox
        check("file: the sender reports it delivered, or kept for delivery",
              sender.returncode == 0 and ("directly" in out or "outbox" in out), out)
        check("file: delivered", wait_until(lambda: saved.exists() and not a.status()["outbox"], 120),
              a.status()["outbox"])
        check("file: byte-identical", saved.exists() and sha(saved) == sha(big))
        m = re.search(r"resuming \S+ at byte (\d+)", b.logtext())
        check("file: resumed where it stopped, not from the start", m and int(m.group(1)) >= interrupted_at - 1,
              m.group(0) if m else b.logtext()[-2000:])
        check("file: no partial left behind", not list(b.downloads().glob(".droplet-*")))

        # --- live control, over the direct link, into the same handlers as via the hub
        for msg, want in (
            ({"t": "input", "ev": [{"k": "move", "dx": 3, "dy": 4}, {"k": "key", "key": "ArrowRight"},
                                   {"k": "text", "s": "héllo"}]}, "input (dry run): text héllo"),
            # no player runs in the throwaway session: the handler answers "nothing is playing"
            ({"t": "media", "action": "play-pause"}, "media play-pause: nothing is playing"),
            ({"t": "cmd", "cmd": "lock"}, "lock (dry run)"),
        ):
            r = a.cli("send", b_id, json.dumps(msg))
            check(f"{msg['t']}: sent directly", r.returncode == 0 and "directly" in r.stdout, r.stdout + r.stderr)
            check(f"{msg['t']}: handled by the agent's backend", wait_until(lambda: want in b.logtext(), 5), want)
        r = a.cli("send", b_id, json.dumps({"t": "cmd", "cmd": "screenshot"}))
        check("screenshot: comes back to the requester as a file",
              wait_until(lambda: list(a.downloads().glob("screenshot-*.png")), 15), a.logtext()[-1500:])
        r = a.cli("ring", b_id)
        check("ring", r.returncode == 0 and wait_until(lambda: "ring (dry run)" in b.logtext(), 5), r.stderr)
        r = a.cli("ring", b_id, "--stop")
        check("ring-stop", r.returncode == 0 and wait_until(lambda: "ring stopped (dry run)" in b.logtext(), 5))
        r = a.cli("clip", b_id, "--text", "copied on alpha")
        check("clip", r.returncode == 0 and wait_until(
            lambda: "would set 15 characters: 'copied on alpha'" in b.logtext(), 5), r.stderr)

        # --- an untrusted third device
        check("gamma starts", c.start())
        g = c.identity()
        port = b.status()["port"]
        try:
            raw_request(port, g, "/mesh/files/" + "0" * 32)
            refused = False
        except Exception as e:
            refused = "alert" in str(e).lower() or "reset" in str(e).lower() or isinstance(e, ConnectionError)
        check("gamma's certificate is refused in the TLS handshake", refused)
        for path in ("/mesh", "/mesh/files/" + "0" * 32):
            check(f"no certificate: {path} is refused", raw_request(port, None, path) == 403)
        check("no certificate: pairing is reachable", raw_request(port, None, "/mesh/pair", "POST", {}) == 400)
        r = c.cli("text", b_id, "let me in")
        check("gamma can't message beta", r.returncode != 0, r.stdout)
        check("beta counted the refused handshakes", b.status()["refused"] >= 1)

        # --- unpair
        r = a.cli("unpair", b_id)
        check("unpair, and the other side is told", r.returncode == 0 and "was told" in r.stdout, r.stdout + r.stderr)
        check("gone from both trust lists", wait_until(lambda: b.identity().fp not in a.trust()
                                                       and a.identity().fp not in b.trust(), 5))
        r = a.cli("text", b_id, "after unpairing")
        check("no messages after unpairing", r.returncode != 0)
        try:
            raw_request(port, a.identity(), "/mesh/files/" + "0" * 32)
            refused = False
        except Exception:
            refused = True
        check("alpha's certificate is refused by beta now", refused)
    finally:
        for i in (a, b, c):
            i.stop()


# --- with a hub ----------------------------------------------------------------------------

def hub_call(hub, path, token=None, method="GET", body=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(hub + path, method=method, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def with_hub(hub, hub_pid, restart_cmd):
    print(f"hub {hub}, scratch {ROOT}")
    a, b = Instance("alpha"), Instance("beta")
    hub_proc = None
    try:
        for i in (a, b):
            r = i.cli("setup", "--hub", hub, "--name", f"{i.name}-{int(time.time()) % 100000}")
            check(f"{i.name} links to the hub", r.returncode == 0, r.stdout + r.stderr)
        cfg_a, cfg_b = (json.loads(i.cfg_file.read_text()) for i in (a, b))
        b_id = cfg_b["device"]["id"]
        check("alpha starts", a.start())
        check("beta starts", b.start())
        check("the roster arrives: they trust each other with no pairing", wait_until(
            lambda: (a.trust().get(b.identity().fp) or {}).get("source") == "roster"
            and (b.trust().get(a.identity().fp) or {}).get("source") == "roster", 30),
            (a.trust(), a.logtext()[-2000:]))
        check("the peer id is the hub's device id",
              [p["id"] for p in a.status()["peers"] if p["fp"] == b.identity().fp] == [b_id], a.status()["peers"])
        r = a.cli("text", b_id, "roster hello")
        check("text goes directly, not through the hub", r.returncode == 0 and "directly" in r.stdout, r.stdout)

        # --- the hub goes down: direct keeps working
        os.kill(hub_pid, signal.SIGTERM)
        check("the hub is down", wait_until(lambda: not _up(hub), 10))
        r = a.cli("text", b_id, "no hub needed")
        check("hub down: text, directly", r.returncode == 0 and "directly" in r.stdout
              and any(m["body"] == "no hub needed" for m in b.chat()), r.stdout + r.stderr)
        f = ROOT / "small.bin"
        f.write_bytes(os.urandom(3 * 1024 * 1024))
        r = a.cli("send-file", b_id, str(f))
        check("hub down: file, directly", r.returncode == 0 and sha(b.downloads() / "small.bin") == sha(f),
              r.stdout + r.stderr)
        r = a.cli("send", b_id, json.dumps({"t": "input", "ev": [{"k": "key", "key": "F5"}]}))
        check("hub down: input, directly", r.returncode == 0 and wait_until(
            lambda: "input (dry run): key F5" in b.logtext(), 5), r.stdout + r.stderr)

        # --- the hub's mailbox: the hub is up, beta is off
        hub_proc = restart_cmd()
        check("the hub is back", wait_until(lambda: _up(hub), 20))
        check("alpha reconnects to the hub", wait_until(lambda: a.logtext().count("connected to the hub") >= 2, 40))
        b.stop()
        wait_until(lambda: False, 2)
        r = a.cli("text", b_id, "for the mailbox")
        check("peer off, hub up: text goes to the hub's mailbox", r.returncode == 0 and "mailbox" in r.stdout,
              r.stdout + r.stderr)
        chat = hub_call(hub, f"/api/chat/{cfg_a['device']['id']}", token=cfg_b["token"])
        check("the hub holds it for beta", any(m["text"] == "for the mailbox" for m in chat["messages"]), chat)
        r = a.cli("send-file", b_id, str(f))
        files = hub_call(hub, "/api/files", token=cfg_b["token"])
        check("peer off, hub up: the file goes to beta's inbox on the hub",
              r.returncode == 0 and "mailbox" in r.stdout and any(x["name"] == "small.bin" for x in files["inbox"]),
              r.stdout + r.stderr)
        r = a.cli("send", b_id, json.dumps({"t": "input", "ev": []}))
        check("live input isn't queued for an offline peer", r.returncode != 0, r.stdout)

        # --- the outbox: hub and peer both down
        hub_proc.send_signal(signal.SIGTERM)
        hub_proc.wait(10)
        check("the hub is down again", wait_until(lambda: not _up(hub), 10))
        r = a.cli("text", b_id, "kept in the outbox")
        check("both down: kept in the outbox", r.returncode == 0 and "outbox" in r.stdout, r.stdout + r.stderr)
        check("beta comes back", b.start())
        check("…and the outbox delivers it directly", wait_until(
            lambda: any(m["body"] == "kept in the outbox" for m in b.chat()), 40), a.logtext()[-2000:])

        # --- removing a device on the hub removes the trust
        hub_proc = restart_cmd()
        check("the hub is back", wait_until(lambda: _up(hub), 20))
        check("both reconnect", wait_until(lambda: b.logtext().count("connected to the hub") >= 1
                                           and a.logtext().count("connected to the hub") >= 3, 40))
        hub_call(hub, f"/api/device/{b_id}/remove", token=cfg_a["token"], method="POST", body={})
        check("removed on the hub: alpha stops trusting beta", wait_until(
            lambda: b.identity().fp not in a.trust(), 20), a.trust())
        r = a.cli("text", b_id, "after removal")
        check("…and can't message it", r.returncode != 0)
    finally:
        for i in (a, b):
            i.stop()
        if hub_proc is not None and hub_proc.poll() is None:
            hub_proc.send_signal(signal.SIGTERM)
            hub_proc.wait(10)


def _up(hub):
    try:
        urllib.request.urlopen(hub + "/api/hub/info", timeout=2).read()
        return True
    except Exception:
        return False


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "direct"
    if mode == "direct":
        direct()
    elif mode == "hub":
        hub, pid = sys.argv[2], int(sys.argv[3])
        env_file = Path(f"/proc/{pid}/environ").read_bytes().split(b"\0")
        hub_env = dict(kv.decode().split("=", 1) for kv in env_file if b"=" in kv)
        cmdline = [c.decode() for c in Path(f"/proc/{pid}/cmdline").read_bytes().split(b"\0") if c]
        cwd = os.readlink(f"/proc/{pid}/cwd")

        def restart():
            log = open(ROOT / "hub.log", "a")
            return subprocess.Popen(cmdline, env=hub_env, cwd=cwd, stdout=log, stderr=subprocess.STDOUT)
        with_hub(hub.rstrip("/"), pid, restart)
    failed = [n for n, ok in checks if not ok]
    print(f"\n{len(checks) - len(failed)}/{len(checks)} passed" + (f"; failed: {failed}" if failed else ""))
    if not failed:
        shutil.rmtree(ROOT, ignore_errors=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
