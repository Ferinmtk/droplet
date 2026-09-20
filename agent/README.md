# droplet agent (Linux)

Lets your phone and other devices control a Linux computer through droplet:
mouse and keyboard, the presentation remote, media playback and volume,
locking, screenshots and clipboard sync. It keeps a WebSocket open to the hub
and acts on what arrives. The protocol is [docs/remote.md](../docs/remote.md).

It's also a **mesh peer** ([docs/mesh.md](../docs/mesh.md)): your devices
talk to it directly, like KDE Connect, and it keeps working when the hub is
down. Chat, files, ringing, clipboard and remote control all go device to
device, and fall back to the hub only when the other device can't be
reached directly.

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
- installs the agent, downloaded from the hub itself (only its
  dependencies, `websockets`, `jeepney`, `zeroconf` and `cryptography`,
  come from PyPI),
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

## The mesh: talking to your devices directly

Each agent has its own key and certificate (made the first time it runs,
kept in `~/.config/droplet-agent/mesh/`, owner-only), and listens on port
**1739** (or the next free one up to 1749). It announces itself on the LAN
over mDNS (`_droplet-peer._tcp`).

**Who it trusts:**

- **Every device on your hub**, automatically. The hub sends the agent its
  **roster**: the certificates of all the devices you've let in. The agent
  keeps a copy, so they keep reaching each other when the hub is down,
  and over Tailscale when you're away. Remove a device on the hub and every
  agent stops trusting it.
- **Devices you pair with directly**, for a computer with no hub, or a
  friend's laptop:

  ```sh
  droplet-agent peers              # who's around, and who's trusted
  droplet-agent pair beta          # its name, id, or address[:port]
  ```

  Both screens show the same four-digit code. On the other computer:

  ```sh
  droplet-agent pair               # shows who's asking, and the code; answer y
  droplet-agent pair --accept      # or answer straight away (--deny to refuse)
  ```

  The agent also shows a desktop notification when someone asks. Answer
  only if the codes match: that's what proves nobody is in the middle.
  `droplet-agent unpair beta` undoes it, on both sides if the other is
  reachable.

**Sending**, from scripts or the terminal (the agent must be running):

| command | what it does |
|---|---|
| `droplet-agent text beta "on my way"` | a chat message |
| `droplet-agent send-file beta photo.jpg …` | files; they land in the other computer's `~/Downloads/droplet` |
| `droplet-agent ring beta` | rings it (`--stop` to stop) |
| `droplet-agent clip beta` | sends this computer's clipboard (`--text '…'` to send some text instead) |
| `droplet-agent send beta '{"t":"input","ev":[{"k":"key","key":"ArrowRight"}]}'` | one `input`, `media` or `cmd` message (docs/remote.md) |

Each says how it went: directly over the LAN, directly over Tailscale,
through the hub, or to the hub's mailbox when the other device is off.
When nothing can reach it (no hub, and the device is off), chat and files
wait in the **outbox** (`~/.local/share/droplet-agent/mesh/outbox.json`)
and go out as soon as the device or the hub is back, in order. A file waits
where it is, so don't delete or change it before it's sent. Remote
control, ringing and the clipboard are never queued: an hour late, they'd
be wrong.

A big file that stops part-way (Wi-Fi dropped, one side restarted) carries
on from where it stopped the next time.

**Receiving** needs nothing: messages from trusted devices show as desktop
notifications (and are kept in `~/.local/share/droplet-agent/mesh/chat.jsonl`),
files land in `~/Downloads/droplet` (never overwriting anything), a ring
plays the incoming-call sound, and remote control, media, lock, screenshot
and clipboard work exactly as they do through the hub. A screenshot goes
back to whoever asked, the way the request came.

**The firewall.** Other devices must be able to reach the mesh port. On
Fedora (firewalld), `droplet-agent doctor` says so and gives the command:

```sh
sudo firewall-cmd --permanent --add-port=1739-1749/tcp && sudo firewall-cmd --reload
```

**Without a hub**, `droplet-agent run` (and the service) runs the mesh alone.

## Commands

