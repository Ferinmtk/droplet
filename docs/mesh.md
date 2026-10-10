# droplet mesh: devices talk directly, the hub is optional

**Status: v1.0, in progress.** Built so far:

- **The Linux agent is a full peer** (`agent/droplet_agent/mesh/`), and the
  reference the other apps follow: identity, mDNS, the mutual-TLS port,
  the trust list, direct pairing, every message below, routing with the
  outbox, and the `droplet-agent peers | pair | unpair | text | send-file |
  ring | clip | send | allow | pause | resume | transfers | cancel | open-link |
  rename | nickname` commands.
- **Per-device permissions and Pause** (§9.9): your device or someone
  else's, a switch per capability, Pause per device and for everything;
  each device enforces its own, both ways. Linux and Mac agent and the
  iPhone web app; Windows and Android next.
- **Progress and Cancel, links, rename and nicknames, several devices at
  once** (§9.10): Linux and Mac agent and the iPhone web app; Windows and
  Android next.
- **The hub's roster** (`mesh.py`): §3.1 and §9.
- **The Android app is a peer** (`android/app/src/main/java/dev/droplet/app/mesh/`
  and `Mesh.kt`): the same protocol, tested against the Linux agent both
  ways (§9.8). Its features use direct links: sharing files and text,
  chat, ring, clipboard, the presentation remote, and the media, SMS and
  files bridges answering over a link. See `android/README.md`.

Not yet: the Windows app as a peer, chat history merged on the hub (§6),
and the TV remote in the apps (§7).

An iPhone, which can't run a peer, connects to the Linux agent from a web
app over WebRTC instead, with no server: docs/iphone.md (experimental).
It's kept in the trust list with source `browser`.

Where the design changed while it was built, this document says so, and why.
§9 is the exact wire protocol, for implementing a peer.

Today everything goes through the hub, so if it's down, almost nothing
works. KDE Connect has no hub: every device talks to every other one
directly. The mesh brings that to droplet:

- **Devices with the droplet app** (Android, Windows, Linux) talk **directly
  to each other**, at home over the Wi-Fi and away over Tailscale.
- **The hub becomes an optional helper, a peer with extras:**
  - a **mailbox** for devices that are off;
  - **browsers** and guests;
  - web push;
  - the shared folder;
  - the TV bridge for devices without the app.

  When the hub is down, device-to-device features keep working.
- **Browser-only devices** still need the hub. Browsers can't run a server
  or discover devices.

## 1. Identity

- Each app has a **long-lived key pair and self-signed certificate** for the
  mesh. Its **fingerprint** (SHA-256 of the DER) *is* the peer's identity.
  It never changes unless the app is reinstalled or reset.
- **`peer id`:** a device that has joined a hub uses the hub's device id, so
  the web app, the hub and the mesh all name it the same way. A device that
  has never met a hub makes its own random id (16 hex characters). Hub
  device ids are 12 hex characters, so a peer id is 8 to 64 lowercase hex.
- **`name`:** the device's name, as today.

## 2. Discovery

- **mDNS:** each app announces `_droplet-peer._tcp.local.` on its mesh port.
  TXT records:

  | key | value |
  |---|---|
  | `id` | peer id |
  | `fp` | certificate fingerprint |
  | `name` | device name |
  | `os` | `android`, `windows`, `linux` or `macos` |
  | `caps` | comma-separated, same names as `docs/remote.md`, plus `notify` (§9.4) |
  | `hub` | the id of the hub it belongs to, or empty |
  | `v` | protocol version, `1` |

- **The roster from the hub:** when a device is connected to its hub, the hub
  sends it the **roster**: every approved peer's id, name, fingerprint, caps,
  last LAN addresses and tailnet address. The device caches it, so it can
  reach its peers later without the hub, even away from home over Tailscale.
- **Add by address:** for networks that block mDNS.

An mDNS announcement proves nothing (anyone on the Wi-Fi can send one); it's
only a hint where a peer is. Peers announce their LAN addresses only, not
container, VM, VPN or tailnet ones: another device on the Wi-Fi can't reach
those, and each would cost it a timeout.

## 3. Trust

A peer is trusted only if its fingerprint is in the local **trust list**.
There are two ways in:

1. **Vouched for by the hub.** Every device approved on the same hub trusts
   the others automatically, through the roster. It arrives over the
   authenticated hub connection, so it's as trustworthy as the hub. Removing
   a device on the hub removes it from every roster.
2. **Direct pairing, with no hub.** Like Bluetooth: device A asks, both
   screens show the same **4-digit code** (derived from both fingerprints),
   and the owner accepts on the other device. Both save each other's
   fingerprint. This is how a household with no hub, or a friend's laptop,
   pairs.

Unpairing removes the fingerprint on both sides where possible, and locally
always. A peer the hub vouches for can't be unpaired locally (the next
roster would bring it back): remove it on the hub, and every device drops
it.

### 3.1 The roster (built)

Each device posts its identity to its hub, and gets every other approved
device's back (§9.6). The device keeps it in its trust list with source
`roster`: a fetch replaces all `roster` entries, so a device removed or
denied on the hub is dropped at the next fetch, and cached entries keep
working while the hub is down. When the roster changes the hub sends
`{"t":"roster"}` on every `/ws` connection, and devices fetch it again at
once. A directly paired peer stays `paired` even if the roster also lists it.

## 4. Transport

