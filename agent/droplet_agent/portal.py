"""A small client for xdg-desktop-portal over D-Bus (with jeepney, pure Python).

Portal calls that need the user (a permission dialog) don't answer directly:
they return a Request object, which later emits a Response signal. The
signal can come any time after the call, so the match is set up before
calling, on the path the portal will use (derived from our handle_token).
"""

from __future__ import annotations

import logging
import queue
import secrets
import threading

log = logging.getLogger("droplet_agent.portal")

BUS_NAME = "org.freedesktop.portal.Desktop"
OBJECT_PATH = "/org/freedesktop/portal/desktop"
REGISTRY = "org.freedesktop.host.portal.Registry"

# Response codes
OK, CANCELLED, FAILED = 0, 1, 2


class PortalError(Exception):
    pass


class Cancelled(PortalError):
    """The user said no, or dismissed the dialog."""


def _unwrap(v):
    """jeepney gives variants as (signature, value); nested dicts too."""
    if isinstance(v, tuple) and len(v) == 2 and isinstance(v[0], str):
        return _unwrap(v[1])
    if isinstance(v, dict):
        return {k: _unwrap(x) for k, x in v.items()}
    return v


class Portal:
    """One D-Bus connection to the portal. Thread-safe for sending."""

    def __init__(self, app_id: str | None = None):
        try:
            from jeepney.io.threading import DBusRouter, open_dbus_connection
        except ImportError as e:  # pragma: no cover - dependency missing
            raise PortalError("the jeepney package isn't installed") from e
        try:
            self._conn = open_dbus_connection(bus="SESSION")
        except Exception as e:
            raise PortalError(f"can't reach the session D-Bus: {e}") from e
        self.router = DBusRouter(self._conn)
        # ":1.42" → "1_42", as the portal spells it in request paths
        self.sender = self.router.unique_name.lstrip(":").replace(".", "_")
        self.registered = False
        if app_id:
            self._register(app_id)

    def _address(self, interface: str):
        from jeepney import DBusAddress
        return DBusAddress(OBJECT_PATH, bus_name=BUS_NAME, interface=interface)

    def _register(self, app_id: str):
        """Tell the portal who we are, so a remembered permission sticks to us.

        Host apps have no app id otherwise. It must be the first portal call
        on the connection. Older portals don't have the Registry; that's fine.
        """
        from jeepney import new_method_call
        from jeepney.wrappers import unwrap_msg
        msg = new_method_call(self._address(REGISTRY), "Register", "sa{sv}", (app_id, {}))
        try:
            unwrap_msg(self.router.send_and_get_reply(msg, timeout=5))
            self.registered = True
        except Exception as e:
            log.info("portal: couldn't register as %s (%s); carrying on without", app_id, e)

    def call(self, interface: str, method: str, signature: str = "", args: tuple = (), timeout: float = 10):
        from jeepney import new_method_call
        from jeepney.wrappers import unwrap_msg
        msg = new_method_call(self._address(interface), method, signature, args)
        return unwrap_msg(self.router.send_and_get_reply(msg, timeout=timeout))

    def send(self, interface: str, method: str, signature: str, args: tuple):
        """Call without waiting for the reply (input events: order matters, latency too)."""
        from jeepney import new_method_call
        self.router.send(new_method_call(self._address(interface), method, signature, args))

    def get_property(self, interface: str, name: str, timeout: float = 5):
        from jeepney import Properties
        from jeepney.wrappers import unwrap_msg
        msg = Properties(self._address(interface)).get(name)
        return _unwrap(unwrap_msg(self.router.send_and_get_reply(msg, timeout=timeout))[0])

    def _match(self, add: bool, rule):
        from jeepney import message_bus
        from jeepney.wrappers import unwrap_msg
        msg = message_bus.AddMatch(rule) if add else message_bus.RemoveMatch(rule)
        try:
            unwrap_msg(self.router.send_and_get_reply(msg, timeout=5))
        except Exception as e:
            if add:
                raise PortalError(f"D-Bus refused a signal match: {e}") from e

    def request(self, interface: str, method: str, signature: str, args: tuple, options: dict,
                stop: threading.Event | None = None, timeout: float | None = None) -> dict:
        """Make a portal request and wait for its Response.

        `signature` covers `args` plus the trailing options dict. Waits until
        the user answers (or `timeout` seconds, or `stop` is set). Returns
        the results with variants unwrapped. Raises Cancelled or PortalError.
        """
        from jeepney import MatchRule
        token = "droplet" + secrets.token_hex(6)
        path = f"{OBJECT_PATH}/request/{self.sender}/{token}"
        rule = MatchRule(type="signal", interface="org.freedesktop.portal.Request",
                         member="Response", path=path)
        self._match(True, rule)
        try:
            with self.router.filter(rule, bufsize=4) as q:
                handle = self.call(interface, method, signature,
                                   (*args, {**options, "handle_token": ("s", token)}))[0]
                if handle != path:
                    # a portal older than handle_token support; the signal
                    # comes on the path it returned, which we can't have
                    # matched in time. Very old; say so rather than hang.
                    raise PortalError(f"the portal is too old (request path {handle})")
                waited = 0.0
                while True:
                    if stop is not None and stop.is_set():
                        raise PortalError("stopped")
                    try:
                        msg = q.get(timeout=1)
                        break
                    except queue.Empty:
                        waited += 1
                        if timeout is not None and waited >= timeout:
                            raise PortalError("no answer from the portal in time")
        finally:
            self._match(False, rule)
        code, results = msg.body
        if code == CANCELLED:
            raise Cancelled("the request was declined")
        if code != OK:
            raise PortalError(f"the portal refused (response {code})")
        return _unwrap(results)

    def watch_signal(self, interface: str, member: str, path: str, callback, stop: threading.Event):
        """Call `callback(body)` from a thread when the signal arrives (once)."""
        from jeepney import MatchRule
        rule = MatchRule(type="signal", interface=interface, member=member, path=path)
        self._match(True, rule)

        def wait():
            with self.router.filter(rule, bufsize=4) as q:
                while not stop.is_set():
                    try:
                        msg = q.get(timeout=1)
                    except queue.Empty:
                        continue
                    callback(msg.body)
                    return

        threading.Thread(target=wait, name=f"portal-{member}", daemon=True).start()

    def close(self):
        try:
            self.router.close()
        finally:
            self._conn.close()


def read_property(interface: str, name: str):
    """One read-only property of the portal, or None when unreachable. Never shows anything."""
    try:
        p = Portal()
    except PortalError:
        return None
    try:
        return p.get_property(interface, name)
    except Exception:
        return None
    finally:
        p.close()


def request_token() -> str:
    return "droplet" + secrets.token_hex(6)
