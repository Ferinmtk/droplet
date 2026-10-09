"""The tray: the menu built from the agent's status, and D-Bus answers that serialise."""

import struct

import pytest
from jeepney import DBusAddress, new_error, new_method_call, new_method_return, new_signal
from jeepney.low_level import HeaderFields, Parser

from droplet_agent import tray
from droplet_agent.tray import (ATTENTION, ACTIVE, ITEM_IFACE, ITEM_PATH, MENU_IFACE, MENU_PATH, Actions,
                                Icons, Menu, TrayObjects, build_view, summarise)

STATUS = {
    "id": "me", "name": "slim", "fp": "", "port": 1739,
    "peers": [
        {"id": "p1", "name": "phone", "link": "lan 192.168.1.5", "on_lan": True, "os": "android"},
        {"id": "p2", "name": "office_pc", "link": None, "on_lan": True, "os": "windows"},
        {"id": "p3", "name": "laptop", "link": None, "on_lan": False, "os": "linux"},
    ],
    "nearby": [], "outbox": [],
    "incoming": [{"request": "r1", "name": "friend", "code": "1234", "id": "f1", "os": "android"}],
}


@pytest.fixture(autouse=True)
def linux_tray(monkeypatch):
    """These are the Linux tray's (D-Bus, .desktop files) wherever they run; test_macos has the Mac's."""
    monkeypatch.setattr(tray, "MAC", False)


def labels(items):
    return [i.label for i in items if not i.separator]


def find(items, key):
    for i in items:
        if i.key == key:
            return i
        hit = find(i.children, key)
        if hit:
            return hit
    return None


# --- the menu model -------------------------------------------------------------

def test_view_lists_peers_with_their_state_and_actions():
    v = build_view(STATUS)
    assert v.running
    assert labels(v.items) == ["Open Droplet", "slim", "friend wants to pair (code 1234)", "phone — connected",
                               "office_pc — nearby", "laptop — not reachable", "Open received files"]
    assert find(v.items, "open-app").action == ("open-app",)
    header = find(v.items, "header")
    assert not header.enabled and header.action is None
    phone = find(v.items, "peer:p1")
    assert labels(phone.children) == ["Send files…", "Send clipboard", "Ring"]
    assert [c.action for c in phone.children] == [("send-files", "p1", "phone"), ("send-clipboard", "p1", "phone"),
                                                  ("ring", "p1", "phone")]
    assert find(v.items, "open-downloads").action == ("open-downloads",)


def test_pairing_requests_need_attention():
    v = build_view(STATUS)
    assert v.status == ATTENTION
    req = find(v.items, "pair:r1")
    assert [(c.label, c.action) for c in req.children] == [("Accept", ("pair-answer", "r1", True, "friend")),
                                                           ("Decline", ("pair-answer", "r1", False, "friend"))]
    assert v.tooltip == "1 device connected. friend wants to pair"
    assert build_view({**STATUS, "incoming": []}).status == ACTIVE


def test_tooltip_counts_connected_devices():
    two = {**STATUS, "incoming": [], "peers": [dict(p, link="lan x") for p in STATUS["peers"]]}
    assert build_view(two).tooltip == "3 devices connected"
    none = {**STATUS, "incoming": [], "peers": [dict(p, link=None) for p in STATUS["peers"]]}
    assert build_view(none).tooltip == "No devices connected"


def test_no_peers_says_so():
    v = build_view({"name": "slim", "peers": [], "incoming": []})
    item = find(v.items, "no-peers")
    assert item.label == "No paired devices yet" and not item.enabled
    assert v.tooltip == "No paired devices yet" and v.status == ACTIVE


def test_agent_not_running():
    v = build_view(None)
    assert not v.running and v.status == ACTIVE
    assert labels(v.items) == ["Open Droplet", "droplet agent isn't running", "Open received files"]
    assert not find(v.items, "not-running").enabled
    assert v.tooltip == "droplet agent isn't running"


