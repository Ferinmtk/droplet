# droplet for Android

A native companion app for the droplet hub. It does eight things that the web
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
- **Direct connections to your devices (the mesh).** The phone talks to
  your computers straight over the Wi-Fi or Tailscale, so sending files and
  messages, ringing, the clipboard and the presentation remote keep working
  when the hub is down. See [Direct connections](#direct-connections-the-mesh).
- **A Bluetooth mouse and keyboard.** The phone pairs with a computer or TV
  as an ordinary Bluetooth keyboard and mouse: touchpad, keyboard, media keys
  and slides, with nothing installed on the other side and no Wi-Fi or hub.
  See [Bluetooth mouse & keyboard](#bluetooth-mouse--keyboard).
- **A TV remote that talks to the TV itself.** The phone pairs with an
  Android TV or Google TV and controls it over the Wi-Fi with the same
  protocol as Google's own remote app: D-pad or touchpad, volume (the phone's
  volume keys too), apps, typing and power, with the hub off. See
  [TV remote](#tv-remote).

Everything else is the droplet web app, full screen in a WebView. It uses the
same device token as the page, so the app is the same device the page named.
The app talks only to your hub and your own devices: no Firebase, no
Google Play Services, no analytics.

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

## Direct connections (the mesh)

The phone is a peer in droplet's mesh ([docs/mesh.md](../docs/mesh.md)):
your devices with the droplet app talk to each other directly, and the hub
is a helper. The phone speaks exactly the protocol the Linux agent does
(§9 of the design), and is tested against it.

**Who it talks to.** Only devices whose certificate fingerprint is in its
trust list:

- **Devices on your hub** trust each other by themselves. Each time the
  phone connects to the hub it announces its certificate and fetches the
  hub's roster (`POST /api/mesh/announce`, `GET /api/mesh/roster`), and
  again whenever the hub says the roster changed. Removing a device on the
  hub removes it everywhere.
- **Anything else pairs directly**, like Bluetooth: Settings → Direct
  connections → **Devices and pairing**, tap a device on the Wi-Fi (or
  **Pair by address**), and check both screens show the same four-digit
  code. A device asking to pair with the phone shows a notification; tap it
  to see the code and answer.

**How a message gets there**, the first that works: directly over the
Wi-Fi; directly over Tailscale; through the hub; the hub's mailbox (for a
device that's off); or kept on the phone until one of them is back. Live
control (the presentation remote), the clipboard and rings don't wait:
they go directly or through the hub, or not at all.

**What uses it:**

- **Share → droplet** lists your devices with how each is reached now
  ("Direct · Wi-Fi", "Via the hub", "Offline"). Files and text go directly
  when they can, and still reach devices on the hub the usual way.
- **Messages:** the devices screen has a chat per device. Messages that
  arrive show a notification.
- **Files sent to the phone** land in **Download/droplet**, with a
  notification that opens them. A transfer that stops part-way carries on
  where it stopped when the sender offers it again.
- **Ring, clipboard:** a device can ring the phone (the same loud ring) or
  put text on its clipboard directly; the devices screen rings them back.
- **Remote control:** media, SMS and files answer over a direct link
  exactly as through the hub. A file taken off the phone that way goes back
  directly, not through the hub. The phone doesn't take keyboard and mouse
  input, and says so.
- **Presentation remote:** a computer that takes input is listed as
  "<name> (direct)"; key presses go straight to it.

**When it listens.** Other devices can reach the phone while **Stay
connected** runs (and while a droplet screen that sends is open), on port
1739 (or the next free one up to 1749), announced on the Wi-Fi as
`_droplet-peer._tcp`. With no hub at all, Stay connected keeps the phone
reachable by its directly paired devices. Settings → Direct connections
turns it all off.

**Security.** Mutual TLS with self-signed certificates; the fingerprint is
the identity, never a hostname or CA. The phone's key is made in the
Android Keystore and never leaves it (if a phone's Keystore can't do TLS
with it, which the app tests when the key is made, it's kept in the app's
private storage instead, and the devices screen says so). A device whose
certificate isn't trusted fails the TLS handshake; a device with no
certificate may only ask to pair. The phone checks each server's
fingerprint before sending anything. Pairing uses committed nonces and a
signature, so the code can't be forced to match by a man in the middle.

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

## TV remote

One tap from anywhere: the **TV remote** quick-settings tile (add it from
the tile editor), the **TV remote** shortcut on droplet's launcher icon
(long-press it), Settings → **TV remote**, the **TVs** list on the devices
screen, or **TV remote** on the "can't reach the hub" screen. It opens on the
last TV and connects by itself; the header says "Connecting…" meanwhile. The phone talks to the TV
itself, over Google's Android TV Remote protocol v2 (what the Google TV
phone app uses), so it works when the hub is off or away.

**Pairing.** The first time, **Find my TV** lists the Android TV and Google
TV sets announcing themselves on the Wi-Fi (`_androidtvremote2._tcp`); tap
yours. When nothing is paired yet and there's exactly one TV around, it's
asked without a tap. "Look at your TV": it shows 6 characters (digits and
A-F); type them into the six boxes (they advance by themselves, and a paste
works). The sixth character sends the code, and a match opens the remote. A
TV that doesn't show up (mDNS blocked by the router, another subnet) can be
added by its IP address; only private, link-local and Tailscale addresses
are accepted, as on the hub.

- A **mistyped code** is caught on the phone before anything is sent: the
  code's first two characters are a checksum of the rest and of both
  certificates. The TV keeps its code up; type it again. After five misses,
  start again for a new code.
- **The TV turned the code down**, or its pairing screen closed (Cancel on
  the TV, or it timed out): start again. Pairing also times out on the phone
  after five minutes.
- **Can't reach it:** it's off (with network standby off), asleep, or on
  another network.

**It's a second remote.** The hub's TV remote in the web app (tv.py) keeps
working as before; the phone is paired separately, with its own certificate,
and the TV lists it as a remote of its own, named "droplet (<phone>)".
Pairing or forgetting one doesn't touch the other.

**The remote** opens in a **simple view**: just the round D-pad with OK,
Back, Home, volume down, mute and volume up, and Power in the header, large
enough for a thumb. **More buttons** adds everything else, and the choice is
remembered: a D-pad with OK (hold OK for a long press), or a
touchpad (swipe to move, one step every 34 dp, tap for OK, hold for a long
OK); Back and Home (both long-press when held), Menu; volume and channel
rockers and arrows that repeat while held; mute and input; rewind, previous,
play/pause, next, fast-forward; a keyboard field for the TV's focused text
box, with Delete, Enter and Search; the app launcher (YouTube, Netflix,
Prime Video, Spotify, Showmax, Disney+, Plex, Home: the same catalogue as the
hub's) and an https link box; numbers, Info, Guide, TV settings and Stop.
While the screen is open, the phone's **volume keys** set the TV's volume
(the phone's own volume when the TV isn't connected). The header shows the
TV's state: on and the app in front, standby, connecting, can't reach, or
needs pairing, with the volume under it. Every press vibrates as the finger
lands (a switch in the full view turns that off).

**Power.** Connected, Power toggles the TV between on and standby. Not
connected, it sends a Wake-on-LAN packet to the MAC in the TV's certificate
and tries again at once; many Google TVs never need it, as they keep the
network up in standby.

**When the TV forgets droplet** (a reset, or droplet removed from the TV's
remotes), the TV refuses the phone's certificate; the remote says so and
offers **Pair again**, which goes straight to a new code. When the device at
the TV's address presents a different certificate than at pairing (a reset
TV, or another device that took the address), the phone sends it nothing
and asks for pairing again too. A TV that moves to a new address (DHCP) is
followed through its mDNS name.

**Or Bluetooth.** Without Wi-Fi, the Mouse & keyboard mode's media keys,
Home and Back work on a TV paired over Bluetooth; the TV remote links to it.

**Under the hood.**

- *The protocol:* TLS on port 6467 for pairing and 6466 for the remote,
  both with a client certificate; varint-framed protobuf messages from
  androidtvremote2's `polo.proto` and `remotemessage.proto`, encoded by
  hand (`tv/TvWire.kt`, `tv/TvMessages.kt`): about twenty small messages, so
  a small codec instead of the protobuf plugin, a protoc download at build
  time and a runtime library. Unknown fields are skipped.
- *Pairing:* pairing request, options and configuration (6 hex symbols,
  droplet as the input side), then the secret: SHA-256 over the phone's RSA
  modulus and exponent, the TV's, and the code's last two bytes; the code's
  first byte must equal the hash's first byte (`tv/TvSecret.kt`).
- *The remote channel:* the TV's configure is answered with the features
  both have (keys, text, power, volume, app links, pings); then remote
  start (on or standby), the volume and the app in front; key presses
  (short, or START_LONG and END_LONG at least 0.9 s apart); text as the IME
  batch edit the library sends; apps as `market://launch?id=<package>` or an
  https link. The TV pings every 5 s when idle and the phone answers; 16 s
  of silence means the connection is dead, and it's opened again at once,
  then with backoff (1 s doubling to 30 s), and at once when the TV
  announces itself on mDNS or a key is pressed.
- *The certificate:* RSA 2048, self-signed, shaped like the library's (CN
  and a DNS name, CA:TRUE, path length 0). Like the mesh key, the private key
  is made in the Android Keystore once a loopback TLS 1.2 and 1.3 handshake
  shows the Keystore key can sign TLS on the phone; otherwise it's a file in
  the app's private storage. The remote screen says which ("This remote").
