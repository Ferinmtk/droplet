# droplet privacy policy

*Draft, for the Microsoft Store listing's privacy URL. Last updated: September 2026.*

droplet connects your own devices (phones, PCs, a TV) so they can send each other files,
messages, clipboard text and rings, and so one can control another. It's free software
(GPL-3.0), and it's built so your data stays on your devices.

## What droplet collects

Nothing. There's no droplet account, no droplet server, no analytics, no telemetry, no
advertising and no crash reporting. The people who make droplet never receive anything
from your devices.

## Where your data goes

- **Between your own devices, directly.** Files, messages, clipboard text, rings and remote
  control travel straight from one of your devices to another over your own network (your
  Wi-Fi, or your Tailscale network), encrypted with TLS. Each device has its own key; a
  device only talks to devices you paired with it (both of you compare a four-digit code),
  or that your own hub vouches for.
- **Through your hub, if you run one.** A droplet hub is a program *you* run on one of your
  own computers. It keeps files and messages for devices that are switched off, and passes
  things between devices that can't reach each other directly. It's yours: nobody else has
  access to it.
- **Push notifications (hub only, optional).** To wake a phone's browser app when it's
  closed, a hub can send a notification through the browser maker's push service (Google's
  for Chrome, Mozilla's for Firefox). The hub encrypts each notification to your browser
  first, so the push service sees only that *a* notification was sent, never the file name
  or text. The hub's owner can turn push off. The Windows app itself doesn't use push.

## What the Windows app keeps on your PC

- Its settings, the list of devices it trusts, messages waiting to be sent and recent chat
  messages, in `%LOCALAPPDATA%\droplet` (inside the app's own storage when installed from
  the Store). Its private key is sealed with Windows' data protection (DPAPI) for your
  Windows account only.
- Files you receive, in the download folder you choose (`Downloads\droplet` by default).
- A log of what it did, in the same folder, so problems can be diagnosed. The log never
  contains typed text, clipboard contents or message text.

Uninstalling droplet removes the app; deleting `%LOCALAPPDATA%\droplet` removes everything
it kept.

## Remote control and the clipboard

Other devices can control your PC only in the ways you allow in Settings, and you can pause
all of it from the tray at any time. Clipboard sync is off until you turn it on, because it
sends what you copy to your other devices; even then, copies that a password manager marks
as private are never sent. The tray icon turns amber while another device is sending input,
and you're notified each time a device takes a screenshot.

## Network access

droplet listens on a network port (TCP 1739–1749) so your paired devices can reach it, and
announces itself on private networks (mDNS) so they can find it. Connections from devices
that aren't paired, or vouched for by your hub, are refused.

## Children

droplet isn't directed at children and collects nothing from anyone.

## Changes and contact

Changes to this policy are published with the source code at
https://github.com/Ferinmtk/droplet. Questions: open an issue there.
