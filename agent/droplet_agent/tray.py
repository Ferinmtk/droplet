"""droplet in the system tray: `droplet-agent tray`.

A StatusNotifierItem (the tray protocol KDE Plasma speaks, and GNOME with
the AppIndicator extension, and most Wayland bars) with its menu exported
over com.canonical.dbusmenu, both served with jeepney, so no toolkit is
needed. It's a separate process from the agent, started with the graphical
session, and it does everything through the agent's control socket, the
same way the CLI does: if the agent isn't running, the menu says so and
the tray keeps trying.

The menu is rebuilt from the agent's `status` every few seconds (and when
it's about to open), and the desktop is told only when something changed.
Actions (picking files, sending, ringing, answering a pairing request) run
in worker threads, never in the thread answering D-Bus.

One tray per session: it owns io.github.ferinmtk.DropletAgent.Tray and lets
a newer one take it over, so starting it again (or upgrading) replaces it.
"""

from __future__ import annotations

import logging
import os
import queue
import shutil
import signal
import struct
import subprocess
import sys
import threading
import zlib
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import unquote, urlsplit

from . import APP_ID

log = logging.getLogger("droplet_agent.tray")

TRAY_ID = f"{APP_ID}.Tray"            # the single-instance bus name, and the autostart file's name
ITEM_PATH = "/StatusNotifierItem"
MENU_PATH = "/MenuBar"
ITEM_IFACE = "org.kde.StatusNotifierItem"
MENU_IFACE = "com.canonical.dbusmenu"
PROPS_IFACE = "org.freedesktop.DBus.Properties"
INTROSPECT_IFACE = "org.freedesktop.DBus.Introspectable"
PEER_IFACE = "org.freedesktop.DBus.Peer"
WATCHER = "org.kde.StatusNotifierWatcher"
WATCHER_PATH = "/StatusNotifierWatcher"

POLL = 3.0        # seconds between status checks while the agent runs
RETRY = 5.0       # and while it doesn't
ICON_FILE = Path(__file__).with_name("tray_icon.bin")

ACTIVE, ATTENTION = "Active", "NeedsAttention"

# RequestName flags and answers
ALLOW_REPLACEMENT, REPLACE_EXISTING, DO_NOT_QUEUE = 1, 2, 4
PRIMARY_OWNER, ALREADY_OWNER = 1, 4


# --- what the tray shows -------------------------------------------------------

@dataclass
class Item:
    """One menu entry. `key` names it across rebuilds, so its D-Bus id stays put."""
    key: str
    label: str = ""
    enabled: bool = True
    separator: bool = False
    icon: str = ""
    action: tuple | None = None
    children: list = field(default_factory=list)


@dataclass
class View:
    items: list
    status: str = ACTIVE
    tooltip: str = ""
    running: bool = True


def _plural(n: int, one: str, many: str) -> str:
    return f"{n} {one if n == 1 else many}"


def peer_state(p: dict) -> str:
    if p.get("link"):
        return "connected"
    if p.get("on_lan"):
        return "nearby"
    return "not reachable"


OS_ICONS = {"android": "smartphone", "ios": "smartphone", "windows": "computer", "linux": "computer",
            "macos": "computer"}


def build_view(status: dict | None, app: bool = True) -> View:
    """The menu, tooltip and status for an answer to the agent's `status` (None: it isn't running).
    `app`: Droplet's window can be opened (PySide6 is installed)."""
    downloads = Item("open-downloads", "Open received files", icon="folder-download", action=("open-downloads",))
    top = [Item("open-app", "Open Droplet", icon=LAUNCHER_ID, action=("open-app",)),
           Item("sep-app", separator=True)] if app else []
    if status is None:
        return View(items=[*top, Item("not-running", "droplet agent isn't running", enabled=False),
                           Item("sep-end", separator=True), downloads],
                    tooltip="droplet agent isn't running", running=False)

    items = [*top, Item("header", status.get("name") or "this computer", enabled=False, icon="computer"),
             Item("sep-top", separator=True)]
    incoming = [r for r in status.get("incoming") or [] if r.get("request")]
    for r in incoming:
        rid, name = str(r["request"]), str(r.get("name") or "a device")
        items.append(Item(f"pair:{rid}", f"{name} wants to pair (code {r.get('code', '?')})",
                          icon="dialog-question", children=[
                              Item(f"pair:{rid}:accept", "Accept", icon="dialog-ok-apply",
                                   action=("pair-answer", rid, True, name)),
                              Item(f"pair:{rid}:decline", "Decline", icon="dialog-cancel",
                                   action=("pair-answer", rid, False, name)),
                          ]))
    if incoming:
        items.append(Item("sep-pair", separator=True))

    peers = [p for p in status.get("peers") or [] if p.get("id")]
    if not peers:
        items.append(Item("no-peers", "No paired devices yet", enabled=False))
    for p in peers:
        pid, name = str(p["id"]), str(p.get("name") or p["id"])
        items.append(Item(f"peer:{pid}", f"{name} — {peer_state(p)}", icon=OS_ICONS.get(p.get("os") or "", ""),
                          children=[
                              Item(f"peer:{pid}:files", "Send files…", icon="document-send",
                                   action=("send-files", pid, name)),
                              Item(f"peer:{pid}:clip", "Send clipboard", icon="edit-paste",
                                   action=("send-clipboard", pid, name)),
                              Item(f"peer:{pid}:ring", "Ring", icon="preferences-desktop-notification-bell",
                                   action=("ring", pid, name)),
                          ]))
    items += [Item("sep-end", separator=True), downloads]

    connected = sum(1 for p in peers if p.get("link"))
    if not peers:
        tip = "No paired devices yet"
    elif connected:
        tip = _plural(connected, "device", "devices") + " connected"
    else:
        tip = "No devices connected"
    if incoming:
        asking = ", ".join(str(r.get("name") or "a device") for r in incoming)
        tip += f". {asking} {'wants' if len(incoming) == 1 else 'want'} to pair"
    return View(items=items, status=ATTENTION if incoming else ACTIVE, tooltip=tip)


