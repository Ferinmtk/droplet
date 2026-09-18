import base64
import subprocess

import pytest

from droplet_agent import mediastate
from droplet_agent import mediactl as mc


@pytest.fixture
def fake_players(monkeypatch):
    players = {
        "spotify": {"id": "spotify", "name": "Spotify", "status": "Paused", "title": "A", "artist": "B",
                    "album": "C", "art": None, "_art_file": None, "position": 1.0, "length": 200.0,
                    "can_seek": True, "can_next": True, "can_previous": True, "can_play": True, "can_pause": True},
        "vlc": {"id": "vlc", "name": "VLC", "status": "Playing", "title": "D", "artist": "", "album": "",
                "art": "https://example.com/a.jpg", "_art_file": None, "position": 5.0, "length": 60.0,
                "can_seek": True, "can_next": False, "can_previous": False, "can_play": True, "can_pause": True},
    }
    monkeypatch.setattr(mc, "list_players", lambda: list(players))
    monkeypatch.setattr(mc, "read_player", lambda p: dict(players[p]))
    monkeypatch.setattr(mc, "read_volume", lambda: {"level": 1.2, "muted": False})
    monkeypatch.setattr(mediastate.env, "which", lambda name: f"/usr/bin/{name}")
    return players


def runner_log():
    calls = []

    def run(*argv):
        calls.append(list(argv))
        return subprocess.CompletedProcess(argv, 0, "", "")
    return run, calls


def test_snapshot_shape(fake_players):
    snap = mediastate.Media().snapshot()
    assert snap["active"] == "vlc"
    assert snap["volume"] == {"level": 1.0, "muted": False}  # clamped to 0..1
    assert [p["id"] for p in snap["players"]] == ["spotify", "vlc"]
    assert all("_art_file" not in p for p in snap["players"])
    assert snap["players"][1]["art"] == "https://example.com/a.jpg"


def test_actions(fake_players):
    run, calls = runner_log()
    m = mediastate.Media(runner=run)
    assert m.act("play-pause") is None                      # active player: the one playing
    assert m.act("next", player="spotify") is None
    assert m.act("seek", value=42.5) is None
    assert m.act("volume", value=1.7) is None
    assert m.act("mute") is None
    assert m.act("mute", value=True) is None
    assert calls == [
        ["playerctl", "-p", "vlc", "play-pause"],
        ["playerctl", "-p", "spotify", "next"],
        ["playerctl", "-p", "vlc", "position", "42.50"],  # the active one: playing beats last controlled
        ["wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "1.00"],
        ["wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"],
        ["wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "1"],
    ]


def test_bad_actions_run_nothing(fake_players):
    run, calls = runner_log()
    m = mediastate.Media(runner=run)
    assert m.act("rm -rf") is not None
    assert m.act("next", player="evil; reboot") is not None
    assert m.act("seek", value="10") is not None
    assert m.act("volume", value=float("nan")) is not None
    assert m.act("mute", value="yes") is not None
    assert calls == []


def test_art_data_url(tmp_path, monkeypatch):
    monkeypatch.setattr(mc, "_art_roots", lambda: [tmp_path.resolve()])
    small = tmp_path / "small.png"
    small.write_bytes(b"\x89PNG\r\n\x1a\n" + b"x" * 1000)
    url = mediastate.ArtCache().data_url(str(small))
    assert url.startswith("data:image/png;base64,")
    assert base64.b64decode(url.split(",", 1)[1]) == small.read_bytes()

    big = tmp_path / "big.jpg"
    big.write_bytes(b"\xff\xd8\xff" + b"x" * 100_000)
    monkeypatch.setattr(mediastate.env, "which", lambda name: None)  # nothing to shrink it with
    assert mediastate.ArtCache().data_url(str(big)) is None
    assert mediastate.ArtCache().data_url("/etc/passwd") is None


def test_art_limit_fits_64k():
    url = mediastate.ArtCache._encode("image/jpeg", b"x" * mediastate.ART_RAW_MAX)
    assert len(url) <= 64 * 1024


def test_publisher_only_on_change(fake_players):
    import threading
    sent = []
    pub = mediastate.MediaPublisher(mediastate.Media(), lambda d: sent.append(d) or True, threading.Event())
    assert pub.poll_once() is True  # vlc is playing
    pub.poll_once()
    assert len(sent) == 1
    fake_players["vlc"]["position"] = 6.0
    pub.poll_once()
    assert len(sent) == 2
    pub.force()
    pub.poll_once()
    assert len(sent) == 3