- *The TV's certificate* is pinned at pairing (SHA-256 of its DER); the
  pairing secret binds both certificates, so nothing in the middle can pair.
- The TVs are in `files/tv/tvs.json` (name, address, port, MAC, mDNS name,
  pin, model), the identity in `files/tv/identity.json` (and `key.p8` when
  the key isn't in the Keystore). The link is open only while the remote is
  on screen, and closes five seconds after it leaves (so a rotation doesn't
  drop it).

### What only the real TV can confirm

The JVM tests pair with and drive `tests/fake_tv.py`, a pretend Google TV
built from the library's own protobufs, over real TLS. What it can't show:

1. Open the TV remote (the tile, the shortcut or Settings). With the TCL the
   only TV on the Wi-Fi, it's asked for a code by itself; otherwise **Find my
   TV** lists it as "Living room TV".
2. A code appears on the TV. Type one character wrong: the phone says the
   code didn't match, clears the boxes, and the TV keeps showing it. Type it
   right: the remote opens, "Paired with Living room TV".
3. On the TV, Settings → Remotes & Accessories (or System → About → the
   remote list, depending on the firmware): droplet on the phone shows as a
   remote next to the hub's.
4. The remote opens in the simple view. The header says "On · Home" and
   shows the volume. Close and reopen it: it connects by itself. In **More
   buttons**, try the
   D-pad (each arrow held repeats), OK, OK held (a long press: options on a
   tile), Back, Home, Home held, Menu, the rockers, mute, input, the media
   keys in YouTube, the touchpad, and the phone's volume keys.
5. Open YouTube's search, type in the keyboard field and Send; Delete and
   Enter. Open Netflix from the launcher; paste a YouTube link.
6. Power: to standby and back ("In standby" in the header). With the TV
   fully off at the wall, Power says it sent a wake-up; that it wakes is up
   to the TV's network standby setting.
7. Stop the hub: everything above still works. Start it again: the web
   app's TV card still works too.
8. Remove droplet (the phone's entry, not the hub's) from the TV's remotes:
   the phone says the TV forgot droplet and offers Pair again, which pairs
   with a new code.
9. Whether the Keystore key works for TLS on the phone: "This remote" on
   the remote screen says where the key is.

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
- **`MeshUnitTest`** (always runs): the mesh certificate against the
  profile, pairing codes against vectors from the Linux reference, the
  pairing proof checks, Range, safe names, the server's access rules over
  real TLS sockets (strangers refused in the handshake, no certificate
  gets only pairing, an unpaired peer refused even on a resumed session,
  the client's fingerprint pin), and two phones pairing and talking.
- **`MeshInteropTest`** (needs the Linux agent in a venv, and a hub for
  its second half): the phone against real `droplet-agent run --dry-run`
  processes, both ways. TLS fingerprints, strangers refused, pairing
  started by either side with matching codes, text, a 20 MB file each way
  interrupted and resumed, ring, clip, media and RPC answered by the
  phone's bridges, `files.get` coming back as a mesh file, `input`
  refused, unpairing; then the roster through a hub, and direct delivery
  after the hub is stopped. The agent is driven by
  `src/test/python/mesh_agent.py`.
- **`TvProtocolTest`** (always runs): the TV protocol against
  androidtvremote2 itself. `src/test/resources/tv/vectors.json` was written
  by the library's own code (`src/test/python/tv_vectors.py`): pairing
  secrets from its `async_finish_pairing` for certificates from its own
  generator (and the codes it refuses), the bytes of every message droplet
  sends, and every message the TV sends, read back. Also unknown fields,
  merged messages, broken input and framing.
- **`TvCatalogTest`** (always runs): the keys and app catalogue against
  `tv.py`'s own source, the https-only rule, codes, text, addresses on this
  network only, MACs, Wake-on-LAN packets, mDNS names, the client
  certificate and the TV list.
- **`TvFakeTvTest`** (needs a Python with androidtvremote2, e.g. the hub's
  venv): the phone against `tests/fake_tv.py` over real TLS, as a separate
  process. Pairing with the code on screen, a typo caught before sending, a
  code the TV turns down, then keys and long presses, volume, mute, text,
  app links, Home and power with the TV's state reported back; pings keeping
  a quiet connection up; the TV frozen (no FIN, no RST) and coming back; the
  TV forgetting droplet (then pairing again); another TV at the same address;
  and the app's TV list marking a TV that forgot it.
- **`TvScreensTest`**: with the fake TV, pairing and driving it through the
  screens themselves (the only TV asked without a tap, a mistyped code
  refused and cleared, the right one sent by its sixth character, then the
  D-pad, Home, play/pause, the phone's volume keys, typing, an app tile,
  power, "Pair again"); the simple and full views; with `DROPLET_SHOTS`,
  the TV screens as PNGs.
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

# the TV remote against the fake TV (the hub's venv has androidtvremote2)
DROPLET_TEST_TV_PY=../.venv/bin/python ./gradlew testReleaseUnitTest --tests 'dev.droplet.app.tv.*'

# the mesh against the Linux agent (and a hub, which the test stops)
python3 -m venv /tmp/v && /tmp/v/bin/pip install ../agent
H=$(mktemp -d); DROPLET_HOME=$H DROPLET_PORT=8881 DROPLET_LAN_TLS_PORT=8882 DROPLET_HOST=0.0.0.0 DROPLET_PUSH=0 python ../app.py &
DROPLET_TEST_AGENT_PY=/tmp/v/bin/python DROPLET_TEST_HUB=http://127.0.0.1:8881 DROPLET_TEST_HUB_PID=$! \
  ./gradlew testReleaseUnitTest --tests '*MeshInteropTest*'
```

### What only the real phone can confirm (the mesh)

- that the Keystore key works for TLS on MIUI/HyperOS (the app tests it
  when it makes the key, and falls back if not; the devices screen says
  which it got);
- NsdManager announcing `_droplet-peer._tcp` and finding other peers on
  the Wi-Fi (Robolectric has no NsdManager; the TXT parsing is tested);
- MediaStore saving into Download/droplet, and opening a received file;
- the server staying reachable with the screen off under MIUI's battery
  rules (Stay connected is a foreground service, as before);
- the devices, pairing and chat screens by hand.
