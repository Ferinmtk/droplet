# droplet agent (Linux and macOS)

Lets your phone and other devices control a Linux computer or a Mac through droplet:
mouse and keyboard, the presentation remote, media playback and volume,
locking, screenshots and clipboard sync. The protocol is
[docs/remote.md](../docs/remote.md).

It doesn't need a hub. It's a **mesh peer** ([docs/mesh.md](../docs/mesh.md)):
your devices pair with it and talk to it directly, like KDE Connect. Chat,
files, ringing, clipboard and remote control all go device to device. A
droplet hub is an optional extra: with one, the agent also keeps a WebSocket
open to it, and falls back to it when the other device can't be reached
directly.

## Install

No hub needed. On the computer, run:

```sh
curl -fsSL https://github.com/Ferinmtk/droplet/releases/latest/download/install-agent.sh | sh
```

Then pair it with your phone: open droplet on the phone, tap **Pair a
device** and pick this computer. Both screens show the same four digits;
accept on the computer in [Droplet's window](#droplets-window) or the tray
(or run `droplet-agent pair`). Other computers pair from the window's
**Pair a device**, or with `droplet-agent pair <name>` (see [the mesh](#the-mesh-talking-to-your-devices-directly)).

The installer needs no sudo. It:

- downloads the agent from droplet's latest GitHub release and checks it
  against the release's `SHA256SUMS.txt` (only its dependencies,
  `websockets`, `jeepney`, `zeroconf` and `cryptography`, come from PyPI),
- creates a Python virtual environment in `~/.local/share/droplet-agent`,
  and links `droplet-agent` into `~/.local/bin`,
- installs and starts a systemd user service, `droplet-agent.service`, which
  starts with your desktop session (`--no-service` leaves it out),
- puts droplet in the system tray, now and with every desktop session
  (`--no-tray` leaves it out), and **Droplet** in the app menu,
- run from a desktop session, installs [Droplet's window](#droplets-window)
  (PySide6, about 80 MB from PyPI; `--no-app` leaves it out). If it can't
  be installed, everything else works without it,
- prints `droplet-agent doctor`'s advice.

Running it again upgrades the agent and keeps your settings and pairings.
`--release v1.2.0` installs a given release instead of the latest, and
`--wheel FILE` (a path or URL) a wheel you have.

Needs Python 3.9 or newer, with `venv` (on Debian and Ubuntu:
`sudo apt install python3-venv`), and curl or wget.

On a Mac it's the same command: see [On a Mac](#on-a-mac).

### With a droplet hub (optional)

A [hub](../README.md) adds a web app, a mailbox for devices that are off,
and remote access over Tailscale. If you run one, it serves the installer
and the agent itself, and links the computer to it. On the computer you
want to control, open droplet in its browser, go to **Devices → Link an
app**, and run the command it shows, with the hub's address in it. On the
home Wi-Fi that's its LAN address, and no Tailscale is needed:

```sh
curl -fsSL http://<hub's LAN address>:8000/agent/install.sh | sh -s -- --code 123456
```

or, from anywhere on your tailnet:

```sh
curl -fsSL https://<hub's tailnet name>/agent/install.sh | sh -s -- --code 123456
```

That installs the agent the hub serves (the same version as the hub), and
links to the hub: the token and the hub's identity go in
`~/.config/droplet-agent/config.json` (mode 600). The release's installer
does the same with `--hub URL`. An agent installed without a hub links to
one later with `droplet-agent setup`.

**Without a link code** (a computer with no browser, like the hub), leave
`--code` out. The computer joins as a new device named after itself (or
`--name NAME`). Over the tailnet it's in straight away. Over the LAN it
waits: it prints a four-digit code, and one of your devices gets "slim wants
to join, code 1234: Allow / Deny". Check the codes match and allow it. Or
give the hub's PIN with `--pin 1234`, if it has one.

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

## Droplet's window

**Droplet** in the app menu (or `droplet-agent app`) opens a window like the
Windows app's:

- **Devices**: each paired device as a card, with how it's reached now
  (*Connected on this network*, *Connected via Tailscale*, *On this network*,
  or *Not reachable*, when what you send waits for it) and **Send files…**,
  **Send clipboard**, **Ring** and **Message**. **⋯** has Stop ringing, About
  this device and Unpair. Drop files on a card to send them; the card says
  when they've arrived, failed, or wait in the outbox.
- **Pair a device**: the droplet devices on this network, or one by its
  address. Both screens show the same four digits: **They match**, then
  accept on the other device.
- A device **asking to pair** shows on top of every page, with its code and
  **Accept** / **Decline**.
- **Messages**: a chat with each device, newest at the bottom. Enter sends
  (Shift+Enter for a new line); a message to a device that can't be reached
  waits and goes when it can.
- **Received**: the latest files your devices sent, to open or show in the
  folder.
- **Settings**: this computer's name, id and fingerprint; clipboard sync,
  your phone's notifications and what your devices may control here (saved
  to `config.json`, and the agent restarts to use them); the tray; about.

Like the tray it's a separate process that does everything through the
running agent; when the agent isn't running it says so, with **Start it**.
Opening it again brings the open window to the front. It's Qt (PySide6),
installed as the agent's `app` extra; on a computer installed without it:

```sh
~/.local/share/droplet-agent/bin/python -m pip install 'droplet-agent[app]'
```

Without PySide6, Droplet in the app menu starts the tray and says where it is.

## The tray

`droplet-agent tray` puts droplet in the system tray. Its menu lists:

- **Open Droplet**: [the window](#droplets-window), when it's installed;
- this computer's name, then each trusted device with how it can be reached
  (*connected*, *nearby* on the LAN, or *not reachable*). Each has **Send
  files…** (the desktop's file picker; a notification says when they've
  arrived, failed, or are waiting in the outbox), **Send clipboard** and
  **Ring**;
- each device **asking to pair**, with its code, and **Accept** / **Decline**.
  While one is waiting the icon gets an orange dot and asks for attention;
- **Open received files**.

The tooltip says how many devices are connected. When the agent isn't
running the icon greys out and the menu says so; it picks up again when
the agent starts.

It speaks the StatusNotifierItem protocol, so it shows on KDE Plasma, most
Wayland bars (waybar, and others with a tray), and on GNOME with the
AppIndicator extension (Ubuntu has it already). It's a small separate
process that talks to the agent the way the CLI does; the systemd service
can't show icons, so the tray starts with your desktop session instead:
the installer adds `~/.config/autostart/io.github.ferinmtk.DropletAgent.Tray.desktop`
and starts it (`--no-tray` leaves it out). On a computer installed before
the tray existed:

```sh
droplet-agent tray --autostart      # start it now, and with the desktop from now on
droplet-agent tray --no-autostart   # stop that, and close it
```

Starting it again replaces the one running, so there's only ever one.

## On a Mac

Free, and no Apple developer account or App Store needed. Open **Terminal**
(in Applications → Utilities) and paste:

```sh
curl -fsSL https://github.com/Ferinmtk/droplet/releases/latest/download/install-agent.sh | sh
```

It needs Python 3 (3.9 or newer). On a Mac without it, macOS offers to
install Apple's Command Line Tools, which bring it (or run
`xcode-select --install`, a few minutes); Python from
[python.org](https://www.python.org/downloads/macos/) works too. Then run the
command again. It uses python.org's Python if there is one, then Apple's, then
any other (Homebrew's): the first two keep the permissions you give the agent
across their updates, while after a Homebrew Python upgrade macOS asks again
(and the installer should be run again).

What it sets up, all in your own user account (no admin password):

- the agent, in `~/.local/share/droplet-agent`, and `droplet-agent` in
  `~/.local/bin` (added to your PATH in `~/.zprofile` for new Terminal windows);
- a **LaunchAgent**, `~/Library/LaunchAgents/io.github.ferinmtk.DropletAgent.plist`,
  which starts the agent now and every time you log in, and restarts it if
  it stops (`--no-service` leaves it unloaded). Its log is
  `~/Library/Logs/droplet-agent.log`;
- droplet in the **menu bar**, with the same menu as the Linux tray (your
  devices, Send files…, Send clipboard, Ring, pairing requests with Accept
  and Decline, Open received files), started now and at login by a second
  LaunchAgent (`…DropletAgent.Menu.plist`). It has a **Quit** item; it comes
  back at the next login, or with `droplet-agent tray --autostart`;
- **Droplet** in Launchpad and Spotlight: `~/Applications/Droplet.app`, a
  small app the installer writes on your Mac (so macOS doesn't quarantine
  it), which opens [Droplet's window](#droplets-window).

The menu bar icon and the window are Qt (PySide6, about 80 MB from PyPI),
installed by default on a Mac (`--no-app` leaves them out, and Droplet.app
with them; `--no-tray` leaves out the menu bar and Droplet.app). If PySide6
can't be installed (a Python too old for it), the agent and the commands
still work.

**Allow what you want your devices to do.** macOS asks for some things once,
under **System Settings → Privacy & Security**. The agent runs on Python, so
that's the name listed there:

- **Accessibility**: remote control (mouse, keyboard, typing) and the media
  keys. The first time the agent runs, macOS asks and lists Python there;
  switch it on, and remote control starts working a few seconds later. If
  Python isn't listed, add it with **+**.
- **Screen Recording** (or Screen & System Audio Recording): screenshots.
  Without it, a screenshot shows the desktop picture but no windows.
- If the firewall is on and macOS asks whether Python may **accept incoming
  network connections**, say **Allow**: that's how your phone reaches the Mac.

`droplet-agent doctor` says which of these are still missing.

What works on a Mac:

| | how |
|---|---|
| **pairing, chat, files, ring, clipboard** | the same as on Linux (the mesh). Files land in `~/Downloads/droplet`; a ring plays the Glass sound; notifications appear in Notification Centre |
| **input** | Quartz events (after Accessibility is allowed); text is typed as Unicode, so any character works |
| **media** | volume and mute, and play/pause, next and previous through the media keys. macOS doesn't let other apps read what's playing, so there's no title or artwork |
| **lock** | locks the screen, like the menu bar's Lock Screen |
| **screenshot** | `screencapture` (after Screen Recording is allowed) |
| **clipboard** | text, checked once a second; text a password manager marks as concealed is never sent |
| **battery** | from `pmset` |

Commands are the same as on Linux. `droplet-agent status` shows the
LaunchAgent's state; to restart the agent:
`launchctl kickstart -k gui/$(id -u)/io.github.ferinmtk.DropletAgent`.

**Uninstall:** `droplet-agent uninstall` stops the agent and the menu bar
icon and removes both LaunchAgents, Droplet.app, the agent and its settings.
Then switch Python off under Privacy & Security if you like.

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
| `droplet-agent app` | open [Droplet's window](#droplets-window) (`--page` opens it on a page: `devices`, `pair`, `messages`, `received` or `settings`), or bring it to the front |
| `droplet-agent open` | what Droplet in the app menu runs: starts the tray if it isn't running, and opens the window (without PySide6, says where the tray is). `--install` only adds Droplet to the app menu |
| `droplet-agent tray` | droplet in the system tray: see [the tray](#the-tray). `--autostart` / `--no-autostart` |
| `droplet-agent uninstall` | stop the service and the tray, and remove the agent, its settings, the service file and the tray's autostart entry |

Logs: `journalctl --user -u droplet-agent -f` (on a Mac: `~/Library/Logs/droplet-agent.log`).

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
  "mesh": {"enabled": true, "port": null, "downloads": null, "max_rate": 0, "announce": true,
           "phone_notifications": true}
}
```

- **hub_identity**: who the hub is, written by setup and kept current by
  the agent (`lan` is the last addresses that worked). Leave it alone; to
  trust a new certificate, run `droplet-agent setup`.
- **pending**: `true` while this computer waits to be let in.
- **caps**: set any to `false` and the agent neither offers it nor acts on it.
- **input_backend**: `auto`, `portal`, `uinput`, `x11`, `quartz` (a Mac's), or `log` (does nothing).
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
  `phone_notifications: false` stops showing a paired phone's notifications
  here, and tells the phone not to send them (the `notify` cap goes).

Restart the service after editing: `systemctl --user restart droplet-agent` (on a Mac:
`launchctl kickstart -k gui/$(id -u)/io.github.ferinmtk.DropletAgent`).

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
.venv/bin/pip install 'PySide6-Essentials>=6.6'  # then the window's tests run too (offscreen)
.venv/bin/python -m droplet_agent app --demo     # the window, with pretend devices and no agent
.venv/bin/python tests/tls_probe.py              # the mesh's mutual TLS on this Python's OpenSSL/LibreSSL
```

The same tests run on a Mac (CI's `macos-latest` job runs them, the
two-agent e2e, and the installer with launchd, on both Apple's Python and a
python.org one). The Mac's code is `macos.py` (launchd, Droplet.app, the
system tools, the framework calls through ctypes), `macmenu.py` (the menu
bar) and `inject/quartz.py` (input); elsewhere it's a `sys.platform ==
"darwin"` branch beside the Linux code.

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
only through `mesh_host.py`. The window lives in `droplet_agent/app/`: what
it shows is worked out in `model.py`, without Qt, and every request to the
agent runs on a worker thread (`agent.py`), never on the window's.

The hub serves the agent at `/agent/install.sh`, `/agent/droplet-agent.tar.gz`
and `/agent/droplet_agent-<version>-py3-none-any.whl`, built from this
directory (`agent_dist.py`). `droplet_agent/mediactl.py` is a link to the
hub's `mediactl.py`, so the hub's media card and the agent read players and
volume the same way.