- **Mutual TLS.** Both sides present their mesh certificate, and each checks
  the other's fingerprint against its trust list. No CA, no hostnames. An
  untrusted peer may only use the pairing endpoint.
- **The mesh port** defaults to 1739, and the next free port in 1739–1749 if
  that's taken. It's announced in mDNS and the roster.
- **Messages:** a WebSocket at `wss://<peer>:<port>/mesh`, carrying the
  **same JSON messages as the hub protocol** (`docs/remote.md`): `input`,
  `media`, `cmd`, `rpc`, `rpc-result`, `state`, `clip`. On a direct link, the
  sender is the connected peer, so `to` and `from` aren't needed. The mesh
  adds:
  - `{"t":"hello","id","name","caps","os","v":1,"port"}` / `{"t":"welcome", …}`
    to start (`port`, the sender's own mesh port, was added: a peer that was
    dialled needs it to fetch offered files);
  - `{"t":"text","id","body","ts"}`: a chat message, answered with
    `{"t":"ack","id"}`;
  - `{"t":"offer","id","name","size","mime"}`: a file. The receiver fetches
    it with `GET https://<sender>:<port>/mesh/files/<id>` over the same
    mutual TLS, streamed, with range support to resume;
  - `{"t":"ring"}` / `{"t":"ring-stop"}`;
  - `{"t":"notify","app","title","text","key"}` / `{"t":"notify-removed","key"}`
    for notification mirroring;
  - added while building: `{"t":"ack","id"}` also answers a completed file,
    `{"t":"nack","id","error"}` refuses a text or file for good, and
    `{"t":"unpair"}` tells a peer it was unpaired (§9.4);
  - added later (§9.10): `{"t":"cancel","id"}` stops a file either way,
    `{"t":"link","id","url","ts"}` is a web link to open, and
    `{"t":"rename","name"}` says the sender has a new name.
- **Pairing endpoint:** `POST https://<peer>:<port>/mesh/pair`, then
  `/confirm`, then `GET /mesh/pair/<request>` polls for the answer (§9.3).

  *Changed from the design:* the design had the client present its
  not-yet-trusted certificate in TLS, and send `{"id","name","fp"}`. Two
  problems. Python's `ssl` (and other stacks) can't accept an unknown
  self-signed client certificate on a listener that also refuses unknown
  certificates for everything else. And a code derived from two
  fingerprints alone can be ground: an attacker in the middle makes key
  pairs until its two codes match, which for 4 digits takes seconds. So a
  pairing client connects **without** a certificate, sends its certificate
  in the request, proves it holds the key with a signature, and both sides
  commit to random nonces before the code can be computed (§9.3). An
  attacker then has one chance in 10,000 per attempt, and each attempt
  shows the owner a request.

## 5. Routing: how a message reaches a device

For each target, the first that works:

1. **Direct LAN:** mDNS or a cached address, mutual TLS.
2. **Direct Tailscale:** the peer's tailnet address from the roster.
3. **Through the hub,** if it's reachable: today's hub routing.
4. **Hub mailbox:** the peer is offline, so it's held on the hub (files,
   chat), exactly as today.
5. **Outbox:** no hub and no peer. Keep it locally, and send it when either
   appears.

Live control (`input`, `media`, `cmd`) only uses routes 1–3. Queueing mouse
moves makes no sense.

As built:

- **Live control** goes through the hub (3) only while the hub lists the
  peer as connected; otherwise the hub would only answer with an error.
- **`clip` and `ring`** also use 1–3 only: a clipboard or a ring an hour
  late is wrong. The hub has no addressed clipboard message, so `clip`
  through the hub reaches all your devices' clipboards, as clipboard sync
  does today. `ring` through the hub is its ring API, which also reaches a
  closed app by push.
- **Chat and files** use 1–5. Through the hub they become the hub's own chat
  message (`POST /text`) and inbox file (`POST /upload?to=`), which the hub
  holds for an offline device, so routes 3 and 4 are the same call; the
  sender reports "through the hub" when the peer is connected to it, and
  "the hub's mailbox" otherwise.
- The hub routes (3, 4) are used only for a peer that the current hub's
  roster lists. A peer paired directly is unknown to the hub.
- **Order** is kept per peer: each peer's queue goes out one message at a
  time, and a message that has to wait holds the ones after it.
- **The outbox** is kept in the data directory, so it survives restarts.
  A file waits where it is, not copied: its size and modification time are
  recorded, and a file that changed or went away fails rather than sending
  something else. The outbox is tried every 15 s, and at once when a peer
  appears on the LAN or links in, or the hub reconnects.
- A file transfer that stops part-way (either side restarted, the Wi-Fi
  dropped) is offered again with the same id, and the receiver resumes
  from what it has (§9.5).

## 6. Chat and history

- **Chat** is stored on both devices. When the hub is reachable, each device
  also uploads its messages to the hub, which keeps the merged history and
  shows it in the web app. Messages carry unique ids, so merging is
  idempotent. *Not built yet:* the Linux agent keeps its direct chat in
  `~/.local/share/droplet-agent/mesh/chat.jsonl`; the hub's chat store has
  no ids or upload endpoint yet. Chat sent through the hub (routes 3–4) is
  in the hub's history as today.
- **Files sent directly** land in the receiver's normal download location,
  and never touch the hub. On Linux: `~/Downloads/droplet` (the desktop's
  download folder; `mesh.downloads` in the config), with a unique name
  (`photo (1).jpg`), never overwriting anything.

