# droplet mesh: devices talk directly, the hub is optional

**Status: design, for v1.0.** Nothing here is built yet.

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
  has never met a hub makes its own random id (16 hex characters).
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
always.

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
  - `{"t":"hello","id","name","caps","os","v":1}` / `{"t":"welcome", …}` to
    start;
  - `{"t":"text","id","body","ts"}`: a chat message, answered with
    `{"t":"ack","id"}`;
  - `{"t":"offer","id","name","size","mime"}`: a file. The receiver fetches
    it with `GET https://<sender>:<port>/mesh/files/<id>` over the same
    mutual TLS, streamed, with range support to resume;
  - `{"t":"ring"}` / `{"t":"ring-stop"}`;
  - `{"t":"notify","app","title","text","key"}` / `{"t":"notify-removed","key"}`
    for notification mirroring.
- **Pairing endpoint:** `POST https://<peer>:<port>/mesh/pair` (TLS; the
  client certificate is required but not yet trusted), with
  `{"id","name","fp"}`. The target shows the request and the code. Then
  `GET /mesh/pair/<request>` polls for the answer.

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

## 6. Chat and history

- **Chat** is stored on both devices. When the hub is reachable, each device
  also uploads its messages to the hub, which keeps the merged history and
  shows it in the web app. Messages carry unique ids, so merging is
  idempotent.
- **Files sent directly** land in the receiver's normal download location,
  and never touch the hub.

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
   agent comes first, as the reference and test peer.
2. The roster on the hub, which is the hub vouching.
3. The Android app as a peer: server, routing, and the existing features
   over direct links.
4. The Windows app as a peer.
5. Direct files, chat, ring, notifications and clipboard; routing fallback;
   the outbox.
6. The TV remote in the Android app.
7. Tests: each pair of platforms directly, with the hub off.