def _shape(items) -> tuple:
    return tuple((i.key, i.label, i.enabled, i.separator, i.icon, i.action, _shape(i.children)) for i in items)


def menu_label(text: str) -> str:
    """dbusmenu reads "_" as the mnemonic marker; a literal one is "__"."""
    return text.replace("_", "__")


class Menu:
    """The dbusmenu side: ids, revisions, layouts. Thread-safe."""

    ROOT = 0

    def __init__(self):
        self._lock = threading.Lock()
        self._ids: dict[str, int] = {}
        self._next = 1
        self.revision = 1
        self._shape = None
        self._items: dict[int, Item] = {}
        self._children: dict[int, list[int]] = {self.ROOT: []}

    def set(self, items: list) -> bool:
        """Show these items. True when that changed anything (and the revision went up)."""
        shape = _shape(items)
        with self._lock:
            if shape == self._shape:
                return False
            found: dict[int, Item] = {}
            children: dict[int, list[int]] = {}

            def walk(parent: int, its):
                children[parent] = []
                for it in its:
                    iid = self._ids.get(it.key)
                    if iid is None:
                        iid = self._ids[it.key] = self._next
                        self._next += 1
                    found[iid] = it
                    children[parent].append(iid)
                    walk(iid, it.children)

            walk(self.ROOT, items)
            first = self._shape is None
            self._items, self._children, self._shape = found, children, shape
            if not first:
                self.revision += 1
            return True

    def id_of(self, key: str) -> int | None:
        with self._lock:
            iid = self._ids.get(key)
            return iid if iid in self._items else None

    def properties(self, iid: int, names=()) -> dict:
        """An item's dbusmenu properties: {name: (signature, value)}. Defaults are left out."""
        if iid == self.ROOT:
            props = {"children-display": ("s", "submenu")}
        else:
            it = self._items[iid]
            if it.separator:
                props = {"type": ("s", "separator")}
            else:
                props = {"label": ("s", menu_label(it.label))}
                if not it.enabled:
                    props["enabled"] = ("b", False)
                if it.icon:
                    props["icon-name"] = ("s", it.icon)
                if it.children:
                    props["children-display"] = ("s", "submenu")
        if names:
            props = {k: v for k, v in props.items() if k in names}
        return props

    def _node(self, iid: int, depth: int, names) -> tuple:
        kids = []
        if depth != 0:
            kids = [("(ia{sv}av)", self._node(c, depth - 1, names)) for c in self._children.get(iid, [])]
        return (iid, self.properties(iid, names), kids)

    def layout(self, parent: int, depth: int, names=()) -> tuple:
        """GetLayout's answer: (revision, (id, properties, children))."""
        with self._lock:
            if parent != self.ROOT and parent not in self._items:
                raise KeyError(parent)
            return (self.revision, self._node(parent, depth, names))

    def group_properties(self, ids, names=()) -> list:
        with self._lock:
            ids = list(ids) or [self.ROOT, *self._items]
            return [(i, self.properties(i, names)) for i in ids if i == self.ROOT or i in self._items]

    def property(self, iid: int, name: str) -> tuple:
        with self._lock:
            if iid != self.ROOT and iid not in self._items:
                raise KeyError(iid)
            return self.properties(iid)[name]

    def known(self, iid: int) -> bool:
        with self._lock:
            return iid == self.ROOT or iid in self._items

    def action(self, iid: int) -> tuple | None:
        with self._lock:
            it = self._items.get(iid)
            return it.action if it is not None and it.enabled else None


# --- the icon ------------------------------------------------------------------

def load_pixmaps(path: Path = ICON_FILE) -> list:
    """[(width, height, ARGB32 big-endian bytes)] from the generated icon file."""
    try:
        data = zlib.decompress(path.read_bytes())
    except (OSError, zlib.error) as e:
        log.warning("tray: no icon (%s)", e)
        return []
    out, pos = [], 0
    while pos + 8 <= len(data):
        w, h = struct.unpack_from(">II", data, pos)
        pos += 8
        out.append((w, h, data[pos:pos + w * h * 4]))
        pos += w * h * 4
    return out


def greyed(pixmaps: list) -> list:
    """The icon washed out, for when the agent isn't running."""
    out = []
    for w, h, px in pixmaps:
        b = bytearray(px)
        for i in range(0, len(b), 4):
            y = (b[i + 1] * 30 + b[i + 2] * 59 + b[i + 3] * 11) // 100
            b[i] = b[i] * 3 // 5
            b[i + 1] = b[i + 2] = b[i + 3] = y
        out.append((w, h, bytes(b)))
    return out