def test_without_the_window_there_is_no_open_droplet():
    for status in (STATUS, None):
        v = build_view(status, app=False)
        assert find(v.items, "open-app") is None
        assert labels(v.items)[0] in ("slim", "droplet agent isn't running")
        assert not v.items[0].separator


def test_open_droplet_starts_the_window(monkeypatch):
    started = []
    monkeypatch.setattr(tray.subprocess, "Popen", lambda argv, **kw: started.append(argv))
    Actions(lambda *a, **k: {}, lambda *a: None).open_app()
    assert started and started[0][1:] == ["-m", "droplet_agent", "app"]


def test_menu_ids_stay_put_and_revision_moves_only_on_change():
    m = Menu()
    assert m.set(build_view(STATUS).items)
    rev, phone = m.revision, m.id_of("peer:p1:ring")
    assert not m.set(build_view(STATUS).items)
    assert m.revision == rev
    # the pairing request goes away: the layout changes, the ids that remain don't
    assert m.set(build_view({**STATUS, "incoming": []}).items)
    assert m.revision == rev + 1
    assert m.id_of("peer:p1:ring") == phone
    assert m.id_of("pair:r1") is None
    assert m.action(phone) == ("ring", "p1", "phone")
    assert m.action(m.id_of("header")) is None       # disabled items do nothing


def test_menu_layout_escapes_underscores_and_respects_depth():
    m = Menu()
    m.set(build_view(STATUS).items)
    rev, (root, props, kids) = m.layout(0, 1)
    assert root == 0 and props == {"children-display": ("s", "submenu")}
    by_label = {k[1][1].get("label", ("s", ""))[1]: k[1] for k in kids}
    assert "office__pc — nearby" in by_label
    assert by_label["office__pc — nearby"][2] == []    # depth 1: no grandchildren
    _, (_, _, deep) = m.layout(0, -1)
    assert any(k[1][2] for k in deep)
    _, (pid, only, _) = m.layout(m.id_of("peer:p1"), 0, ("label",))
    assert only == {"label": ("s", "phone — connected")}
    with pytest.raises(KeyError):
        m.layout(9999, -1)


# --- D-Bus: every answer and signal serialises for its signature -------------------

def round_trip(msg, serial=9):
    data = msg.serialise(serial=serial)
    parser = Parser()
    parser.add_data(data)
    out = parser.get_next_message()
    assert out is not None
    return out


def call(objects, path, iface, member, sig="", body=()):
    """Send `member` through a real serialise/parse, and serialise the answer."""
    msg = new_method_call(DBusAddress(path, bus_name=":1.2", interface=iface), member, sig or None, body)
    msg.header.fields[HeaderFields.sender] = ":1.1"
    parsed = round_trip(msg, serial=7)
    try:
        out_sig, out_body, action = objects.dispatch(path, iface, member, parsed.body)
        reply = new_method_return(parsed, out_sig or None, out_body)
    except tray.DBusError as e:
        action, reply = None, new_error(parsed, e.name, "s", (str(e),))
    back = round_trip(reply)
    return back, action


@pytest.fixture
def objects():
    icons = Icons.load()
    o = TrayObjects(icons=icons)
    o.show(build_view(STATUS))
    return o


def test_icon_file_has_the_sizes_the_tray_needs():
    px = tray.load_pixmaps()
    assert [(w, h) for w, h, _ in px] == [(22, 22), (32, 32), (48, 48), (64, 64)]
    assert all(len(data) == w * h * 4 for w, h, data in px)
    icons = Icons.load()
    assert [len(d) for *_, d in icons.attention] == [len(d) for *_, d in px]
    assert icons.attention != px and icons.off != px


def test_item_properties_serialise(objects):
    reply, _ = call(objects, ITEM_PATH, tray.PROPS_IFACE, "GetAll", "s", (ITEM_IFACE,))
    props = reply.body[0]
    assert props["Status"] == ("s", ATTENTION)
    assert props["Menu"] == ("o", MENU_PATH)
    assert props["ItemIsMenu"] == ("b", True)
    assert props["ToolTip"][1][2:] == ("droplet", "1 device connected. friend wants to pair")
    assert len(props["IconPixmap"][1]) == 4
    for name in props:
        reply, _ = call(objects, ITEM_PATH, tray.PROPS_IFACE, "Get", "ss", (ITEM_IFACE, name))
        assert reply.body[0] == props[name]


