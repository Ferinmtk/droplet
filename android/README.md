# droplet for Android

A native companion app for the droplet hub. It does four things that the web
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

## Stay connected, and battery

Stay connected is a quiet notification ("droplet connected") plus a check with
the hub every 15 seconds. That check picks up rings, and new files and
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

## Hub endpoints it uses

| Endpoint | Used by |
|---|---|
| `GET /api/me`, `GET /api/files` | Setup check, share sheet, file and message notifications |
| `POST /upload?to=`, `POST /text` | Share target |
| `GET /api/ring`, `POST /api/ring/stop` | Stay connected, Stop |
| `POST /api/phone/notifications` `{posted, removed, sync}` | Mirroring (`phone.py`) |
| `POST /api/phone/status` `{battery, charging}` | Battery (`phone.py`) |

## Testing against a local hub

The emulator reaches the host as `10.0.2.2`. The network security config
allows plain http **only** for that address; everything else must be HTTPS.

```bash
DROPLET_PORT=8806 DROPLET_HOME=$(mktemp -d) python app.py
# in the app: hub address http://10.0.2.2:8806
adb shell cmd notification allow_listener dev.droplet.app/dev.droplet.app.MirrorService
adb shell cmd notification post -t 'Title' tag 'Some text'
```
