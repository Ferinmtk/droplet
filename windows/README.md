# droplet for Windows

A small tray companion for a droplet hub. The web app is still the main
interface (open the hub in Edge or Chrome and install it as an app). The
companion covers what a browser tab can't do in the background:

- **Receives files** sent to this PC and saves them to
  `Downloads\droplet` (optional), with a notification that opens or shows the file.
- **Shows messages** from other devices as notifications. Clicking one opens the chat.
- **Rings out loud** when another device rings this PC, even with no
  browser open. It stops when you press **Stop**, after 60 seconds, or when
  the ring is cancelled elsewhere.
- **Lets your other devices control this PC** in real time: mouse and
  keyboard (and the presentation remote), media and volume, locking,
  screenshots, and clipboard sync. See [Remote control](#remote-control).
- **Adds a Send to menu in Explorer.** Right-click any file and choose
  **Send to → droplet → Hub** (or a device name).
- **Tray menu:** Open droplet · Send files to ▸ · Send clipboard to ▸ (text,
  a screenshot or copied files) · Ring ▸ · Open downloads folder ·
  Pause notifications · Pause remote control · Settings… · Quit.

The tray icon dims when the hub can't be reached. Hover over it to see why,
and to see how the hub is reached: "droplet — t15 on Wi-Fi" or "droplet —
t15 via Tailscale".

**Local-first.** Like KDE Connect, the app talks to the hub directly on your
home network, with no Tailscale needed. Tailscale is the extra that makes it
work away from home. See [How it finds and trusts the hub](#how-it-finds-and-trusts-the-hub).

## Install

1. Put `droplet.exe` somewhere it can stay, for example
   `%LOCALAPPDATA%\Programs\droplet\droplet.exe`. Start-with-Windows and the
   Send to entries point at this location.
2. Run it. The exe isn't code-signed, so SmartScreen warns you the first
   time: click **More info → Run anyway**.
3. The settings page opens in your browser. Under **Hubs on this network**
   it lists the droplet hubs it finds on your Wi-Fi. Then either:
   - **Join (at home, no Tailscale needed).** Check the PC's name, and
     choose **Join** next to your hub. The page shows a four-digit code, and
     your other devices get "maryanne wants to join". Allow it on one of
     them, and check the code is the same on both screens. That's how you
     know the PC found your hub and not an impostor. If the hub has a PIN,
     you can type it instead of waiting.
   - **Link with code.** Open droplet in this PC's browser, choose **Set up
     remote control of this device**, and type the six-digit code into **Link
     with code**. The app joins that browser as the same device, so the PC is
     listed once and gets its files, messages and rings. The code works once,
     for 10 minutes. Pick the hub under **Hubs on this network** first to
     link over the LAN.
   - **Over Tailscale:** enter the hub's Tailscale address (for example
     `https://t15.tail7375fe.ts.net`), keep or change the name, and click
     **Save**. Tailnet members are trusted, so there's no code to compare.

   If a name is taken (usually by this PC's own browser), link with a code
   instead, or pick another name such as `maryanne-tray`.

If you set the app up as its own device before linking existed, link with a
code now. The settings page then offers to remove the old entry.

You can reopen Settings from the tray, or by running `droplet.exe` again.
Each Windows sign-in runs only one copy.

## Command line

```
droplet send --to <device|hub> <file>...
droplet text --to <device|hub> "message"
droplet ring <device|hub>
droplet status
droplet hubs
droplet join [<hub>] --name <name> [--pin <pin>]
droplet setup --hub <url> --name <name> [--pin <pin>]
droplet link --code <123456> [--hub <url> | --lan <hub>] [--pin <pin>]
droplet live [--caps input,media,lock,screenshot,clipboard]
droplet stop-ring | settings | uninstall | version
```

`status` shows the hub, its pinned certificate, and the route in use.
`hubs` lists the hubs on this network. `join` asks one of them (by name or
id, or the only one there is) to let this PC in, shows the code, and waits
for the answer. `link` does what **Link with code** does on the settings
page (`--lan <hub>` links over the LAN). `live` runs
only the remote-control connection in the foreground and logs what it does,
for testing. `--caps` offers exactly those capabilities, whatever Settings
says. Typed text and clipboard contents are never logged.

Devices can be given by name or id. `droplet.exe` is a GUI program so that
no console window flashes up, which means `cmd` doesn't wait for it to
finish. Output still reaches the console. To make the shell wait, use
`start /wait droplet status` or pipe the output: `droplet status | more`.

## Remote control

Other devices control the PC through droplet's remote in the web app. The
tray app keeps a live connection to the hub (`wss://<hub>/ws`, protocol in
[`docs/remote.md`](../docs/remote.md)) and announces only what's switched on
under **Settings → Remote control**:

| Switch | Default | What it allows |
|---|---|---|
| Allow remote control | on | Mouse moves, clicks, drags, scrolling (both directions), typing any Unicode text (emoji too), and keys and shortcuts with Ctrl/Alt/Shift/Win. This includes the presentation remote. |
| Media and volume | on | Play/pause, next, previous and stop via the system media keys. Sets an exact volume and mute via Core Audio. Shows what's playing. |
| Lock this PC | on | Locks the screen, as Win+L does. |
| Screenshots | on | Captures all monitors as one PNG and sends it to whoever asked. It arrives in their **For this device** list. A notification tells you each time. |
| Clipboard sync | **off** | Sends text you copy (up to 256 KB) to your other devices' helpers, and writes theirs to this clipboard. |

Why these defaults: remote control is what the connection is for, and the
tray shows when it's in use. Clipboard sync is off until you ask for it,
because it sends everything you copy, passwords included, to every device.
Even when it's on, copies that a password manager marks as private are
never sent. droplet honours Windows' `ExcludeClipboardContentFromMonitorProcessing`,
`CanUploadToCloudClipboard` and `CanIncludeInClipboardHistory` formats.

**Knowing it's in use:** while another device is sending input, the tray
drop turns amber for a few seconds. For two minutes after any remote action,
the tooltip reads "droplet — being controlled by Home". **Pause remote
control** in the tray menu (or on the settings page) switches everything off
at once, and a drag in progress is released.

**Details:**
- Pointer moves are applied as absolute positions (`SendInput` with
  `MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK`), starting from the real
  cursor. The controller already applies its own acceleration, so this skips
  Windows' "Enhance pointer precision" instead of accelerating twice.
  Fractions of a pixel carry over to the next move. Swapped mouse buttons
  are respected: "left" is the primary button.
- Scrolling converts lines to `WHEEL_DELTA` (120) units and keeps
  fractions, so touchpad-style scrolling is smooth.
- If the controller disconnects mid-drag, the button is released.
- If Core Audio isn't available, volume falls back to the volume keys
  (down to zero, then up in 2 % steps), and mute to the mute key (a toggle).
- What's playing comes from Windows' media sessions (the same source as the
  volume flyout: Spotify, browsers, Media Player…). droplet reads it once a
  second through the WinRT API itself, called directly from Go, with no cgo
  and no helper process. It's read only while the live connection is up with
  Media on. If reading fails repeatedly, droplet stops trying and reports the
  volume with an empty player list. Seeking isn't supported (`can_seek` is always false), and
  media keys act on Windows' current session, not a chosen player.
- The connection pings every 25 s and reconnects after any drop, waiting 1,
  2, 4 … up to 30 s. It authenticates with the device token as a bearer
  (plus the PIN sign-in, if the hub has a PIN), and sends no `Origin`.

**Limits (Windows' rules, not droplet's):**
- **UIPI:** input can't reach a window that runs as administrator (Task
  Manager, installers, an admin terminal) unless droplet runs elevated too.
  While one is in front, keys and clicks don't arrive, and droplet logs
  "Windows blocked … input events".
- Nothing reaches the lock screen, a UAC prompt or Ctrl+Alt+Del. Unlocking
  remotely isn't possible.
- Screenshots of DRM-protected video come out black.

## Where things live

| What | Where |
|---|---|
| Settings (including the remote-control switches `remote_*` and `clipboard_sync`), the device token, the hub's identity (`hub`: id, pinned certificate, last LAN addresses, tailnet URL), and the "already shown" markers | `%APPDATA%\droplet\config.json` |
| Log | `%APPDATA%\droplet\droplet.log` |
| Received files | `%USERPROFILE%\Downloads\droplet` (configurable) |
| Start with Windows | `HKCU\Software\Microsoft\Windows\CurrentVersion\Run\droplet` |
| Send To entries | `%APPDATA%\Microsoft\Windows\SendTo\droplet → *.lnk` |
| Notification identity and the `droplet:` link handler | `HKCU\Software\Classes\AppUserModelId\Ferinmtk.droplet`, `HKCU\Software\Classes\droplet` |

Everything is per user, and nothing needs admin rights. `droplet uninstall`
removes the registry entries, the shortcuts and autostart. Delete
`%APPDATA%\droplet` as well to forget the PC entirely.

## How it finds and trusts the hub

This follows [`docs/local-first.md`](../docs/local-first.md).

- **Finding it.** The hub announces `_droplet._tcp` over mDNS, with its
  permanent id and the SHA-256 fingerprint of its LAN certificate. The app
  asks with one-shot mDNS queries from an ordinary port, on each network
  adapter. The answers come straight back as replies, so the app doesn't
  need port 5353 (Windows' own mDNS service holds it). It shouldn't need an
  inbound firewall rule either: by default, Windows Firewall lets through
  unicast replies to a multicast query for 3 seconds. If discovery finds
  nothing, the address that worked last is still tried, and so is
  Tailscale.
- **Trusting it.** On the LAN the app connects to
  `https://<hub's LAN IP>:8443` and accepts the certificate only if its
  fingerprint is the pinned one. Where the pin comes from:
  - read over Tailscale (normal, verified TLS), when the PC first reached
    the hub that way, or when a Tailscale address is added later;
  - otherwise from the mDNS announcement, trusted on first use. The code
    comparison when joining is what confirms it's your hub.

  The tailnet route keeps normal certificate checks.
- **Choosing the route.** The app tries the LAN first. It tries the address
  that worked last, plus whatever mDNS finds with the hub's id, allowing
  about 1.5 s each. The hub's LAN IP can change with DHCP, so a stored IP is
  only a hint. The tailnet is tried at the same time, but only used when the
  LAN fails. The app chooses again whenever the network adapters change
  (Wi-Fi joined or left, Tailscale up or down). It checks every 4 s. While
  on Tailscale, it also looks for the LAN every 3 minutes. Everything moves
  with the route, including the live remote-control connection. The same
  device token works on both.
- **If the hub's certificate changes** (its `certs` folder was deleted, or
  something else is pretending to be it), the app never switches to it by
  itself. It notifies you, opens Settings, and shows **Re-pair**:
  - If the hub can be reached over Tailscale and vouches for the new
    certificate, Re-pair pins it, and nothing else changes.
  - Otherwise, Re-pair has you join again on the LAN as a new device,
    comparing the code. The old token is never sent to a certificate that
    no one has confirmed.
- **Not let in.** If the hub answers `403 {"pair": true}` (the PC was
  removed, or its request was declined), the app notifies you and opens
  Settings to pair again.
- **Upgrading.** A config from before this, with only a hub URL, works as
  before. The app then learns the hub's identity from `/api/hub/info` over
  that URL, or from the hub's mDNS announcement if the announcement names
  that URL. After that, it prefers the LAN.
- **Links in notifications** and **Open droplet** open the tailnet URL when
  this PC is on Tailscale, since this PC's browser is probably signed in
  there. Otherwise they open the hub's plain-HTTP LAN address, because a
  browser can't use the pinned connection.

## How it works

- It polls the hub every 5 s, backing off to 1 minute while the hub is
  unreachable, and every 2.5 s while waiting to be let in. It uses the same API as the web app: `/api/files`,
  `/d/inbox/…`, `/api/chat/…` and `/api/ring`.
- The device identity is a token kept in `config.json`: either this app's
  own link token from `POST /api/device/link` (linked to the PC's browser),
  or the `droplet_device` cookie from `POST /api/device` (a device of its
  own). It is sent both as that cookie and as `Authorization: Bearer`.
- Remote control runs over a separate WebSocket (see
  [Remote control](#remote-control)). The 5 s poll carries on as before for
  files, messages and rings.
- An inbox file is downloaded only once the hub has labelled it with its
  sender. The hub does that after the upload finishes, so a file that is
  still uploading is never picked up. The download goes to a temporary file
  and is renamed when complete (`name (1).ext` if the name is taken). Only
  then is the file deleted from the hub. Saved files get the same "downloaded
  from the internet" mark a browser adds.
- Each file and message is announced once, including across restarts: the
  markers are kept in the config.
- Notifications use Windows' own toast API (WinRT), called directly from
  droplet.exe. If they can't be shown, the failure is logged and droplet
  carries on. Notification buttons are `droplet:` links that start a short-lived
  `droplet.exe`. Each link carries a random key from the config, so a web
  page can't trigger them.
- Hubs without ringing are handled: the ring options report "ring not
  supported by this hub", and the hub is checked again every 5 minutes.

## Build

You need Go 1.26 or newer, on any OS. The build is pure Go and cgo-free:

```bash
cd windows
./build.sh 1.0.0            # → dist/droplet.exe
go test ./...               # tests run on Linux/macOS too
```

`build.sh` runs [go-winres](https://github.com/tc-hib/go-winres), which embeds
the icon, the manifest and the version information, then
`GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -ldflags "-H windowsgui"`.
The icons in `assets/` are generated from `../static/icon-512.png` by
`go run ./tools/mkicon` (including `droplet-live.ico`, the amber "being
controlled" icon). The committed icons were made from the icon as it was
before the redesign. Running `mkicon` now would update all four to the new
palette together.

On Linux, `go run .` starts the same companion headless: it polls, downloads
and serves the settings page, and prints notifications to the terminal.
Remote control there uses a stand-in backend that acts on nothing. It
records every call as a JSON line (to `$DROPLET_FAKE_LOG`, or stderr) and
uses the file `$DROPLET_FAKE_CLIP` as the clipboard, so
`droplet live` can be driven end to end against a real hub. It
is useful for testing against a local hub, for example with
`DROPLET_CONFIG_DIR=/tmp/dc go run . setup --hub http://127.0.0.1:8000`.

## Limits

- The route is shown as "on Wi-Fi" for any direct LAN connection,
  Ethernet included (the wording is shared with the other droplet apps).
- Hubs are found over IPv4 only, which is what the hub announces.
- Windows 10/11, 64-bit only.
- Upload progress appears as a "Sending…" notification (for anything over
  20 MB) and a final one. There's no live progress bar.
- Showing a message marks the chat as read in the web app too (the hub's
  `/api/chat` does that). Turn off message notifications if you'd rather
  keep the web app's unread badges.
- **Send files to** picks the destination first and then opens the file
  picker.
- It is unsigned, so SmartScreen warns on first run (see Install).
