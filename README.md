# droplet 💧

LAN file drop. Run it on any device on your network — every other device
sends/fetches files through its web page. No cloud, no accounts, no installs
on the other devices.

## Run (venv)

```bash
cd ~/curiosity/projects/droplet
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
sudo firewall-cmd --add-port=8000/tcp   # Fedora: open the port (until reboot)
python app.py
```

Scan the QR code in the terminal with your phone, or open the printed URL.
mDNS-capable devices (iPhone, Mac, Linux, Windows 10+) can also use
`http://droplet.local:8000`. Older Android browsers can't resolve `.local` —
use the IP/QR there.

To open the firewall port permanently:

```bash
sudo firewall-cmd --permanent --add-port=8000/tcp && sudo firewall-cmd --reload
```

## Run (Docker)

```bash
docker compose up -d --build
```

Files land in `./data/received` and `./data/shared` on the host
(`DROPLET_HOME=/data` inside the container). `docker-compose.yml` uses
`network_mode: host` (Linux only) — this is required, not just nice-to-have,
see mDNS notes below.

**Verified against this repo's Dockerfile/docker-compose.yml** (Fedora 44,
Docker 29, SELinux enforcing):

- Image builds clean, container starts, `curl http://localhost:8000/` returns
  `200`.
- **Uploads persist across `docker compose restart`** — tested by uploading a
  file, restarting the container, and confirming it's still in
  `GET /api/files`.
- **SELinux systems (Fedora/RHEL): the bind mount needs `:z`.** Without it,
  the container can't write to `./data` — it hits a `PermissionError` on
  `.secret_key` on first boot and immediately exits. `docker-compose.yml`
  already has `./data:/data:z`. The flag is harmless (a no-op) on non-SELinux
  hosts, so this doesn't need to be conditional.
- Port mapping: with `network_mode: host` there's no `ports:` section to get
  wrong — the container binds `DROPLET_PORT` (default `8000`) directly on the
  host. Same firewall rule as the venv setup applies:
  `sudo firewall-cmd --add-port=8000/tcp`.
- `DROPLET_HOME=/data` — sensible: `received/`, `shared/`, `certs/`, and
  `.secret_key` all live under the one mounted volume, nothing writes outside
  it.

### mDNS in Docker — what actually happens

- **With `network_mode: host` (the shipped default): mDNS works.** Verified
  with `avahi-browse` — the container's `_http._tcp` service showed up on the
  host's real LAN interface, `droplet.local` resolved to the host's LAN IP,
  and `curl http://droplet.local:8000/` succeeded.
- **With default bridge networking: mDNS does not reach the LAN.** Docker's
  bridge network NATs at L3; mDNS's multicast packets (`224.0.0.251:5353`)
  don't cross that boundary to the physical interface. Confirmed by running
  the same image in bridge mode (`-p 8001:8000`) — no new mDNS record ever
  appeared on the LAN, and `droplet.local` kept resolving to a stale record
  from an earlier host-mode run rather than anything from the bridge
  container.
- **Bridge mode is worse than "no mDNS," it's actively misleading:** the app
  auto-detects its own IP for the startup banner and QR code, and in bridge
  mode that's the container's internal Docker IP (e.g. `172.17.0.4`) — not
  reachable from any other device on the LAN. If you must run in bridge mode,
  set `DROPLET_LAN_IP` to the host's real address and use the host's mapped
  port; don't trust the printed banner/QR.
- **Practical takeaway:** on Linux, keep `network_mode: host`. It's the only
  mode where the QR code, banner, and mDNS name are actually correct for
  other devices on the LAN. Docker Desktop (Mac/Windows) doesn't support host
  networking the same way — mDNS discovery won't work there regardless; use
  the IP/QR fallback.

## Run as a service

Runs droplet as a `systemd --user` service, so it starts automatically and
restarts if it crashes, instead of you having to remember to launch it.

```bash
mkdir -p ~/.config/systemd/user ~/.config/droplet
cp deploy/droplet.service ~/.config/systemd/user/droplet.service
cp deploy/droplet.env.example ~/.config/droplet/droplet.env
# edit ~/.config/droplet/droplet.env to taste (PIN, port, etc.)

systemctl --user daemon-reload
systemctl --user enable --now droplet.service
```

Check it's running and follow the logs:

```bash
systemctl --user status droplet.service
journalctl --user -u droplet -f
```

