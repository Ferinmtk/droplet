"""The WebSocket to the hub: hello, then messages both ways, reconnecting with backoff."""

from __future__ import annotations

import json
import logging
import threading
import time

from . import hub

log = logging.getLogger("droplet_agent.connection")

PING_EVERY = 25       # seconds; the hub pings too
MAX_BACKOFF = 30
REVOKED_WAIT = 300    # a token the hub doesn't know won't start working by itself
MAX_FRAME = 1024 * 1024


class Revoked(Exception):
    pass


class Connection:
    def __init__(self, agent, hub_url: str, token: str):
        self.agent = agent
        self.url = hub.ws_url(hub_url)
        self.token = token
        self.ws = None
        self._send_lock = threading.Lock()
        self._reconnect_now = False
        agent.transport = self.send
        agent.on_caps_changed = self.reconnect

    def send(self, msg: dict) -> bool:
        ws = self.ws
        if ws is None:
            return False
        try:
            with self._send_lock:
                ws.send(json.dumps(msg))
            return True
        except Exception:
            return False

    def reconnect(self):
        """Drop the connection and open a new one at once (to send a new hello)."""
        self._reconnect_now = True
        ws = self.ws
        if ws is not None:
            try:
                ws.close()
            except Exception:
                pass

    def run(self, stop: threading.Event):
        backoff = 1
        while not stop.is_set():
            started = time.monotonic()
            self._reconnect_now = False
            try:
                self._session(stop)
                wait = None
            except Revoked as e:
                log.error("%s", e)
                wait = REVOKED_WAIT
            except Exception as e:
                log.warning("connection to the hub failed: %s", e)
                wait = None
            if stop.is_set():
                break
            if self._reconnect_now:
                backoff = 1
                continue
            if time.monotonic() - started > 60:
                backoff = 1  # it was up for a while: this is a fresh drop
            if wait is None:
                wait, backoff = backoff, min(backoff * 2, MAX_BACKOFF)
            log.info("reconnecting in %d s", wait)
            stop.wait(wait)

    def _session(self, stop: threading.Event):
        from websockets.exceptions import ConnectionClosed
        from websockets.sync.client import connect

        ws = connect(self.url, additional_headers={"Authorization": f"Bearer {self.token}"},
                     user_agent_header=hub.USER_AGENT, open_timeout=20, close_timeout=3,
                     ping_interval=PING_EVERY, ping_timeout=20, max_size=MAX_FRAME)
        try:
            ws.send(json.dumps(self.agent.hello()))
            try:
                welcome = json.loads(ws.recv(timeout=20))
            except ConnectionClosed as e:
                rcvd = e.rcvd
                if rcvd is not None and rcvd.code == 1008:
                    raise Revoked(f"the hub refused this agent ({rcvd.reason or 'policy'}): was the device "
                                  "removed? Run `droplet-agent setup` again with a new code.") from e
                raise
            if not isinstance(welcome, dict) or welcome.get("t") != "welcome":
                raise RuntimeError(f"expected a welcome, got {str(welcome)[:80]}")
            self.ws = ws
            self.agent.on_connected(welcome)
            # something may have started working (the portal) while we were
            # saying hello; if so, say it again
            self.agent.check_caps()
            while not stop.is_set():
                try:
                    raw = ws.recv(timeout=1)
                except TimeoutError:
                    continue
                try:
                    msg = json.loads(raw)
                except (ValueError, TypeError):
                    continue
                self.agent.dispatch(msg)
        except ConnectionClosed as e:
            if not self._reconnect_now:
                log.info("the hub closed the connection (%s)", e)
        finally:
            self.ws = None
            try:
                ws.close()
            except Exception:
                pass
