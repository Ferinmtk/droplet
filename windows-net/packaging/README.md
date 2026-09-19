# Packaging droplet as an MSIX

`Droplet.Package.proj` turns a published droplet into an MSIX with the Windows SDK's own
tools (MakePri, MakeAppx, SignTool), so it builds the same on a GitHub runner and on any PC
with the Windows 10/11 SDK. CI does all of this (`.github/workflows/windows-net.yml`).

```powershell
cd windows-net
dotnet publish src/Droplet.Windows -c Release -r win-x64 --self-contained -p:PublishSingleFile=false -o artifacts/app
dotnet msbuild packaging/Droplet.Package.proj -t:Pack                    # artifacts/msix/*.msix, unsigned
dotnet msbuild packaging/Droplet.Package.proj -t:Sign -p:CertificateFile=my.pfx -p:CertificatePassword=...
```

`-t:Layout` stops at the package folder (`packaging/obj/x64/layout`), and needs no SDK.

## The identity: where the Partner Center values go

A Store app's identity is assigned by Partner Center when the name is reserved. Until then
the package uses test values:

| Build property | Test default | From Partner Center |
|---|---|---|
| `MsixIdentityName` | `Ferinmtk.droplet.Test` | Product identity → **Package/Identity/Name** |
| `MsixPublisher` | `CN=droplet test` | Product identity → **Package/Identity/Publisher** (the whole `CN=…` string) |
| `MsixPublisherDisplayName` | `Ferinmtk` | Product identity → **Package/Properties/PublisherDisplayName** |
| `MsixVersion` | `2.0.0.0` | yours to choose; the Store wants the last part `0` |

(Partner Center → Apps and games → droplet → Product management → **Product identity**.)

Pass them on the command line (`-p:MsixIdentityName=...`), or, for CI, set them as
**repository variables** (not secrets; they're public in the Store anyway): Settings →
Secrets and variables → Actions → Variables: `MSIX_IDENTITY_NAME`, `MSIX_PUBLISHER` and
`MSIX_PUBLISHER_DISPLAY_NAME`. The workflow then also builds an unsigned
`droplet-windows-store-*` package, which is what you upload to Partner Center; Microsoft
signs Store packages.

## Sideloading the test package

The `droplet-windows-msix-*` artifact is signed with a certificate made in the CI job and
thrown away (its private key never leaves the job). To install it on a test PC:

1. Double-click `droplet-test.cer` → Install Certificate → **Local Machine** → Place all
   certificates in: **Trusted People**. (This trusts only that throwaway certificate.)
2. Double-click the `.msix` and choose Install.

Remove the certificate afterwards (certlm.msc → Trusted People) if you like.

## What the manifest declares

- `runFullTrust`: it's a desktop app (tray icon, SendInput, a local listener).
- `internetClient`, `privateNetworkClientServer`: talking to the hub and paired devices.
- A **StartupTask** (`dropletStartup`), declared off: Start with Windows only once the person
  turns it on in droplet's Settings (they can also change it in Settings → Apps → Startup).
- The **droplet:** protocol, for notification buttons, and a `droplet.exe` execution alias.
- **Firewall rules** for droplet.exe: TCP 1739–1749 in (the mesh port, any network profile:
  connections from unpaired devices fail the TLS handshake anyway) and UDP 5353 in (mDNS,
  private and domain networks only).

Explorer's Send To isn't offered in the package: a packaged app's writes to AppData go to its
own copy, which Explorer never sees.

## Images

`Assets/` is generated from `static/icon-512.png` by `tools/make-assets.py` (every scale, plus
the target-size taskbar icons), and committed.