## 7. What moves out of the hub

- **TV remote:** the Android app speaks the Android TV Remote protocol v2
  itself (TLS + protobuf, pairing code on the TV), so the phone controls the
  TV with no hub. The Linux agent can do the same (`androidtvremote2`). The
  hub's TV bridge stays for browsers.
- Everything else in `docs/remote.md` (input, media, lock, screenshot,
  clipboard, SMS, files) already runs in the apps. The mesh only changes how
  messages reach them.

## 8. Order of work

1. This spec. Then the peer library for each platform: identity, mDNS,
   mutual-TLS WebSocket server and client, trust list, pairing. The Linux
   agent comes first, as the reference and test peer. **Linux: done.**
2. The roster on the hub, which is the hub vouching. **Done.**
3. The Android app as a peer: server, routing, and the existing features
   over direct links. **Done.**
4. The Windows app as a peer.
5. Direct files, chat, ring, notifications and clipboard; routing fallback;
   the outbox. **Linux: done** (it receives notifications; it has none to
   mirror).
6. The TV remote in the Android app.
7. Tests: each pair of platforms directly, with the hub off. **Linux–Linux:
   done** (`agent/tests/e2e_mesh.py`). **Android–Linux: done**
   (`MeshInteropTest`, §9.8), and Android–Android on the JVM (`MeshUnitTest`).

## 9. The wire protocol, v1 (for implementing a peer)

This is exactly what the Linux agent does. A peer on another platform that
follows it interoperates with it.

### 9.1 Identity and certificate

- Key: **EC P-256** (secp256r1). Signatures are ECDSA with SHA-256,
  **DER-encoded** (the X9.62 `SEQUENCE {r, s}` that OpenSSL and Java's
  `SHA256withECDSA` give; on .NET pass `DSASignatureFormat.Rfc3279DerSequence`,
  since its default is the raw P1363 form).
- Certificate: X.509 v3, self-signed, SHA-256 signature, a unique subject
  (Linux: `CN=droplet-peer-<random id>`), `basicConstraints` CA:FALSE
  (critical), `keyUsage` digitalSignature, `extendedKeyUsage` serverAuth and
  clientAuth.
- **Validity must never lapse, and must not start in the future**:
  OpenSSL checks the dates of a trust anchor too. Linux uses notBefore
  2020-01-01 and notAfter 30 years out.
