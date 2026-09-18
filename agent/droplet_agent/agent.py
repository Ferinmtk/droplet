"""The agent: what this machine can do, and acting on messages from the hub or a peer.

Transport-agnostic: `send(msg) -> bool` goes to the hub, and
`dispatch(msg, source)` handles a message from wherever it came: the hub
(connection.py) or a direct link with a peer (mesh/node.py). Both feed the
same handlers; `source` is where replies and screenshots go back. The tests
drive this with a fake transport.
"""

from __future__ import annotations

import logging
import queue
import socket
import threading
import time
from concurrent.futures import ThreadPoolExecutor

from . import APP_ID, __version__, battery, clip, config, hub, lock, mediastate, screenshot
from .inject.manager import InputManager

log = logging.getLogger("droplet_agent")

INPUT_QUEUE = 2000       # event batches waiting to be applied
BUTTON_WATCHDOG = 20     # seconds of silence after which held buttons are let go
BATTERY_EVERY = 60


class HubSource:
    """A message that came through the hub: replies go back over it, screenshots are uploaded to it."""

    kind = "hub"

    def __init__(self, agent: "Agent"):
        self.agent = agent

    def reply(self, msg: dict) -> bool:
        return self.agent.send(msg)

    def deliver_file(self, name: str, data: bytes, mime: str, to: str):
        a = self.agent
        # the route of the WebSocket the request came over: the pinned LAN,
        # loopback or the tailnet (the configured URL only before any connection)
        if mime == "image/png":
            hub.upload(a.route or a.cfg["hub"], a.cfg["token"], to, name, data)
        else:
            hub.upload(a.route or a.cfg["hub"], a.cfg["token"], to, name, data, mime)


def _dry_runner(*argv):
    import subprocess
    log.info("media (dry run): %s", " ".join(argv))
    return subprocess.CompletedProcess(list(argv), 0, "", "")


