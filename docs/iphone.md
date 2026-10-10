# droplet on an iPhone: a web app, with no server in between

**Status: first slice, experimental; on by default in the Linux and Mac
agent.** The agent and the web app (`site/app/`) work end to end in WebKit and Chromium on Linux
(`agent/tests/e2e_iphone.mjs`). Not yet confirmed on a real iPhone (§7).
The Windows and Android apps don't take part yet.

An iPhone has no droplet app: an App Store app needs an Apple developer
account and a Mac, and droplet depends on neither. A browser can't open a
TCP port or speak the mesh's mutual TLS (docs/mesh.md), and droplet runs no
server. What a browser *can* do is WebRTC, and WebRTC can reach a peer
directly without a signalling server, when the peer is set up for it. That
is libp2p's ["WebRTC Direct"](https://github.com/libp2p/specs/blob/master/webrtc/webrtc-direct.md),
and it's what this uses.

- **The web app** is plain HTML, CSS and JavaScript in `site/app/`, served
  once from the website (`https://droplet.noxeratech.com/app/`). Safari's
  **Share → Add to Home Screen** keeps it as an app; a service worker keeps
  it working with no internet after the first visit. It talks only to your
  computers, over your Wi-Fi (or Tailscale).
- **The computer** (the Linux or Mac agent; the iPhone link is on by
  default) listens on a UDP port and answers the browser's WebRTC
  connection.
- **Pairing** is a QR code on the computer, scanned in the web app, then the
  same 4-digit code check as everywhere else in droplet.

