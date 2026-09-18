# droplet for Android

A native companion app for the droplet hub. It does five things that the web
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

Everything else is the droplet web app, full screen in a WebView. It uses the
same cookies as the page, so the app is the same device the page named. The
app talks only to your hub: no Firebase, no Google Play Services, no
analytics.

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
3. Open droplet. It asks for the hub address; the default is the T15's
   tailnet URL. **Tailscale must be on.**
4. Name the phone in the page, as in the browser.
5. Open Settings (⚙ at the top right) and turn on what you want.

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

Nothing here is asked for when the app starts. SMS and files access are
requested only when you tap their buttons under Settings → What other devices
can do.

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

## Hub endpoints it uses

| Endpoint | Used by |
|---|---|
| `GET /api/me`, `GET /api/files` | Setup check, share sheet, file and message notifications |
| `POST /upload?to=`, `POST /text` | Share target |
| `GET /api/ring`, `POST /api/ring/stop` | Stay connected, Stop |
| `POST /api/phone/notifications` `{posted, removed, sync}` | Mirroring (`phone.py`) |
| `POST /api/phone/status` `{battery, charging}` | Battery (`phone.py`) |
| `GET /ws` (WebSocket) | Remote control (`remote.py`, [docs/remote.md](../docs/remote.md)); battery also goes out as `state` "battery" |
| `POST /api/device/link` | Link with code |

## Testing against a local hub

The emulator reaches the host as `10.0.2.2`. The network security config
allows plain http **only** for that address; everything else must be HTTPS.

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
- **`ScreensTest`** renders the remote and Settings screens to PNGs for
  review.

```bash
DROPLET_PORT=8814 DROPLET_PUSH=0 DROPLET_HOME=$(mktemp -d) python app.py &
cd android
DROPLET_TEST_HUB=http://127.0.0.1:8814 DROPLET_SHOTS=/tmp/shots ./gradlew testReleaseUnitTest
```