def _disc(b: bytearray, w: int, h: int, cx: float, cy: float, r: float, rgb: tuple):
    """Paint a smooth filled circle over ARGB pixels (4x4 samples a pixel)."""
    for y in range(max(0, int(cy - r - 1)), min(h, int(cy + r + 2))):
        for x in range(max(0, int(cx - r - 1)), min(w, int(cx + r + 2))):
            inside = sum(1 for sy in range(4) for sx in range(4)
                         if (x + (sx + .5) / 4 - cx) ** 2 + (y + (sy + .5) / 4 - cy) ** 2 <= r * r)
            if not inside:
                continue
            cov = inside / 16
            i = (y * w + x) * 4
            a0 = b[i] / 255
            a = cov + a0 * (1 - cov)
            for k in range(3):
                b[i + 1 + k] = round((rgb[k] * cov + b[i + 1 + k] * a0 * (1 - cov)) / a) if a else 0
            b[i] = round(a * 255)


def badged(pixmaps: list) -> list:
    """The icon with an orange dot: someone is asking to pair."""
    out = []
    for w, h, px in pixmaps:
        b = bytearray(px)
        r = max(3.0, w * 0.2)
        cx, cy = w - r - w * 0.04, h - r - h * 0.04
        _disc(b, w, h, cx, cy, r + max(1.0, w * 0.05), (14, 26, 28))
        _disc(b, w, h, cx, cy, r, (255, 159, 26))
        out.append((w, h, bytes(b)))
    return out


@dataclass
class Icons:
    normal: list
    attention: list
    off: list

    @classmethod
    def load(cls, path: Path = ICON_FILE) -> "Icons":
        px = load_pixmaps(path)
        return cls(normal=px, attention=badged(px), off=greyed(px))


# --- the D-Bus objects -----------------------------------------------------------

class DBusError(Exception):
    def __init__(self, name: str, text: str):
        super().__init__(text)
        self.name = name


def _unknown(what: str) -> DBusError:
    return DBusError("org.freedesktop.DBus.Error.UnknownMethod", f"no {what} here")


ITEM_XML = """<interface name="org.kde.StatusNotifierItem">
<property name="Category" type="s" access="read"/><property name="Id" type="s" access="read"/>
<property name="Title" type="s" access="read"/><property name="Status" type="s" access="read"/>
<property name="WindowId" type="i" access="read"/><property name="IconThemePath" type="s" access="read"/>
<property name="IconName" type="s" access="read"/><property name="IconPixmap" type="a(iiay)" access="read"/>
<property name="OverlayIconName" type="s" access="read"/>
<property name="OverlayIconPixmap" type="a(iiay)" access="read"/>
<property name="AttentionIconName" type="s" access="read"/>
<property name="AttentionIconPixmap" type="a(iiay)" access="read"/>
<property name="AttentionMovieName" type="s" access="read"/>
<property name="ToolTip" type="(sa(iiay)ss)" access="read"/>
<property name="ItemIsMenu" type="b" access="read"/><property name="Menu" type="o" access="read"/>
<method name="ContextMenu"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
<method name="Activate"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
<method name="SecondaryActivate"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
<method name="Scroll"><arg name="delta" type="i" direction="in"/><arg name="orientation" type="s" direction="in"/></method>
<signal name="NewTitle"/><signal name="NewIcon"/><signal name="NewAttentionIcon"/>
<signal name="NewOverlayIcon"/><signal name="NewToolTip"/>
<signal name="NewStatus"><arg name="status" type="s"/></signal>
</interface>"""

MENU_XML = """<interface name="com.canonical.dbusmenu">
<property name="Version" type="u" access="read"/><property name="TextDirection" type="s" access="read"/>
<property name="Status" type="s" access="read"/><property name="IconThemePath" type="as" access="read"/>
<method name="GetLayout"><arg type="i" name="parentId" direction="in"/>
<arg type="i" name="recursionDepth" direction="in"/><arg type="as" name="propertyNames" direction="in"/>
<arg type="u" name="revision" direction="out"/><arg type="(ia{sv}av)" name="layout" direction="out"/></method>
<method name="GetGroupProperties"><arg type="ai" name="ids" direction="in"/>
<arg type="as" name="propertyNames" direction="in"/><arg type="a(ia{sv})" name="properties" direction="out"/></method>
<method name="GetProperty"><arg type="i" name="id" direction="in"/><arg type="s" name="name" direction="in"/>
<arg type="v" name="value" direction="out"/></method>
<method name="Event"><arg type="i" name="id" direction="in"/><arg type="s" name="eventId" direction="in"/>
<arg type="v" name="data" direction="in"/><arg type="u" name="timestamp" direction="in"/></method>
<method name="EventGroup"><arg type="a(isvu)" name="events" direction="in"/>
<arg type="ai" name="idErrors" direction="out"/></method>
<method name="AboutToShow"><arg type="i" name="id" direction="in"/><arg type="b" name="needUpdate" direction="out"/></method>
<method name="AboutToShowGroup"><arg type="ai" name="ids" direction="in"/>
<arg type="ai" name="updatesNeeded" direction="out"/><arg type="ai" name="idErrors" direction="out"/></method>
<signal name="ItemsPropertiesUpdated"><arg type="a(ia{sv})" name="updatedProps"/>
<arg type="a(ias)" name="removedProps"/></signal>
<signal name="LayoutUpdated"><arg type="u" name="revision"/><arg type="i" name="parent"/></signal>
<signal name="ItemActivationRequested"><arg type="i" name="id"/><arg type="u" name="timestamp"/></signal>
</interface>"""

