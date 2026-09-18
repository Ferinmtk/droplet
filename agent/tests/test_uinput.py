import struct

from droplet_agent.inject import uinput as u


def decode(frames):
    size = struct.calcsize(u.EVENT_FORMAT)
    out = []
    for f in frames:
        for i in range(0, len(f), size):
            _, _, kind, code, value = struct.unpack(u.EVENT_FORMAT, f[i:i + size])
            out.append((kind, code, value))
    return out


def backend():
    frames = []
    b = u.UinputBackend(writer=frames.append, key_delay=0, text_mode="ascii")
    return b, frames


def test_ioctl_numbers_match_linux_headers():
    assert u.UI_SET_EVBIT == 0x40045564
    assert u.UI_SET_KEYBIT == 0x40045565
    assert u.UI_SET_RELBIT == 0x40045566
    assert u.UI_DEV_SETUP == 0x405C5503
    assert u.UI_DEV_CREATE == 0x5501
    assert u.UI_DEV_DESTROY == 0x5502
    assert struct.calcsize(u.EVENT_FORMAT) == 24  # x86_64


def test_move_and_button():
    b, frames = backend()
    b.move(5, -3)
    b.button("left", True)
    assert decode(frames) == [
        (u.EV_REL, u.REL_X, 5), (u.EV_REL, u.REL_Y, -3), (u.EV_SYN, 0, 0),
        (u.EV_KEY, 0x110, 1), (u.EV_SYN, 0, 0),
    ]


def test_scroll_down_is_negative_wheel_with_hi_res():
    b, frames = backend()
    b.scroll(0, 0.5)   # half a line down: hi-res only
    b.scroll(0, 0.5)   # completes the line: the legacy click too
    evs = decode(frames)
    assert evs[:2] == [(u.EV_REL, u.REL_WHEEL_HI_RES, -60), (u.EV_SYN, 0, 0)]
    assert evs[2:] == [(u.EV_REL, u.REL_WHEEL_HI_RES, -60), (u.EV_REL, u.REL_WHEEL, -1), (u.EV_SYN, 0, 0)]


def test_scroll_right_and_up():
    b, frames = backend()
    b.scroll(2, -1)
    evs = decode(frames)
    assert (u.EV_REL, u.REL_WHEEL_HI_RES, 120) in evs
    assert (u.EV_REL, u.REL_HWHEEL_HI_RES, 240) in evs
    assert (u.EV_REL, u.REL_WHEEL, 1) in evs
    assert (u.EV_REL, u.REL_HWHEEL, 2) in evs


def test_key_with_mods():
    b, frames = backend()
    assert b.key("c", ["ctrl", "shift"])
    keys_only = [(c, v) for k, c, v in decode(frames) if k == u.EV_KEY]
    assert keys_only == [(29, 1), (42, 1), (46, 1), (46, 0), (42, 0), (29, 0)]
    assert not b.key("NoSuchKey", [])


def test_text_us_layout_and_fallback():
    b, frames = backend()
    b.text("Hi!é")
    keys_only = [(c, v) for k, c, v in decode(frames) if k == u.EV_KEY]
    shift = 42
    assert keys_only == [
        (shift, 1), (35, 1), (35, 0), (shift, 0),   # H
        (23, 1), (23, 0),                           # i
        (shift, 1), (2, 1), (2, 0), (shift, 0),     # !
        (18, 1), (18, 0),                           # é → e (ascii mode)
    ]
