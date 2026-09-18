# Local-first: how native apps reach the hub

droplet works like KDE Connect: on the home network, devices talk to the hub
directly. Tailscale is the extra that makes it work away from home. Browsers
use whichever URL they were opened on. The **native apps** (Android,
Windows, the Linux agent) pick the best route on their own, following this
document.

## 1. Finding the hub on the LAN

The hub announces itself over mDNS/DNS-SD:

- service type `_droplet._tcp.local.`
- port: the LAN HTTPS port (default 8443)
- TXT records:

| key | value |
|---|---|
| `id` | the hub's permanent id (16 hex characters) |
| `fp` | SHA-256 of the LAN certificate (DER encoding), lowercase hex, 64 characters |
| `name` | the hub machine's name, e.g. `t15` |
| `http` | its plain-HTTP port, for browsers |
| `ts` | its tailnet URL, or empty |

`GET /api/hub/info` returns the same, and is reachable without being let in:

```json
{"id":"9b16173d305cd15a","name":"t15","fingerprint":"3c10…2094",
 "lan":{"addresses":["192.168.100.20"],"http_port":8000,"https_port":8443},
 "tailnet":"https://t15.tail7375fe.ts.net","pin":false}
```

The hub's LAN IP changes with DHCP. **Never rely on a stored IP alone.**
Store the hub `id` and `fingerprint`, and treat an IP as a hint to try first.

## 2. Trusting the LAN connection: pinning

The LAN certificate is self-signed and long-lived. Apps connect to
`https://<lan ip>:<port>` and accept the TLS certificate **if and only if**
its SHA-256 (DER) equals the pinned fingerprint. Hostname and CA checks are
skipped for this connection only. Everything else stays normal: the tailnet
URL uses ordinary, fully verified TLS.

Where the pin comes from:

- **Best:** the app first reached the hub over Tailscale, which is verified
  TLS, and read `fingerprint` from `/api/hub/info`. Then the LAN pin is as
  trustworthy as Tailscale.
- **First contact on the LAN, with no Tailscale ever:** trust on first use.
  Read `fp` from mDNS, connect, and check the certificate matches. The
  pairing approval, where the owner compares the four-digit code on both
  screens, confirms it's the right hub.

A mismatch later means it's either not your hub, or the hub's certificate
was regenerated (its `certs/` folder was deleted). **Never switch silently.**
Show a clear "the hub's identity changed" error, with a button to re-pair.

## 3. Choosing the route

Try in this order, in parallel where possible, and use the first that works:

1. **LAN:** an mDNS result whose `id` matches the paired hub. Otherwise the
   last LAN address that worked. Connect with the pin, with a short timeout
   (about 1.5 s).
2. **Tailnet:** the `tailnet` URL, with normal TLS.

Then:
- Re-evaluate when the network changes (Wi-Fi joined or left, VPN up or
  down).
- While on the tailnet, look for the LAN again from time to time (on every
  Wi-Fi connect, and every few minutes), and move over when it appears.
- Show the current route in the UI: "On Wi-Fi" or "Via Tailscale".

The same device token works on every route. Auth is `Authorization: Bearer
<token>` (or the `droplet_device` cookie). The WebSocket is
`wss://<route>/ws`, identical on both.

## 4. Getting in: pairing

- **Already paired** (the app has a token): nothing changes. The token
  works over the LAN and the tailnet alike.
- **New device, on the tailnet:** as before. Naming the device gets it in
  straight away, because tailnet members are trusted.
- **New device, LAN only:** `POST /api/device {"name"}` returns
  `{"id","name","pending":true,"code":"7156"}`, plus the token in
  `Set-Cookie: droplet_device=…`. Show the code large, with "On one of your
  devices, allow <name>, and check the code matches". Poll `GET /api/me`
  (bearer token) every 2–3 s:
  - `device.pending` gone means you've been let in;
  - `device: null` means the request was denied or expired.
  - Offer the alternatives too: the hub's PIN (`POST /login`, form field
    `pin`, if `/api/hub/info` says `pin: true`; keep the session cookie), or
    a link code (`POST /api/device/link`).
- **Until let in**, every API except the ones above answers
  `403 {"error":…,"pair":true}`. Treat `pair: true` as "not let in (any
  more)", and show the pairing screen, not a generic error.

## 5. WebViews (Android)

Cookies are per origin. So when the WebView loads the LAN origin, set the
device's `droplet_device=<token>` cookie for that origin first, using
`CookieManager.setCookie`. In `onReceivedSslError`, `proceed()` only when the
certificate's SHA-256 matches the pin; otherwise `cancel()`.