Limits, by design: an iPhone pairs with computers, not with another iPhone
(neither can listen); the QR code is scanned again if the computer's
address changes; the app works while it's open (iOS suspends web apps in
the background, so no notifications yet); and it isn't a share-sheet
target (iOS doesn't let web apps be one).

## 1. Connecting with no signalling server

WebRTC normally needs each side's SDP (the description of the session)
carried to the other by some server. Here the browser writes the
computer's SDP itself, from what the QR code says, and the computer never
needs the browser's:

- The computer runs an **ICE-lite** agent on a known UDP port (the mesh's
  port number, 1739–1749, on UDP). ICE-lite never sends connectivity checks;
  it only answers them, from the one address it has. So it needs nothing
  from the browser in advance.
- Its **DTLS certificate is stable**: it's the mesh certificate, so the DTLS
  fingerprint *is* the mesh fingerprint (docs/mesh.md §9.1). The browser
  pins it from the QR code, and its DTLS handshake fails against anything
  else.
- **ICE credentials.** The browser picks the computer's ICE username
  fragment and password: both are `droplet+v1/` followed by 24 random
  ice-chars (`A–Z a–z 0–9 + /`). Its first STUN check carries
  `USERNAME = <computer's ufrag>:<browser's ufrag>`, so the computer learns
  its ufrag from the check, and uses the same string as the password, to
  check the request's MESSAGE-INTEGRITY and to sign its answer. That's no
  secret, and needn't be: ICE only proves the path works; DTLS and the
  challenge below are the security. The browser's own offer is **never
  modified** ("munged"): Chromium is removing support for changing
  `ice-ufrag`/`ice-pwd` in `setLocalDescription` (libp2p's v1 did; its v2
  moved away from it for that reason), and an ICE-lite agent never needs the
  browser's credentials.

The answer the browser writes (`site/app/rtc.js`):

```
v=0
o=- 0 0 IN IP4 192.168.1.20
s=-
t=0 0
a=ice-lite
a=group:BUNDLE 0
m=application 1739 UDP/DTLS/SCTP webrtc-datachannel
c=IN IP4 192.168.1.20
a=mid:0                                   (the mid of the browser's own offer)
a=ice-ufrag:droplet+v1/<24 ice-chars>
a=ice-pwd:droplet+v1/<the same>
a=ice-options:ice2
a=fingerprint:sha-256 <the computer's fingerprint, from the QR code>
a=setup:passive                           (the browser is the DTLS client)
a=sctp-port:5000
a=max-message-size:262144
a=candidate:1 1 udp 2130706431 192.168.1.20 1739 typ host
a=end-of-candidates
```

Then:

- **DTLS**: the computer is the server. It accepts whatever certificate the
  browser presents (a new one each time; nothing to pin) and hands its
  SHA-256 up to the protocol, which binds the app's identity to it (§3).
- **SCTP** on port 5000, and **one data channel**, negotiated out of band:
  id 0, label `droplet`, ordered and reliable. No DCEP round trip.
- Each connection is keyed by the computer's ufrag, and follows the address
  the browser's checks last nominated (USE-CANDIDATE). A connection with no
  packet for 30 s is dropped (browsers send a consent check every few
  seconds); one that hasn't opened its channel 20 s after its first check
  is dropped; at most 8 at once.
- The QR code lists up to three LAN addresses and a tailnet address; the
  app tries them in turn, and puts first the one that worked.

## 2. The QR code

A link to the web app, with everything in the fragment (a browser never
sends a fragment to the website):

```
https://droplet.noxeratech.com/app/#pair=<base64url of the JSON below>

{"v":1, "n":"<computer's name>", "i":"<peer id>", "f":"<fingerprint, base64url of the 32 bytes>",
 "a":["192.168.1.20", "100.101.102.103"], "p":1739, "t":"<one-time token, base64url of 16 bytes>"}
```

About 290 characters: a version 13 QR code, which fits an 80-column
terminal. The **token** lets a browser ask to pair at all: it's good for 10
minutes from when the code is shown (`droplet-agent pair --qr`, or **Pair →
Pair an iPhone** in Droplet's window). Without one, a browser that isn't
paired can't even make a request.

Scanning is done inside the web app (with `BarcodeDetector` where the
browser has it, else the vendored [jsQR](https://github.com/cozmo/jsQR),
Apache-2.0; iOS Safari has no `BarcodeDetector`), because a web app on the
Home Screen has its own storage, separate from Safari's: the pairing must
happen in the app that will keep it. The iPhone's Camera app opening the
link works too, but pairs Safari, not the Home Screen app. Pasting the link
works as well, for an iPhone with no camera access.

## 3. The protocol on the channel

Text frames are one JSON object each; binary frames carry file data. The
computer speaks first, as soon as the channel opens:

```
S → {"t":"server-hello","v":1,"id","name","os":"linux","fp":<mesh fp>,"nonce":nS}
```

The app checks `fp` is the fingerprint it pinned (DTLS already did).

### 3.1 Who the app is

The app's identity is an **ECDSA P-256 key made by WebCrypto as
non-extractable**, kept in IndexedDB: the page can sign with it, but the
private key can never be read out, not even by the page. Its fingerprint
`fpK` is the SHA-256 of its SubjectPublicKeyInfo (DER); its peer id the
first 16 hex of that.

A paired app proves itself:

```
C → {"t":"auth","v":1,"key":<base64 SPKI>,"name","sig":<base64>}
    sig = ECDSA P-256 / SHA-256 over the ASCII
          "droplet-webrtc-auth-v1" LF fpK LF fpS LF fpD LF nS
S → {"t":"welcome","v":1,"id","name","os":"linux","caps":[],"perm":{"paused":false,"allow":{…}}}
    or {"t":"auth-failed","error","paired":false|true}
```

`perm` (§3.5) is how the computer treats this app: a hint for its screens.

`fpS` is the computer's fingerprint (pinned by DTLS), `fpD` the SHA-256 of
the **browser's** DTLS certificate as the computer saw it in this very
handshake, and `nS` the computer's fresh nonce. So a signature can't be
replayed (new nonce) or relayed: someone who connected to the computer
themselves and passed a real app's messages through would present their
own DTLS certificate, not the app's, and the computer checks `fpD` against
the one in *its* handshake. `sig` is WebCrypto's raw r‖s (64 bytes); DER is
accepted too. A failed proof closes the channel; `paired:false` tells the
app this computer doesn't know it (it offers to pair again). A channel that
neither proves itself nor pairs within 30 s is closed.

Both directions of authentication, in short: the app knows it's talking to
the computer because DTLS pinned the computer's certificate from the QR
code; the computer knows it's talking to the app because of the signature,
bound to that DTLS session.

### 3.2 Pairing

The mesh's pairing (docs/mesh.md §9.3), carried over the channel, with the
app's `fpK` as the initiator's fingerprint, and the QR's token:

```
C → {"t":"pair","v":1,"key","name","os":"ios","token","commit":hex(SHA-256(nC))}
S → {"t":"pair-nonce","request","nonce":nR}            or {"t":"pair-failed","error"}
C → {"t":"pair-confirm","nonce":nC,"sig":<over "droplet-pair-v1" LF fpK LF fpS LF nC LF nR>}
S → {"t":"pair-state","state":"waiting"}  … then "accepted" | "denied" | "expired" | "cancelled"
C → {"t":"pair-cancel"}
```

The same commitment (the app is bound to `nC` before it sees `nR`), the
same signature, the same 4-digit code from the same transcript, the same
limits (20 requests a minute, 3 waiting, 5 minutes each). The computer
shows the request like any other (`droplet-agent pair`, the window's
banner, a notification); the app shows the code. The computer trusts the
app when its owner accepts; the app keeps the computer only when its owner
said the codes match **and** the computer said accepted. Then it sends
`auth` on the same channel. Closing the channel while waiting cancels the
request.

The computer keeps the app in its trust list with source `browser`: a
bare public key (`key`, base64 SPKI) and no certificate, so it's never a
TLS trust anchor and never dialled (docs/mesh.md §3).

### 3.3 Messages and files

Once the app has proved itself, the channel is a link like any other in the
mesh node (`droplet_agent/webrtc/bridge.py`): its route is `webrtc`.

- `{"t":"text","id","body","ts"}` → `{"t":"ack","id"}` or `nack`, both ways,
  exactly as docs/mesh.md §9.4. On the computer it's in the chat
  (Droplet's Messages page, `chat.jsonl`) and notifies.
- `{"t":"ping"}` → `{"t":"pong"}`; `{"t":"ring"}` (the app shows it);
  `{"t":"unpair"}` both ways.
- **Files** go over the channel itself (a browser can't serve the mesh's
  HTTPS file endpoint):

  ```
  {"t":"file","id":<32 hex>,"name","size","mime"}
  binary frames: the first 8 bytes of the id (its first 16 hex), then at most 16 KB of the file
  {"t":"file-end","id"}
  → {"t":"ack","id"}   or   {"t":"nack","id","error"}
  ```

  One file at a time in each direction. The sender keeps the channel's
  buffer under 1 MB (`bufferedAmount`, waiting for `bufferedamountlow` at
  256 KB). The receiver acknowledges only once the file is kept: on the
  computer, saved in the downloads folder under a safe, unique name (as
  docs/mesh.md §6), and listed with what was received; in the app, stored
  in IndexedDB (as bytes: WebKit can't always store a `Blob`), from where
  **Save** hands it to the share sheet (Files, Photos) or downloads it. A
  file is refused if it's larger than the free space, or if more or fewer
  bytes arrive than it said. There's no resume yet: a transfer that breaks
  starts again (the computer's outbox retries it when the app reconnects).

Messages and files for an app that isn't connected wait in the computer's
outbox and go when it connects, which is when the app is open.

### 3.4 The clipboard

iOS lets a web app read or write the clipboard only in answer to a tap, so
the app never syncs it by itself: everything is one tap.

- **iPhone → computer.** **Send clipboard** (on the computer's screen, just
  above the message box, and on its row on the home screen) reads the
  clipboard (`navigator.clipboard.readText()`: iOS first shows its **Paste**
  button, which must be tapped) and sends

  ```
  {"t":"clip","id":<8–64 of [0-9A-Za-z_-]>,"text"}  →  {"t":"ack","id"}  or  {"t":"nack","id","error"}
  ```

  The computer puts it on its clipboard as it would a native peer's `clip`
  (the same `clipboard_max_bytes` limit), and answers; the app says "Sent to
  *computer*: it's on its clipboard", or why not. Because only a tap sends
  one, a clip from the app is applied **even when the computer's automatic
  clipboard sync (`caps.clipboard`) is off**. Only the computer's mesh node
  marks a clip as one (`"explicit"`, for a `webrtc` link): a native peer or
  the hub can't. If the computer has no clipboard tool (wl-clipboard,
  xclip/xsel, pbcopy) the answer says so.
- **Computer → iPhone.** `{"t":"clip","text"}`, sent when the computer's
  clipboard changes, *if automatic clipboard sync is on* (the same broadcast
  to every linked peer as for native ones, and the same rules: text a
  password manager marks secret is never read, let alone sent), and always
  when its owner sends it on purpose: **Send clipboard** on the iPhone's card
  in Droplet's window, the iPhone's **Send clipboard** in the tray menu, or
  `droplet-agent clip <iphone>` (route `webrtc`; if the app isn't open, it
  says so). The window's Send clipboard skips a clipboard a password manager
  marked secret, as the tray and the command already did. The app shows a
  card, "From *computer*", with a preview, **Copy** (one tap:
  `navigator.clipboard.writeText()`) and ×; the newest five from each
  computer, newest first, on the home screen, and the newest above the
  message box. A new one is highlighted and badged ("New clipboard" on the
  computer's row) until it's copied, dismissed, or its computer's screen is
  opened.
- **Never stored.** Clipboard text often holds passwords: the app keeps the
  cards in memory only (never IndexedDB), so they survive reconnects while
  it's open and are gone when iOS ends it.
- **No echo.** The app never sends on its own. Text the computer puts on its
  clipboard from the iPhone is remembered (`ClipboardSync.last_applied`), so
  the change it causes isn't broadcast back to the iPhone or anyone else.
- **Size.** A clip is one frame on the channel, at most 256 KB of UTF-8
  (the max-message-size both ends announce). Frames go out as UTF-8, not
  `\u` escapes, so nearly the whole 256 KB is text. The app refuses longer
  text before sending; the computer refuses to send it ("too much text"),
  and its broadcast skips the iPhone for it.

### 3.5 Your iPhone or someone else's, and Pause

docs/mesh.md §9.9, over the channel. Each side enforces its own settings.

- **The computer asks** "Is *iPhone* your device, or someone else's?" when
  its owner accepts the pairing (the window's two big choices, the tray's
  "Accept: it's my device" / "Accept: it's someone else's", or
  `droplet-agent pair --accept [--own|--other]`). Someone else's iPhone may
  send files and messages and ring; the computer takes no clipboard from it
  (a `clip` is answered `{"t":"nack","id","error":"t15 doesn't allow the
  clipboard from you","cap":"clipboard","why":"denied"}`) and sends it none.
  Its switches can be changed any time (Permissions on its card,
  `droplet-agent allow`).
- **The computer tells the app** how it treats it: `perm` in `welcome`, and
  `{"t":"perm","paused","allow","caps"}` when that changes. The app greys
  out what won't be taken ("t15 doesn't take the clipboard from this
  iPhone") and shows **Paused by t15** while the computer paused it. It's
  a hint: the computer refuses regardless.
- **The computer pauses the app** (Pause on its card or in the tray,
  `droplet-agent pause <iphone>`, or Pause everything): it sends nothing,
  and what the app sends is answered `{"t":"refused","re","id","cap",
  "why":"paused","error"}`. The computer's messages and files for it wait in
  its outbox and go on resume.
- **The app pauses a computer**: **Pause** on its row on the home screen, or
  **⋯ → Pause sharing with *computer***. Kept in IndexedDB (`paused` on the
  computer's record), so it holds across launches. The app then sends
  nothing (its buttons are off, with the reason above the message box),
  answers whatever the computer sends with the same `refused`, drops
  incoming file data, and tells the computer with
  `{"t":"perm","paused":true,"allow":{}}` (again on every connection while
  paused). The computer holds what it would send (`waiting: iPhone paused
  sharing with you`) until `{"t":"perm","paused":false}`.

## 4. The computer's side

`agent/droplet_agent/webrtc/`:

| module | |
|---|---|
| `transport.py` | the UDP port: ICE-lite (STUN through `aioice.stun`), DTLS and SCTP through aiortc, the data channel |
| `protocol.py` | §3, independent of the transport (tested with a fake channel) |
| `bridge.py` | a connected app as a mesh link; the `qr` control command |
| `qr.py` | the QR payload, drawn in a terminal with `segno` |
| `deps.py` | loads aiortc without PyAV |

**It's on by default.** Installed the usual way (`install.sh`, on Linux or
a Mac), the agent listens for iPhones from the start; nobody configures
anything. To pair one: **Droplet → Pair an iPhone** (or **Pair an iPhone…**
in the tray or menu bar, which opens the window there; without the window,
`droplet-agent pair --qr` in a terminal).

- `droplet-agent status` says, on its `iphone:` line, whether it's
  listening (and on which UDP port), and if not, why, with the command that
  fixes it. `droplet-agent doctor` checks the same, offers to install what's
  missing, and looks for a firewall in the way.
- If its parts aren't installed (§ Dependencies), the agent runs without it:
  one line in its log, nothing else, and **Pair an iPhone** says so.
- **The firewall** must let the UDP port in. Fedora's Workstation zone
  already allows 1025–65535/udp. Elsewhere `doctor` detects firewalld and
  ufw and prints the command, such as
  `sudo firewall-cmd --permanent --add-port=1739-1749/udp && sudo firewall-cmd --reload`
  or `sudo ufw allow 1739:1749/udp`. A Mac asks whether Python may accept
  incoming connections: Allow.
- To turn it off, in `~/.config/droplet-agent/config.json`:
  `"iphone": {"enabled": false}`, and restart the agent. (`"port"`: the UDP
  port, default the mesh's port number; `"app_url"`: the web app the QR code
  opens.)

**Dependencies.** aiortc does the DTLS (through pyOpenSSL) and the SCTP and
data channels in Python. It also does audio and video, for which it
requires PyAV, the FFmpeg bindings: about 100 MB. A data channel never
touches it, but aiortc imports it at the top of several modules, so when
PyAV isn't installed `deps.py` puts a stand-in module in its place (every
name in it an empty class). droplet always imports aiortc through
`deps.load()`; imported directly with no PyAV, it fails with
`No module named 'av'`, as expected.

So the agent's wheel depends on the light parts (segno, aioice, pyee,
pylibsrtp, pyOpenSSL, google-crc32c, each with wheels for Linux x86-64 and
ARM64 and macOS Intel and Apple silicon, Python 3.9 to 3.14), and
`install.sh` then adds aiortc on its own, without its dependencies:

```sh
~/.local/share/droplet-agent/bin/python -m pip install --no-deps 'aiortc>=1.9'
```

(`droplet-agent doctor` runs that for you when it's missing.)

| | installed size |
|---|---|
| aiortc | 1.0 MB |
| aioice (+ dnspython 2.3 MB, ifaddr) | 0.3 MB |
| pylibsrtp (bundles libsrtp; imported, never used: no media), + cffi, pycparser | 7.0 + 1.0 MB |
| pyOpenSSL, pyee, google-crc32c, typing-extensions | 0.9 MB |
| segno | 0.6 MB |
| **total, light** | **≈ 13 MB** (about 3.5 MB to download) |
| PyAV, if installed the plain way (`pip install 'droplet-agent[iphone]'`) | + ≈ 100 MB |

cryptography is already a dependency of the agent.

**Speed** (this laptop, to its own LAN address, WebKit): a 3 MB file
from the app in about 0.6–1 s, a 2 MB file to the app in about 0.15 s. The
SCTP stack is pure Python, so expect a few MB/s from the phone, which is
the slower direction (the computer acknowledges every chunk in Python).

## 5. Can Safari do it? (the feasibility question)

**Yes in WebKit, as far as Linux can show it.** The risk was that WebKit
might refuse a hand-written answer for an ICE-lite peer, or the credentials
scheme. It doesn't:

- **Playwright's WebKit** (WebKit 2370, "Version/27.2 Safari/605.1.15") on
  Linux. Its WebRTC is **libwebrtc**, the same stack iOS Safari uses (its
  offer is libwebrtc's: `o=- <id> 2 IN IP4 127.0.0.1`,
  `a=extmap-allow-mixed`, `a=msid-semantic: WMS`; GStreamer's webrtcbin
  would look different). It accepted the synthesized answer as is: ICE-lite,
  the `droplet+v1/` credentials, `a=setup:passive`, one host candidate. The
  channel opened in about 20 ms; a wrong fingerprint in the answer made the
  connection fail (DTLS pinning works).
- **Chromium** (Headless Shell 156): the same.
- Nothing about the browser's own offer is changed, so the
  "no SDP munging" rule that ended libp2p's v1 scheme doesn't apply.

The end-to-end run (`agent/tests/e2e_iphone.mjs`), against a real agent,
both engines, 20/20 checks each: pairing with the same code on both sides,
a message each way, a 3 MB file to the computer and a 2 MB file to the app
(SHA-256 checked), a reload that reconnects with the key kept in IndexedDB,
a browser with another key refused, and unpairing from the computer.

## 6. Testing it

```sh
# unit tests (protocol, pairing, keys, QR)
python -m pytest agent/tests/test_webrtc.py

# end to end, in WebKit and Chromium (one at a time)
npm install playwright@1.64 && npx playwright install webkit chromium-headless-shell
PLAYWRIGHT_DIR=$PWD/node_modules PYTHON=<a python with aiortc> node agent/tests/e2e_iphone.mjs webkit
PLAYWRIGHT_DIR=$PWD/node_modules PYTHON=<a python with aiortc> node agent/tests/e2e_iphone.mjs chromium
```

`SHOTS=<dir>` saves screenshots of the app's screens.

The end-to-end run turns the computer's automatic clipboard sync off and
checks the clipboard both ways anyway (explicit sends), with 192 KB texts,
and the refusal of text too large for a frame. With no one to tap iOS's
Paste button, the browser context is granted `clipboard-read` (and, in
Chromium, `clipboard-write`; WebKit refuses that name and allows writes
from a click anyway). The dry-run agent logs the text it would set, which
the test reads.

## 7. What still needs a real iPhone

- **iOS Safari itself.** Playwright's WebKit is WebKit with libwebrtc, but
  not iOS: Apple's build, its network stack and its policies differ.
- **Host candidates without camera access.** Safari has restricted which
  ICE candidates a page gets when it hasn't been granted the camera or
  microphone (now mostly hidden behind mDNS names). Here the browser only
  needs to *send* checks from its host candidates, never to reveal them,
  so it should work; it must be seen on a phone, on a later launch when the
  camera isn't in use.
- **iOS's Local Network permission.** iOS asks before an app talks to the
  local network. Whether a Home Screen web app (or Safari) asks, and what
  happens if it's refused, needs a phone.
- **The camera in a Home Screen app**, and jsQR reading a code off a
  computer screen (jsQR decodes the agent's codes exactly in WebKit, from
  an image; a camera adds glare and blur).
- **Saving** through the share sheet, and how large a file IndexedDB lets
  a Home Screen app keep.
- **The clipboard on iOS**: the Paste button iOS shows for **Send
  clipboard** (and what tapping elsewhere does: the app says it wasn't
  allowed), **Copy** from a Home Screen app, and a clip as large as 256 KB
  over Safari's data channel.
- **Suspension.** What happens to a transfer when the screen locks or the
  app goes to the background (the link closes; the computer's outbox
  retries a file from the start when the app comes back).
- **Wi-Fi with client isolation**, and Tailscale on the phone (the tailnet
  address in the QR code).

## 8. Next

- The iPhone link in the **Windows** app (.NET has no WebRTC: SIPSorcery's
  data channels, or the same design over a small native library) and the
  **Android** app (Android phones don't need it, but an Android tablet
  could serve an iPhone).
- Resuming a broken transfer; notifications (web push needs a server, so
  probably not).
- An "iPhone" button on the landing page once a real iPhone has confirmed §7.