def test_menu_properties_serialise(objects):
    reply, _ = call(objects, MENU_PATH, tray.PROPS_IFACE, "GetAll", "s", (MENU_IFACE,))
    assert reply.body[0]["Version"] == ("u", 3)
    assert reply.body[0]["Status"] == ("s", "notice")


def test_menu_methods_serialise(objects):
    menu = objects.menu
    reply, _ = call(objects, MENU_PATH, MENU_IFACE, "GetLayout", "iias", (0, -1, []))
    rev, (root, _, kids) = reply.body
    assert rev == menu.revision and root == 0 and len(kids) == 11   # Open Droplet and its separator first
    reply, _ = call(objects, MENU_PATH, MENU_IFACE, "GetGroupProperties", "aias", ([1, 2, 3], ["label"]))
    assert [i for i, _ in reply.body[0]] == [1, 2, 3]
    reply, _ = call(objects, MENU_PATH, MENU_IFACE, "GetProperty", "is", (menu.id_of("header"), "label"))
    assert reply.body == (("s", "slim"),)
    reply, action = call(objects, MENU_PATH, MENU_IFACE, "AboutToShow", "i", (0,))
    assert reply.body == (False,) and action == ("refresh",)
    reply, _ = call(objects, MENU_PATH, MENU_IFACE, "AboutToShowGroup", "ai", ([0, 1, 9999],))
    assert reply.body == ([], [9999])


def test_clicks_become_actions(objects):
    ring = objects.menu.id_of("peer:p2:ring")
    reply, action = call(objects, MENU_PATH, MENU_IFACE, "Event", "isvu", (ring, "clicked", ("s", ""), 0))
    assert reply.body == () and action == ("ring", "p2", "office_pc")
    _, action = call(objects, MENU_PATH, MENU_IFACE, "Event", "isvu", (ring, "hovered", ("s", ""), 0))
    assert action is None
    accept = objects.menu.id_of("pair:r1:accept")
    reply, action = call(objects, MENU_PATH, MENU_IFACE, "EventGroup", "a(isvu)",
                         ([(accept, "clicked", ("i", 0), 0), (9999, "clicked", ("i", 0), 0)],))
    assert reply.body == ([9999],) and action == ("pair-answer", "r1", True, "friend")


def test_item_methods_and_errors_serialise(objects):
    for member, sig, body in (("Activate", "ii", (1, 2)), ("SecondaryActivate", "ii", (1, 2)),
                              ("ContextMenu", "ii", (1, 2)), ("Scroll", "is", (1, "vertical"))):
        reply, action = call(objects, ITEM_PATH, ITEM_IFACE, member, sig, body)
        assert reply.body == () and action is None
    reply, _ = call(objects, ITEM_PATH, tray.INTROSPECT_IFACE, "Introspect")
    assert "org.kde.StatusNotifierItem" in reply.body[0]
    reply, _ = call(objects, MENU_PATH, tray.INTROSPECT_IFACE, "Introspect")
    assert "com.canonical.dbusmenu" in reply.body[0]
    for path, iface, member, sig, body in (
            (MENU_PATH, MENU_IFACE, "GetLayout", "iias", (9999, -1, [])),
            (MENU_PATH, MENU_IFACE, "Event", "isvu", (9999, "clicked", ("s", ""), 0)),
            (ITEM_PATH, tray.PROPS_IFACE, "Get", "ss", (ITEM_IFACE, "Nope")),
            (ITEM_PATH, ITEM_IFACE, "Nope", "", ()),
            ("/elsewhere", tray.PROPS_IFACE, "GetAll", "s", ("x",))):
        reply, action = call(objects, path, iface, member, sig, body)
        assert reply.header.fields[HeaderFields.error_name].startswith("org.freedesktop.DBus.Error.")
        assert action is None