| command | what it does |
|---|---|
| `droplet-agent status` | the hub, its pinned fingerprint, the route in use, and what works here and why the rest doesn't. Changes nothing, shows no dialogs |
| `droplet-agent doctor` | how to fix what's missing, with the exact commands |
| `droplet-agent setup` | find the hub on this network and join it (lists the hubs it finds; asks when there's more than one). On a linked computer: read the hub's identity again |
| `droplet-agent setup --hub URL --code 123456` | link to a given hub (`http://<address>:8000`, or its tailnet URL). `--name NAME` joins as a new device, `--pin` uses the hub's PIN instead of waiting to be allowed |
| `droplet-agent run` | run in the foreground (the service does this). `--dry-run` only logs what it would do; `-v` for more detail. Without a hub, runs the mesh alone |
| `droplet-agent peers` | the devices this one talks to directly, who's nearby, who's asking to pair, and what's waiting to be sent |
| `droplet-agent pair [PEER]` | pair directly with a device; with nothing, answer the devices asking (`--accept`, `--deny`) |
| `droplet-agent unpair PEER` | stop trusting a directly paired device |
| `droplet-agent text`, `send-file`, `ring`, `clip`, `send` | send to a device: see [the mesh](#the-mesh-talking-to-your-devices-directly) |
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
  "clipboard_max_bytes": 262144,
  "mesh": {"enabled": true, "port": null, "downloads": null, "max_rate": 0, "announce": true}
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
- **mesh**: `enabled: false` turns direct connections off (the agent then
  only talks to the hub). `port`: a fixed mesh port (`null`: 1739, or the
  next free one up to 1749). `downloads`: where files sent directly land
  (`null`: `~/Downloads/droplet`, following your desktop's download folder).
  `max_rate`: bytes a second when sending files directly (0: no limit).
  `announce: false` stops announcing over mDNS (peers then need its address).

Restart the service after editing: `systemctl --user restart droplet-agent`.

## Security

Anyone who can open droplet can control a computer whose agent offers input:
the same trust as the rest of droplet (your tailnet, the PIN, or a device
you've allowed in). The agent only sends its token to the hub it paired
with: over the LAN, to the pinned certificate; over the tailnet, with
verified TLS; on the hub machine, over loopback. Switch off
what you don't want under `caps`.

On the mesh, a device is trusted only by its certificate's fingerprint:
your hub's roster (which lists only devices you've let in, and carries no
tokens), or a direct pairing you confirmed by comparing codes. Every
connection is mutual TLS. A device whose certificate isn't trusted is
refused in the TLS handshake; one with no certificate can only ask to
pair. Pairing commits both sides to random numbers before the code exists,
and the asking device signs with its key, so an attacker in the middle
can't make the two codes match, and nobody can pair with someone else's
certificate. Files offered to one device can only be fetched by that
device. The agent's local commands reach it over a socket only your user
can open. The agent never runs anything a message
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
.venv/bin/python tests/e2e_mesh.py direct        # two agents with no hub, and an untrusted third
.venv/bin/python tests/e2e_mesh.py hub http://127.0.0.1:8861 <hub pid>   # the roster, hub down, mailbox, outbox
```

`tests/e2e_local.py` links an agent to a throwaway hub with a real link code,
runs it with the dry-run backends and drives it from a fake controller.
`tests/e2e_lan.py` needs a throwaway hub listening on the LAN
(`DROPLET_HOST=0.0.0.0`): it finds it over mDNS, checks the hub-local route,
and moves a dry-run agent from the pinned LAN to a stand-in tailnet and back.
`tests/e2e_mesh.py` runs separate dry-run agent processes, each with its
own HOME, and drives them with the real commands: pairing, a 50 MB file
interrupted and resumed, input, ring, clip, an untrusted third agent,
unpairing; with a throwaway hub, the roster, the hub stopped and
restarted, the mailbox and the outbox.

The mesh lives in `droplet_agent/mesh/` and reaches the rest of the agent
only through `mesh_host.py`.

The hub serves the agent at `/agent/install.sh`, `/agent/droplet-agent.tar.gz`
and `/agent/droplet_agent-<version>-py3-none-any.whl`, built from this
directory (`agent_dist.py`). `droplet_agent/mediactl.py` is a link to the
hub's `mediactl.py`, so the hub's media card and the agent read players and
volume the same way.