The unit runs `python` from the project's `.venv`, so create the venv and
install `requirements.txt` first (see [Run (venv)](#run-venv) above) —
`deploy/droplet.service` assumes the repo lives at
`~/curiosity/projects/droplet`; edit the paths in the unit file if yours is
elsewhere.

A **user service normally stops when you log out.** To have it survive logout
and start at boot:

```bash
sudo loginctl enable-linger "$USER"
```

## Local-first: home Wi-Fi first, Tailscale when away

droplet works like KDE Connect: on the home network, devices talk to the hub
directly. [Tailscale](#tailnet-real-https-from-anywhere) is the extra that
makes it work from anywhere.

**Who gets in.** Being on the Wi-Fi alone isn't enough, since anyone on it
could otherwise use remote control, hub commands or SMS. A request is trusted
when it comes:
- from the hub machine itself,
- over your tailnet,
- with the hub's PIN, or
- from a device you've already let in.

A **new device on the Wi-Fi** names itself and waits. Your devices show
"*X wants to join*" with a four-digit code, plus **Allow** / **Deny** (and a
push notification). Check the code matches the one on the new device. The
PIN, or a link code from a device you already use, also gets it in. Until
then it can only drop files on the hub (`DROPLET_LAN_GUESTS=none` blocks that
too). At most five join requests wait at once, and they expire after a day.

**How the apps find the hub.** The hub announces itself on the LAN as
`_droplet._tcp` (mDNS) with its permanent id and certificate fingerprint. It
also serves HTTPS on port 8443 with its own long-lived certificate. The
Android, Windows and Linux apps:
- **find the hub by itself,** even when DHCP moves it to a new address;
- **trust only that certificate,** pinned by fingerprint, so an impostor on
  the Wi-Fi never gets a request or a login token;
- **switch between Wi-Fi and Tailscale** as you come and go (the app shows
  which: "On Wi-Fi" / "Via Tailscale");
- **can join without Tailscale at all,** through the approval above.

The full contract is in [docs/local-first.md](docs/local-first.md).

**Browsers are the exception.** A browser only allows notifications,
installing and the clipboard on a certificate it already trusts, and the only
trusted one is Tailscale's. So the web app is Tailscale-first. On the plain
LAN address it still works for the basics, as its own device, once let in.
The native apps are local-first.

### Devices talk directly (the mesh)

droplet's native apps are **peers**, like KDE Connect: they find each other
on the Wi-Fi and talk directly over mutual TLS, and over Tailscale when
you're away.

The hub is an **optional helper**. When it's up, it vouches for your devices
(every device you've let in trusts the others automatically), and it holds
messages and files for devices that are off. **When it's down,** chat,
files, ringing, the clipboard and remote control between your devices keep
working.

**Without a hub at all,** pair two devices directly: both screens show the
same four-digit code, protected by a commit-then-reveal exchange so a
machine in the middle can't fake it.

The **Linux agent** is the first peer (`droplet-agent peers`, `pair`,
`text`, `send-file`, `ring`, `clip`); Android and Windows follow. Browsers
still go through the hub. The Android app also controls your TV directly.
Details: [agent/README.md](agent/README.md) and [docs/mesh.md](docs/mesh.md).

## Tailnet: real HTTPS from anywhere

If the hub is on [Tailscale](https://tailscale.com), droplet can put itself
behind `tailscale serve`. Every device on your tailnet then reaches it at
`https://<machine>.<tailnet>.ts.net` (for example
`https://t15.tail7375fe.ts.net`) from anywhere, not just from home Wi-Fi. The
certificate is a real Let's Encrypt one that Tailscale renews itself, so
there's no browser warning. The LAN URL keeps working for guests.

One-time setup:

1. In the [admin console → DNS](https://login.tailscale.com/admin/dns), turn
   on **MagicDNS** and **HTTPS Certificates**.
2. Let your user drive Tailscale without sudo:
   `sudo tailscale set --operator=$USER`
3. Set `DROPLET_TAILSCALE=1` (in `~/.config/droplet/droplet.env` for the
   service) and start droplet.

On startup droplet runs `tailscale serve --bg --https=443
http://127.0.0.1:<port>`, prints the `ts.net` URL, and puts it in the QR
code. If something's missing it says what, and carries on LAN-only. It won't
take over `:443` if something else on the machine is already served there.

The `serve` setting lives in `tailscaled`, so it outlives droplet: while
droplet is stopped, the URL returns a 502. Remove it with
`tailscale serve --https=443 off`.

**PIN and the tailnet:** devices on your tailnet are already approved by
you, so with `DROPLET_PIN` set they skip it, and the page shows who they're
signed in as. droplet only believes the `Tailscale-User-Login` header on
requests coming from loopback (where `tailscale serve` connects from) after it
has set serve up itself. LAN clients can't fake it. Tagged devices carry no
user, so they get the PIN. `DROPLET_TAILNET_TRUST=0` makes everyone enter it.

**Verified** on a Fedora Kinoite hub (Tailscale 1.x, droplet as the
systemd user service), tested from another tailnet machine:

- `https://t15.<tailnet>.ts.net` serves a Let's Encrypt certificate that
  curl verifies. The page shows "via tailnet as <login>".
- A 50 MB upload and download over the tailnet came back byte-identical
  (~10 MB/s upload).
- With `DROPLET_PIN` set: tailnet → `200`; LAN → `302` to `/login`; LAN
  sending a forged `Tailscale-User-Login` header → `302`.
- Restarting droplet leaves the existing serve config alone instead of
  re-adding it.
- Before HTTPS Certificates were enabled, droplet printed the fix and ran
  LAN-only.

**Docker:** the container has no `tailscale` CLI. Run
`tailscale serve --bg 8000` on the host yourself. The PIN then applies to
tailnet devices too, because droplet didn't set serve up and doesn't trust
the header.

## Install it as an app + "Share → droplet" (Android)

Over HTTPS (the [tailnet URL](#tailnet-real-https-from-anywhere)) droplet is
an installable web app. Browsers only allow installing from a secure origin,
so this doesn't work on the plain `http://` LAN address.

1. Open the `https://…ts.net` URL in **Chrome** on the phone.
2. Tap **Install app** next to the title (or ⋮ → **Install app**).
3. Open it from the home screen. It runs full-screen, without an address bar.
   If you see an address bar, it's a shortcut, not an install: remove it and
   install again.

After installing, **droplet shows up in Android's share sheet.** Share
photos, files, text or links to it from any app (Gallery, Files, Chrome,
YouTube…). Files land in `received/`. Text and links are saved as a
`text-*.txt` file, and the link many apps repeat inside the text is only
written once.

How it works: `static/sw.js` (the service worker) catches the share, parks
the items in a cache and opens the page, which uploads them with the normal
progress bar. So:

- **PIN on and logged out?** The shared items wait through the login and
  send afterwards. Logins last 30 days, because an installed app forgets
  normal session cookies whenever it's closed.
- **Hub unreachable** (Tailscale off, hub asleep)? You get a "Can't reach the
  hub" page instead of Chrome's error, and the shared items stay parked until
  the next time droplet opens with the hub reachable.
- If the service worker isn't running yet, Android posts straight to
  `POST /share` on the server, which saves the items directly.

**Verified** on a Redmi Note 11E Pro (Chrome, installed from the tailnet URL):
Gallery → Share → droplet uploads the photo. Tested on a desktop browser:
a PIN login in between, text+URL de-duplication, and the offline page.
Note that some gallery apps (Xiaomi's included) hand over a numeric name like
`1789694404320.jpg` instead of the original filename. droplet saves whatever
name the app gives it.

The share sheet is Android-only. iOS Safari can add droplet to the home
screen, but it doesn't offer web apps as share targets.

## Devices: send to one, chat, get notified

Every browser can **name itself as a device** in the box at the top of the
page. Over the tailnet the name is filled in from the machine's Tailscale name
(for example `redmi-note-11e-pro`). Names are unique, so there's never two
"slim"s to choose between.

Once two or more devices are named:

- **Send to** picks where drops go: the **Hub** (`received/`, as before) or
  a device. Items sent to a device wait in its **For this device** list,
  labelled with who sent them. If the device is off they stay on the hub
  until it comes back, which KDE Connect can't do.
- **Chat.** With a device picked, the text box becomes a chat with it:
  bubbles, clickable links, tap a message to copy it. Unread counts show on
  the device buttons. Links shared from Android's share sheet to a device
  land in the chat. Text sent to the Hub is still saved as a file. Chatting
  needs a named device on both ends, so replies have somewhere to go.
- **Notifications.** Tap **Turn on notifications** on a device and it gets
  one for every file or message sent to it, even with droplet closed on a
  phone. Tapping opens the chat or the inbox. A message that's only a link
  opens the link directly. **Test** sends one to yourself.
- **Devices** (at the bottom) lists every device with an online dot, and
  lets you remove old ones. Removing a device deletes what was waiting for
  it and its chats. Over the tailnet it also lists **Tailscale machines that
  haven't opened droplet yet**, so you know what's missing.

**How notifications travel:** a web app can only wake a closed phone app
through the browser's push service (Google's for Chrome, Mozilla's for
Firefox). droplet encrypts each notification to the browser before handing it
over, so the push service sees only that *a* message arrived, never the
filename or text. Files and chats never leave your hub. The hub needs
internet access for this. `DROPLET_PUSH=0` turns push off for a fully local
hub: new items then show up while droplet is open.

Good to know:

- A device is a browser + address pair. The same phone on the LAN URL and on
  the tailnet URL counts as two devices (different cookies), so stick to one
  URL per device, ideally the tailnet one.
- On desktop, notifications arrive while that browser is running (it can be
  in the background). On Android they arrive with the app closed.
- A device's identity is a random token in a long-lived cookie. Only its
  hash is stored on the hub (`devices.json`, owner-only permissions).
  Clearing site data in the browser makes it a new device; remove the old
  one under Devices.

**Verified** with the T15 hub, a Redmi Note 11E Pro (installed app, Chrome)
and slim: files both ways land in the right inbox with the sender's name, a
push notification rang the phone with the app closed, and the chat works
both ways. Tested in two desktop browsers: unread counts, opening a chat
from a notification link, duplicate names refused, an unnamed browser can't
chat, and one device can't read another's inbox.

## The Hub tab: control the hub from any device

The app has four tabs on a phone (**Send · Files · Hub · Devices**). On a
desktop they sit side by side. The **Hub** tab is a remote for the hub
machine, with four cards.

### Now playing

Shows what's playing on the hub: album art, title and artist, a progress bar
(tap it to jump), previous / play-pause / next, ±10 s skips when the player
can seek, and the hub's volume with a mute button. With more than one player
open there's a picker. **Speaker ▾** switches the hub's audio output (speaker,
HDMI, an AirPlay sink…).

It uses `playerctl` (any MPRIS player: browsers, VLC, Spotify, and phones
relayed by KDE Connect, shown as e.g. "Chrome on fedora") and `wpctl`
(PipeWire). Both talk over the session bus, so run droplet as the
[`systemd --user` service](#run-as-a-service). Without either tool the card
doesn't appear.

- Devices never send commands, only choices: players and outputs must be
  ones the hub lists itself, and volume is capped at 100%.
- Album art from local players is served only if it's a real image file (the
  file's bytes are checked, not its name) under your home or `/tmp`, at most
  5 MB. Web art loads straight from its URL.
- The card only polls while it's on screen and the page is visible.

### Shared clipboard

Copy on one device, paste on another.

- **In a chat**, the clipboard button next to **Send** sends whatever is on
  this device's clipboard as a message. The other device taps the bubble to
  copy it.
- The **Hub clipboard** card shows what's on the hub's own clipboard. **Send
  mine to the hub** puts this device's clipboard there, ready to paste on the
  hub. **Copy the hub's here** does the reverse. The preview refreshes when
  the card appears, when you switch back to droplet, or with ↻. It isn't
  polled.

The hub side uses `wl-paste`/`wl-copy` (wl-clipboard), so the hub needs a
Wayland session (tested on KDE Plasma and niri). Text only, up to 1 MB.
Browsers only allow clipboard access over HTTPS, so use the tailnet URL. On
the plain `http://` LAN address you get a box to paste into and a block of
text to select instead.

Clipboards often hold passwords, so the hub clipboard is stricter than files:
it works over the tailnet, or on the LAN after entering `DROPLET_PIN`, and
refuses LAN guests on a hub without a PIN. What you send stays on the hub's
clipboard until something replaces it, or until the droplet service restarts
(systemd stops wl-copy's background process along with the service).
`DROPLET_CLIPBOARD=0` turns it off.

### Hub commands

Like KDE Connect's **Run command**: the hub's owner lists commands in a file,
and each gets a button. Tap one to see its output and exit code. Devices only
say *which* command to run. What runs comes from the file on the hub, so a
phone can't run anything that isn't listed.

```bash
cp deploy/commands.example.json ~/.config/droplet/commands.json
# edit it; droplet picks up changes on its own, no restart
```

The file is a JSON list. Each entry:

| Key | | Meaning |
|---|---|---|
| `name` | required | button label (also its id, so keep names unique) |
| `run` | required | the program and its arguments, as a list: `["df", "-h"]` |
| `icon` | optional | an emoji for the button |
| `confirm` | optional | `true` = ask "are you sure?" first |
| `timeout` | optional | seconds before it's killed (default 30, max 300) |

- `run` is **not** a shell line. Pipes, `~` and `$VARS` need a shell, so ask
  for one explicitly: `["bash", "-lc", "..."]`.
- Commands run as droplet's user, in their home folder, with no input and
  without droplet's own `DROPLET_*` settings (so the PIN isn't passed on).
  The output shown is the last 8 KB of stdout and stderr together. On
  timeout, everything the command started is killed.
- One run of each command at a time.
- A broken entry is skipped and the rest still work. The reason goes to the
  log (`journalctl --user -u droplet`) and to `GET /api/hub/commands` under
  `errors`. No file means no card.
- From a script:
  `curl -X POST -H 'X-Droplet-Run: 1' https://<hub>/api/hub/commands/uptime/run`.
  The header is required.

**Locking the screen.** The example uses `loginctl lock-session`, which only
works if something listens for it (Plasma and GNOME do). On niri or sway,
start the locker yourself, in its own session so the command's timeout can't
kill it:
`["bash", "-lc", "setsid -f gtklock -d >/dev/null 2>&1 </dev/null"]`.

Anyone who can open droplet can press these, so only list commands you'd let
any of your devices run. The example deliberately has no suspend or
power-off: the hub is meant to stay awake.

### Find a device

A button for every other device, plus **Ring the hub**.

- **Ringing a device** makes it loud for up to a minute:
  - **droplet open on it:** a full-screen alert with a big **Stop**, a ringing
    tone and vibration. If the browser blocks sound until the page is
    touched, it says "Tap anywhere to hear it".
  - **droplet closed** (with notifications on): a notification that stays up
    and vibrates. Tapping it, its **Stop ringing** button, or swiping it away
    stops the ring.

  The sender's button pulses until the other side answers. Either side can
  stop it, and it stops by itself after 60 s.
- **Ringing the hub** plays the freedesktop "incoming call" sound on the hub
  four times, using the first of `pw-play`, `paplay` or `canberra-gtk-play`
  it finds, at the hub's current volume.

**Limits:** a closed web app can't play its own sound, so on a phone with
droplet closed the ring is the notification's sound and vibration, which
silent mode and Do Not Disturb mute. The [Android app](#android-app) rings
through the alarm channel instead, which isn't muted by silent mode. Rings
live in memory, so restarting the hub forgets them.

### Cross-site protection

Tailnet devices are trusted by network, not by cookie. Without a guard, any
website you visit could make your browser post to droplet (delete files, run
a command). So droplet refuses any write whose `Origin` isn't droplet itself.
Browsers always send `Origin` on cross-site posts. curl and the native apps
don't send it and aren't affected.

**Verified** on the T15 hub (niri): commands ran from slim over the tailnet,
the media card read the KDE Connect player, the volume and all five outputs,
and the clipboard read works under niri. Also tested in desktop browsers
against real `playerctl`/`wl-clipboard`, with no sound played and nothing
changed on the hub. Not yet tried: ringing a real phone, and the hub's ring
sound.

## Android app

A native companion in `android/` (see [android/README.md](android/README.md)).
It adds what a web app can't do on a phone:

- **Share → droplet** from any app, with a device picker and the original
  file names (Xiaomi Gallery otherwise hands over bare numbers).
- **A loud "find my phone" ring.** It plays on the alarm channel at full
  volume, so silent mode doesn't mute it, then puts the volume back.
- **Notification mirroring:** the phone's notifications appear on a **Phone**
  card in the Hub tab on your other devices, with its battery level.
- **Notifications for files and messages** sent to the phone, even with the
  app closed.

Everything else is the normal web app inside it.

**Install:** open droplet on the phone, tap **droplet.apk** under Shared, and
allow installs from your browser when Android asks. Open the droplet app,
keep the default hub address (Tailscale must be on), and name the phone in
the page. Then open ⚙ (top right) for settings:

- **Stay connected** lets other devices ring the phone and notifies you about
  files and messages. It shows a quiet "droplet connected" notification.
- **Notification access** turns on mirroring. Leave apps out under **Apps not
  to mirror**.

Permissions: notifications, notification access (you grant it), a
foreground service, exact alarms, and run at startup. No Google services,
nothing outside your tailnet.

**Xiaomi / Redmi (MIUI, HyperOS):** MIUI kills background apps. For rings to
arrive, turn on **Autostart** for droplet, set its battery saver to **No
restrictions**, and lock it in Recents. The app's settings have buttons for
the first two.

**Build:** `cd android && ./gradlew assembleRelease` (JDK 17+, Android SDK).
Release builds are signed with `~/.android/droplet-release.jks` when
`~/.android/droplet-release.properties` exists, otherwise with the debug key.
Keep that keystore backed up: updates must be signed with the same key.

Hub side (`phone.py`): `POST /api/phone/notifications` (`posted` / `removed`
/ `sync`), `GET /api/phone/notifications`, `POST /api/phone/status`
(battery), `POST /api/phone/<id>/clear`. The latest 50 per phone are kept in
`phone.json` (owner-only permissions, git-ignored).

**Verified** on an Android 16 emulator against a local hub: setup, the
WebView, downloads and zip, the file chooser, a share from the Files app
with the original name kept, sharing text into a chat, a ring picked up by
Stay connected (alarm volume raised and restored, Stop from the notification
and the full-screen screen, auto-stop, waking a locked screen), and
mirroring a posted and removed notification to the Phone card. Not yet
tried on the Redmi itself (MIUI autostart and battery rules, real Doze).

## Remote control: drive your other devices

Like KDE Connect's remote input and multimedia control, for every machine
you own. A device becomes controllable once a small **helper** runs on it:
the [Linux agent](#linux-computers-droplet-agent), the
[Windows app](#windows-app) or the [Android app](#android-app). The helper
and the browser on that machine are **one device**, so each machine appears
once.

Controllable devices get a **Control** button: in the **Remote control** card
on the Hub tab, on their row under **Devices**, and in the chat header. It
opens a full-screen remote on a phone and a large panel on a desktop, with a
section for each thing that device's helper can do:

- **Touchpad.** Slide to move (with acceleration), tap to click, two-finger
  tap to right-click, three-finger tap to middle-click, two fingers to
  scroll (natural direction, optional glide), double-tap and hold to drag.
  Left and Right buttons sit under the pad, and ⚙ sets the speed. On a
  desktop, drag with the mouse or use the wheel. **Capture mouse** hands this
  computer's mouse and keyboard over until you press Esc.
- **Keyboard.** Type in the box and it types on the other device as you go:
  emoji, accents and phone autocorrect included. There are special keys
  (Esc, Tab, arrows, Home/End, PgUp/PgDn, F1–F12), sticky Ctrl/Alt/Shift/Super
  (tap for the next key, double-tap to hold), common shortcuts and
  media/volume keys.
- **Slides.** A presentation remote with big Next and Previous, Start, Black
  screen, End and a timer. It sends the standard keys, so it works in any
  slide app. **Hold to point** turns the phone into a laser pointer through
  its motion sensors. The screen stays on while it's open. The Android app
  has a native version too, where the **volume keys change slides**.
- **Media.** What's playing on that device, with art, seek,
  play/pause/next/previous, volume, mute and a player picker. That includes
  **your phone's music**, controlled from a computer.
- **More.** **Lock** the screen, or take a **Screenshot**. It arrives in this
  device's **For this device** list, and is shown right away.
- **Messages and Files** (the Android app). Read and answer the phone's SMS,
  and browse its Downloads, Camera, Pictures, Documents, Music and Movies.
  **Get** sends a file to the device you're on.

**Clipboard sync.** Helpers can share the clipboard automatically: copy on
one computer, paste on another. The Linux agent and the Android app can have
it on; the Windows app has it off by default, because it sends *everything*
you copy, passwords included. Android only lets the app on screen read the
clipboard, so the phone's clipboard goes out when you open droplet, or with
**Send clipboard** in its notification or quick-settings tile.

**Setting a machine up:** on it, open droplet in the browser, name the
device, and choose **This device → Set up remote control**. It shows a
six-digit code that works once, for 10 minutes, and the steps for that
system. The page notices when the helper links.

Everything goes over one live connection per device (`/ws`, a WebSocket
through the hub; the format is in [docs/remote.md](docs/remote.md)). The hub
only relays and the helpers do the acting, so if a helper isn't running its
controls say so and come back when it reconnects. Anyone who can open
droplet can control a device whose helper allows it: the same trust as the
rest of droplet (your tailnet, or the PIN). Each helper lets you switch every
ability off.

### Bluetooth mouse & keyboard (Android)

The phone can pair with **any computer or TV as an ordinary Bluetooth
keyboard, mouse and media remote**:
- a touchpad (tap, two-finger right-click, drag, natural scrolling);
- a keyboard with special keys and shortcuts;
- media and volume keys, plus Home and Back for TVs;
- the presentation remote's slide keys (the volume buttons change slides).

**Nothing is installed on the other side, and it needs no hub and no
Wi-Fi.** That covers the TV, a friend's laptop, a projector PC, even a
computer's boot menu.

Open it from **Settings → Bluetooth mouse & keyboard** in the Android app, or
pick **Bluetooth: <device>** in the presentation remote. The first time, tap
**Make this phone visible** and pair from the computer or TV (the app shows
the steps for Windows, KDE and Google TV). After that, it's one tap to
reconnect.

It types by key position with a US layout, so characters a US keyboard can't
produce are skipped, and the app names them. It needs Android 9+ and a phone
whose firmware allows Bluetooth HID device mode. If yours doesn't, the app
says so. Details: [android/README.md](android/README.md#bluetooth-mouse--keyboard).

### Linux computers (droplet agent)

On the computer, run the command **Set up remote control** shows. At home,
use the hub's LAN address; no Tailscale needed:

```sh
curl -fsSL http://<hub's LAN address>:8000/agent/install.sh | sh -s -- --code 123456
```

(or `https://<hub's tailnet name>/agent/install.sh` from anywhere on your
tailnet). Without a link code, leave `--code` out: over the LAN it shows a
four-digit code and waits for you to allow it (or give the PIN with `--pin`).
`droplet-agent setup` on its own finds hubs on the network. Like the other
apps, the agent is local-first. On the hub machine itself it uses loopback,
and `droplet-agent status` shows the route.

No sudo: it installs into `~/.local/share/droplet-agent` and runs as a
`systemd --user` service with your desktop. The hub serves both the script
and the agent itself. For a machine with no browser (like the hub), use
`--name NAME` instead of `--code`.

- **Input.** On KDE and GNOME it goes through the desktop's remote-control
  portal, which asks you once to allow it. On niri, sway and other
  compositors it needs `/dev/uinput`, which takes a one-time root step:
  `droplet-agent doctor` prints the exact commands.
- **Media and volume** use `playerctl` and `wpctl`, and **lock** runs
  `loginctl lock-session` or your locker (gtklock, swaylock).
- **Screenshots** use the portal, then niri, spectacle, gnome-screenshot or
  grim, whichever works. **Clipboard** uses wl-clipboard (xclip on X11).
- `droplet-agent status` shows what works and why the rest doesn't. Switch
  abilities off in `~/.config/droplet-agent/config.json`. Details:
  [agent/README.md](agent/README.md).

### Windows app

`windows/` holds **droplet.exe**, a tray app for Windows 10/11. It's in
droplet's **Shared** folder on the hub.

- Received files save to `Downloads\droplet`, with a notification. Messages
  show as notifications too.
- It rings loudly (with a Stop button) when another device rings the PC.
- Explorer's right-click gets **Send to → droplet → …**. The tray menu sends
  files, the clipboard (text, screenshots, copied files) or a ring to any
  device.
- **Remote control:** mouse and keyboard, slides, media with exact volume,
  lock, screenshots of all monitors, and optional clipboard sync. Link it
  with **Link with code** in its settings (or `droplet link --code 123456`).
  **Pause remote control** in the tray switches it all off, and the tray
  icon turns amber while the PC is being controlled.

It isn't code-signed, so SmartScreen warns the first time: **More info → Run
anyway**. Input can't reach apps running as administrator unless droplet
does too. Build it on any OS with Go 1.26+ (`cd windows && ./build.sh`).
Details: [windows/README.md](windows/README.md).

### TV remote

Control an Android TV or Google TV from any droplet device. The hub talks to
the TV directly, over the same protocol as the Google TV phone app.

**Pair it once:** turn the TV on, open **Hub → TV remote**, and pick your TV
(or add it by IP: on the TV, Settings → Network & Internet). The TV shows a
six-character code: type it in. Every droplet device can use the TV from then
on.

**Open remote** gives:
- a D-pad with OK, Back / Home / Menu, volume and channel, mute, input and
  media keys;
- **touchpad mode** (swipe to move, tap for OK, hold for options);
- **keyboard** (type into the TV's search box);
- **apps** (YouTube, Netflix, Prime Video, Spotify, Showmax, Disney+, Plex),
  or any https:// link.

Hold a key to repeat it or long-press. On a computer, the keyboard drives it
(arrows, Enter = OK, Backspace = Back, +/- = volume, Space = play/pause).
**Turn on** works while the TV is in network standby (most Google TVs keep
it on). From deep standby, droplet also tries Wake-on-LAN.

**From the phone, without the hub.** The Android app (1.5+) has its own TV
remote. It pairs with the TV itself and controls it over Wi-Fi with the same
protocol, so it keeps working when the hub is off.

- **Opening it:** the quick-settings tile, the launcher shortcut, Settings,
  or the devices screen.
- **Layout:** it opens on the essentials (D-pad, Back, Home, volume, power),
  with **More buttons** for the rest.
- **Volume keys:** the phone's volume keys drive the TV's volume.

The TV lists the phone as a remote of its own, next to the hub's; pairing one
doesn't pair the other. Details:
[android/README.md](android/README.md#tv-remote).

It needs `androidtvremote2` (in requirements.txt). The hub's client
certificate and paired TVs live in `tv/` under DROPLET_HOME (owner-only,
git-ignored); deleting it un-pairs everything. Keys come from a fixed list,
and links must be https://.

**Verified:**
- The TV code against `tests/fake_tv.py`, a pretend TV that speaks the real
  protocol over TLS: pairing (including a wrong code), keys, text, apps,
  power, state, and the TV going away and coming back.
- The Linux agent: installed on the T15 with the one-liner, it reported its
  abilities, published what was playing, and took a real screenshot that
  arrived as a PNG.
- The web remote: its touchpad, keyboard, slides and media events were
  checked one by one against the protocol with a fake helper, and the
  control sheet was shown with the T15's real playing track.
- Windows: 33 end-to-end checks against a real hub, plus a Wine smoke test.
- Android: JVM tests against a live hub (linking, media, SMS, files,
  clipboard, volume-key slides).

**Not yet tried on real hardware:** input on slim, the T15 or maryanne; the
Redmi's SMS and file access under MIUI; the real TV.

## Config (env vars)

| Var | Default | Meaning |
|---|---|---|
| `DROPLET_PORT` | `8000` | listen port |
| `DROPLET_HOST` | `0.0.0.0` | bind address |
| `DROPLET_LAN_IP` | *(auto)* | address to advertise in the QR code, banner, mDNS record and cert. Set this when the host is on more than one network and the autodetected address is the wrong one |
| `DROPLET_PIN` | *(off)* | require this PIN before access |
| `DROPLET_HTTPS` | *(off)* | `1` = HTTPS with a persistent self-signed cert (browser will warn once — accept it) |
| `DROPLET_NAME` | `droplet` | mDNS hostname (`<name>.local`) |
| `DROPLET_HOME` | app dir | where `received/`, `shared/`, `certs/` live |
| `DROPLET_MAX_MB` | `1024` | max upload size |
| `DROPLET_TAILSCALE` | *(off)* | `1` = also serve at `https://<machine>.<tailnet>.ts.net` with a real certificate, via `tailscale serve` (see [Tailnet](#tailnet-real-https-from-anywhere)) |
| `DROPLET_TAILNET_TRUST` | `1` | with `DROPLET_PIN` set, tailnet devices skip the PIN. `0` = they enter it like everyone else |
| `DROPLET_LAN_GUESTS` | `drop` | what a device on the LAN can do before it's let in: `drop` = send files to the hub, `none` = nothing (see [Local-first](#local-first-home-wi-fi-first-tailscale-when-away)) |
| `DROPLET_LAN_TLS_PORT` | `8443` | LAN HTTPS port for the native apps, with a pinned self-signed certificate. `0` = off |
| `DROPLET_PUSH` | `1` | `0` = no push notifications (nothing goes through Google/Mozilla); devices see new items while droplet is open. See [Devices](#devices-send-to-one-chat-get-notified) |
| `DROPLET_COMMANDS` | `~/.config/droplet/commands.json` | preset commands for the Hub tab (see [Hub commands](#hub-commands)) |
| `DROPLET_CLIPBOARD` | `1` | `0` = no shared hub clipboard (see [Shared clipboard](#shared-clipboard)) |

## How files flow

- **Send to the hub:** open the page on any device, drop/pick files → they land in `received/` on the hub.
- **Fetch from the hub:** anything in `shared/` (or `received/`) is listed on the page with a download link.
- **Phone → phone:** phone A uploads, phone B downloads from the list. The hub relays.

## Phone as the hub (Termux, Android)

```bash
pkg install python
pip install -r requirements.txt
python app.py
```

## Posting files from a microcontroller / script

`/upload` is a plain multipart POST — anything that can speak HTTP can drop
files into `received/` with zero changes on the droplet side. Sensor logs,
camera captures, cron jobs, whatever. The field name is `files` (see
`request.files.getlist("files")` in `app.py`), and the response is JSON:
`{"saved": ["<filename>", ...]}`.

### curl

Tested against a running container from this branch:

```bash
$ curl -F "files=@reading.csv" http://192.168.1.7:8000/upload
{"saved":["reading.csv"]}
```

Filenames get sanitized (`secure_filename`) and de-duplicated
(`reading.csv`, `reading-1.csv`, …) — you don't need to worry about
collisions from repeated posts.

### ESP8266 (Arduino, `ESP8266HTTPClient`)

`ESP8266HTTPClient` has no built-in multipart helper, so the sketch below
builds the multipart body by hand. The field name (`files`) and the response
shape match `app.py` exactly — this isn't a guessed API.

```cpp
#include <ESP8266WiFi.h>
#include <ESP8266HTTPClient.h>
#include <WiFiClient.h>

const char* WIFI_SSID     = "your-ssid";
const char* WIFI_PASSWORD = "your-password";

// droplet's LAN IP. Don't rely on droplet.local from a microcontroller —
// ESP8266 doesn't resolve mDNS by default, so use the printed IP.
const char* DROPLET_HOST = "192.168.1.7";
const uint16_t DROPLET_PORT = 8000;

bool uploadFile(const String& filename, const uint8_t* data, size_t len) {
  WiFiClient client;
  HTTPClient http;

  String url = "http://" + String(DROPLET_HOST) + ":" + String(DROPLET_PORT) + "/upload";
  if (!http.begin(client, url)) {
    Serial.println("HTTPClient begin failed");
    return false;
  }

  // Field name must be "files" — matches request.files.getlist("files") in app.py.
  String boundary = "dropletESP8266Boundary";
  String head = "--" + boundary + "\r\n"
                "Content-Disposition: form-data; name=\"files\"; filename=\"" + filename + "\"\r\n"
                "Content-Type: application/octet-stream\r\n\r\n";
  String tail = "\r\n--" + boundary + "--\r\n";

  size_t contentLength = head.length() + len + tail.length();
  uint8_t* body = (uint8_t*)malloc(contentLength);
  if (!body) {
    Serial.println("Out of memory building request body");
    http.end();
    return false;
  }
  memcpy(body, head.c_str(), head.length());
  memcpy(body + head.length(), data, len);
  memcpy(body + head.length() + len, tail.c_str(), tail.length());

  http.addHeader("Content-Type", "multipart/form-data; boundary=" + boundary);
  int status = http.POST(body, contentLength);
  free(body);

  if (status > 0) {
    Serial.printf("POST /upload -> %d\n", status);
    Serial.println(http.getString());  // e.g. {"saved":["reading.csv"]}
  } else {
    Serial.printf("POST /upload failed: %s\n", http.errorToString(status).c_str());
  }

  http.end();
  return status == 200;
}

void setup() {
  Serial.begin(115200);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nConnected: " + WiFi.localIP().toString());

  const char* reading = "temp_c,25.4\nhumidity_pct,61\n";
  uploadFile("sensor-" + String(millis()) + ".csv", (const uint8_t*)reading, strlen(reading));
}

void loop() {
  // e.g. delay(60000); read a sensor; uploadFile(...) again
}
```

(ESP32's `HTTPClient` is API-compatible with the above — same approach works
with `#include <HTTPClient.h>` and `WiFi.h` instead of the ESP8266 headers.)

### `DROPLET_PIN` and devices

If the droplet instance has `DROPLET_PIN` set, the PIN gate applies to
**every** route, including `/upload` — verified: an unauthenticated POST to
`/upload` gets a `302` redirect to `/login`, not the upload. Auth is a
session cookie set by `POST /login` with a `pin` form field; there's no
separate token/header auth for API-style clients.

That's workable from a script with a cookie jar (`curl -c jar.txt -d
"pin=1234" http://host:8000/login` once, then `curl -b jar.txt -F
"files=@..." http://host:8000/upload`), but it's awkward for a microcontroller
— `ESP8266HTTPClient` has no cookie jar, so you'd have to capture the
`Set-Cookie` header from the login response yourself and re-add it as a
`Cookie` header on every upload. **Simplest option: leave `DROPLET_PIN`
unset on any droplet instance a device posts to.** If you need the PIN for
browser access too, consider a second droplet instance/port dedicated to
device ingestion with the PIN off.

## Notes

- Flask's built-in server — fine for home LAN and your tailnet, not for the public internet (don't `tailscale funnel` it).
- Repeated filenames don't overwrite: `shot.png`, `shot-1.png`, …

## Licence

droplet is free software: you can redistribute it and/or modify it under the
terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE).

Copyright © 2026 Ferinmtk