def test_signals_serialise_and_only_on_change(objects):
    assert objects.show(build_view(STATUS)) == []
    sigs = objects.show(build_view({**STATUS, "incoming": []}))
    members = [s[2] for s in sigs]
    assert members == ["LayoutUpdated", "NewStatus", "NewAttentionIcon", "PropertiesChanged", "NewToolTip"]
    sigs += objects.show(build_view(None))
    assert "NewIcon" in [s[2] for s in sigs]
    for path, iface, member, sig, body in sigs:
        round_trip(new_signal(DBusAddress(path, interface=iface), member, sig or None, body))
    reply, _ = call(objects, ITEM_PATH, tray.PROPS_IFACE, "Get", "ss", (ITEM_IFACE, "IconPixmap"))
    assert reply.body[0][1] == objects.icons.off


# --- actions ------------------------------------------------------------------------

def test_send_files_waits_for_each_job_and_reports_once(tmp_path):
    calls, notes = [], []
    states = {"j1": {"state": "done"}, "j2": {"state": "queued", "why": "no route"},
              "j3": {"state": "failed", "why": "disk full"}}

    def fake_call(req, timeout=30):
        calls.append(req)
        if req["cmd"] == "send-file":
            return {"id": f"j{len([c for c in calls if c['cmd'] == 'send-file'])}", "state": "queued"}
        return {"id": req["id"], **states[req["id"]]}

    a = Actions(fake_call, lambda t, b: notes.append((t, b)))
    files = [tmp_path / n for n in ("a.jpg", "b.jpg", "c.jpg")]
    a.send_files("p1", "phone", files)
    assert [c["cmd"] for c in calls] == ["send-file"] * 3 + ["job"] * 3
    assert all(c["peer"] == "p1" for c in calls if c["cmd"] == "send-file")
    assert notes == [("Some files didn't reach phone",
                      "Sent a.jpg.\nb.jpg waits in the outbox until it's back.\nc.jpg: disk full")]


def test_summaries():
    assert summarise("phone", ["a.jpg"], [], []) == ("Sent to phone", "a.jpg")
    assert summarise("phone", ["a", "b"], [], []) == ("Sent to phone", "2 files")
    assert summarise("phone", [], ["a", "b"], []) == ("phone can't be reached right now",
                                                     "2 files wait in the outbox until it's back.")
    assert summarise("phone", [], [], [("a", "nope")]) == ("Couldn't send to phone", "a: nope")


def test_pair_answer_reports_errors_and_refreshes():
    notes, kicked = [], []
    a = Actions(lambda req, timeout=30: {"error": "it expired"}, lambda t, b: notes.append(t),
                refresh=lambda: kicked.append(1))
    a.pair_answer("r1", True, "friend")
    assert notes == ["Couldn't answer friend"] and kicked == [1]


# --- autostart ----------------------------------------------------------------------

def test_autostart_entry(isolated_home):
    path = tray.enable_autostart()
    assert path == isolated_home / "config" / "autostart" / "io.github.ferinmtk.DropletAgent.Tray.desktop"
    text = path.read_text()
    assert "\nType=Application\n" in text
    exec_line = next(line for line in text.splitlines() if line.startswith("Exec="))
    assert exec_line.startswith('Exec="') and exec_line.endswith(" tray")
    assert tray.disable_autostart() and not path.exists()
    assert not tray.disable_autostart()


def test_exec_quoting():
    assert tray._quote('/home/a b/x"$') == '"/home/a b/x\\"\\$"'


def test_badge_paints_inside_the_icon():
    px = [(8, 8, struct.pack(">I", 0) * 64)]
    (w, h, data), = tray.badged(px)
    assert len(data) == 8 * 8 * 4
    corner = (7 * 8 + 6) * 4
    assert data[corner] > 0          # opaque-ish dot near the bottom-right corner
    assert data[0] == 0              # top-left untouched


# --- Droplet in the app menu ---------------------------------------------------------------

