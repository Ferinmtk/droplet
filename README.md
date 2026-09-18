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
| `DROPLET_PUSH` | `1` | `0` = no push notifications (nothing goes through Google/Mozilla); devices see new items while droplet is open. See [Devices](#devices-send-to-one-chat-get-notified) |

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
