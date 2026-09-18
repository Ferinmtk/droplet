# droplet for Android

A native companion app for the droplet hub. It does six things that the web
app can't do as a browser tab or PWA:

- **Share → droplet from any app.** A native sheet lists the hub and your
  named devices. Files stream up in the background with a progress
  notification and keep their real names, even from Xiaomi Gallery, which
  otherwise gives files numeric names.
- **Find my phone, loud.** Another device rings this phone, and it plays the
  alarm sound at full volume on the alarm stream, which silent mode doesn't
  mute. It also vibrates and shows a full-screen Stop button over the lock
  screen.
- **Notification mirroring.** The phone's notifications show up in a Phone
  card on your other devices, with the battery level.
- **Notifications for files and messages** sent to the phone. Push doesn't
  work inside an app WebView, so the app checks for these itself.
- **Remote control, both ways.** Your other devices can control the phone's
  music, read and send its SMS, browse its files and share its clipboard.
  The phone becomes a presentation remote for a computer: the volume keys
  change slides. See [Remote control](#remote-control).
- **A Bluetooth mouse and keyboard.** The phone pairs with a computer or TV
  as an ordinary Bluetooth keyboard and mouse: touchpad, keyboard, media keys
  and slides, with nothing installed on the other side and no Wi-Fi or hub.
  See [Bluetooth mouse & keyboard](#bluetooth-mouse--keyboard).

Everything else is the droplet web app, full screen in a WebView. It uses the
same device token as the page, so the app is the same device the page named.
The app talks only to your hub: no Firebase, no Google Play Services, no
analytics.

It's **local-first**, like KDE Connect: at home it talks to the hub straight
over the Wi-Fi, and Tailscale is only the way in when you're away. See
[Local-first: home Wi-Fi first, Tailscale away](#local-first-home-wi-fi-first-tailscale-away).

## Build

Needs JDK 17+ and the Android SDK (compileSdk 36).

```bash
cd android
export JAVA_HOME=/path/to/jdk-21 ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

`local.properties` (with `sdk.dir=…`) is machine-specific and git-ignored;
`ANDROID_HOME` works instead.

### Signing

Release builds are signed with a key that lives **outside the repo**:

```
~/.android/droplet-release.jks          # the keystore (chmod 600)
~/.android/droplet-release.properties   # its passwords (chmod 600)
```

```properties
storeFile=/home/you/.android/droplet-release.jks
storePassword=…
keyAlias=droplet
keyPassword=…
```

Set `DROPLET_SIGNING=/other/path.properties` to use a different file. Without
it, release builds fall back to the debug key, so a fresh clone still builds.
Keep the keystore backed up: Android only installs an update over an existing
install if it's signed with the same key.

Create one with:

```bash
keytool -genkeypair -keystore ~/.android/droplet-release.jks -storetype PKCS12 \
  -alias droplet -keyalg RSA -keysize 4096 -validity 10000
```

## Install on the phone

1. Put the APK where the phone can reach it, e.g. the hub's `shared/` folder,
   then download it in droplet or the browser.
2. Open it. Android asks to allow installs from that app once.
3. Open droplet on the same Wi-Fi as the hub. It shows "Looking for droplet
   on your Wi-Fi…" and lists the hubs it finds. Tap yours. (Or enter its IP,
   or use its Tailscale address.)
4. Name the phone (it suggests the phone's own name) and tap **Ask to join**.
   A four-digit code shows. On one of your devices that's already in, droplet
   shows "<name> wants to join" with the same code: check it matches and
   allow it. The hub's PIN, if it has one, or a link code also let it in.
   Over Tailscale, naming the phone lets it in straight away.
5. Open Settings (⚙ at the top right) and turn on what you want.

Updating from 1.1 (which knew only the tailnet URL) needs nothing: the first
time it reaches the hub, the app learns what it needs for the Wi-Fi. See
[Upgrading from 1.1](#upgrading-from-11).

## Permissions, and why

| Permission | For |
|---|---|
| Notifications | Upload progress, rings, and files and messages sent to the phone |
| Notification access (a system setting, granted by you) | Notification mirroring |
| Foreground service (data sync, special use) | Uploads that outlive the share sheet; Stay connected |
| Exact alarms | Stay connected keeps checking every 15 s with the screen off |
| Full-screen intent, vibrate, change audio settings | The ring: full volume on the alarm stream, then the volume is put back |
| Run at startup | Stay connected comes back after a reboot |
| Ignore battery optimisations | The Battery settings button asks for it directly |
| Read SMS, send SMS, read contacts (asked for only from Settings) | The SMS capability: listing conversations, sending, and showing contact names instead of numbers |
| All files access (Android 11+, a system setting granted by you), or storage (Android 8–10) | The Files capability: browsing Downloads, Camera, Pictures, Documents, Music and Movies |
| Nearby devices: Bluetooth connect and advertise (Android 12+), or Bluetooth (Android 8–11) | The Bluetooth mouse and keyboard: registering as one, connecting to a paired computer or TV, and making the phone visible for pairing |

Nothing here is asked for when the app starts. SMS and files access are
requested only when you tap their buttons under Settings → What other devices
can do; Nearby devices only when you open Mouse & keyboard and tap Allow.
droplet doesn't ask to scan for Bluetooth devices: the computer or TV does the
searching.

## Stay connected, and battery

Stay connected is a quiet notification ("droplet connected"), a live
connection to the hub for remote control, and a check with the hub every 15
seconds. That check picks up rings, and new files and
messages for the phone. It also reports the battery every few minutes and
whenever the battery changes. It pauses while there's no network.

In deep sleep (Doze), Android spaces the checks out to about one a minute.
That's the price of not holding the phone awake all day, so a ring can take up
to a minute to arrive on a phone that has been lying still for a long time.

### Xiaomi, Redmi, POCO (MIUI / HyperOS)

MIUI kills background apps hard. For rings to arrive:

1. **Autostart:** Settings → Apps → droplet → Autostart **on**. The app's
   Settings has a button that goes straight there.
2. **Battery saver:** set droplet to **No restrictions**.
3. **Lock it in Recents:** open Recents, long-press droplet and tap the lock,
   so "clear all" doesn't kill it.

Opening droplet restarts Stay connected if MIUI killed it.

## Notification mirroring

Turn it on with Settings → Notification access. The app forwards what shows
up on the phone: app name, title, text and time. It also forwards removals,
so dismissing on the phone clears it from the hub.

It skips:

- ongoing notifications (music, navigation, downloads)
- group summaries
- notifications marked local-only
- droplet's own notifications
- system-UI noise
- any app you untick under **Apps not to mirror**

The hub keeps the latest 50 per phone in memory and in `phone.json`, which
only the hub's user can read. **Clear** on the Phone card empties the hub's
copy, not the phone.

## Remote control

With Stay connected on, the app keeps one WebSocket open to the hub
(`wss://<hub>/ws`, see [docs/remote.md](../docs/remote.md)). It signs in with
the same device token as the page (the `droplet_device` cookie, sent as a
bearer), so the phone is one device whether you use the page or the app. It
announces only what is switched on under **Settings → What other devices can
do** and has the permission it needs. Settings shows the connection, e.g.
"Connected · 3 devices online", and the notification shows the same line.

Between messages the only traffic is a WebSocket ping every 25 seconds. If
the connection drops, the app reconnects after 1, 2, 4 … up to 30 seconds, at
once when the network changes, and at the latest on the next 15-second check,
which still fires in deep sleep. Rings aren't part of the WebSocket protocol,
so the 15-second HTTP check stays. It also runs right after each reconnect.

| Capability | What other devices get | Needs |
|---|---|---|
| Media control | What's playing in each app with a media session (title, artist, album, a small cover, position, what it can do) and the music volume; play, pause, skip, seek, volume, mute | Notification access (the same one mirroring uses) |
| SMS | The conversation list (newest first, with unread counts and contact names), a conversation's messages, and sending from the default SIM (long texts go in parts) | SMS and contacts permissions |
| Files | Browsing Downloads, Camera, Pictures, Documents, Music and Movies, and taking a copy of a file, which lands in the requester's **For this device** list | All files access |
| Clipboard sync | Text copied on a computer lands on the phone's clipboard, and the other way round | Nothing |

What stays out of reach:

- **Files:** only those six folders. Paths with `..` are refused, and every
  path is checked again after following links. Hidden files aren't listed.
  While a file goes out, the phone shows a "Sending a file to …" notification.
- **SMS:** plain SMS only. MMS (group chats, pictures) isn't read. The app
  never marks messages read or deletes them. Android files what it sends in
  the Sent box itself.
- **Media:** position updates aren't streamed. The app sends a new state when
  something changes (track, play/pause, seek, volume), at most once a second,
  and other devices advance the position while it's playing.

**Clipboard, honestly:** since Android 10 only the app on screen may read the
clipboard. So text copied on a computer reaches the phone straight away, but
the phone's clipboard goes out only:

- when you open droplet and the clipboard has changed since droplet last saw
  it, or
- when you tap **Send clipboard**: in the Stay connected notification, or on
  the quick-settings tile (edit the quick settings and drag droplet's tile in).

Copies that a password manager marks as sensitive aren't sent when you open
droplet; Send clipboard sends them only if you choose to. Android 12+ shows a
"droplet pasted from your clipboard" notice when droplet reads it. Android
13+ shows its own "copied" preview when a computer's text arrives.

### Files: why All files access

Android offers two ways to reach shared storage. MediaStore only shows other
apps' photos, videos and audio: a PDF in Downloads or a document another app
saved stays invisible. All files access gives plain paths, so the listing
matches what a file manager shows. droplet only reads, and only within the six
folders above.

### Presentation remote

Settings → **Presentation remote**, or **Remote** on the Stay connected
notification. Pick a computer that's running droplet's helper (one that offers
input). Then:

- **Next** and **Previous** are big buttons, and so are the **volume keys**:
  up is next, down is previous. Holding a key down moves one slide, not
  twenty. The page keys of a Bluetooth clicker work too.
- **Start** (F5), **Black** (b) and **End** (Esc) work in PowerPoint,
  LibreOffice Impress, Google Slides and most PDF viewers.
- A timer starts with Start or the first Next. Tap it to pause, hold it to
  reset.
- The screen stays on while it's open. It works without Stay connected: the
  screen connects by itself while it's open.

**Over Bluetooth, without the hub.** The **Presenting on** picker also lists
the phone's paired Bluetooth devices as **Bluetooth: <name>**. Aimed at one,
the same buttons and volume keys go out as a Bluetooth keyboard (ArrowRight,
ArrowLeft, F5, Esc, b): no droplet helper on the computer, no hub, no Wi-Fi.
See [Bluetooth mouse & keyboard](#bluetooth-mouse--keyboard).

### Link with code

The app is normally the same device as the droplet page you named in it,
because it shares the page's cookie. **Settings → Link with code** is for the
odd case where you want it to be a device named in another browser: make a
link code on that browser's droplet page and type it in. The app, and its
page, become that device.

### Xiaomi (MIUI / HyperOS) and SMS

MIUI has its own permission layer on top of Android's. If SMS doesn't work
after you allow it:

1. Settings → Apps → droplet → **Other permissions**: allow **Read SMS** and
   **Send SMS** (sometimes listed as "Send messages").
2. If Android greys out a permission for an app installed from a file, open
   App info → ⋮ → **Allow restricted settings** first.

When MIUI blocks sending, the app returns a clear error saying so instead of
pretending it worked. A send counts as done only once the radio reports it
sent.

## Bluetooth mouse & keyboard

Settings → **Bluetooth mouse & keyboard**, the touchpad icon on the
presentation remote, or its **Presenting on** picker. The phone registers
with Android's `BluetoothHidDevice` (Android 9+) as a combined keyboard,
mouse and media remote, so any computer or TV that takes a Bluetooth keyboard
works with nothing installed: Windows, Linux, macOS, Google TV and Android TV.

**Pairing.** Open Mouse & keyboard, tap **Make this phone visible** (two
minutes), then on the other device:

- **Windows:** Settings → Bluetooth & devices → Add device → Bluetooth.
- **KDE Plasma:** System Settings → Bluetooth → Add New Device.
- **Google TV:** Settings → Remotes & Accessories → Pair remote or accessory.

It connects by itself once paired. Next time, **Reconnect to <name>**, or tap
any paired device in the list. Keep the screen open while pairing: a computer
learns the phone is a keyboard only if it pairs while the phone is registered.
A computer or TV that was paired with the phone before (for music or calls)
may not know it can be a keyboard: remove the phone on that device and pair
again.

**What's on the screen, once connected:**

- **Touchpad:** slide to move (with the web touchpad's acceleration), tap to
  click, two-finger tap to right-click, three fingers for middle, double-tap
  to double-click, double-tap and hold (or slide) to drag, two fingers to
  scroll in the natural direction, sideways too. Left and Right below it
  press while held, so holding Left and sliding also drags.
- **Keyboard:** a text field that types what you type; Esc, Tab, Backspace,
  Enter, arrows, Home/End, PgUp/PgDn, Delete, F1–F12; sticky Ctrl, Alt, Shift
  and Win (tap for the next key, double-tap to hold); Copy, Paste, Cut, Undo,
  Select all, Alt+Tab, Alt+F4 and Start.
- **Media:** play/pause, previous, next, stop, volume and mute, and Home and
  Back for the TV.

**Typing, honestly.** A Bluetooth keyboard sends key positions, not letters,
and the computer turns them into characters with its own layout. droplet
types with the **US layout**: every printable ASCII character works when the
computer uses US (or UK, for letters and digits). Characters the US layout
has no key for (é, ñ, €, emoji) are skipped, with a note saying which; a
computer set to another layout may show other symbols for some keys.

**While it's on.** The phone is a Bluetooth keyboard only while Mouse &
keyboard is open, or the presentation remote is aimed at a Bluetooth device.
A couple of seconds after the last of them closes (not at once, so turning
the phone doesn't drop the connection), it disconnects and unregisters.
Android also ends the registration by itself when droplet leaves the screen.
While a computer is connected, the screen stays on.

**Under the hood.** One report descriptor, three reports:

| id | report | layout |
|---|---|---|
| 1 | keyboard | modifiers, reserved, six keys: the boot keyboard layout; LED output report |
| 2 | mouse | five buttons, X and Y (−127…127, the boot mouse layout), wheel, AC Pan |
| 3 | consumer | one 16-bit consumer usage: media keys, volume, AC Home, AC Back |

Report ids 1 and 2 with the boot layouts in front are what the Bluetooth HID
spec asks of a keyboard and mouse, so hosts that switch to boot protocol (a
PC's firmware setup, simple TVs) still read them. X and Y are 8 bits for the
same reason; bigger moves are split into several reports, and fractions are
carried to the next move rather than rounded away. QoS is best effort with
an 11.25 ms latency, the usual setting for keyboards and mice.

**If the phone can't.** Some phone makers leave the HID device service out
of their firmware. droplet then says "This phone can't be a Bluetooth
keyboard" instead of failing, and Settings shows what the phone did the
last time: works, not offered by the firmware, or refused (usually because
another app is using the phone as a keyboard).

### What only the real phone can confirm

The JVM tests cover the descriptor (parsed and decoded back like a host
would), the key map, packing, the gestures and the registration and
connection state machine against a fake stack. They can't show:

- whether MIUI/HyperOS on the Redmi Note 11E Pro offers `HID_DEVICE`
  (that is up to the firmware, and nothing here can tell in advance);
- pairing and connecting with Windows, KDE and a Google TV, and how each
  treats the consumer and pan reports;
- latency and how the pointer feels on each host.

## Local-first: home Wi-Fi first, Tailscale away

The app follows [docs/local-first.md](../docs/local-first.md).

**Finding the hub.** The hub announces itself on the Wi-Fi as
`_droplet._tcp` (mDNS), with its permanent id and the fingerprint of its LAN
certificate. The app browses with Android's NsdManager. It remembers the hub's
id, not its IP: DHCP moves the IP, and the last address that worked is only a
hint to try first.

**Trusting it.** On the Wi-Fi the app uses HTTPS on the hub's LAN port (8443)
and accepts the connection only if the certificate's SHA-256 equals the one
it paired with (the pin). Nothing else counts: no CA, no hostname. Tailscale
keeps ordinary, fully verified HTTPS. The pin comes from the hub's answer over
Tailscale when possible (verified); a phone that has never used Tailscale
trusts the hub's certificate the first time, and approving the join code on
one of your devices confirms it's your hub. Plain http is never used, except
for the emulator's `10.0.2.2` test address.

**Choosing the route.** The app tries the Wi-Fi first (the hub found by its
id, then its last address, 1.5 s at most), and then Tailscale. It looks again
whenever the network changes (Wi-Fi joined or left, Tailscale switched on or
off), and every few minutes while it's on Tailscale, and moves back to the
Wi-Fi when the hub is there. The page, uploads, rings, mirroring and the live
connection all follow the route; the page reloads on the new address and
keeps its place. Settings shows the route ("On Wi-Fi · t15 · 192.168.100.20"
or "Via Tailscale"), the hub's id and certificate, **Find hub again** and
**Forget this hub**.

**When it can't be reached**, the app says why: "Not on the same Wi-Fi as
t15, and Tailscale is off", with Try again, Open Tailscale and Settings. It
tries again by itself when the network changes.

**If the hub's certificate changes** (its `certs/` folder was deleted, or
something else is pretending to be it), the app never switches silently. It
stops using the Wi-Fi route and says "The hub's identity changed", with
**Pair again**. Tailscale keeps working meanwhile.

**Not let in (any more).** If the hub answers `403 {"pair": true}` (a join
not yet allowed, or the device was removed), the app goes back to the pairing
screen instead of showing an error.

### Upgrading from 1.1

1.1 knew the hub only by its tailnet URL, and kept its device token as the
WebView's cookie. On first start, 1.2:

- asks the hub over Tailscale (verified) for its id and certificate, and from
  then on uses the Wi-Fi at home;
- if Tailscale is off, looks on the Wi-Fi for the one hub announcing that
  same tailnet URL, trusts its certificate on first use, and checks it over
  Tailscale the next time Tailscale is on;
- copies the device token out of the cookie, so the phone stays the same
  device on every route.

### What needs the real phone

The JVM tests (below) cover the pinning, the route manager, pairing and the
live connection against real hubs, but not Android's own networking: mDNS
discovery through NsdManager (Robolectric can't run it), the WebView's
SSL-error path and cookies, and network callbacks from real Wi-Fi and VPN
changes. On MIUI/HyperOS check that discovery finds the hub; some routers and
guest networks block mDNS, and then entering the hub's IP works instead.

## Hub endpoints it uses

| Endpoint | Used by |
|---|---|
| `GET /api/hub/info` | Finding and checking the hub, the route manager, upgrading from 1.1 |
| `POST /api/device` `{name}`, `GET /api/me` | Asking to join and waiting for approval; where the phone stands |
| `POST /login` (form `pin`) | The hub's PIN during pairing |
| `GET /api/files` | Share sheet, file and message notifications |
| `POST /upload?to=`, `POST /text` | Share target |
| `GET /api/ring`, `POST /api/ring/stop` | Stay connected, Stop |
| `POST /api/phone/notifications` `{posted, removed, sync}` | Mirroring (`phone.py`) |
| `POST /api/phone/status` `{battery, charging}` | Battery (`phone.py`) |
| `GET /ws` (WebSocket) | Remote control (`remote.py`, [docs/remote.md](../docs/remote.md)); battery also goes out as `state` "battery" |
| `POST /api/device/link` | Link with code (Settings, and during pairing) |
| `POST /api/device/<id>/remove` | Withdrawing a join request after linking with a code instead |

## Testing against a local hub

The emulator reaches the host as `10.0.2.2`. The network security config
allows plain http **only** for that address; everything else must be HTTPS
(the LAN too, with the pinned certificate).

```bash
DROPLET_PORT=8806 DROPLET_HOME=$(mktemp -d) python app.py
# in the app: hub address http://10.0.2.2:8806
adb shell cmd notification allow_listener dev.droplet.app/dev.droplet.app.MirrorService
adb shell cmd notification post -t 'Title' tag 'Some text'
```

### Tests without a phone

`src/test` has Robolectric tests that run the app's own code on the JVM:

- **`SmsSendTest`** (always runs): splitting long texts and waiting for
  the radio's report.
- **`LiveHubTest`** (needs a hub): the whole remote protocol against a
  real hub. A fake controller in the test links the app with a code and
  checks the hello and caps, battery and media state, media actions,
  `files.*` including refused paths and a real upload, `sms.threads` and
  `sms.thread`, clipboard both ways without echo, re-announcing when a
  switch changes, and the presentation remote's volume keys and buttons.
- **`LocalFirstUnitTest`** (always runs): mDNS TXT and `/api/hub/info`
  parsing, choosing among hubs, and the pin checks (trust manager, hostname
  verifier, the WebView's decision, including before Android 10).
- **`LocalFirstHubTest`** (needs hubs): the app connects to this machine's LAN
  address, where it's a stranger, while the test plays the owner from
  127.0.0.1 (which the hub trusts as itself). It checks the pinned client
  accepts the hub and refuses an openssl server with another certificate;
  the route manager going Wi-Fi → Tailscale → Wi-Fi (on a network callback
  and on its periodic look); a hub that kept its id but changed certificate;
  pairing through setup's screens with approval, denial, the PIN and a link
  code; the WebSocket over the pinned connection, following the route; and
  upgrading from a tailnet-only install, with and without Tailscale. mDNS is
  stubbed with the hub's own announcement.
- **`HidReportsTest`** (always runs): a small HID descriptor parser reads
  the report descriptor like a host: balanced collections, report sizes that
  match the packers, the boot layouts first, logical ranges that cover every
  usage sent, and each packer's output decoded back through the descriptor.
- **`HidKeysTest`**, **`HidMotionTest`** (always run): the US key map for
  every printable ASCII character, skipped characters, key names and
  consumer usages; mouse packing (clamping, splitting big moves, carrying
  fractions, buttons, wheel and pan), typing and sticky modifiers.
- **`TouchpadTest`** (always runs): the gestures with synthetic touch
  events: tap, two- and three-finger taps, double-tap, drag, moving with
  acceleration, and natural scrolling.
- **`BtHidTest`** (always runs): the registration and connection state
  machine against a fake Bluetooth stack (permission, Bluetooth off,
  firmware without the service, a refused or unanswered registration,
  connecting, switching hosts, letting go), and the real backend under
  Robolectric's Bluetooth shadows.
- **`ScreensTest`** renders the remote, Settings, setup, the pairing code,
  the offline screen and the Bluetooth screens to PNGs for review.

```bash
# the hub, a second one with a PIN, and a "clone" with the first one's id but its own certificate
A=$(mktemp -d); P=$(mktemp -d); C=$(mktemp -d)
DROPLET_HOME=$A DROPLET_PORT=8831 DROPLET_LAN_TLS_PORT=8832 DROPLET_PUSH=0 python app.py &
DROPLET_HOME=$P DROPLET_PORT=8981 DROPLET_LAN_TLS_PORT=8982 DROPLET_PUSH=0 DROPLET_PIN=2468 python app.py &
sleep 2; cp $A/.hub_id $C/
DROPLET_HOME=$C DROPLET_PORT=8985 DROPLET_LAN_TLS_PORT=8986 DROPLET_PUSH=0 python app.py &
cd android
DROPLET_TEST_HUB=http://127.0.0.1:8831 DROPLET_TEST_PIN_HUB=http://127.0.0.1:8981 DROPLET_TEST_PIN=2468 \
  DROPLET_TEST_CLONE_HUB=http://127.0.0.1:8985 DROPLET_SHOTS=/tmp/shots ./gradlew testReleaseUnitTest
```
