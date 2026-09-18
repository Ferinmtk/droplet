from droplet_agent import keys


def test_named_keys_map_to_evdev():
    assert keys.evdev_code("Enter") == 28
    assert keys.evdev_code("Escape") == 1
    assert keys.evdev_code("ArrowRight") == 106
    assert keys.evdev_code("ArrowLeft") == 105
    assert keys.evdev_code("PageDown") == 109
    assert keys.evdev_code("F5") == 63
    assert keys.evdev_code("F11") == 87
    assert keys.evdev_code("F12") == 88
    assert keys.evdev_code("MediaPlayPause") == 164
    assert keys.evdev_code("AudioVolumeMute") == 113
    assert keys.evdev_code("PrintScreen") == 99
    assert keys.evdev_code("Space") == keys.evdev_code(" ") == 57


def test_every_protocol_key_is_known():
    names = ("Enter Backspace Tab Escape Space Delete Insert Home End PageUp PageDown ArrowUp "
             "ArrowDown ArrowLeft ArrowRight MediaPlayPause MediaNext MediaPrevious MediaStop "
             "AudioVolumeUp AudioVolumeDown AudioVolumeMute PrintScreen ContextMenu").split()
    names += [f"F{i}" for i in range(1, 13)] + list("abcdefghijklmnopqrstuvwxyz0123456789")
    for n in names:
        assert keys.evdev_code(n) is not None, n
        assert keys.keysym_for_key(n) is not None, n
        assert keys.xdotool_key(n, []) is not None, n


def test_letters_and_digits():
    assert keys.evdev_code("a") == 30
    assert keys.evdev_code("B") == 48  # shortcuts may arrive upper-case
    assert keys.evdev_code("z") == 44
    assert keys.evdev_code("1") == 2
    assert keys.evdev_code("0") == 11
    assert keys.evdev_code("é") is None
    assert keys.evdev_code("NotAKey") is None
    assert keys.evdev_code(None) is None


def test_buttons():
    assert keys.BUTTONS == {"left": 0x110, "right": 0x111, "middle": 0x112}


def test_mods_are_cleaned_and_ordered():
    assert keys.clean_mods(["shift", "ctrl", "bogus", "ctrl"]) == ["ctrl", "shift"]
    assert keys.clean_mods("ctrl") == []
    assert keys.clean_mods(None) == []


def test_keysyms_for_text():
    assert keys.keysym_for_char("a") == 0x61
    assert keys.keysym_for_char("é") == 0xE9          # Latin-1: itself
    assert keys.keysym_for_char("€") == 0x010020AC    # else 0x01000000 + code point
    assert keys.keysym_for_char("👋") == 0x0101F44B
    assert keys.keysym_for_char("\n") == 0xFF0D
    assert keys.keysym_for_char("\t") == 0xFF09
    assert keys.keysym_for_char("\x07") is None


def test_xdotool_spec():
    assert keys.xdotool_key("Enter", ["shift", "ctrl"]) == "ctrl+shift+Return"
    assert keys.xdotool_key("c", ["ctrl"]) == "ctrl+c"
    assert keys.xdotool_key("PageUp", []) == "Prior"
    assert keys.xdotool_key("x;rm", []) is None


def test_us_layout():
    assert keys.US_LAYOUT["a"] == (30, False)
    assert keys.US_LAYOUT["A"] == (30, True)
    assert keys.US_LAYOUT["!"] == (2, True)
    assert keys.US_LAYOUT["?"] == (53, True)
    assert keys.US_LAYOUT["\n"] == (28, False)


def test_to_ascii():
    assert keys.to_ascii("héllo “world” — ok") == ('hello "world" - ok', 0)
    assert keys.to_ascii("hi 👋") == ("hi ", 1)
    assert keys.to_ascii("a\r\nb") == ("a\nb", 0)
