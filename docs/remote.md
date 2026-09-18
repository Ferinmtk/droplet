# droplet remote protocol

How devices control each other in real time: mouse and keyboard, slides,
media, locking, screenshots, clipboard sync, and a phone's SMS and files.
Everything goes through the hub. Devices never connect to each other directly,
and the hub never acts on anything itself; it only relays.

- **Controller:** whatever sends commands. Usually the web app on a phone or
  laptop.
- **Helper:** a native program that acts on its machine: the Linux agent
  (`agent/`), the Windows app (`windows/`) or the Android app (`android/`).

A helper and the browser on the same machine are **one device**. They share
the device's name and id, so "slim" appears once however many parts of it
are connected.

## 1. Identity and linking

Every connection belongs to a named device. HTTP requests and the WebSocket
authenticate the same way, with either:

- the browser's `droplet_device` cookie, or
- `Authorization: Bearer <token>`, for helpers.

A helper gets its token in one of two ways:

- **Link to an existing device (preferred).** On that machine's browser,
  droplet shows a six-digit code: `POST /api/device/link-code` →
  `{"code":"123456","expires_in":600,"device":{"id","name"}}`. The helper
  trades it: `POST /api/device/link {"code":"123456","client":"droplet-agent on slim"}`
  → `{"id","name","token"}`.
  - The code works once and lasts 10 minutes.
  - After 10 wrong codes, every open code is cancelled.
  - The token never expires. Removing the device revokes it.
- **Register as a new device** (a machine with no browser, e.g. a headless
  hub): `POST /api/device {"name":"T15"}` → `{"id","name"}`, plus a
  `Set-Cookie: droplet_device=<token>` header. Keep that token and send it as
  a bearer.

Names are unique. A clash returns 409 with `{"error": "..."}`.

Helpers **must not** send an `Origin` header. The hub refuses writes and
WebSocket handshakes whose `Origin` isn't the hub itself.

## 2. The WebSocket

`wss://<hub>/ws`, authenticated as above. The first frame from the client
must be `hello`, within 15 s:

```json
{"t": "hello", "caps": ["input", "media", "lock", "screenshot", "clipboard", "sms", "files"],
 "platform": "linux", "app": "droplet-agent/1.0"}
```

- **`caps`**: what this connection can do. A controller (a browser tab)
  sends `[]`. Only list what works right now: if input injection isn't
  available, leave out `input`.
- **`platform`**: `linux` | `windows` | `android` | `web`.

The hub answers:

```json
{"t": "welcome", "conn": "c0ffee", "device": {"id": "8a0f…", "name": "slim"},
 "devices": {"<device id>": {"caps": ["input", "media"], "apps": [{"platform": "linux", "app": "…"}]}},
 "state": {"<device id>": {"media": {…}, "battery": {…}}}}
```

**Keeping the connection healthy:**
- The hub pings every 25 s at the WebSocket level.
- Clients may also send `{"t":"ping"}` and get `{"t":"pong"}`. Browsers
  should, every ~25 s, to notice a dead link.
- Helpers reconnect on any drop, with backoff: 1 s, 2, 4 … capped at 30 s.

**Limits:** frames are JSON text, at most 512 KB. Messages the receiver
doesn't know are ignored, never treated as errors, so versions can differ.

When any device connects or leaves, everyone gets
`{"t":"presence","devices":{…same shape as welcome.devices…}}`.
`GET /api/files` also includes `caps` and `apps` per device, and
`GET /api/remote/presence` returns the same summary plus the latest state.

## 3. Messages

The hub adds `"from": {"id","name"}` to everything it forwards. The target
is the newest connection of the device `to` that has the needed capability.
If there isn't one, the sender gets:

```json
{"t": "error", "re": "input", "to": "<id>", "error": "slim isn't connected for this. Is its droplet app running?"}
```

### 3.1 `input` (needs `input`)

```json
{"t": "input", "to": "<device id>", "ev": [ …events… ]}
```

Controllers batch events, sending at most one frame per animation frame
(~60/s). Helpers apply them in order.

**Pointer and scroll events:**

| event | meaning |
|---|---|
| `{"k":"move","dx":4.5,"dy":-2}` | move the pointer by this many pixels, relative. The controller has already applied acceleration. Helpers keep the fractions and carry the remainder to the next event. |
| `{"k":"button","b":"left","down":true}` | press or release: `left` \| `right` \| `middle`. Used for drag. |
| `{"k":"click","b":"left","n":1}` | a click, or a double click with `n:2` |
| `{"k":"scroll","dx":0,"dy":1.5}` | scroll in lines. Positive `dy` scrolls down (content moves up); positive `dx` scrolls right. Helpers accumulate fractions. |

**Keyboard events:**

| event | meaning |
|---|---|
| `{"k":"text","s":"héllo 👋"}` | type this Unicode text as-is |
| `{"k":"key","key":"Enter","mods":["ctrl"]}` | press and release one key, with modifiers held |

**Key names** (the web `KeyboardEvent.key` names):
- Navigation and editing: `Enter` `Backspace` `Tab` `Escape` `Space` `Delete` `Insert` `Home` `End` `PageUp` `PageDown` `ArrowUp` `ArrowDown` `ArrowLeft` `ArrowRight`
- Function keys: `F1`–`F12`
- Media and volume: `MediaPlayPause` `MediaNext` `MediaPrevious` `MediaStop` `AudioVolumeUp` `AudioVolumeDown` `AudioVolumeMute`
- System: `PrintScreen` `ContextMenu`
- Single characters `a`–`z` and `0`–`9`, for shortcuts
- `mods` is any of `ctrl`, `alt`, `shift`, `meta`.