COMMON_XML = """<interface name="org.freedesktop.DBus.Introspectable">
<method name="Introspect"><arg name="xml" type="s" direction="out"/></method></interface>
<interface name="org.freedesktop.DBus.Properties">
<method name="Get"><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="out"/></method>
<method name="GetAll"><arg type="s" direction="in"/><arg type="a{sv}" direction="out"/></method>
<signal name="PropertiesChanged"><arg type="s"/><arg type="a{sv}"/><arg type="as"/></signal>
</interface>"""


def _introspection(path: str) -> str:
    head = '<!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN" ' \
           '"http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">\n<node>\n'
    if path == ITEM_PATH:
        return head + ITEM_XML + COMMON_XML + "</node>"
    if path == MENU_PATH:
        return head + MENU_XML + COMMON_XML + "</node>"
    nodes = {"/": ["StatusNotifierItem", "MenuBar"]}.get(path, [])
    return head + "".join(f'<node name="{n}"/>' for n in nodes) + "</node>"


class TrayObjects:
    """The tray's two D-Bus objects, without the bus: method calls in, replies and signals out.

    `dispatch` answers one call as (signature, body, action). `action` is a
    menu action to run (elsewhere), ("refresh",) when the menu is about to
    open, or None. `show` takes a new View and returns the signals to emit.
    """

    def __init__(self, icons: Icons | None = None):
        self.icons = icons if icons is not None else Icons.load()
        self.menu = Menu()
        self._lock = threading.Lock()
        self.view = View(items=[], running=False)
        self.menu.set([])

    def show(self, view: View) -> list:
        """Signals as (path, interface, member, signature, body)."""
        with self._lock:
            old, self.view = self.view, view
        out = []
        if self.menu.set(view.items):
            out.append((MENU_PATH, MENU_IFACE, "LayoutUpdated", "ui", (self.menu.revision, Menu.ROOT)))
        if old.status != view.status:
            out.append((ITEM_PATH, ITEM_IFACE, "NewStatus", "s", (view.status,)))
            out.append((ITEM_PATH, ITEM_IFACE, "NewAttentionIcon", "", ()))
            out.append((MENU_PATH, PROPS_IFACE, "PropertiesChanged", "sa{sv}as",
                        (MENU_IFACE, {"Status": self.menu_properties()["Status"]}, [])))
        if old.tooltip != view.tooltip:
            out.append((ITEM_PATH, ITEM_IFACE, "NewToolTip", "", ()))
        if old.running != view.running:
            out.append((ITEM_PATH, ITEM_IFACE, "NewIcon", "", ()))
        return out

    def item_properties(self) -> dict:
        with self._lock:
            v = self.view
        icon = self.icons.normal if v.running else self.icons.off
        return {
            "Category": ("s", "Communications"),
            "Id": ("s", "droplet"),
            "Title": ("s", "droplet"),
            "Status": ("s", v.status),
            "WindowId": ("i", 0),
            "IconThemePath": ("s", ""),
            "IconName": ("s", ""),
            "IconPixmap": ("a(iiay)", icon),
            "OverlayIconName": ("s", ""),
            "OverlayIconPixmap": ("a(iiay)", []),
            "AttentionIconName": ("s", ""),
            "AttentionIconPixmap": ("a(iiay)", self.icons.attention),
            "AttentionMovieName": ("s", ""),
            "ToolTip": ("(sa(iiay)ss)", ("", [], "droplet", v.tooltip)),
            "ItemIsMenu": ("b", True),
            "Menu": ("o", MENU_PATH),
        }

    def menu_properties(self) -> dict:
        with self._lock:
            attention = self.view.status == ATTENTION
        return {
            "Version": ("u", 3),
            "TextDirection": ("s", "ltr"),
            "Status": ("s", "notice" if attention else "normal"),
            "IconThemePath": ("as", []),
        }

    def _props(self, path: str, member: str, body: tuple):
        objects = {ITEM_PATH: (ITEM_IFACE, self.item_properties),
                   MENU_PATH: (MENU_IFACE, self.menu_properties)}
        if path not in objects:
            raise _unknown(f"object {path}")
        iface, get = objects[path]
        if member == "GetAll":
            return "a{sv}", ((get() if body[0] in (iface, "") else {}),), None
        if member == "Get":
            name = body[1]
            props = get() if body[0] in (iface, "") else {}
            if name not in props:
                raise DBusError("org.freedesktop.DBus.Error.UnknownProperty", f"no property {name}")
            return "v", (props[name],), None
        if member == "Set":
            raise DBusError("org.freedesktop.DBus.Error.PropertyReadOnly", "these properties are read-only")
        raise _unknown(f"method {member}")

    def _item(self, member: str, body: tuple):
        if member in ("ContextMenu", "Activate", "SecondaryActivate", "Scroll", "ProvideXdgActivationToken"):
            return "", (), None
        raise _unknown(f"method {member}")

    def _menu(self, member: str, body: tuple):
        m = self.menu
        if member == "GetLayout":
            parent, depth, names = body
            try:
                return "u(ia{sv}av)", m.layout(parent, depth, tuple(names)), None
            except KeyError:
                raise DBusError("org.freedesktop.DBus.Error.InvalidArgs", f"no menu item {parent}") from None
        if member == "GetGroupProperties":
            ids, names = body
            return "a(ia{sv})", (m.group_properties(ids, tuple(names)),), None
        if member == "GetProperty":
            iid, name = body
            try:
                return "v", (m.property(iid, name),), None
            except KeyError:
                raise DBusError("org.freedesktop.DBus.Error.InvalidArgs", f"no property {name} on {iid}") from None
        if member == "Event":
            iid, event = body[0], body[1]
            if not m.known(iid):
                raise DBusError("org.freedesktop.DBus.Error.InvalidArgs", f"no menu item {iid}")
            return "", (), (m.action(iid) if event == "clicked" else None)
        if member == "EventGroup":
            errors, action = [], None
            for iid, event, _data, _ts in body[0]:
                if not m.known(iid):
                    errors.append(iid)
                elif event == "clicked" and action is None:
                    action = m.action(iid)
            return "ai", (errors,), action
        if member == "AboutToShow":
            return "b", (False,), ("refresh",)
        if member == "AboutToShowGroup":
            return "aiai", ([], [i for i in body[0] if not m.known(i)]), ("refresh",)
        raise _unknown(f"method {member}")

    def dispatch(self, path: str, interface: str | None, member: str, body: tuple):
        if interface == INTROSPECT_IFACE and member == "Introspect":
            return "s", (_introspection(path),), None
        if interface == PEER_IFACE:
            if member == "Ping":
                return "", (), None
            if member == "GetMachineId":
                try:
                    return "s", (Path("/etc/machine-id").read_text().strip(),), None
                except OSError:
                    raise DBusError("org.freedesktop.DBus.Error.Failed", "no machine id") from None
        if interface == PROPS_IFACE:
            return self._props(path, member, body)
        try:
            if path == ITEM_PATH and interface in (ITEM_IFACE, None):
                return self._item(member, body)
            if path == MENU_PATH and interface in (MENU_IFACE, None):
                return self._menu(member, body)
        except (ValueError, TypeError, IndexError) as e:
            raise DBusError("org.freedesktop.DBus.Error.InvalidArgs", str(e)) from None
        raise _unknown(f"{interface}.{member} on {path}")