class Agent:
    def __init__(self, cfg: dict, *, dry_run: bool = False, input_backend: str | None = None):
        self.cfg = cfg
        self.dry_run = dry_run
        self.stop = threading.Event()
        self.transport = None      # set by the connection: send(dict) -> bool
        self.route = None          # set by the connection: the route it last connected over (hub.Route)
        self.on_caps_changed = None  # set by the connection: reconnect with a new hello
        self.peers_broadcast = None  # set by the mesh: send(dict) to every directly linked peer -> bool
        self.on_roster = None        # set by the mesh: the hub says its roster changed
        self.on_hub_up = None        # set by the mesh: connected to the hub (announce, fetch the roster)
        self.hub_source = HubSource(self)
        self.hub_devices: set[str] = set()   # devices with a live connection to the hub
        self.last_state: dict[str, dict] = {}  # kind → the latest state published, for new links
        self.advertised: set[str] = set()
        # media actions and clipboard writes keep their order; locks and
        # screenshots (slow) run beside them
        self.serial = ThreadPoolExecutor(max_workers=1, thread_name_prefix="agent-serial")
        self.pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="agent")
        self.input_q: queue.Queue = queue.Queue(maxsize=INPUT_QUEUE)

        choice = "log" if dry_run else (input_backend or cfg.get("input_backend") or "auto")
        self.input = InputManager(choice, APP_ID, config.portal_token_path(), self.check_caps,
                                  text_mode=cfg.get("uinput_text") or "auto")
        self.media = mediastate.Media(runner=_dry_runner if dry_run else None)
        self.media_pub = mediastate.MediaPublisher(self.media, lambda d: self.publish("media", d), self.stop)
        self.lock_cmd, self.lock_why = lock.detect(cfg.get("lock_command"))
        self.shooter = screenshot.Screenshotter(cfg.get("screenshot_command"), fake=dry_run)
        mode, self.clip_why = clip.detect()
        if dry_run:
            # never touch the real clipboard in a dry run
            self.clip = clip.ClipboardSync("dry-run", self.send_clip, cfg.get("clipboard_max_bytes") or 256 * 1024,
                                           reader=lambda: None, writer=self._dry_clip_write)
            self.clip_why = "dry run: writes are only logged"
        else:
            self.clip = clip.ClipboardSync(mode, self.send_clip, cfg.get("clipboard_max_bytes") or 256 * 1024)
        self.clip.on_failed = self.check_caps
        self.battery_last: dict | None = None
        self.host = socket.gethostname().split(".")[0]

    # --- what works ---------------------------------------------------------

    def works(self) -> dict[str, tuple[bool, str]]:
        """cap → (works right now, why)."""
        out = {}
        out["input"] = (self.input.available, self.input.reason)
        out["media"] = mediastate.available()
        out["lock"] = (self.lock_cmd is not None, self.lock_why)
        shots = ["fake"] if self.dry_run else screenshot.methods(self.cfg.get("screenshot_command"))
        out["screenshot"] = (bool(shots), ", ".join(shots) if shots else "no screenshot tool found")
        clip_ok = (self.clip.mode is not None) and not self.clip.failed
        out["clipboard"] = (clip_ok, self.clip.failed or self.clip_why)
        for cap in config.CAPS:
            if not config.enabled(self.cfg, cap):
                out[cap] = (False, "switched off in the config")
        return out

    def caps(self) -> list[str]:
        return [c for c, (ok, _) in self.works().items() if ok]

    def hello(self) -> dict:
        caps = self.caps()
        self.advertised = set(caps)
        return {"t": "hello", "caps": caps, "platform": "linux", "app": f"droplet-agent/{__version__}"}

    def check_caps(self):
        cb = self.on_caps_changed
        now = set(self.caps())
        if now == self.advertised:
            return
        if cb:
            log.info("capabilities changed; reconnecting to tell the hub")
            cb()   # the new hello updates `advertised`
        else:
            self.advertised = now   # no hub: only the mesh, which reads `advertised` directly

    # --- lifecycle ------------------------------------------------------------

    def start(self):
        if config.enabled(self.cfg, "input"):
            self.input.start()
        else:
            self.input.reason = "switched off in the config"
        threading.Thread(target=self._input_worker, name="input", daemon=True).start()
        if config.enabled(self.cfg, "media") and mediastate.available()[0]:
            self.media_pub.start()
        if config.enabled(self.cfg, "clipboard") and self.clip.mode and not self.dry_run:
            self.clip.start()
        threading.Thread(target=self._battery_loop, name="battery", daemon=True).start()

    def close(self):
        self.stop.set()
        self.clip.stop()
        self.input.close()
        self.serial.shutdown(wait=False, cancel_futures=True)
        self.pool.shutdown(wait=False, cancel_futures=True)

    def on_connected(self, welcome: dict):
        dev = welcome.get("device") or {}
        self.hub_devices = set((welcome.get("devices") or {}).keys()) if isinstance(welcome.get("devices"), dict) else set()
        log.info("connected to the hub as %s, offering: %s", dev.get("name", "?"),
                 ", ".join(sorted(self.advertised)) or "nothing")
        # the hub forgot our state when we dropped; send it again
        self.media_pub.force()
        self.battery_last = None
        self._battery_once()
        if self.on_hub_up:
            self.on_hub_up()

    # --- outgoing -------------------------------------------------------------

    def send(self, msg: dict) -> bool:
        t = self.transport
        return bool(t and t(msg))

    def _to_peers(self, msg: dict) -> bool:
        cb = self.peers_broadcast
        try:
            return bool(cb and cb(msg))
        except Exception:
            log.exception("sending to peers failed")
            return False

    def publish(self, kind: str, data: dict) -> bool:
        if kind == "media" and "media" not in self.advertised:
            return False
        msg = {"t": "state", "kind": kind, "data": data}
        self.last_state[kind] = data
        hub_ok = self.send(msg)
        return self._to_peers(msg) or hub_ok

    def send_clip(self, text: str) -> bool:
        """A local clipboard change: to the hub (which passes it to your other devices)
        and to every peer with an open direct link."""
        if "clipboard" not in self.advertised:
            return False
        msg = {"t": "clip", "text": text}
        hub_ok = self.send(msg)
        return self._to_peers(msg) or hub_ok

    def _battery_once(self):
        b = battery.read()
        if b is not None and b != self.battery_last and self.advertised:
            if self.publish("battery", b):
                self.battery_last = b

    def _battery_loop(self):
        while not self.stop.wait(BATTERY_EVERY):
            try:
                self._battery_once()
            except Exception:
                log.exception("reading the battery failed")

    # --- incoming -------------------------------------------------------------

    def dispatch(self, msg: dict, source=None):
        """Handle one message from the hub, or from a peer over a direct link (`source`).

        Never raises; never blocks for long.
        """
        if not isinstance(msg, dict):
            return
        source = source or self.hub_source
        t = msg.get("t")
        try:
            if t == "input":
                if "input" in self.advertised:
                    try:
                        self.input_q.put_nowait(msg.get("ev"))
                    except queue.Full:
                        log.warning("input is arriving faster than it can be applied; dropping some")
            elif t == "media":
                if "media" in self.advertised:
                    self.serial.submit(self._media, msg)
            elif t == "cmd":
                self._cmd(msg, source)
            elif t == "clip":
                if "clipboard" in self.advertised and config.enabled(self.cfg, "clipboard"):
                    self.serial.submit(self._clip, msg.get("text"))
            elif t == "rpc":
                # files.* isn't offered on Linux; answer at once so nobody waits 30 s
                source.reply({"t": "rpc-result", "id": msg.get("id"), "error": "Not supported by the Linux agent."})
            elif source.kind != "hub":
                pass   # the rest only means something coming from the hub
            elif t == "error":
                log.warning("hub: %s", msg.get("error"))
            elif t == "presence":
                devs = msg.get("devices")
                if isinstance(devs, dict):
                    self.hub_devices = set(devs.keys())
            elif t == "roster":
                if self.on_roster:
                    self.on_roster()
            # welcome, presence, state, pong and anything newer: nothing to do
        except Exception:
            log.exception("handling %r failed", t)

    def _input_worker(self):
        last = time.monotonic()
        while not self.stop.is_set():
            try:
                events = self.input_q.get(timeout=5)
            except queue.Empty:
                if time.monotonic() - last > BUTTON_WATCHDOG:
                    self.input.release_all()
                continue
            last = time.monotonic()
            if config.enabled(self.cfg, "input"):
                self.input.apply(events)

    def _media(self, msg: dict):
        if not config.enabled(self.cfg, "media"):
            return
        why = self.media.act(msg.get("action"), msg.get("player"), msg.get("value"))
        if why:
            log.warning("media %s: %s", msg.get("action"), why)
        self.media_pub.force()  # show the result straight away

    def _cmd(self, msg: dict, source=None):
        source = source or self.hub_source
        cmd = msg.get("cmd")
        if cmd == "lock":
            if "lock" not in self.advertised or not config.enabled(self.cfg, "lock"):
                return
            self.pool.submit(self._lock)
        elif cmd == "screenshot":
            if "screenshot" not in self.advertised or not config.enabled(self.cfg, "screenshot"):
                return
            to = (msg.get("from") or {}).get("id")
            if not isinstance(to, str) or not to:
                log.warning("screenshot asked for without a sender; nowhere to send it")
                return
            self.pool.submit(self._screenshot, to, (msg.get("from") or {}).get("name", to), source)

    def _lock(self):
        if self.dry_run:
            log.info("lock (dry run): %s", " ".join(self.lock_cmd or []))
            return
        why = lock.lock(self.lock_cmd)
        log.info("locked the screen" if why is None else f"locking failed: {why}")

    def _screenshot(self, to: str, to_name: str, source=None):
        source = source or self.hub_source
        data, how = self.shooter.capture()
        if data is None:
            log.warning("screenshot failed: %s", how)
            return
        name = f"screenshot-{self.host}-{time.strftime('%Y%m%d-%H%M%S')}.png"
        try:
            # back the way the request came: uploaded to the hub, or offered over the direct link
            source.deliver_file(name, data, "image/png", to)
        except Exception as e:
            log.warning("sending the screenshot failed: %s", e)
            return
        log.info("sent %s (%d KB, %s) to %s", name, len(data) // 1024, how, to_name)

    def _clip(self, text):
        why = self.clip.apply(text)
        if why:
            log.warning("clipboard: couldn't apply: %s", why)

    def _dry_clip_write(self, text: str):
        log.info("clipboard (dry run): would set %d characters: %r", len(text), text[:60])
        return None
