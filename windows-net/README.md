# droplet for Windows, in .NET

The Windows app is being rebuilt in C# on .NET 10 (#43), to replace the Go
app in [`../windows`](../windows) once it's verified on real Windows. This
folder is its engine, **Droplet.Core**: everything that isn't Windows, so it
builds and is tested on Linux. The WPF shell, the Windows integrations and
MSIX packaging come next, on top of it.

It does what the Go app does (the hub client, local-first routing with a
pinned LAN certificate, joining and linking, the poll loop, remote control
over the hub's live connection) and makes Windows a **mesh peer**
([`docs/mesh.md`](../docs/mesh.md)): devices talk to each other directly,
with the hub as an optional helper. It follows the Linux agent, the
reference peer, byte for byte, and its tests run against it.

## Architecture

| Namespace | What's in it |
|---|---|
| `Config` | `AppConfig` (the Go app's field names, so its `config.json` imports on first run), `ConfigStore` (atomic, owner-only writes) |
| `Hub` | `HubClient`: the hub's HTTP API as one device. Bearer token, no `Origin` header, no redirects |
| `LocalFirst` | [local-first](../docs/local-first.md): the pinned LAN connection, `_droplet._tcp` discovery, route choice (`RouteSelector`, `RouteManager`), the hub's identity over time, and getting in (`HubSetup`: join with a code, link, PIN, re-pair) |
| `Mdns` | a minimal mDNS responder and browser (DNS messages, RFC 6762) |
| `Mesh` | the peer: identity, trust list, pairing, the mesh port (Kestrel), links, files with resume, the outbox, routing (`MeshNode`), the hub's roster (`HubMeshBridge`), mDNS (`PeerDirectory`) |
| `Remote` | `RemoteDispatcher`, which acts on `input`, `media`, `cmd`, `clip` and `rpc` from the hub or a peer alike; `HubLiveSession`, the hub's `/ws`; clipboard and media watchers |
| `Polling` | `HubPoller`: inbox downloads, messages, rings |
| `Platform` | the interfaces the Windows shell implements: `IInput`, `IMedia`, `IClipboard`, `INotifications`, `IScreenshot`, `ILock`, `ISound` |

`DropletEngine` puts it all together as the app runs it: the shell creates
one with its `PlatformServices` and an identity store, shows its state, and
calls into its parts.

What happens to a message this device sends (docs/mesh.md §5): an open link
or a new one on the LAN, then the tailnet, then the hub, then the hub's
mailbox, then the outbox, which keeps it until a route appears. Live control,
clipboard and ring never queue.

## Security properties

- **The mesh port** asks for a client certificate without requiring one. The
  TLS validation callback accepts only a certificate whose fingerprint is in
  the trust list, so any other one fails the handshake, as in the reference.
  A client with no certificate reaches only `/mesh/pair*`; everything else is
  a 403 before any body is read. Every request checks the fingerprint again,
  which also covers a resumed TLS session of a peer unpaired since.
- **As a client**, the peer presents its certificate whatever CAs the server
  names, and checks the server's fingerprint inside the handshake, before
  sending a byte. No SNI, no host names, no CAs anywhere in the mesh.
- **Pairing** commits to a nonce before seeing the other's, signs the
  transcript (ECDSA P-256, DER), and trusts the other side only when both
  owners said yes: 1 chance in 10,000 per attempt for a man in the middle,
  and each attempt shows a request. At most 3 open requests and 20 a minute.
- **The roster** is trusted only as far as the hub is, and only with each
  entry's certificate matching its fingerprint; a peer never trusts itself.
- **The hub on the LAN** is trusted by its pinned certificate fingerprint and
  nothing else; that relaxation is confined to one handler. The tailnet route
  keeps normal TLS validation. A changed certificate is reported, never
  adopted silently, and the token is never sent to one nobody vouched for.
- **The mesh key** is kept by an `IIdentityStore`: sealed with DPAPI on
  Windows (`ProtectedIdentityStore`), owner-only files elsewhere.
- **Remote control** acts only on what's switched on and what the platform
  can do; `from` is always the authenticated sender. Typed text and clipboard
  contents are never logged.
- **Received files** get safe, unique names (no paths, hidden names, Windows
  device names or forbidden characters), and a download never overwrites.

## Build and test

Install the .NET 10 SDK (`global.json` pins it), then:

```bash
dotnet build
dotnet test                   # unit and interop tests
dotnet test --filter-not-namespace Droplet.Core.Tests.Interop        # unit tests only
```

The interop tests run the real reference on Linux (they skip themselves on
Windows):

- **The Linux agent** (`../agent`) runs as `droplet-agent run --dry-run` in a
  throwaway HOME and XDG directories, driven through its control socket as
  `agent/tests/e2e_mesh.py` does. It's installed into a throwaway virtual
  environment on first use, or `DROPLET_AGENT_PYTHON` names a Python that has
  it already.
- **The hub** (`../app.py`) runs from a throwaway `DROPLET_HOME` on ports
  8891 and 8892 (keep them free), with the repository's `.venv`, or
  `DROPLET_HUB_PYTHON`.

They prove, both ways, against the reference: finding each other over mDNS;
mutual TLS, with untrusted certificates refused in the handshake and no
certificate reaching only pairing; pairing from either side with the same
code, and denial; text, a 20 MB file interrupted and resumed, ring, clip,
input, media, lock and screenshots reaching the fake platform services; the
roster through a hub, the hub's mailbox, remote control through the hub's
`/ws`, the poll loop, and direct delivery with the hub stopped; the hub
found over mDNS, its LAN certificate pinned, and joining over the LAN until
approved or declined. Set `DROPLET_TEST_LOG` to a file to get the engine's log there.

On a small machine, build with `-m:1 -p:UseSharedCompilation=false`.

## Choices

- **No third-party runtime dependencies.** The mesh port is ASP.NET Core's
  Kestrel (a framework reference); links and requests use `ClientWebSocket`
  and `SocketsHttpHandler`; certificates and ECDSA are .NET's own (DPAPI,
  in the shell, comes with the Windows Desktop runtime).
- **mDNS is written here** (`Mdns/`, about 1,400 lines with its comments)
  rather than taken from a library: the widely used .NET ones either only
  browse (Zeroconf) or are no longer maintained (Makaretu.Dns.Multicast, whose
  forks have few users), and the app needs little. Browsing uses one-shot legacy
  unicast queries from an ephemeral port, as the Go app did, so it works
  without port 5353 and without an inbound firewall rule. Announcing binds
  5353 with address reuse, which is how responders share it with Windows'
  own (the DNS Client service) and with avahi on Linux; if it can't, the
  device isn't announced, and everything else still works.
- **Tests** use xUnit v3 on Microsoft.Testing.Platform.
