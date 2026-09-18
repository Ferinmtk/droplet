from droplet_agent.inject import remotedesktop as rd
from droplet_agent.inject.xdotool import XdotoolBackend


class FakeSession:
    def __init__(self):
        self.calls = []

    def notify(self, method, signature, *args):
        self.calls.append((method, *args))

    def close(self):
        pass


def test_portal_pointer():
    s = FakeSession()
    b = rd.PortalBackend(s)
    b.move(3, -4)
    b.button("right", True)
    b.scroll(0, 0.6)
    b.scroll(0, 0.6)
    b.scroll(-1, 0)
    assert s.calls == [
        ("NotifyPointerMotion", 3.0, -4.0),
        ("NotifyPointerButton", 0x111, 1),
        ("NotifyPointerAxisDiscrete", rd.AXIS_VERTICAL, 1),   # down is positive
        ("NotifyPointerAxisDiscrete", rd.AXIS_HORIZONTAL, -1),
    ]


def test_portal_keys_and_text():
    s = FakeSession()
    b = rd.PortalBackend(s)
    assert b.key("z", ["ctrl"])
    assert b.key("Enter", [])
    assert not b.key("Bogus", [])
    b.text("é👋")
    assert s.calls == [
        ("NotifyKeyboardKeycode", 29, 1),
        ("NotifyKeyboardKeysym", ord("z"), 1),
        ("NotifyKeyboardKeysym", ord("z"), 0),
        ("NotifyKeyboardKeycode", 29, 0),
        ("NotifyKeyboardKeycode", 28, 1),
        ("NotifyKeyboardKeycode", 28, 0),
        ("NotifyKeyboardKeysym", 0xE9, 1),
        ("NotifyKeyboardKeysym", 0xE9, 0),
        ("NotifyKeyboardKeysym", 0x0101F44B, 1),
        ("NotifyKeyboardKeysym", 0x0101F44B, 0),
    ]


def test_xdotool_commands():
    runs = []
    b = XdotoolBackend(runner=runs.append)
    b.move(-2, 3)
    b.button("middle", False)
    b.scroll(0, 2.2)
    b.scroll(0, -3)
    b.key("ArrowRight", ["alt"])
    b.text("-rf")
    assert runs == [
        ["mousemove_relative", "--", "-2", "3"],
        ["mouseup", "2"],
        ["click", "--repeat", "2", "5"],
        ["click", "--repeat", "3", "4"],
        ["key", "--clearmodifiers", "alt+Right"],
        ["type", "--clearmodifiers", "--delay", "2", "--", "-rf"],
    ]
