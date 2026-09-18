import time

from droplet_agent import clip


class Clock:
    def __init__(self):
        self.t = 100.0

    def __call__(self):
        return self.t


def make(sent, clock=None):
    written = []
    c = clip.ClipboardSync("fake", lambda text: sent.append(text) or True,
                           max_bytes=1000, reader=lambda: None,
                           writer=lambda t: written.append(t), clock=clock or Clock())
    return c, written


def test_sends_new_text_once():
    sent = []
    c, _ = make(sent)
    c.local_change("hello")
    c.local_change("hello")  # the same text again: nothing new
    assert sent == ["hello"]


def test_echo_of_applied_text_is_suppressed():
    sent = []
    c, written = make(sent)
    assert c.apply("from the phone") is None
    assert written == ["from the phone"]
    c.local_change("from the phone")  # wl-paste --watch sees our own write
    assert sent == []
    c.local_change("typed here")
    assert sent == ["typed here"]


def test_debounce_keeps_the_latest():
    sent = []
    clock = Clock()
    c, _ = make(sent, clock)
    c.local_change("one")
    clock.t += 0.2
    c.local_change("two")
    c.local_change("three")
    assert sent == ["one"]
    c._timer.join(2)  # the trailing send, a second after the first
    assert sent == ["one", "three"]


def test_limits():
    sent = []
    c, written = make(sent)
    c.local_change("x" * 1001)
    c.local_change(None)
    c.local_change("")
    assert sent == []
    assert c.apply("y" * 1001) == "too large"
    assert c.apply("") == "no text"
    assert c.apply(5) == "no text"
    assert c.apply("\ud800") == "not valid Unicode"
    assert written == []


def test_startup_content_is_not_sent(monkeypatch):
    sent = []
    c = clip.ClipboardSync("fake", lambda t: sent.append(t) or True, reader=lambda: "already there")
    monkeypatch.setattr(c, "_poll_x11", lambda: None)
    monkeypatch.setattr(c, "_watch_wayland", lambda: None)
    c.start()
    c.local_change("already there")
    assert sent == []
    time.sleep(0.05)
