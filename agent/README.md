# droplet agent (Linux)

Lets your phone and other devices control a Linux computer through droplet:
mouse and keyboard, the presentation remote, media playback and volume,
locking, screenshots and clipboard sync. It keeps a WebSocket open to the hub
and acts on what arrives. The protocol is [docs/remote.md](../docs/remote.md).

## Install

On the computer you want to control, open droplet in its browser, go to
**Devices → Link an app**, and run the command it shows:

```sh
curl -fsSL https://<hub>/agent/install.sh | sh -s -- --code 123456
```

The installer needs no sudo. It:

- creates a Python virtual environment in `~/.local/share/droplet-agent`,
- installs the agent, downloaded from the hub itself (only its two small
  dependencies, `websockets` and `jeepney`, come from PyPI),
- trades the code for a token and saves it in
  `~/.config/droplet-agent/config.json` (mode 600),
- installs and starts a systemd user service, `droplet-agent.service`, which
  starts with your desktop session,
- prints `droplet-agent doctor`'s advice.

Running it again upgrades the agent and keeps the link. On a computer with no
browser, register it as a new device instead: `--name "T15"`.

Needs Python 3.9 or newer, with `venv` (on Debian and Ubuntu:
`sudo apt install python3-venv`).

## Commands

| command | what it does |
|---|---|
| `droplet-agent status` | what works here, and why the rest doesn't. Changes nothing, shows no dialogs |
| `droplet-agent doctor` | how to fix what's missing, with the exact commands |
| `droplet-agent setup --hub URL --code 123456` | link to the hub again (`--name NAME` registers a new device) |
| `droplet-agent run` | run in the foreground (the service does this). `--dry-run` only logs what it would do; `-v` for more detail |
| `droplet-agent uninstall` | stop the service and remove the agent, its settings and the service file |

Logs: `journalctl --user -u droplet-agent -f`.

## What it can do, and how

The agent only offers what works on this computer right now, and only what the
config allows.

| capability | how |
|---|---|
| **input** | The first that works: the desktop's **RemoteDesktop portal** (KDE, GNOME), then **uinput** (any compositor, e.g. niri, sway, Hyprland), then **xdotool** on X11 |
| **media** | `playerctl` for players (MPRIS), `wpctl` for the default output's volume and mute |
| **lock** | `loginctl lock-session` on desktops with their own lock screen (KDE, GNOME…), otherwise `gtklock`, `swaylock`, `hyprlock` or `waylock` |
| **screenshot** | The portal's Screenshot, then the desktop's own tool (`spectacle`, `gnome-screenshot`, `niri msg action screenshot-screen`, `grim`), then `import` on X11. Sent to the device that asked |
| **clipboard** | `wl-paste --watch` / `wl-copy` on Wayland, `xclip` or `xsel` on X11. Text only, at most 256 KB, at most one change a second |

It also publishes the battery level when the computer has one.

### Input through the portal (KDE, GNOME)

The first time the agent starts, the desktop asks you to **allow remote
control**. Say yes. The agent asks for a permanent grant and keeps the restore
token the desktop gives back, so later starts don't ask again. While it's
allowed, KDE shows a remote-control icon in the system tray; ending the
session there switches input off until the agent restarts. If you said no,
`systemctl --user restart droplet-agent` asks again.

Text is typed as keysyms, independent of the keyboard layout.

### Input through uinput (niri, sway, other compositors)

`/dev/uinput` belongs to root, so it needs a one-time root step. The agent
prints the exact commands for your user with `droplet-agent doctor`; for a
user whose group is `alice` they are:

```sh
echo 'KERNEL=="uinput", SUBSYSTEM=="misc", GROUP="alice", MODE="0660", OPTIONS+="static_node=uinput"' | sudo tee /etc/udev/rules.d/60-droplet-uinput.rules
echo uinput | sudo tee /etc/modules-load.d/droplet-uinput.conf
sudo modprobe uinput
sudo udevadm control --reload-rules
sudo udevadm trigger --action=add --sysname-match=uinput
systemctl --user restart droplet-agent
```

This gives only your own user's group write access to `/dev/uinput`, so
nothing else on the system can create virtual input devices.

uinput sends key positions, not characters, so text is typed as on a US
keyboard. Characters a US keyboard can't type are handed to `wtype` when it's
installed (it types any Unicode on wlroots compositors and niri); without it,
accents are dropped (é → e) and the rest is skipped. On KDE and GNOME the
portal types everything. The compositor also applies its own pointer
acceleration to the virtual mouse.

## Settings

`~/.config/droplet-agent/config.json`:

```json
{
  "hub": "https://t15.example.ts.net",
  "token": "…",
  "device": {"id": "…", "name": "slim"},
  "caps": {"input": true, "media": true, "lock": true, "screenshot": true, "clipboard": true},
  "input_backend": "auto",
  "uinput_text": "auto",
  "lock_command": null,
  "screenshot_command": null,
  "clipboard_max_bytes": 262144
}
```

- **caps**: set any to `false` and the agent neither offers it nor acts on it.
- **input_backend**: `auto`, `portal`, `uinput`, `x11`, or `log` (does nothing).
- **uinput_text**: `auto` (use wtype when installed) or `ascii`.
- **lock_command**: e.g. `["swaylock", "-f"]`. `null` picks one.
- **screenshot_command**: e.g. `["grim", "{out}"]`, where `{out}` is the PNG
  path to write. `null` picks one.

Restart the service after editing: `systemctl --user restart droplet-agent`.

## Security

Anyone who can open droplet can control a computer whose agent offers input:
the same trust as the rest of droplet (your tailnet, or the PIN). Switch off
what you don't want under `caps`. The agent never runs anything a message
supplies: commands are a fixed list, media players must be ones `playerctl`
lists, and numbers are clamped. Clipboard text that a password manager marks as
secret (KeePassXC does) is never sent.

## Development

```sh
cd agent
python -m venv .venv && .venv/bin/pip install websockets jeepney pytest simple-websocket
.venv/bin/python -m pytest tests                 # unit tests
.venv/bin/python tests/e2e_local.py http://127.0.0.1:8822   # against a local hub
```

`tests/e2e_local.py` links an agent to a throwaway hub with a real link code,
runs it with the dry-run backends and drives it from a fake controller.

The hub serves the agent at `/agent/install.sh`, `/agent/droplet-agent.tar.gz`
and `/agent/droplet_agent-<version>-py3-none-any.whl`, built from this
directory (`agent_dist.py`). `droplet_agent/mediactl.py` is a link to the
hub's `mediactl.py`, so the hub's media card and the agent read players and
volume the same way.
