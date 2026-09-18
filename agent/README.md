# droplet agent (Linux)

Lets your phone and other devices control a Linux computer through droplet:
mouse and keyboard, the presentation remote, media playback and volume,
locking, screenshots and clipboard sync. It keeps a WebSocket open to the hub
and acts on what arrives. The protocol is [docs/remote.md](../docs/remote.md).

## Install

On the computer you want to control, open droplet in its browser, go to
**Devices → Link an app**, and run the command it shows, with the hub's
address in it. On the home Wi-Fi that's its LAN address, and no Tailscale is
needed:

```sh
curl -fsSL http://<hub's LAN address>:8000/agent/install.sh | sh -s -- --code 123456
```

or, from anywhere on your tailnet:

```sh
curl -fsSL https://<hub's tailnet name>/agent/install.sh | sh -s -- --code 123456
```

The installer needs no sudo. It:

- creates a Python virtual environment in `~/.local/share/droplet-agent`,
- installs the agent, downloaded from the hub itself (only its small
  dependencies, `websockets`, `jeepney` and `zeroconf`, come from PyPI),
- links to the hub, and saves the token and the hub's identity in
  `~/.config/droplet-agent/config.json` (mode 600),
- installs and starts a systemd user service, `droplet-agent.service`, which
  starts with your desktop session,
- prints `droplet-agent doctor`'s advice.

Running it again upgrades the agent and keeps the link.

**Without a link code** (a computer with no browser, like the hub), leave
`--code` out. The computer joins as a new device named after itself (or
`--name NAME`). Over the tailnet it's in straight away. Over the LAN it
waits: it prints a four-digit code, and one of your devices gets "slim wants
to join, code 1234: Allow / Deny". Check the codes match and allow it. Or
give the hub's PIN with `--pin 1234`, if it has one.

Needs Python 3.9 or newer, with `venv` (on Debian and Ubuntu:
`sudo apt install python3-venv`).

## How it finds the hub

Like KDE Connect, the agent talks to the hub directly on the home network,
and uses Tailscale only when it isn't there ([docs/local-first.md](../docs/local-first.md)).
Each time it connects it tries, in order:

1. **hub-local**: on the hub machine itself, `http://127.0.0.1:<port>`. The
   fastest, and no Tailscale at all.
2. **LAN**: the hub's LAN HTTPS, `https://<address>:8443`. It finds the
   address over mDNS (`_droplet._tcp`), or uses the one that worked last:
   the hub's IP changes with DHCP, so an address is only a hint.
3. **tailnet**: the hub's tailnet URL, with ordinary TLS.

While on the tailnet it keeps looking for the LAN (every few minutes, and at
once when this computer's network changes) and moves back when it's there.
`droplet-agent status` shows the route in use.

**The LAN certificate is pinned.** The hub's LAN certificate is
self-signed, so the agent trusts it by its SHA-256 fingerprint, stored when
it paired: read over the tailnet (verified TLS) when it has that, or on
first contact over the LAN, confirmed by you allowing the device with the
matching code. The check runs right after the TLS handshake, before the
agent sends anything, so a machine that isn't your hub never sees the token.
If the certificate ever differs, the agent refuses that address and says:

```
the hub's identity changed: … Either that isn't your hub, or its certificate
was regenerated. Nothing was sent to it. If you trust it, re-pair with: droplet-agent setup
```

It never switches silently. `droplet-agent setup` (with nothing else) reads
the hub's identity again over a route that proves who it is (the tailnet,
or loopback on the hub machine) and keeps the link. Without Tailscale, pair
again: `droplet-agent setup --name NAME` or `--code`.

An agent set up before this (its config holds only a hub URL) reads the
hub's identity from that URL the first time it runs, and stores it.

## Commands

| command | what it does |
|---|---|
| `droplet-agent status` | the hub, its pinned fingerprint, the route in use, and what works here and why the rest doesn't. Changes nothing, shows no dialogs |
| `droplet-agent doctor` | how to fix what's missing, with the exact commands |
| `droplet-agent setup` | find the hub on this network and join it (lists the hubs it finds; asks when there's more than one). On a linked computer: read the hub's identity again |
| `droplet-agent setup --hub URL --code 123456` | link to a given hub (`http://<address>:8000`, or its tailnet URL). `--name NAME` joins as a new device, `--pin` uses the hub's PIN instead of waiting to be allowed |
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
  "hub_identity": {
    "id": "9b16173d305cd15a", "name": "t15",
    "fingerprint": "3c10…2094",
    "lan": ["192.168.100.20"], "https_port": 8443, "http_port": 8000,
    "tailnet": "https://t15.example.ts.net"
  },
  "pending": false,
  "caps": {"input": true, "media": true, "lock": true, "screenshot": true, "clipboard": true},
  "input_backend": "auto",
  "uinput_text": "auto",
  "lock_command": null,
  "screenshot_command": null,
  "clipboard_max_bytes": 262144
}
```

- **hub_identity**: who the hub is, written by setup and kept current by
  the agent (`lan` is the last addresses that worked). Leave it alone; to
  trust a new certificate, run `droplet-agent setup`.
- **pending**: `true` while this computer waits to be let in.
- **caps**: set any to `false` and the agent neither offers it nor acts on it.
- **input_backend**: `auto`, `portal`, `uinput`, `x11`, or `log` (does nothing).
- **uinput_text**: `auto` (use wtype when installed) or `ascii`.
- **lock_command**: e.g. `["swaylock", "-f"]`. `null` picks one.
- **screenshot_command**: e.g. `["grim", "{out}"]`, where `{out}` is the PNG
  path to write. `null` picks one.

Restart the service after editing: `systemctl --user restart droplet-agent`.

## Security

Anyone who can open droplet can control a computer whose agent offers input:
the same trust as the rest of droplet (your tailnet, the PIN, or a device
you've allowed in). The agent only sends its token to the hub it paired
with: over the LAN, to the pinned certificate; over the tailnet, with
verified TLS; on the hub machine, over loopback. Switch off
what you don't want under `caps`. The agent never runs anything a message
supplies: commands are a fixed list, media players must be ones `playerctl`
lists, and numbers are clamped. Clipboard text that a password manager marks as
secret (KeePassXC does) is never sent.

## Development

```sh
cd agent
python -m venv .venv && .venv/bin/pip install websockets jeepney zeroconf pytest simple-websocket cryptography
.venv/bin/python -m pytest tests                 # unit tests
.venv/bin/python tests/e2e_local.py http://127.0.0.1:8822   # against a local hub
.venv/bin/python tests/e2e_lan.py 8851           # routes, against a hub on the LAN
```

`tests/e2e_local.py` links an agent to a throwaway hub with a real link code,
runs it with the dry-run backends and drives it from a fake controller.
`tests/e2e_lan.py` needs a throwaway hub listening on the LAN
(`DROPLET_HOST=0.0.0.0`): it finds it over mDNS, checks the hub-local route,
and moves a dry-run agent from the pinned LAN to a stand-in tailnet and back.

The hub serves the agent at `/agent/install.sh`, `/agent/droplet-agent.tar.gz`
and `/agent/droplet_agent-<version>-py3-none-any.whl`, built from this
directory (`agent_dist.py`). `droplet_agent/mediactl.py` is a link to the
hub's `mediactl.py`, so the hub's media card and the agent read players and
volume the same way.