# --- what the menu does ------------------------------------------------------------

def _uri_path(uri: str) -> Path | None:
    u = urlsplit(uri)
    return Path(unquote(u.path)) if u.scheme == "file" and u.path else None


def summarise(name: str, done: list, waiting: list, failed: list) -> tuple[str, str]:
    """A notification (title, body) for files sent to `name`. Each list holds file names
    (failed: (file name, why))."""
    def files(names):
        return names[0] if len(names) == 1 else _plural(len(names), "file", "files")

    if failed:
        title = f"Some files didn't reach {name}" if done or waiting else f"Couldn't send to {name}"
    elif waiting:
        title = f"{name} can't be reached right now"
    else:
        title = f"Sent to {name}"
    lines = []
    if done:
        lines.append(f"Sent {files(done)}." if failed or waiting else files(done))
    if waiting:
        lines.append(f"{files(waiting)} {'waits' if len(waiting) == 1 else 'wait'} in the outbox until it's back.")
    lines += [f"{f}: {why}" for f, why in failed[:5]]
    if len(failed) > 5:
        lines.append(f"and {len(failed) - 5} more")
    return title, "\n".join(lines)


class Actions:
    """Runs menu actions in their own threads. `call` is control.call; `notify(title, body)`."""

    def __init__(self, call, notify, refresh=lambda: None):
        self.call = call
        self.notify = notify
        self.refresh = refresh

    def run(self, action: tuple):
        threading.Thread(target=self._run, args=(action,), name=f"tray-{action[0]}", daemon=True).start()

    def _run(self, action: tuple):
        from .mesh import control
        kind, *args = action
        try:
            getattr(self, kind.replace("-", "_"))(*args)
        except control.NotRunning:
            self.notify("droplet agent isn't running", "Start it with: systemctl --user start droplet-agent")
        except Exception as e:
            log.exception("tray: %s failed", kind)
            self.notify("droplet", f"That didn't work: {e}")

    def _ask(self, request: dict, timeout: float = 30) -> dict:
        out = self.call(request, timeout=timeout)
        if out.get("error"):
            raise RuntimeError(out["error"])
        return out

    @staticmethod
    def _route(out: dict) -> str:
        from .cli import ROUTE_TEXT
        return ROUTE_TEXT.get(out.get("route"), out.get("route") or "")

    def pick_files(self, name: str) -> list:
        from . import portal
        try:
            p = portal.Portal(APP_ID)
        except portal.PortalError as e:
            self.notify("Can't pick files", f"{e}. Or: droplet-agent send-file {name} FILE…")
            return []
        try:
            res = p.request("org.freedesktop.portal.FileChooser", "OpenFile", "ssa{sv}",
                            ("", f"Send files to {name}"),
                            {"multiple": ("b", True), "accept_label": ("s", "Send")})
        except portal.Cancelled:
            return []
        except portal.PortalError as e:
            self.notify("Can't pick files", str(e))
            return []
        finally:
            p.close()
        return [x for x in (_uri_path(u) for u in res.get("uris") or []) if x is not None]

    def send_files(self, peer: str, name: str, paths: list | None = None):
        paths = self.pick_files(name) if paths is None else paths
        if not paths:
            return
        jobs, failed = [], []
        for path in paths:
            out = self.call({"cmd": "send-file", "peer": peer, "path": str(path), "wait": 0}, timeout=30)
            if out.get("error"):
                failed.append((path.name, out["error"]))
            else:
                jobs.append((path.name, out["id"]))
        done, waiting = [], []
        for fname, jid in jobs:
            job = self.call({"cmd": "job", "id": jid, "wait": 3600}, timeout=3700)
            state = job.get("state")
            if job.get("error"):
                failed.append((fname, job["error"]))
            elif state == "done":
                done.append(fname)
            elif state == "failed":
                failed.append((fname, job.get("why") or "failed"))
            else:
                waiting.append(fname)
        self.notify(*summarise(name, done, waiting, failed))

    def send_clipboard(self, peer: str, name: str):
        from . import clip
        mode, why = clip.detect()
        if mode is None:
            self.notify("Can't read the clipboard", why)
            return
        text = clip.ClipboardSync(mode, lambda _t: True).reader()
        if not text:
            self.notify("Nothing to send", "The clipboard holds no text (or a password manager marked it secret).")
            return
        try:
            out = self._ask({"cmd": "clip", "peer": peer, "text": text})
        except RuntimeError as e:
            self.notify(f"Couldn't send the clipboard to {name}", str(e))
            return
        self.notify(f"Sent the clipboard to {name}", self._route(out).capitalize())

    def ring(self, peer: str, name: str):
        try:
            out = self._ask({"cmd": "ring", "peer": peer, "stop": False})
        except RuntimeError as e:
            self.notify(f"Couldn't ring {name}", str(e))
            return
        self.notify(f"Ringing {name}", self._route(out).capitalize())

    def pair_answer(self, request: str, accept: bool, name: str):
        try:
            out = self._ask({"cmd": "pair-answer", "request": request, "accept": accept})
        except RuntimeError as e:
            self.notify(f"Couldn't answer {name}", str(e))
        else:
            if accept:
                self.notify(f"Paired with {out.get('name') or name}",
                            "It's in the droplet menu now.")
        self.refresh()

    def open_app(self):
        """Droplet's window: a second one just brings the open one up."""
        subprocess.Popen([sys.executable, "-m", "droplet_agent", "app"], stdin=subprocess.DEVNULL,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)

    def open_downloads(self):
        from . import config
        d = config.downloads_dir(config.load())
        d.mkdir(parents=True, exist_ok=True)
        opener = shutil.which("xdg-open")
        if opener is None:
            self.notify("Received files", str(d))
            return
        subprocess.Popen([opener, str(d)], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL, start_new_session=True)


