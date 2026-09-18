import os
import stat

from droplet_agent import config


def test_round_trip_and_permissions(isolated_home):
    cfg = config.load()
    assert not config.is_set_up(cfg)
    assert cfg["caps"] == {c: True for c in config.CAPS}
    cfg.update(hub="https://t15.example.ts.net", token="secret", device={"id": "abc", "name": "slim"})
    cfg["caps"]["clipboard"] = False
    cfg["lock_command"] = ["swaylock", "-f"]
    cfg["future_setting"] = 42
    path = config.save(cfg)
    assert path == isolated_home / "config" / "droplet-agent" / "config.json"
    assert stat.S_IMODE(os.stat(path).st_mode) == 0o600
    assert stat.S_IMODE(os.stat(path.parent).st_mode) == 0o700
    back = config.load()
    assert back == cfg
    assert config.is_set_up(back)
    assert not config.enabled(back, "clipboard")
    assert config.enabled(back, "input")


def test_defaults_fill_gaps(isolated_home):
    p = config.config_path()
    p.parent.mkdir(parents=True)
    p.write_text('{"hub": "https://h", "token": "t", "caps": {"input": false}}')
    cfg = config.load()
    assert cfg["caps"]["input"] is False
    assert cfg["caps"]["media"] is True
    assert cfg["input_backend"] == "auto"
    config.DEFAULTS["caps"]["media"]  # untouched
    cfg["caps"]["media"] = False
    assert config.DEFAULTS["caps"]["media"] is True


def test_secret_file(isolated_home):
    p = config.portal_token_path()
    assert config.read_secret(p) is None
    config.write_secret(p, "tok")
    assert config.read_secret(p) == "tok"
    assert stat.S_IMODE(os.stat(p).st_mode) == 0o600
