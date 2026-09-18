"""The WebSocket to the hub: hello, then messages both ways, reconnecting with backoff.

Each connection starts by picking a route (routes.py): hub-local, the LAN
with the pinned certificate, or the tailnet. While on the tailnet it keeps
looking for the LAN, and moves over when it's there.
"""

from __future__ import annotations

import json
import logging
import threading
import time

from . import discovery, hub
from .hub import PinMismatch
from .pinning import check_peer, pinned_context

log = logging.getLogger("droplet_agent.connection")

PING_EVERY = 25       # seconds; the hub pings too
MAX_BACKOFF = 30
REVOKED_WAIT = 300    # a token the hub doesn't know won't start working by itself
MISMATCH_WAIT = 60    # nor will a certificate that isn't the pinned one
MAX_FRAME = 1024 * 1024
NET_POLL = 10         # seconds between looks at this machine's addresses
LAN_RECHECK = 180     # while on the tailnet, look for the LAN this often


class Revoked(Exception):
    pass


class Connection:
    def __init__(self, agent, router, token: str, *, net_poll: float = NET_POLL,
                 lan_recheck: float = LAN_RECHECK, local_addresses=discovery.local_addresses):
        self.agent = agent
        self.router = router
        self.token = token
        self.route: hub.Route | None = None
        self.ws = None
        self.net_poll = net_poll
        self.lan_recheck = lan_recheck
        self.local_addresses = local_addresses
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
        """Drop the connection and open a new one at once (to send a new hello, or change route)."""
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
            except PinMismatch as e:
                log.error("%s", e)
                wait = MISMATCH_WAIT
            except hub.HubError as e:
                log.warning("can't reach the hub: %s", e)
                wait = None
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

    def _open(self, route: hub.Route):
        from websockets.exceptions import InvalidStatus
        from websockets.sync.client import connect

        kwargs = dict(additional_headers={"Authorization": f"Bearer {self.token}"},
                      user_agent_header=hub.USER_AGENT, open_timeout=20, close_timeout=3,
                      ping_interval=PING_EVERY, ping_timeout=20, max_size=MAX_FRAME)
        if route.kind in ("lan", "hub-local"):
            kwargs["proxy"] = None  # straight to the hub, never through a proxy
        if route.pin:
            # checks the certificate inside the TLS handshake, before the
            # upgrade request (and the token in it) is sent
            kwargs["ssl"] = pinned_context(route.pin)
        try:
            ws = connect(route.ws, **kwargs)
        except InvalidStatus as e:
            if e.response.status_code == 403:
                raise Revoked("the hub doesn't let this device in (was it removed, or never allowed?). "
                              "Run droplet-agent setup again.") from e
            raise
        if route.pin:
            try:
                # and once more on the established transport, before hello
                check_peer(ws.socket, route.pin, route.url)
            except BaseException:
                ws.close()
                raise
        return ws

    def _session(self, stop: threading.Event):
        from websockets.exceptions import ConnectionClosed

        route = self.router.select()
        if self.router.warning:
            log.warning("%s", self.router.warning)
        ws = self._open(route)
        done = threading.Event()
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
            self.route = route
            self.agent.route = route
            log.info("connected over %s", route.describe())
            self.agent.on_connected(welcome)
            threading.Thread(target=self._watch, args=(route, done, stop), name="route-watch",
                             daemon=True).start()
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
            done.set()
            self.ws = None
            try:
                ws.close()
            except Exception:
                pass

    def _watch(self, route: hub.Route, done: threading.Event, stop: threading.Event):
        """Move to a better route when one appears; leave a dead one quickly.

        Every NET_POLL seconds it looks at this machine's addresses: a change
        means Wi-Fi was joined or left, or a VPN went up or down. On the
        tailnet it then looks for the LAN at once, and every LAN_RECHECK
        seconds anyway. On the LAN it checks the hub still answers there.
        """
        addrs = self._addresses()
        next_look = time.monotonic() + self.lan_recheck
        while not done.wait(self.net_poll) and not stop.is_set():
            now_addrs = self._addresses()
            changed = now_addrs != addrs
            addrs = now_addrs
            try:
                if route.kind in ("lan", "hub-local"):
                    if changed and not self.router.still_works(route):
                        log.info("the network changed and the hub no longer answers at %s; "
                                 "finding it again", route.url)
                        self.reconnect()
                        return
                elif changed or time.monotonic() >= next_look:
                    next_look = time.monotonic() + self.lan_recheck
                    better = self.router.nearer(route)
                    if better is not None and not done.is_set():
                        log.info("the hub is reachable over %s now; moving over", better.describe())
                        self.reconnect()
                        return
            except Exception:
                log.exception("checking the route failed")

    def _addresses(self) -> set:
        try:
            return self.local_addresses()
        except Exception:
            return set()