# --- the bus ---------------------------------------------------------------------

class Tray:
    def __init__(self, call=None, notify=None):
        from .mesh import control
        from .mesh.desktop import Desktop
        from . import app
        self.call = call or control.call
        self.has_app = app.available()
        self.objects = TrayObjects()
        self.actions = Actions(self.call, notify or Desktop(dry_run=False).notify, refresh=self.kick)
        self.stop = threading.Event()
        self._kick = threading.Event()
        self.router = None
        self.name = f"org.kde.StatusNotifierItem-{os.getpid()}-1"

    def kick(self):
        self._kick.set()

    def status(self) -> dict | None:
        from .mesh import control
        try:
            st = self.call({"cmd": "status"}, timeout=5)
        except (control.NotRunning, OSError, ValueError):
            return None
        return None if st.get("error") else st

    def refresh(self):
        for path, iface, member, sig, body in self.objects.show(build_view(self.status(), self.has_app)):
            self.emit(path, iface, member, sig, body)

    def emit(self, path, iface, member, sig, body):
        from jeepney import DBusAddress, new_signal
        if self.router is None:
            return
        try:
            self.router.send(new_signal(DBusAddress(path, interface=iface), member, sig or None, body))
        except OSError as e:
            log.warning("tray: lost the session bus (%s)", e)
            self.stop.set()

    def _poll(self):
        while not self.stop.is_set():
            self._kick.wait(POLL if self.objects.view.running else RETRY)
            self._kick.clear()
            if self.stop.is_set():
                return
            try:
                self.refresh()
            except Exception:
                log.exception("tray: refreshing failed")

    def _bus(self, msg):
        from jeepney.wrappers import unwrap_msg
        return unwrap_msg(self.router.send_and_get_reply(msg, timeout=5))

    def register(self):
        """Tell the tray host we're here (it may not be up yet; then it's done when it appears)."""
        from jeepney import DBusAddress, new_method_call
        addr = DBusAddress(WATCHER_PATH, bus_name=WATCHER, interface=WATCHER)
        try:
            self._bus(new_method_call(addr, "RegisterStatusNotifierItem", "s", (self.name,)))
            log.info("tray: registered with the tray")
        except Exception as e:
            log.info("tray: no tray to show in yet (%s); waiting for one", e)

    def _answer(self, msg):
        from jeepney import new_error, new_method_return
        from jeepney.low_level import HeaderFields, MessageFlag
        f = msg.header.fields
        path, iface, member = f.get(HeaderFields.path), f.get(HeaderFields.interface), f.get(HeaderFields.member)
        try:
            sig, body, action = self.objects.dispatch(path, iface, member, msg.body)
            reply = new_method_return(msg, sig or None, body)
        except DBusError as e:
            action, reply = None, new_error(msg, e.name, "s", (str(e),))
        except Exception as e:
            log.exception("tray: answering %s.%s failed", iface, member)
            action, reply = None, new_error(msg, "org.freedesktop.DBus.Error.Failed", "s", (str(e),))
        if not msg.header.flags & MessageFlag.no_reply_expected:
            self.router.send(reply)
        if action == ("refresh",):
            self.kick()
        elif action:
            log.info("tray: %s", action[0])
            self.actions.run(action)

    def run(self) -> int:
        from jeepney import MatchRule, message_bus
        from jeepney.io.threading import DBusRouter, open_dbus_connection
        from jeepney.low_level import HeaderFields, MessageType
        try:
            conn = open_dbus_connection(bus="SESSION")
        except Exception as e:
            print(f"Can't reach the session D-Bus ({e}). The tray runs inside your desktop session.",
                  file=sys.stderr)
            return 1
        self.router = DBusRouter(conn)
        q: queue.Queue = queue.Queue()
        filters = [self.router.filter(MatchRule(type="method_call"), queue=q),
                   self.router.filter(MatchRule(type="signal", interface="org.freedesktop.DBus",
                                                member="NameLost"), queue=q)]
        owner = MatchRule(type="signal", sender="org.freedesktop.DBus", interface="org.freedesktop.DBus",
                          member="NameOwnerChanged", path="/org/freedesktop/DBus")
        owner.add_arg_condition(0, WATCHER)
        filters.append(self.router.filter(owner, queue=q))
        try:
            got = self._bus(message_bus.RequestName(TRAY_ID, ALLOW_REPLACEMENT | REPLACE_EXISTING | DO_NOT_QUEUE))[0]
            if got not in (PRIMARY_OWNER, ALREADY_OWNER):
                print("Another droplet tray is running and won't step aside.", file=sys.stderr)
                return 1
            self._bus(message_bus.RequestName(self.name, DO_NOT_QUEUE))
            self._bus(message_bus.AddMatch(owner))
            self.refresh()
            threading.Thread(target=self._poll, name="tray-poll", daemon=True).start()
            threading.Thread(target=self.register, name="tray-register", daemon=True).start()
            for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
                signal.signal(sig, lambda *_: self.stop.set())
            log.info("tray: running as %s", self.name)
            while not self.stop.is_set():
                try:
                    msg = q.get(timeout=1)
                except queue.Empty:
                    continue
                member = msg.header.fields.get(HeaderFields.member)
                if msg.header.message_type == MessageType.method_call:
                    self._answer(msg)
                elif member == "NameLost" and msg.body == (TRAY_ID,):
                    log.info("tray: another droplet tray took over; leaving")
                    self.stop.set()
                elif member == "NameOwnerChanged" and msg.body[0] == WATCHER and msg.body[2]:
                    # the tray host (re)started: it doesn't know us yet
                    threading.Thread(target=self.register, name="tray-register", daemon=True).start()
            return 0
        finally:
            self.stop.set()
            self._kick.set()
            for f in filters:
                f.close()
            try:
                self.router.close()
            finally:
                conn.close()