**The presentation remote** is plain `key` events, so it works in any slide
app:

| action | key |
|---|---|
| next slide | `ArrowRight` (`PageDown` as an alternative) |
| previous slide | `ArrowLeft` (`PageUp` as an alternative) |
| start from the beginning | `F5` |
| end | `Escape` |
| black screen | `b` |

The laser pointer is `move` events driven by the phone's motion sensors.

### 3.2 `media` (needs `media`)

The controller asks:

```json
{"t": "media", "to": "<id>", "action": "play-pause", "player": "<player id, optional>", "value": 0.5}
```

- `action` is one of `play-pause` `play` `pause` `next` `previous` `stop`
  `seek` `volume` `mute`.
- `value` depends on the action:
  - `seek`: seconds, absolute.
  - `volume`: 0..1.
  - `mute`: `true`/`false`, or omit it to toggle.
- With no `player`, the helper acts on its active player.

Helpers publish what's playing whenever it changes (and at most once a
second while playing):

```json
{"t": "state", "kind": "media", "data": {
  "players": [{"id": "spotify", "name": "Spotify", "status": "Playing", "title": "…", "artist": "…",
               "album": "…", "art": "data:image/jpeg;base64,… (≤ 64 KB) or https://… or null",
               "position": 42.0, "length": 210.0, "can_seek": true, "can_next": true, "can_previous": true}],
  "active": "spotify",
  "volume": {"level": 0.6, "muted": false}}}
```

`players` may be empty, which means nothing is playing. `volume` may be
`null` if unknown. Local art files can't be reached by other devices, so
helpers send art as a small data: URL.

### 3.3 `cmd`

```json
{"t": "cmd", "to": "<id>", "cmd": "lock"}         // needs "lock": lock the screen
{"t": "cmd", "to": "<id>", "cmd": "screenshot"}   // needs "screenshot"
```

For a screenshot, the helper captures the screen as PNG and uploads it with
`POST /upload?to=<from.id>` (multipart field `files`, bearer auth, name
`screenshot-<machine>-YYYYMMDD-HHMMSS.png`). It lands in the requester's
**For this device** list, with the usual push notification.

### 3.4 `rpc`: request and reply

Anything that needs an answer is sent as a request:

```json
{"t": "rpc", "id": "r7", "to": "<id>", "method": "files.list", "params": {"path": "/Download"}}
```

The helper receives it with the hub's own id and must answer with that id,
within 30 s:

```json
{"t": "rpc-result", "id": "<id as received>", "result": {…}}
{"t": "rpc-result", "id": "<id as received>", "error": "Permission not granted on the phone"}
```

The controller gets the reply with its own `id`. The capability needed
follows the method's prefix: `files.*` → `files`, `sms.*` → `sms`,
`media.*` → `media`.

**`files.*` methods:**

| method | params | result |
|---|---|---|
| `files.roots` | – | `{"roots":[{"path":"/Download","name":"Downloads"},{"path":"/DCIM","name":"Camera"}]}` |
| `files.list` | `{"path"}` | `{"path","entries":[{"name","dir":bool,"size","mtime"}]}`, folders first |
| `files.get` | `{"path"}` | uploads the file to the requester (`POST /upload?to=<from.id>`) and returns `{"ok":true,"name":"…"}` |

Paths are relative to the helper's own roots. The helper refuses `..` and
anything outside its roots.

**`sms.*` methods:**

| method | params | result |
|---|---|---|
| `sms.threads` | `{"limit":50}` | `{"threads":[{"id","address","name","snippet","ts","unread":int}]}`, newest first |
| `sms.thread` | `{"id","limit":100}` | `{"messages":[{"id","body","ts","out":bool}]}`, oldest first |
| `sms.send` | `{"address","body"}` | `{"ok":true}` |

Timestamps are Unix seconds (float).

### 3.5 `state`

Only helpers may send it: `{"t":"state","kind":"media"|"battery","data":{…}}`.
The hub keeps the latest of each kind per device, sends it to everyone, and
includes it in `welcome` and `GET /api/remote/presence`. It forgets a
device's state when that device's last helper disconnects. The battery
format is `{"level": 0..100, "charging": bool}`.

### 3.6 `clip`: clipboard sync (needs `clipboard`)

```json
{"t": "clip", "text": "…"}
```

A helper sends this when its machine's clipboard changes to new text, at most
once a second and at most 256 KB. The hub forwards it to every other device's
clipboard-capable helper, which writes it to its clipboard. The hub drops a
text it just forwarded, so the write doesn't echo back. Helpers must also
avoid re-sending the text they were just given. Browsers can't write the
clipboard in the background, so only native helpers take part.

## 4. Security notes

- Anyone who can open droplet can control a device whose helper advertises
  `input`. That's the same trust as the rest of droplet: your tailnet, or the
  PIN. Helpers should let the owner switch each capability off locally, and
  show that they're being controlled (for example, a tray icon change).
- The hub checks the sender is a named device, and forwards only to the
  addressed device.
- Helpers must never run anything a message supplies. `cmd` is a fixed list,
  and file paths are confined to the helper's roots.