- Fingerprint: SHA-256 of the certificate's DER, **lowercase hex, 64
  characters**. Made once and kept private (Android Keystore, Windows DPAPI
  or the user's certificate store).

### 9.2 TLS

- One port: **1739**, or the first free one up to **1749**. TCP, dual-stack.
- TLS 1.2 or 1.3. No SNI, hostname or CA checks anywhere: identity is the
  fingerprint.
- **Server:** requests a client certificate but doesn't require one (Linux:
  every trusted peer's certificate is a trust anchor, `CERT_OPTIONAL`).
  - A client presenting a certificate that isn't in the trust list fails
    the handshake (an "unknown CA" alert). With TLS 1.3 the client sees
    that on its first read.
  - After the handshake, the SHA-256 of the presented leaf must be in the
    trust list, whatever chain the TLS library accepted.
  - A client with **no** certificate may only use `/mesh/pair*`. Everything
    else answers 403 before any body is read.
- **Client:** presents its certificate (for everything except pairing),
  accepts any server certificate in the TLS layer, and **checks the server's
  fingerprint against the expected one right after the handshake, before
  sending a byte**. On Android: a custom `X509TrustManager` and
  `X509KeyManager`; on Windows: `RemoteCertificateValidationCallback` and
  `LocalCertificateSelectionCallback`. Present the certificate even if the
  server's CertificateRequest names no CAs.

### 9.3 Pairing (with no hub)

> **Request bodies need a `Content-Length`.** The reference peer refuses chunked
> request bodies (`Transfer-Encoding: chunked`) with 413, so every peer must
> send the length up front. The .NET peer first sent chunked bodies and had to
> change.


The initiator I, the responder R. HTTPS to R's port, **I presents no client
certificate**. `nA` and `nB` are 32 random bytes each, written as 64
lowercase hex characters.

1. `POST /mesh/pair`
   `{"v":1, "id":"<I's peer id>", "name":"…", "os":"android", "cert":"<I's certificate, PEM>", "commit":"<hex SHA-256 of the 32 bytes of nA>"}`
   → `200 {"v":1, "request":"<32 hex>", "nonce":"<nB>", "id", "name", "os", "fp":"<R's fingerprint>"}`.
   I checks `fp` equals the fingerprint of the certificate R presented in
   TLS, and, if it found R over mDNS or the roster, that it's the one it
   expected. Errors: `400` (malformed), `409` (that's R itself), `429`
   (more than 20 requests a minute, or 3 already waiting).
2. `POST /mesh/pair/<request>/confirm`  `{"nonce":"<nA>", "sig":"<base64 of the signature>"}`
   where the signature, by I's key, is over the ASCII transcript
   ```
   "droplet-pair-v1" LF fpI LF fpR LF nA LF nB
   ```
   (fingerprints and nonces as lowercase hex, `LF` a single `\n`, no
   trailing newline). R checks SHA-256(nA) equals the commitment and the
   signature against the key in I's certificate. `200 {"state":"waiting"}`,
   or `403` and the request is dropped.
3. Both show the code:
   ```
   code = SHA-256("droplet-pair-code-v1" LF transcript), first 8 bytes as a big-endian unsigned integer, mod 10000, as 4 digits with leading zeros
   ```
4. I polls `GET /mesh/pair/<request>` → `{"state":"waiting"|"accepted"|"denied"|"expired"|"cancelled"}`
   (`404 {"state":"expired"}` for one it doesn't know). I may cancel with
   `POST /mesh/pair/<request>/cancel`. A request expires after 5 minutes.
5. R trusts I (source `paired`) when its owner accepts. I trusts R (the
   certificate R presented in TLS) only when **both** R says `accepted`
   **and** I's own owner confirmed the codes match: an impostor answering
   for R could say `accepted`, but can't make the codes match.

   So for a while R trusts I but I doesn't trust R yet: I's TLS refuses
   R's certificate ("unknown CA") until its owner has confirmed and its next
   poll has seen `accepted`, seconds or minutes later. For 2 minutes after
   accepting, R tries I again every 2 seconds when it can't reach it
   directly, instead of waiting for the outbox's usual round (15 s), so a
   message sent right after pairing goes out as soon as I trusts back.

The same HTTPS connection may carry several of these requests
(keep-alive). Each body is JSON, at most 64 KB.

### 9.4 The link

`wss://<peer>:<port>/mesh`, with the client certificate. Plain WebSocket
(RFC 6455), no subprotocol, no compression. Text frames, each one JSON
object, at most 1 MiB. Either side pings (WebSocket ping) after 20 s of
silence and closes after 60 s without hearing anything. Unknown message
types are ignored.

The dialler sends first, and the other side answers:

```json
{"t":"hello",   "id":"<peer id>", "name":"slim", "caps":["input","media"], "os":"linux", "v":1, "port":1739}
{"t":"welcome", "id":"<peer id>", "name":"t15",  "caps":[],                "os":"linux", "v":1, "port":1740}
```

`port` is the sender's own mesh port, so a peer that was dialled knows
where to fetch files offered to it (*added to the design*). Nothing else
is sent until the hello has gone both ways; a link with no hello within
15 s is closed. Once open, each side may send its latest `state` (media,
battery). Either side may use a link, whoever opened it.

The sender of every message is the peer at the other end, checked by
TLS. `from` in a message is ignored and replaced; `to` isn't needed.

| message | meaning |
|---|---|
| `input`, `media`, `cmd`, `clip`, `rpc`, `rpc-result`, `state` | as in `docs/remote.md` |
| `{"t":"text","id","body","ts"}` | chat; `id` 8–64 of `[0-9A-Za-z_-]`, `body` at most 64 KB. Answered with `ack`. A repeated `id` is acknowledged, not stored twice |
| `{"t":"ack","id"}` | a `text` or a file (`offer`) was received |
| `{"t":"nack","id","error"}` | refused for good (too long, no space, a bad offer); the sender doesn't retry (*added*) |
| `{"t":"offer","id","name","size","mime"}` | a file; `id` 16–64 hex. See §9.5 |
| `{"t":"ring"}` / `{"t":"ring-stop"}` | play a sound to find the device |
| `{"t":"notify","app","title","text","key"}` / `{"t":"notify-removed","key"}` | notification mirroring: a phone's notification, shown as a desktop notification and replaced by the next with the same `key`; `notify-removed` takes it away. See below |
| `{"t":"unpair"}` | "I unpaired you": a `paired` peer removes the sender; a `roster` one is kept (the hub vouches) (*added*) |
| `{"t":"ping"}` → `{"t":"pong"}` | app-level liveness, as with the hub |
| `{"t":"perm","paused","allow","caps"}` | how the sender treats you now: a hint for your UI (§9.9) (*added*) |
| `{"t":"refused","re","id"?,"cap","why","error"}` | the sender didn't take your `re` message: `why` is `"paused"` or `"denied"` (§9.9) (*added*) |
| `{"t":"cancel","id"}` | stop the file `id` (an `offer`'s id), sent or received; partial data is deleted (§9.10) (*added*) |
| `{"t":"link","id","url","ts"}` | a web link: opened on your own device, an Open button on someone else's. Answered with `ack`/`nack` (§9.10) (*added*) |
| `{"t":"rename","name"}` | the sender is called `name` now (§9.10) (*added*) |

A `cmd` `screenshot` over a link goes back to the requester as an `offer`
over the mesh, not an upload to the hub.

**Notification mirroring** (*added*) needs no hub. A computer that shows a
phone's notifications says so with the cap **`notify`** in its hello (and
mDNS and the roster); it drops it when its owner turns that off (Linux
`mesh.phone_notifications`, Windows Settings → Notifications → Show my
phone's notifications), so the text never leaves the phone for it. The
phone sends `notify` only to peers whose `os` is `linux`, `windows` or
`macos` and whose caps include `notify`:

- `key` is the phone's own key for the notification (Android's
  `StatusBarNotification.getKey()`, at most 200 characters), the same for
  each update of it; `app` is the app's name (at most 80), `title` at most
  200, `text` at most 1000. The receiver shows "`title` (phone name)" with
  `text`, under `app`, keyed by the sender's fingerprint and `key`, so an
  update replaces the last one; `notify-removed` closes it. Neither is
  acknowledged.
- The phone sends what its Notification access sees, minus what it never
  mirrors: apps under **Apps not to mirror**, ongoing ones, foreground
  services, group summaries, local-only ones, progress, transport, service
  and system ones; and for the mesh, also ones the phone itself shows
  silently or holds back for Do Not Disturb, and a repost with the same
  app, title and text as the one already sent.
- Bursts are capped: notifications posted within a second go together, at
  most 10 at once and 20 a minute (the newest go; the rest are dropped, not
  queued).
- Over a link that's already open. With none, the phone dials the computer
  when a notification arrives, at most once a minute per computer; never
  on a timer, and not for a removal alone. A link it opened closes after 5
  idle minutes, as usual.
- With a hub too, the phone also posts to the hub's Phone card
  (`/api/phone/notifications`), which is a list on the hub's page and pops
  nothing up, so nothing is shown twice. The mesh copy goes whether or not
  there's a hub.

### 9.5 Files

1. The sender sends `offer` over the link.
2. The receiver fetches `GET https://<sender>:<port>/mesh/files/<id>` with
   its client certificate, from the address the link is on (and the
   sender's other known addresses). Only the peer the offer was made to
   can fetch it; anyone else gets 404.
   - `Range: bytes=<n>-` resumes; the answer is `206` with
     `Content-Range: bytes <n>-<last>/<size>`, or `416` past the end. A
     single range is supported (also `bytes=a-b` and `bytes=-n`). `200` with
     `Accept-Ranges: bytes` without one. `410` if the file changed since
     it was offered. `HEAD` works too. The connection closes after each
     response.
3. When it has exactly `size` bytes, the receiver saves it under a safe,
   unique name, then sends `ack`. A permanent refusal is `nack`.
4. A partial download is kept (Linux: a hidden `.droplet-<sender fp>-<id>.part`
   beside the downloads) and resumed if the same sender offers the same
   `id` again, after either side restarted. An `id` already received is
   acknowledged at once, not saved twice.

The sender treats a transfer with no progress for 60 s (5 s once its link
has closed) as stopped, and offers it again later with the same `id`.

### 9.6 The roster API (the hub)

Both need a named, approved device: `Authorization: Bearer <token>` or the
cookie. Others get `403`.

- `POST /api/mesh/announce`
  `{"fp", "cert_pem", "port", "lan":["192.168.1.20"], "os":"android", "caps":["input"]}`
  → `{"ok":true, "changed":bool}`. `cert_pem` must be one certificate whose
  SHA-256 is `fp` (`400` otherwise), and no other device may have announced
  it (`409`). Loopback, link-local and tailnet addresses are dropped from
  `lan`. Announce on every connection to the hub, and when the port or
  addresses change.
- `GET /api/mesh/roster` →
  `{"v":1, "hub":"<hub id>", "peers":[{"id","name","fp","cert_pem","port","lan","tailnet_ip","caps","os"}]}`:
  every other approved device that has announced. `tailnet_ip` is the
  address the device last announced from through `tailscale serve`, or
  looked up in `tailscale status` by its node name, or `null`.
- `{"t":"roster"}` on `/ws`: fetch it again.

A peer checks each entry's `cert_pem` against its `fp` before trusting it,
and never trusts its own.

### 9.7 What the Linux agent adds locally

`droplet-agent peers`, `pair [<peer> | --accept | --deny] [--own | --other]`, `unpair`,
`text`, `send-file`, `ring [--stop]`, `clip [--text]`, `send <peer> <json>`,
`allow <peer> <capability> on|off` (or `allow <peer> own|other`),
`pause`/`resume <peer> | --all` (§9.9), and `transfers`, `cancel <id>`,
`open-link`, `rename <name>`, `nickname <peer> [<nickname>]` (§9.10); `text`,
`send-file`, `clip` and `open-link` take several devices (`a,b`, `--to` again
and again, or `--all`)
talk to the running agent over `$XDG_RUNTIME_DIR/droplet-agent/control.sock`
(owner-only, and the peer's uid is checked). Without a hub, `droplet-agent
run` runs the mesh alone. See `agent/README.md`.

### 9.8 The Android peer

The app follows everything above; where Android made a choice necessary,
this is it.

- **Identity.** The key is made in the Android Keystore and never leaves
  it: TLS signs with it through the Keystore (Conscrypt calls back into it
  for handshakes), and so does the pairing proof. Before an identity is
  kept, a mutual-TLS handshake with itself over loopback proves the key
  works for TLS on that phone, as server and as client. If it doesn't, or
  there is no Keystore (the JVM in tests), the key is a PKCS#8 file in the
  app's private storage instead, and the devices screen says so. The
  certificate is written by a small DER encoder (`Der.kt`), not
  BouncyCastle, to the §9.1 profile.
- **TLS.** The server asks for a client certificate and checks its
  fingerprint against the trust list in the handshake itself, so an
  untrusted certificate fails the handshake as on Linux; it checks again
  after the handshake, which also covers a resumed session of a peer
  unpaired since. The client presents its certificate whatever CAs the
  server names, and pins the server's fingerprint in its trust manager and
  again in its hostname verifier.
- **The server** is written by hand (HTTP/1.1 and RFC 6455 over
  `SSLServerSocket`), like the reference's.
- **Port.** 1739–1749, while Stay connected runs (and while a screen that
  sends is open). Received files go to `Download/droplet` through
  MediaStore.
- **What it does with what arrives:** `text` is a notification and the
  chat view; files, a download notification; `ring`, the loud ring;
  `clip`, the clipboard; `notify`, a notification; `media` and `rpc`, the
  same bridges as through the hub; `input` and `cmd` are answered with
  `{"t":"error","re":…}` (the phone takes neither, and doesn't announce
  them).
- **What it sends by itself:** its notifications, as `notify` and
  `notify-removed`, to paired computers that announce `notify` (above),
  while the mesh runs (Stay connected), with Notification access, and
  unless **Show phone notifications on my computers** is off
  (`NotifyMirror.kt`).
- **Permissions and Pause** (§9.9, `mesh/Perms.kt`): the same model, checks
  and messages as the reference. On a phone, `access` is its SMS, browsing
  its files and commands (the caps `sms` and `files`), `control` is remote
  control of its media (the cap `media`), and `notify` is mirroring its
  notifications to that computer (and showing that computer's here).
  Pairing asks "Is <name> your device, or someone else's?" once the codes
  match, on the phone's side only. Each device card on the home screen has
  Pause/Resume, a "Someone else's" badge, says when it's paused (here, by
  Pause everything, or by the device itself) and greys out what can't be
  sent, saying why when tapped; its last refusal shows under it for two
  minutes. Its ⋮ menu has Permissions: whose device, Pause, and a switch
  for each capability. Settings → Direct connections has Pause everything
  (kept in the app's preferences), and so does the Stay connected
  notification (Pause all, then Resume). Through the hub: `media`, `clip`
  and `rpc` from the hub's socket are checked against their sender (an
  `rpc` refused answers `rpc-result` with an error); a ring from the hub
  names its sender only by name, so a trusted device with that name (one
  only) gets its own switches; the clipboard and states don't go to the hub
  as §9.9 says, and neither do notifications for the hub's Phone card while
  everything is paused. The TV remote and the Bluetooth mouse and keyboard
  talk to the TV and the computer directly, not over the mesh, so they're
  unaffected.
- **Tests** (`android/app/src/test`): `MeshUnitTest` (the certificate
  profile, pairing vectors from the reference, Range, the server's access
  rules over real sockets, two JVM peers pairing and talking) and
  `MeshInteropTest`, against real `droplet-agent run --dry-run` processes:
  TLS with fingerprints both ways and strangers refused, pairing started
  from either side with matching codes, text, a 20 MB file each way
  interrupted and resumed, ring, clip, media and RPC answered by the
  phone's bridges, the roster through a hub, and direct delivery after the
  hub is stopped. `PermsTest` ports the reference's permission matrix
  (each capability both ways, allowed, switched off, paused and everything
  paused, between two JVM peers), pairing as your own device or someone
  else's, Pause and resume, and the hub routes; `NoHubTest` checks the same
  against the real agent from the home screen (the agent's `perm` greying
  out Clipboard, its refusal shown on the card, a pause the agent hears,
  a ring refused, a message held until Resume). CI
  (`.github/workflows/android.yml`) runs them all, with the agent
  installed from the same commit.

### 9.9 Per-device permissions and Pause (*added*; GitHub issue #59)

Pairing used to trust a device for everything. Now each device decides,
for each peer, what it shares with it, and can pause it. **Enforcement is
local**: every device checks what it sends and what it accepts against its
own settings, whatever the peer says or does. The Linux and Mac agent is
the reference (`agent/droplet_agent/mesh/perms.py`); the Windows and
Android apps follow this section. The Windows app does
(`windows-net/src/Droplet.Core/Mesh/Perms.cs`), with the reference's
`test_perms.py` ported to .NET and run against the agent too. Nothing here changes `v` (still 1): the
new fields and messages are optional, and an older peer that ignores them
is treated exactly as before.

**Each trust entry** gets three fields, chosen by this device's owner and
never sent as such:

| field | values | missing (an entry from before) |
|---|---|---|
| `relation` | `"own"` (your device) or `"other"` (someone else's: a deskmate's laptop, a friend's phone) | `"own"` |
| `allow` | `{capability: bool}`, one per capability below | the relation's defaults |
| `paused` | `bool` | `false` |

and the device has one **global pause** (Linux: `"mesh": {"paused": true}` in
config.json): every peer paused at once, while presenting, say.

**The capabilities**, the messages each covers, and the defaults:

| capability | messages | direction | own | other |
|---|---|---|---|---|
| `files` | `offer` (§9.5), the iPhone's `file`/`file-end` | both ways | on | on |
| `chat` | `text` | both ways | on | on |
| `clipboard` | `clip`: automatic sync *and* a clipboard sent on purpose | both ways | on | **off** |
| `notify` | `notify`, `notify-removed` | both ways | on | **off** |
| `control` | `input`, `media`, `cmd` (lock, screenshot), `rpc` `media.*`, `state` of kind `media`, the presentation remote | this device | on | **off** |
| `ring` | `ring`, `ring-stop` | this device | on | on |
| `access` | `rpc` `files.*` and `sms.*`, running commands (where a device offers them) | this device | on | **off** |

"Both ways": switched off, it's neither sent to the peer nor taken from
it. "This device": the switch says what the *peer* may do *here*; what this
device may do to the peer is the peer's own switch, which it enforces (and
announces, below). A `state` of kind `battery` needs no capability. `hello`,
`welcome`, `ping`, `pong`, `perm`, `unpair`, `ack`, `nack`, `refused`,
`error` and `rpc-result` are always allowed, paused or not, so a link stays
up, refusals can be explained, and either side can still unpair.

**Asking at pairing.** Each side asks its own owner "Is <name> your
device, or someone else's?" when it accepts (the responder) or confirms the
code (the initiator), and stores the answer as `relation` with its
defaults. The answer isn't sent: the other side asks its own owner. A
device that can't ask (a script, an older caller) uses `"own"`, as before.
Every device the hub's roster brings is `"own"` (the same user's hub); a
roster fetch keeps whatever the owner changed. Existing entries are `"own"`.

**Paused** (the peer, or everything): nothing goes to it (live messages
fail at once with a reason; chat and files wait in the outbox with the
error `waiting: <why>` and go on resume), and nothing from it is taken
except the always-allowed messages above. The link stays open, and a file
it offered earlier can't be fetched (403).

**Telling the peer** (a hint, never trusted for enforcement):

- `hello` and `welcome` carry `"perm": {"paused": bool, "allow": {…}}`: how
  the sender treats the receiver. Their `caps` are only what the receiver
  may use: without `control`, no `input`, `media`, `lock`, `screenshot`;
  without `clipboard`, no `clipboard`; without `notify`, no `notify`; paused,
  none. (mDNS and the roster still announce everything.)
- When the owner changes anything, the sender sends
  `{"t":"perm","paused":bool,"allow":{…},"caps":[…]}` on any open link.
- The receiver uses it to grey out its UI ("Paused by Brian's laptop",
  Send clipboard off) and to not send what would be refused: a live message
  fails at once with "<name> doesn't allow the clipboard from you" or
  "<name> paused sharing with you"; chat and files to a peer that paused
  you wait. A hint counts only while a link is open (the next link's hello
  brings the latest word); no `perm` at all (an older peer) means
  everything allowed, as before.

**Refusing**, so the sender can say why:

- A message that's answered (`text`, `offer`, the iPhone's `file`, a `clip`
  with an `id`), when its capability is **off**: `{"t":"nack","id","error":
  "<my name> doesn't allow <files|messages|the clipboard|…> from you",
  "cap","why":"denied"}`. The sender fails it for good and shows the error.
- The same, while **paused**: `{"t":"refused","re":"<its t>","id","cap",
  "why":"paused","error":"<my name> paused sharing with you"}`. Not a
  `nack`: an older sender hears no answer and retries later; a newer one
  keeps it queued with `waiting: <error>` until a `perm` says `paused:false`.
- Anything else (`clip` without an id, `input`, `ring`, `notify`, `rpc`…):
  `{"t":"refused","re","cap","why","error"}`, at most once every 5 seconds
  for each message type (input arrives many times a second).

**Through the hub** (routes 3–4, §5): the same checks. An outgoing message
is checked before any route is tried. A message arriving through the hub
names its sender (`from.id`); a trusted peer with that id gets its own
switches, and any other device of the hub's counts as your own (the hub's
web page, say), except that Pause everything stops all of it. A clipboard
broadcast to the hub reaches every device it has, so it doesn't go to the
hub while everything is paused, or while any device the hub lists is paused
or has `clipboard` off here; it still goes directly to the peers that may
have it.

**The iPhone** (a browser peer, docs/iphone.md §3.3) takes part the same
way: the computer asks own/other when its owner accepts the pairing, puts
`perm` in its `welcome`, sends `perm` when it changes, and refuses as above.
The web app has a Pause per computer, which it enforces itself and
announces with `perm`.

**Control socket** (Linux; the window and tray use these): `perm-set
{peer, relation?, allow?: {cap: bool}}` or `{peer, capability, on}`; `pause`
and `resume` with `{peer}` or `{all: true}`; `pair-answer` and
`pair-confirm` take `relation`; `status` lists `paused_all`,
`capabilities`, and for each peer `relation`, `allow`, `paused`, `remote`
(its last `perm`, while linked) and `refused` (its last refusal).

### 9.10 Progress and Cancel, links, rename and nicknames, several devices (*added*)

GitHub issues #4 and #5. Nothing here changes `v` (still 1). A peer says
which of these it understands in its `hello`/`welcome` (and the iPhone in
its `auth`/`welcome`, docs/iphone.md §3.3):

```json
{"t":"hello", …, "features":["cancel","link","rename"]}
```

No `features` means an older peer: it gets none of the new messages it
couldn't use (a link goes as a chat message instead), and ignores the ones
it doesn't know (`cancel`, `rename`), as §9.4 says of unknown types. The
Linux and Mac agent is the reference (`mesh/node.py`, `mesh/transfers.py`,
`mesh/links.py`); the Windows and Android apps follow this section.

**Progress** is local: each side counts its own bytes, nothing is sent for
it. The sender of a mesh file counts what it has served (the furthest byte
of `GET /mesh/files/<id>`, so a resumed transfer starts where it was); the
receiver what it has saved; over the iPhone's data channel, what was
written and what arrived. From those, the speed is the bytes of the last
few seconds over their time, and the time left is what's left at that
speed (none when nothing moved for 3 seconds: it's stalled). A UI shows at
most 4 updates a second. Through the hub (routes 3–4) there are no byte
counts: it shows as going, then sent. The agent's control socket has
`transfers` → `{"transfers":[{"id","dir":"out"|"in","fp","peer","name",
"size","done","percent","rate","eta","state","error","route"}],"queued":[…]}`
(`state` `active`, `done`, `failed`, `cancelled` or `waiting`; finished
ones stay listed for 2 minutes), and `status` carries `transfers` too.

**Cancel**, `{"t":"cancel","id"}`, always allowed (like `ack`: it only
stops something, so a paused peer can still send it):

- The **sender** cancels: it stops serving the offer at once (a request in
  progress is cut off, and from then on `GET /mesh/files/<id>` answers
  404), marks the job `cancelled` ("cancelled here") so it's never offered
  again, and sends `cancel` on an open link. The receiver stops fetching,
  deletes its partial file (the `.part` and its `.json`) and shows
  "Cancelled: <sender> cancelled it". It doesn't answer.
- The **receiver** cancels: it stops fetching, deletes the partial file,
  and sends `cancel`. The sender stops serving and marks the job
  `cancelled` ("<receiver> cancelled it"); it doesn't offer it again. An
  older sender ignores the `cancel`, sees the transfer stall (§9.5) and
  offers the same `id` again later: the receiver remembers what it
  cancelled and answers that offer with
  `{"t":"nack","id","error":"<name> cancelled it","why":"cancelled"}`, so it
  fails for good there too.
- Only the peer at the other end of a transfer can cancel it: a `cancel`
  for an id offered to someone else is ignored.
- A send still waiting in the outbox (the peer away or paused) is cancelled
  the same way, without sending anything.
- On the iPhone's channel (docs/iphone.md §3.3) the same `cancel` stops
  the `file` frames either way; the receiver drops what came, the sender
  stops sending, and neither sends `file-end` or an answer for it.

Control socket: `cancel {"id"}` (the id, or its first 6+ characters) →
`{"id","dir","name","peer"}`.

**Links**, `{"t":"link","id","url","ts"}`, `id` as `text`'s (8–64 of
`[0-9A-Za-z_-]`), answered with `ack` or `nack` like `text`:

- Needs the capability `chat` (both ways, §9.9): off, it's refused with a
  `nack` as `text` is; paused, with `refused`, and the sender keeps it as a
  chat message (`text` with the URL as its body), which waits in the outbox
  and goes on resume.
- `url` must be `http` or `https`, with a host, no whitespace or control
  characters (nor the invisible formatting ones: U+200B–U+200F,
  U+202A–U+202E, U+2066–U+2069, U+FEFF, U+00AD), at most 2048 characters.
  Anything else (`file:`, `javascript:`, `data:`, `intent:`, an app's own
  scheme) is refused with a `nack` when it arrives, refused before it's
  sent, and never opened.
- The receiver opens it in the default browser **only if the sender's
  `relation` is `own`** (your own device, §9.9), and at most 5 a minute from
  one device. From someone else's device it's never opened by itself: it's
  stored as a message and shown with an **Open** button (and a
  notification "<name> sent a link"). Either way it's in the chat history
  with `"kind":"link"`.
- The sender sends `link` only to a peer that listed `link` in its
  `features` on the open link. Otherwise (an older peer, or no link open)
  it sends the URL as a `text` message, through the outbox like any chat,
  which also reaches it through the hub.
- A received chat message that is nothing but an http(s) link also gets
  an **Open** button. It's never opened by itself either.
- A web app (the iPhone) can't open a link without a tap: it shows an
  Open button for every link.

Control socket: `link {"peer","url","wait"?}` → `{"how":"link","route"}`, or
`{"how":"message", …the text job's answer}`.

**Rename**, `{"t":"rename","name"}`, always allowed:

- A name is 1 to 40 characters once runs of white space are made one
  space, with no control characters (nor the invisible ones above). The
  same rule for a nickname, which may also be empty.
- Renaming this device: kept locally (Linux: `"device": {"name"}` in
  config.json), announced in mDNS (the TXT `name`) and in every later
  `hello`, and sent as `rename` on every open link at once.
- With a hub, the hub names its devices: the device renames itself there
  first (`POST /api/device {"name"}` with its token: the hub's rules, 40
  characters and no name twice, a 409 otherwise), and the hub's roster
  tells the others (`{"t":"roster"}`). If the hub refuses or can't be
  reached, the name isn't changed.
- Receiving `rename` (or a new name in a `hello`): a peer paired directly
  (`paired`) or a browser (`browser`) is renamed in the trust list; a
  `roster` peer isn't (the hub names it; the next roster brings it). An
  invalid name is ignored. The iPhone's `auth` carries its name too, so a
  rename made while it was away arrives when it connects.

Control socket: `rename {"name"}` → `{"name","told"}` (how many linked
devices were told at once).

**Nicknames** are only local: a trust entry's `nickname` (empty for none),
set by this device's owner, shown everywhere instead of the peer's own
name, with the real one shown small beside it, and used to find the peer
(`droplet-agent text "Brian's laptop" …`). **Never sent**: not in `hello`,
mDNS, the roster, `perm` or anything else. A roster fetch keeps it, like
the switches. Control socket: `nickname {"peer","nickname"}` →
`{"name","fp","nickname"}`; `status` lists each peer's `nickname`.

**Several devices at once** is local too: each device gets its own job and
its own result, exactly as if it were sent to it alone, so permissions,
Pause and routes apply to each (sent; waiting, paused or away, in the
outbox; or refused). "All my devices" means the peers whose `relation` is
`own` and that have the capability on here (paused ones included: theirs
waits). If one device's job has to wait for a route, the jobs queued
behind it for that device say so at once (`waiting: …`) instead of nothing.
