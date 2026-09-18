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

The tray icon dims when the hub can't be reached. Hover over it to see why.

## Install

1. Put `droplet.exe` somewhere it can stay, for example
   `%LOCALAPPDATA%\Programs\droplet\droplet.exe`. Start-with-Windows and the
   Send to entries point at this location.
2. Run it. The exe isn't code-signed, so SmartScreen warns you the first
   time: click **More info → Run anyway**.
3. The settings page opens in your browser. Check the hub address
   (default `https://t15.tail7375fe.ts.net`). Tailscale must be connected
   on the PC. Then either:
   - **Link with code (recommended).** Open droplet in this PC's browser,
     choose **Set up remote control of this device**, and type the six-digit
     code into **Link with code**. The app joins that browser as the same
     device, so the PC is listed once and gets its files, messages and rings.
     The code works once, for 10 minutes.
   - **Or register a new device:** keep or change the name and click
     **Save**. Use this on a PC where droplet has never been opened in a
     browser. If the name is taken (usually by this PC's own browser), link
     with a code instead, or pick another name such as `maryanne-tray`.

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
droplet setup --hub <url> --name <name> [--pin <pin>]
droplet link --code <123456> [--hub <url>] [--pin <pin>]
droplet live [--caps input,media,lock,screenshot,clipboard]
droplet stop-ring | settings | uninstall | version
```

`link` does what **Link with code** does on the settings page. `live` runs
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
  volume flyout: Spotify, browsers, Media Player…). It's read by a hidden
  PowerShell loop, because that WinRT API isn't reachable from Go without
  cgo. The loop runs only while the live connection is up with Media on, and
  it's tied to droplet with a kill-on-close job object. If it fails
  repeatedly, droplet stops trying and reports the volume with an empty
  player list. Seeking isn't supported (`can_seek` is always false), and
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
| Settings (including the remote-control switches `remote_*` and `clipboard_sync`), the device token, and the "already shown" markers | `%APPDATA%\droplet\config.json` |
| Log | `%APPDATA%\droplet\droplet.log` |
| Received files | `%USERPROFILE%\Downloads\droplet` (configurable) |
| Start with Windows | `HKCU\Software\Microsoft\Windows\CurrentVersion\Run\droplet` |
| Send To entries | `%APPDATA%\Microsoft\Windows\SendTo\droplet → *.lnk` |
| Notification identity and the `droplet:` link handler | `HKCU\Software\Classes\AppUserModelId\Ferinmtk.droplet`, `HKCU\Software\Classes\droplet` |

Everything is per user, and nothing needs admin rights. `droplet uninstall`
removes the registry entries, the shortcuts and autostart. Delete
`%APPDATA%\droplet` as well to forget the PC entirely.

## How it works

- It polls the hub every 5 s, backing off to 1 minute while the hub is
  unreachable. It uses the same API as the web app: `/api/files`,
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
- Notifications go through Windows PowerShell 5.1 and the built-in WinRT
  toast API. Notification buttons are `droplet:` links that start a short-lived
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

- Windows 10/11, 64-bit only.
- Upload progress appears as a "Sending…" notification (for anything over
  20 MB) and a final one. There's no live progress bar.
- Showing a message marks the chat as read in the web app too (the hub's
  `/api/chat` does that). Turn off message notifications if you'd rather
  keep the web app's unread badges.
- **Send files to** picks the destination first and then opens the file
  picker.
- It is unsigned, so SmartScreen warns on first run (see Install).
