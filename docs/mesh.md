# droplet mesh: devices talk directly, the hub is optional

**Status: v1.0, in progress.** Built so far:

- **The Linux agent is a full peer** (`agent/droplet_agent/mesh/`), and the
  reference the other apps follow: identity, mDNS, the mutual-TLS port,
  the trust list, direct pairing, every message below, routing with the
  outbox, and the `droplet-agent peers | pair | unpair | text | send-file |
  ring | clip | send` commands.
- **The hub's roster** (`mesh.py`): §3.1 and §9.
- **The Android app is a peer** (`android/app/src/main/java/dev/droplet/app/mesh/`
  and `Mesh.kt`): the same protocol, tested against the Linux agent both
  ways (§9.8). Its features use direct links: sharing files and text,
  chat, ring, clipboard, the presentation remote, and the media, SMS and
  files bridges answering over a link. See `android/README.md`.

Not yet: the Windows app as a peer, chat history merged on the hub (§6),
and the TV remote in the apps (§7).

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
  | `os` | `android`, `windows` or `linux` |
  | `caps` | comma-separated, same names as `docs/remote.md` |
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
    `{"t":"unpair"}` tells a peer it was unpaired (§9.4).
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
| `{"t":"notify","app","title","text","key"}` / `{"t":"notify-removed","key"}` | notification mirroring |
| `{"t":"unpair"}` | "I unpaired you": a `paired` peer removes the sender; a `roster` one is kept (the hub vouches) (*added*) |
| `{"t":"ping"}` → `{"t":"pong"}` | app-level liveness, as with the hub |

A `cmd` `screenshot` over a link goes back to the requester as an `offer`
over the mesh, not an upload to the hub.

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

`droplet-agent peers`, `pair [<peer> | --accept | --deny]`, `unpair`,
`text`, `send-file`, `ring [--stop]`, `clip [--text]` and `send <peer> <json>`
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
- **Tests** (`android/app/src/test`): `MeshUnitTest` (the certificate
  profile, pairing vectors from the reference, Range, the server's access
  rules over real sockets, two JVM peers pairing and talking) and
  `MeshInteropTest`, against real `droplet-agent run --dry-run` processes:
  TLS with fingerprints both ways and strangers refused, pairing started
  from either side with matching codes, text, a 20 MB file each way
  interrupted and resumed, ring, clip, media and RPC answered by the
  phone's bridges, the roster through a hub, and direct delivery after the
  hub is stopped.
