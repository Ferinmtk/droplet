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
- **Adds a Send to menu in Explorer.** Right-click any file and choose
  **Send to → droplet → Hub** (or a device name).
- **Tray menu:** Open droplet · Send files to ▸ · Send clipboard to ▸ (text,
  a screenshot or copied files) · Ring ▸ · Open downloads folder ·
  Pause notifications · Settings… · Quit.

The tray icon dims when the hub can't be reached. Hover over it to see why.

## Install

1. Put `droplet.exe` somewhere it can stay, for example
   `%LOCALAPPDATA%\Programs\droplet\droplet.exe`. Start-with-Windows and the
   Send to entries point at this location.
2. Run it. The exe isn't code-signed, so SmartScreen warns you the first
   time: click **More info → Run anyway**.
3. The settings page opens in your browser. Check the hub address
   (default `https://t15.tail7375fe.ts.net`), keep or change the name,
   and click **Save**. Tailscale must be connected on the PC.

If the name is already taken (often by this PC's own browser, which
registered itself when you opened the web app), either choose another name,
such as `maryanne-tray`, or first remove the old entry under **Devices** in
the web app.

You can reopen Settings from the tray, or by running `droplet.exe` again.
Each Windows sign-in runs only one copy.

## Command line

```
droplet send --to <device|hub> <file>...
droplet text --to <device|hub> "message"
droplet ring <device|hub>
droplet status
droplet setup --hub <url> --name <name> [--pin <pin>]
droplet stop-ring | settings | uninstall | version
```

Devices can be given by name or id. `droplet.exe` is a GUI program so that
no console window flashes up, which means `cmd` doesn't wait for it to
finish. Output still reaches the console. To make the shell wait, use
`start /wait droplet status` or pipe the output: `droplet status | more`.

## Where things live

| What | Where |
|---|---|
| Settings, the device cookie, and the "already shown" markers | `%APPDATA%\droplet\config.json` |
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
- The device identity is the `droplet_device` cookie from `POST /api/device`,
  kept in `config.json`.
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
`go run ./tools/mkicon`.

On Linux, `go run .` starts the same companion headless: it polls, downloads
and serves the settings page, and prints notifications to the terminal. It
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
