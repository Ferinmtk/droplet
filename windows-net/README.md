# droplet for Windows, in .NET

The Windows app is being rebuilt in C# on .NET 10 (#43), to replace the Go
app in [`../windows`](../windows) once it's verified on real Windows. It has
two halves:

- **Droplet.Core**, the engine: everything that isn't Windows, so it builds
  and is tested on Linux.
- **Droplet.Windows**, the app (`droplet.exe`): a WPF shell on the Fluent
  theme, the tray, the windows, and the Windows side of every platform
  service. It builds anywhere and runs on Windows 10 2004 or later.

It ships two ways: a plain self-contained `droplet.exe` for GitHub Releases,
and an MSIX for the Microsoft Store (and for sideloading). See
[Packaged and unpackaged](#packaged-and-unpackaged) and
[`packaging/`](packaging/README.md).

It does what the Go app does (the hub client, local-first routing with a
pinned LAN certificate, joining and linking, the poll loop, remote control
over the hub's live connection) and makes Windows a **mesh peer**
([`docs/mesh.md`](../docs/mesh.md)): devices talk to each other directly,
with the hub as an optional helper. It follows the Linux agent, the
reference peer, byte for byte, and its tests run against it.

## Architecture

| Namespace | What's in it |
|---|---|
| `Config` | `AppConfig` (the Go app's field names, so its `config.json` imports on first run), `ConfigStore` (atomic, owner-only writes) |
| `Hub` | `HubClient`: the hub's HTTP API as one device. Bearer token, no `Origin` header, no redirects |
| `LocalFirst` | [local-first](../docs/local-first.md): the pinned LAN connection, `_droplet._tcp` discovery, route choice (`RouteSelector`, `RouteManager`), the hub's identity over time, and getting in (`HubSetup`: join with a code, link, PIN, re-pair) |
| `Mdns` | a minimal mDNS responder and browser (DNS messages, RFC 6762) |
| `Mesh` | the peer: identity, trust list, pairing, the mesh port (Kestrel), links, files with resume, the outbox, routing (`MeshNode`), the hub's roster (`HubMeshBridge`), mDNS (`PeerDirectory`) |
| `Remote` | `RemoteDispatcher`, which acts on `input`, `media`, `cmd`, `clip` and `rpc` from the hub or a peer alike; `HubLiveSession`, the hub's `/ws`; clipboard and media watchers |
| `Polling` | `HubPoller`: inbox downloads, messages, rings |
| `Platform` | the interfaces the Windows shell implements: `IInput`, `IMedia`, `IClipboard`, `INotifications`, `IScreenshot`, `ILock`, `ISound` |

`DropletEngine` puts it all together as the app runs it: the shell creates
one with its `PlatformServices` and an identity store, shows its state, and
calls into its parts.

What happens to a message this device sends (docs/mesh.md §5): an open link
or a new one on the LAN, then the tailnet, then the hub, then the hub's
mailbox, then the outbox, which keeps it until a route appears. Live control,
clipboard and ring never queue.

## Security properties

- **The mesh port** asks for a client certificate without requiring one. The
  TLS validation callback accepts only a certificate whose fingerprint is in
  the trust list, so any other one fails the handshake, as in the reference.
  A client with no certificate reaches only `/mesh/pair*`; everything else is
  a 403 before any body is read. Every request checks the fingerprint again,
  which also covers a resumed TLS session of a peer unpaired since.
- **As a client**, the peer presents its certificate whatever CAs the server
  names, and checks the server's fingerprint inside the handshake, before
  sending a byte. No SNI, no host names, no CAs anywhere in the mesh.
- **Pairing** commits to a nonce before seeing the other's, signs the
  transcript (ECDSA P-256, DER), and trusts the other side only when both
  owners said yes: 1 chance in 10,000 per attempt for a man in the middle,
  and each attempt shows a request. At most 3 open requests and 20 a minute.
- **The roster** is trusted only as far as the hub is, and only with each
  entry's certificate matching its fingerprint; a peer never trusts itself.
- **The hub on the LAN** is trusted by its pinned certificate fingerprint and
  nothing else; that relaxation is confined to one handler. The tailnet route
  keeps normal TLS validation. A changed certificate is reported, never
  adopted silently, and the token is never sent to one nobody vouched for.
- **The mesh key** is kept by an `IIdentityStore`: sealed with DPAPI on
  Windows (`ProtectedIdentityStore`), owner-only files elsewhere.
- **Remote control** acts only on what's switched on and what the platform
  can do; `from` is always the authenticated sender. Typed text and clipboard
  contents are never logged.
- **Received files** get safe, unique names (no paths, hidden names, Windows
  device names or forbidden characters), and a download never overwrites.

## The Windows app

| Folder | What's in it |
|---|---|
| `Services/AppHost.cs` | the running app: the platform services, the engine (restarted when a mesh setting changes), and what the tray and windows show, kept up to date on the UI thread. One list of destinations: the hub, mesh peers and hub-only devices, each once |
| `Tray/` | a small `Shell_NotifyIcon` wrapper (see [Choices](#choices)), the menu, and the tooltip's wording |
| `Views/` | Devices (the main window: routes, send, message, ring, clipboard, unpair, pairing both ways), Settings, first-run Setup, Chat |
| `Platform/` | `IInput` (SendInput), `IMedia` (media sessions and Core Audio), `IClipboard`, `INotifications` (toasts), `IScreenshot`, `ILock`, `ISound`, DPAPI |
| `Shell/` | single instance, the command line, `droplet:` links, registration, Start with Windows, Send To, packaging detection, the log |

**Tray menu:** Open droplet · Send files to ▸ · Send clipboard to ▸ · Ring ▸ ·
Devices… · Open downloads folder · Pause notifications · Pause remote
control · Settings… · Quit droplet. The tooltip says how the hub is reached
("droplet — t15 on Wi-Fi", "via Tailscale"), or how many devices are linked
without a hub, and "being controlled by Home" for two minutes after remote
control. The drop is aqua, grey when nothing is reachable, and amber for a
few seconds after another device sends input.

**Port of the Go app:** SendInput with absolute moves over the virtual
desktop, carried fractions, swapped buttons and the same key mapping;
the private clipboard formats; toast XML with keyed `droplet:` links (only
files inside the download folders open from a link); the same ring tone;
the Mark of the Web on everything received; the same AppUserModelID
(`Ferinmtk.droplet`), so it takes over the Go app's notification identity,
and its `config.json` is imported on first run. The two can't run at once:
the new one asks you to quit the old one.

**Beyond the Go app:** media commands go to the chosen player through its
media session (the Go app pressed the media keys), and seeking works where
the player allows it; Setup and Settings are native windows instead of a
local web page; the PC is a mesh peer, with pairing in both directions.

**Command line:** `droplet` (start, or bring it to the front),
`droplet send --to <device|hub> <files>` (what Send To runs),
`droplet settings | devices | stop-ring | uninstall | version`. A second
`droplet.exe` hands its command to the running one and exits.

## Packaged and unpackaged

| | Plain exe (Releases) | MSIX (Store, sideloading) |
|---|---|---|
| Start with Windows | the HKCU `Run` key, only once turned on | the manifest's StartupTask, declared off, turned on through the StartupTask API (the person can also change it in Settings → Apps → Startup) |
| `droplet:` links | registered in HKCU at each start | the manifest's protocol extension |
| Notifications | under `Ferinmtk.droplet`, registered in HKCU with a Start menu entry | under the package's identity |
| Send To | `.lnk` files, only once turned on | not offered: a package's writes to AppData stay in its own copy |
| Firewall | Windows asks the first time droplet listens | the manifest's rules: TCP 1739–1749, and UDP 5353 on private networks |
| Settings and data | `%LOCALAPPDATA%\droplet` | the same path, redirected by Windows into the package's storage |
| Removing it | Settings → About → Remove from Windows, or `droplet uninstall` | uninstall the app |

## Build and test

Install the .NET 10 SDK (`global.json` pins it), then:

```bash
dotnet build                  # on Linux the WPF app builds too (EnableWindowsTargeting)
dotnet test                   # unit and interop tests
dotnet test --filter-not-namespace Droplet.Core.Tests.Interop        # unit tests only
```

The test projects are Microsoft.Testing.Platform apps, so they can also be
run directly (`tests/Droplet.Core.Tests/bin/<config>/net10.0/Droplet.Core.Tests`).
`Droplet.Windows.Tests` (the shell's pure logic: keys, input, pointer
coordinates, toast XML, links, destinations, the tooltip, Settings' checks)
needs the Windows Desktop runtime, so it runs on Windows.

To run the app on Windows:

```powershell
dotnet run --project src/Droplet.Windows
# the release exe: one self-contained file, not trimmed (WPF doesn't support trimming)
dotnet publish src/Droplet.Windows -c Release -r win-x64 --self-contained `
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -o artifacts/exe
```

The MSIX: see [`packaging/README.md`](packaging/README.md), including where
the Partner Center identity goes. CI (`.github/workflows/windows-net.yml`)
builds and tests on Windows and Linux, and uploads the exe and a test-signed
MSIX. Images are made from `static/icon-512.png` by `tools/make-assets.py`.

The interop tests run the real reference on Linux (they skip themselves on
Windows):

- **The Linux agent** (`../agent`) runs as `droplet-agent run --dry-run` in a
  throwaway HOME and XDG directories, driven through its control socket as
  `agent/tests/e2e_mesh.py` does. It's installed into a throwaway virtual
  environment on first use, or `DROPLET_AGENT_PYTHON` names a Python that has
  it already.
- **The hub** (`../app.py`) runs from a throwaway `DROPLET_HOME` on ports
  8891 and 8892 (keep them free), with the repository's `.venv`, or
  `DROPLET_HUB_PYTHON`.

They prove, both ways, against the reference: finding each other over mDNS;
mutual TLS, with untrusted certificates refused in the handshake and no
certificate reaching only pairing; pairing from either side with the same
code, and denial; text, a 20 MB file interrupted and resumed, ring, clip,
input, media, lock and screenshots reaching the fake platform services; the
roster through a hub, the hub's mailbox, remote control through the hub's
`/ws`, the poll loop, and direct delivery with the hub stopped; the hub
found over mDNS, its LAN certificate pinned, and joining over the LAN until
approved or declined. Set `DROPLET_TEST_LOG` to a file to get the engine's log there.

On a small machine, build with `-m:1 -p:UseSharedCompilation=false`.

## Choices

- **No third-party runtime dependencies.** The mesh port is ASP.NET Core's
  Kestrel (a framework reference); links and requests use `ClientWebSocket`
  and `SocketsHttpHandler`; certificates and ECDSA are .NET's own (DPAPI,
  in the shell, comes with the Windows Desktop runtime).
- **mDNS is written here** (`Mdns/`, about 1,400 lines with its comments)
  rather than taken from a library: the widely used .NET ones either only
  browse (Zeroconf) or are no longer maintained (Makaretu.Dns.Multicast, whose
  forks have few users), and the app needs little. Browsing uses one-shot legacy
  unicast queries from an ephemeral port, as the Go app did, so it works
  without port 5353 and without an inbound firewall rule. Announcing binds
  5353 with address reuse, which is how responders share it with Windows'
  own (the DNS Client service) and with avahi on Linux; if it can't, the
  device isn't announced, and everything else still works.
- **Tests** use xUnit v3 on Microsoft.Testing.Platform.
- **The tray is written here** (`Tray/NotifyIcon.cs`, about 250 lines) rather
  than taken from a library: Hardcodet.NotifyIcon.Wpf is under the CPOL,
  which isn't GPL-compatible, and H.NotifyIcon (MIT) brings a stack of
  dependencies for what's a hidden window, `Shell_NotifyIcon` and a WPF
  context menu. The menu is WPF's, so it has the Fluent theme.
- **No NuGet packages in the app.** WinRT (media sessions, toasts, the
  StartupTask) comes from the Windows SDK projection the `net10.0-windows10.0.19041.0`
  target brings; DPAPI, WPF and the registry from the Windows Desktop runtime;
  Core Audio and IShellLink through .NET's COM interop. Toasts use
  Windows.UI.Notifications directly (the Community Toolkit's helper is
  deprecated and wants a COM activator), with protocol activation for buttons,
  as the Go app did.
- **MSIX with MakeAppx**, from a small MSBuild project, rather than a Windows
  Application Packaging project (which needs Visual Studio's MSBuild) or
  single-project MSIX (which comes with the Windows App SDK).

## Test on maryanne

This is the only real verification: nothing here has run on Windows yet.
Keep the log open (Settings → About → Open the log) while testing. Quit the
Go app first (its tray icon → Quit droplet).

**A. The plain exe (download `droplet-windows-exe-*` from the CI run)**

1. Put `droplet.exe` in `%LOCALAPPDATA%\Programs\droplet\` and run it.
   SmartScreen: More info → Run anyway. Expect: no console window; the drop
   appears in the notification area; since the Go app's config is imported,
   the Devices window opens (on a fresh PC, Setup opens instead).
2. Run `droplet.exe` again: the Devices window comes to the front, and there's
   still one tray icon. Try `droplet.exe version` from a terminal.
3. Hover the tray icon: "droplet — t15 on Wi-Fi". Turn Wi-Fi off with
   Tailscale on: within a few seconds, "via Tailscale". With neither, grey.
4. Right-click the icon: the menu is themed (switch Windows between light and
   dark mode, the menu and windows follow; the accent is teal). Escape or a
   click elsewhere closes it. Restart Explorer (Task Manager → Windows
   Explorer → Restart): the icon comes back.
5. Windows Firewall asks about droplet the first time: allow on private
   networks. Settings → Direct connections shows "Listening on port 1739" and
   the fingerprint.
6. **Setup** (Settings → Change hub…): the hub shows under Hubs on this
   network. Join as a new name (e.g. `maryanne-test`): a four-digit code; the
   phone asks to let it in with the same code; allow → "This PC is part of
   droplet". Try the PIN path and a link code once each, and the Tailscale
   address.
7. **Devices**: the phone, the hub and other PCs listed once each, with
   routes. Send files… to the phone: they arrive; a notification says sent.
   Message…: a chat; send one each way (Enter sends, Shift+Enter is a new
   line). Ring: the phone rings. Send clipboard with text, an image, and copied
   files.
8. **Receiving**: send a file from the phone. A toast "phone sent a file"
   with Open and Show in folder; both work. The file is in
   `Downloads\droplet`, and its Properties show "This file came from another
   computer" (the Mark of the Web).
9. **Ring this PC** from the phone: loud chime, a toast that stays up with
   Stop, a "Stop ringing" item in the tray and a banner in Devices. Stop from
   the toast (it goes through a droplet: link: the ring stops). Ring again and
   let it run: it stops after a minute.
10. **Remote control** from the phone's remote: pointer moves smoothly across
    both monitors (including one scaled 150 %), clicks, right-click, drag,
    scrolling both ways, typing text with an emoji, Ctrl+C / Alt+Tab / the
    Windows key. The tray drop turns amber while moving, and the tooltip
    says "being controlled by …". Open Task Manager (elevated) and try to
    click in it: nothing happens, and the log says Windows blocked input.
11. Media: play something in Spotify or a browser. The phone shows the title,
    artist and art; play/pause, next, previous work; the volume slider sets
    the exact level (compare with Windows' flyout); mute toggles; seeking
    works in players that allow it.
12. Lock from the phone: the PC locks. Screenshot from the phone: a toast says
    so, and the phone gets one PNG of all monitors, sharp on the scaled one.
13. **Pause remote control** in the tray mid-drag: the button is released and
    the phone's controls stop working. Unpause.
14. **Clipboard sync** is off at first. Turn it on in Settings; copy text on
    the PC, it appears on the phone; copy on the phone, paste on the PC.
    Copy a password from a password manager (e.g. KeePassXC, 1Password): it
    doesn't reach the phone.
15. **Pairing directly**: on another PC or the Linux agent, Devices → Pair a
    device: it's listed under On this network; Pair; both show the same code;
    They match on both. It's listed "on Wi-Fi". Pair in the other direction
    too: the request appears as a toast with Accept/Deny (try Accept from the
    toast) and in Devices. Unpair it.
16. **Settings**: Start with Windows off by default. Turn it on: HKCU\…\Run
    has `droplet` with `--background`; sign out and in: droplet starts in the
    tray without opening a window. Send To off by default; turn it on:
    Explorer → Send to lists "droplet → Hub" and each device; sending a file
    from there works (also while droplet is closed: it starts, then sends).
    Turn it off: the entries go. Change the download folder (Browse…), the
    mesh port (e.g. 1745, Apply: "Listening on port 1745"), rename the PC.
17. Quit droplet from the tray: the icon goes, the process exits (Task
    Manager). Settings → About → Remove from Windows: the Run entry, Send To
    entries, Start menu entry and `HKCU\Software\Classes\droplet` are gone.

**B. The MSIX (`droplet-windows-msix-*`)**

18. Trust `droplet-test.cer` (Local Machine → Trusted People), install the
    `.msix`. droplet is in Start; it starts, and notifications show
    "droplet" with its icon.
19. No firewall prompt: the phone reaches it directly anyway (Devices shows
    "on Wi-Fi" for a peer that connected).
20. Settings → Start with Windows: turn it on; Settings → Apps → Startup lists
    droplet, on. Sign out and in: it starts in the tray only. Send To is
    greyed out, with the reason.
21. Toast buttons (Stop ringing, Open, Accept) work in the package too.
22. Uninstall the app: nothing of it is left in Startup.