def stop_running() -> bool:
    """Close the tray running in this session, if there is one. True if there was."""
    try:
        from jeepney import message_bus
        from jeepney.io.blocking import open_dbus_connection
        from jeepney.wrappers import unwrap_msg
        with open_dbus_connection(bus="SESSION") as conn:
            if not unwrap_msg(conn.send_and_get_reply(message_bus.NameHasOwner(TRAY_ID), timeout=5))[0]:
                return False
            # it lets itself be replaced, and leaves when it loses the name
            conn.send_and_get_reply(message_bus.RequestName(TRAY_ID, REPLACE_EXISTING | DO_NOT_QUEUE), timeout=5)
            conn.send_and_get_reply(message_bus.ReleaseName(TRAY_ID), timeout=5)
            return True
    except Exception as e:
        log.debug("couldn't reach a running tray: %s", e)
        return False


# --- starting with the desktop -----------------------------------------------------

def autostart_path() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config"
    return Path(base) / "autostart" / f"{TRAY_ID}.desktop"


def agent_command() -> str:
    """How the desktop should start this agent, as an Exec= value without the subcommand."""
    from . import config
    installed = config.data_dir() / "bin" / "droplet-agent"
    found = installed if installed.exists() else shutil.which("droplet-agent")
    if found:
        return _quote(str(found))
    return f"{_quote(sys.executable)} -m droplet_agent"


