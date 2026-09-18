import math

from droplet_agent.inject import Carry, InputHandler
from droplet_agent.inject.logonly import LogBackend


def test_carry_keeps_fractions():
    c = Carry()
    assert c.take(0.4, 0) == (0, 0)
    assert c.take(0.4, 0) == (0, 0)
    assert c.take(0.4, 0) == (1, 0)       # 1.2: one pixel, 0.2 kept
    assert math.isclose(c.rx, 0.2)
    assert c.take(-0.3, -2.5) == (0, -2)  # reversing drops the leftover the other way
    assert c.take(0, -0.5) == (0, -1)


def test_carry_units():
    c = Carry(120)
    assert c.take(0, 0.5) == (0, 60)
    assert c.take(0, 1 / 240) == (0, 0)
    assert c.take(0, 1 / 240) == (0, 1)


def test_handler_moves_and_clicks():
    b = LogBackend()
    h = InputHandler(b)
    n = h.apply([
        {"k": "move", "dx": 4.5, "dy": -2},
        {"k": "move", "dx": 0.5, "dy": 0},
        {"k": "click", "b": "left", "n": 2},
        {"k": "button", "b": "right", "down": True},
        {"k": "button", "b": "right", "down": False},
        {"k": "key", "key": "Enter", "mods": ["ctrl"]},
        {"k": "text", "s": "héllo 👋"},
        {"k": "future-thing"},
        "junk",
    ])
    assert n == 7
    assert b.calls == [
        ("move", 4, -2), ("move", 1, 0),
        ("button", "left", "down"), ("button", "left", "up"),
        ("button", "left", "down"), ("button", "left", "up"),
        ("button", "right", "down"), ("button", "right", "up"),
        ("key", "ctrl+Enter"),
        ("text", "héllo 👋"),
    ]


def test_handler_rejects_bad_values():
    b = LogBackend()
    h = InputHandler(b)
    assert h.apply([{"k": "move", "dx": float("nan"), "dy": 1}, {"k": "move", "dx": True, "dy": 0},
                    {"k": "button", "b": "side", "down": True}, {"k": "key", "key": "Hyper"},
                    {"k": "text", "s": 5}]) == 0
    assert b.calls == []
    h.apply([{"k": "move", "dx": 1e12, "dy": 0}])
    assert b.calls == [("move", 10000, 0)]
    assert h.apply("not a list") == 0


def test_scroll_fractions_and_signs():
    b = LogBackend()
    h = InputHandler(b)
    h.apply([{"k": "scroll", "dx": 0, "dy": 0.5}])
    assert b.calls == []
    h.apply([{"k": "scroll", "dx": 0, "dy": 0.75}])
    assert b.calls == [("scroll", 0, 1)]  # positive dy: down, as sent


def test_held_buttons_are_released():
    b = LogBackend()
    h = InputHandler(b)
    h.apply([{"k": "button", "b": "left", "down": True}])
    h.release_all()
    assert b.calls[-1] == ("button", "left", "up")
    assert not h.held


def test_portal_answers(monkeypatch, tmp_path):
    from droplet_agent.inject import manager, remotedesktop as rd

    class FakeSession:
        def __init__(self, app_id, path, on_state):
            self.on_state = on_state

        def start(self):
            pass

        def close(self):
            pass

    monkeypatch.setattr(rd, "usable", lambda: (True, "fake portal"))
    monkeypatch.setattr(rd, "PortalSession", FakeSession)
    monkeypatch.setattr(manager.uinput, "writable", lambda: (True, "fake"))
    opened = []
    monkeypatch.setattr(manager.uinput, "open_backend", lambda mode: opened.append(mode) or LogBackend())
    changes = []

    m = manager.InputManager("auto", "app", tmp_path / "tok", lambda: changes.append(1))
    m.start()
    assert not m.available and "waiting" in m.reason
    m._session.on_state(rd.State.STARTED, "")
    assert m.available and m.backend.name == "portal" and changes == [1]
    m._session.on_state(rd.State.CLOSED, "")
    assert not m.available and changes == [1, 1]
    assert opened == []  # ended by the user: no quiet fallback to uinput

    m = manager.InputManager("auto", "app", tmp_path / "tok", lambda: changes.append(2))
    m.start()
    m._session.on_state(rd.State.DENIED, "")
    assert not m.available and opened == []

    m = manager.InputManager("auto", "app", tmp_path / "tok", lambda: changes.append(3))
    m.start()
    m._session.on_state(rd.State.FAILED, "no backend")
    assert m.available and opened == ["auto"]  # a broken portal falls through to uinput
    assert changes[-1] == 3


def test_uinput_fix_mentions_the_users_group():
    from droplet_agent import cli
    import getpass, grp, os
    fix = cli.uinput_fix()
    group = grp.getgrgid(os.getgid()).gr_name
    assert f'GROUP="{group}"' in fix and 'MODE="0660"' in fix
    assert "modules-load.d" in fix and "udevadm trigger" in fix
    assert getpass.getuser()  # sanity