def test_png_from_argb_decodes_back_to_the_same_pixels():
    import struct as _struct, zlib as _zlib
    from droplet_agent import tray
    argb = bytes([0xFF, 0x10, 0x20, 0x30, 0x80, 0xAA, 0xBB, 0xCC,
                  0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF])          # 2x2
    data = tray.png(2, 2, argb)
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    w, h, depth, colour = _struct.unpack(">IIBB", data[16:26])
    assert (w, h, depth, colour) == (2, 2, 8, 6)                              # RGBA, 8 bits
    idat_len = _struct.unpack(">I", data[33:37])[0]
    assert data[37:41] == b"IDAT"
    raw = _zlib.decompress(data[41:41 + idat_len])
    assert raw == bytes([0, 0x10, 0x20, 0x30, 0xFF, 0xAA, 0xBB, 0xCC, 0x80,
                         0, 0, 0, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF])


def test_the_launcher_and_its_icons_are_installed_and_removed(tmp_path, monkeypatch):
    from droplet_agent import tray
    monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path))
    monkeypatch.setattr(tray, "agent_command", lambda: '"/opt/droplet agent/bin/droplet-agent"')
    path = tray.install_launcher()
    text = path.read_text()
    assert path == tmp_path / "applications" / "io.github.ferinmtk.Droplet.desktop"
    assert "Name=Droplet\n" in text and 'Exec="/opt/droplet agent/bin/droplet-agent" open\n' in text
    assert "Icon=io.github.ferinmtk.Droplet\n" in text and "Terminal=false\n" in text
    icons = [p for p in tray.icon_paths() if p.exists()]
    assert len(icons) == 4 and all(p.read_bytes()[:4] == b"\x89PNG" for p in icons)
    assert (tmp_path / "icons/hicolor/64x64/apps/io.github.ferinmtk.Droplet.png").exists()
    assert tray.remove_launcher() and not path.exists() and not any(p.exists() for p in icons)
    assert not tray.remove_launcher()


def test_opening_droplet_opens_the_window_when_it_can(monkeypatch):
    from droplet_agent import app, tray
    opened, notified = [], []
    monkeypatch.setattr(tray, "is_running", lambda: True)
    monkeypatch.setattr(app, "available", lambda: True)
    monkeypatch.setattr(app, "run", lambda argv: opened.append(argv) or 0)
    from droplet_agent.mesh import desktop
    monkeypatch.setattr(desktop.Desktop, "notify", lambda self, *a, **k: notified.append(a))
    assert tray.open_app() == 0 and opened == [[]] and not notified
    # no PySide6: the notification saying where the tray is, as before
    monkeypatch.setattr(app, "available", lambda: False)
    from droplet_agent.mesh import control
    monkeypatch.setattr(control, "call", lambda *a, **k: (_ for _ in ()).throw(control.NotRunning("no")))
    assert tray.open_app() == 0 and opened == [[]]
    assert notified and "system tray" in notified[0][0]


def test_opening_droplet_says_where_the_tray_is():
    from droplet_agent import tray
    title, body = tray.where_text(None)
    assert "system tray" in title and "isn't running" in body
    status = {"name": "slim", "peers": [{"id": "1", "name": "phone", "fp": "ab" * 32, "link": "lan 1.2.3.4",
                                         "on_lan": True, "os": "android"}], "incoming": [], "nearby": []}
    title, body = tray.where_text(status)
    assert "drop icon near the clock" in body and "1 device connected" in body


def test_no_command_from_the_desktop_opens_droplet_and_from_a_terminal_shows_help(monkeypatch, capsys):
    from droplet_agent import cli, tray
    opened = []
    monkeypatch.setattr(tray, "open_app", lambda: opened.append(1) or 0)

    class Stream:
        def __init__(self, tty): self.tty = tty
        def isatty(self): return self.tty
        def write(self, s): return len(s)
        def flush(self): pass
    monkeypatch.setattr(cli.sys, "stdin", Stream(False))
    monkeypatch.setattr(cli.sys, "stdout", Stream(False))
    assert cli.main([]) == 0 and opened == [1]
    monkeypatch.setattr(cli.sys, "stdin", Stream(True))
    assert cli.main([]) == 2 and opened == [1]