def _quote(arg: str) -> str:
    # the Desktop Entry spec's Exec quoting
    for c in ("\\", '"', "`", "$"):
        arg = arg.replace(c, "\\" + c)
    return f'"{arg}"'


def autostart_entry(command: str) -> str:
    # install.sh writes the same file
    return ("[Desktop Entry]\n"
            "Type=Application\n"
            "Name=droplet\n"
            "Comment=droplet in the system tray: send to your devices, answer pairing requests\n"
            f"Exec={command} tray\n"
            f"Icon={LAUNCHER_ID}\n"
            "Terminal=false\n"
            "X-GNOME-Autostart-enabled=true\n")


def enable_autostart() -> Path:
    path = autostart_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(autostart_entry(agent_command()))
    return path


def disable_autostart() -> bool:
    try:
        autostart_path().unlink()
        return True
    except FileNotFoundError:
        return False


def start_detached():
    """Start a tray that outlives this command (it replaces any running one)."""
    subprocess.Popen([sys.executable, "-m", "droplet_agent", "tray"], stdin=subprocess.DEVNULL,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)


def is_running() -> bool:
    """Whether a tray is up in this desktop session."""
    try:
        from jeepney import message_bus
        from jeepney.io.blocking import open_dbus_connection
        from jeepney.wrappers import unwrap_msg
        with open_dbus_connection(bus="SESSION") as conn:
            return bool(unwrap_msg(conn.send_and_get_reply(message_bus.NameHasOwner(TRAY_ID), timeout=5))[0])
    except Exception as e:
        log.debug("couldn't ask the session bus about the tray: %s", e)
        return False


# --- the launcher entry (Droplet in the app menu) -------------------------------------

LAUNCHER_ID = "io.github.ferinmtk.Droplet"
ICON_SIZES = (22, 32, 48, 64)


def _data_home() -> Path:
    return Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share")


def launcher_path() -> Path:
    return _data_home() / "applications" / f"{LAUNCHER_ID}.desktop"


def icon_paths() -> list[Path]:
    return [_data_home() / "icons/hicolor" / f"{n}x{n}" / "apps" / f"{LAUNCHER_ID}.png" for n in ICON_SIZES]


def png(width: int, height: int, argb: bytes) -> bytes:
    """A PNG from the tray's ARGB32 (big-endian) pixels, without an imaging library."""
    rows = bytearray()
    for y in range(height):
        rows.append(0)                       # no filter
        line = argb[y * width * 4:(y + 1) * width * 4]
        for x in range(0, len(line), 4):
            a, r, g, b = line[x:x + 4]
            rows += bytes((r, g, b, a))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b""))


def launcher_entry(command: str) -> str:
    return ("[Desktop Entry]\n"
            "Type=Application\n"
            "Name=Droplet\n"
            "GenericName=Share with your devices\n"
            "Comment=Send files, messages and your clipboard to your phone and computers\n"
            f"Exec={command} open\n"
            f"Icon={LAUNCHER_ID}\n"
            "Terminal=false\n"
            "StartupNotify=false\n"
            "Categories=Network;FileTransfer;Utility;\n"
            "Keywords=share;send;files;phone;clipboard;pair;droplet;\n")


def install_launcher() -> Path:
    """Put Droplet in the app menu, with the drop icon."""
    by_size = {w: (w, h, d) for w, h, d in load_pixmaps()}
    for size, path in zip(ICON_SIZES, icon_paths()):
        if size in by_size:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(png(*by_size[size]))
    path = launcher_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(launcher_entry(agent_command()))
    for tool in (["update-desktop-database", str(path.parent)],
                 ["gtk-update-icon-cache", "-q", "-t", str(_data_home() / "icons/hicolor")]):
        if shutil.which(tool[0]):
            subprocess.run(tool, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    return path


def remove_launcher() -> bool:
    removed = False
    for path in (launcher_path(), *icon_paths()):
        try:
            path.unlink()
            removed = True
        except FileNotFoundError:
            pass
    return removed


def where_text(status: dict | None) -> tuple[str, str]:
    """The notification shown when droplet is opened from the app menu."""
    if status is None:
        return ("droplet is in your system tray",
                "Its icon is greyed out because the droplet agent isn't running. "
                "Start it with: systemctl --user start droplet-agent")
    view = build_view(status)
    return ("droplet is in your system tray",
            f"Click the drop icon near the clock (it may be behind the ^ arrow). {view.tooltip}.")


def open_app() -> int:
    """What the app menu's Droplet does: make sure the tray is up, and open Droplet's window.
    Without PySide6 there's no window: a notification says where the tray is instead."""
    if not is_running():
        start_detached()
    from . import app
    if app.available():
        try:
            return app.run([])
        except ImportError as e:   # PySide6 is there but broken: say where the tray is instead
            log.warning("Droplet's window can't start: %s", e)
    from .mesh import control
    from .mesh.desktop import Desktop
    try:
        status = control.call({"cmd": "status"}, timeout=5)
        if status.get("error"):
            status = None
    except Exception:
        status = None
    title, body = where_text(status)
    Desktop(dry_run=False).notify(title, body, key="droplet-open")
    return 0


def run() -> int:
    return Tray().run()
