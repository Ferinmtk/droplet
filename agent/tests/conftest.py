import sys
from pathlib import Path

import pytest

# run from a checkout without installing
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


@pytest.fixture(autouse=True)
def isolated_home(tmp_path, monkeypatch):
    """Never read or write the real ~/.config/droplet-agent (nor, on a Mac, ~/Library or ~/Applications)."""
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))
    monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path / "data"))
    if sys.platform == "darwin":
        monkeypatch.setenv("HOME", str(tmp_path / "home"))
    return tmp_path
