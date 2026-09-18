import pytest

from droplet_agent import battery, hub, screenshot


def test_hub_urls():
    assert hub.normalize("t15.tail7375fe.ts.net") == "https://t15.tail7375fe.ts.net"
    assert hub.normalize("https://t15.tail7375fe.ts.net/") == "https://t15.tail7375fe.ts.net"
    assert hub.normalize("http://127.0.0.1:8812") == "http://127.0.0.1:8812"
    assert hub.ws_url("https://h.ts.net") == "wss://h.ts.net/ws"
    assert hub.ws_url("http://127.0.0.1:8812") == "ws://127.0.0.1:8812/ws"
    for bad in ("", "ftp://x", "https://", "https://a b", "https://u@h", "https://h:99999"):
        with pytest.raises(hub.HubError):
            hub.normalize(bad)


def supply(root, name, **files):
    d = root / name
    d.mkdir()
    for k, v in files.items():
        (d / k).write_text(v + "\n")


def test_battery(tmp_path):
    assert battery.read(tmp_path) is None
    supply(tmp_path, "AC", type="Mains", online="1")
    supply(tmp_path, "BAT0", type="Battery", capacity="80", status="Not charging", energy_full="50000000")
    supply(tmp_path, "hidpp_battery_0", type="Battery", scope="Device", capacity="5", status="Discharging")
    assert battery.read(tmp_path) == {"level": 80, "charging": True}
    (tmp_path / "AC" / "online").write_text("0\n")
    (tmp_path / "BAT0" / "status").write_text("Discharging\n")
    assert battery.read(tmp_path) == {"level": 80, "charging": False}


def test_fake_png_is_a_png():
    data = screenshot.fake_png()
    assert data.startswith(screenshot.PNG_MAGIC) and data.endswith(b"IEND\xaeB`\x82")
