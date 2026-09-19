# Microsoft Store listing (draft)

Everything Partner Center asks for under **Store listings** (English). Fill in the rest
(age rating questionnaire, pricing: free) in Partner Center itself.

## Product name

droplet

## Short description (up to 100 characters)

Your devices, together: files, messages, clipboard and remote control, on your own network.

## Description

droplet brings your phone and your PCs together, like KDE Connect, without anyone else's
cloud. Send a file from your phone and it lands in Downloads. Copy on one device, paste on
another. Ring your phone when it's lost in the sofa. Use your phone as a touchpad, keyboard,
media remote or presentation clicker for your PC.

Everything goes directly between your own devices, on your Wi-Fi or over Tailscale, with
each device checking the other's identity. Pair two devices by comparing a four-digit code,
or run your own droplet hub at home to keep things for devices that are off and to reach
them from anywhere.

droplet for Windows sits in the notification area:

- Receive files, messages and rings from your other devices, with Windows notifications.
- Send files, the clipboard or a ring from the tray, or from the Devices window.
- Let your phone control this PC: mouse, keyboard, media and volume, locking, screenshots.
  You choose what's allowed, and one click pauses it all.
- Clipboard sync, off until you turn it on, and never for what your password manager marks
  as private.
- Chat with your devices.
- Starts with Windows only if you ask it to.

Free and open source (GPL-3.0): https://github.com/Ferinmtk/droplet. No account, no ads, no
analytics.

## Features (up to 20, 200 characters each)

1. Send files between your phone and PC directly, on your Wi-Fi or over Tailscale
2. Copy on one device, paste on another (clipboard sync, off by default)
3. Ring a lost phone, or find your PC, with a loud ring that stops when you answer
4. Use your phone as a touchpad, keyboard, media remote and presentation clicker
5. Exact volume control and what's playing, from your phone
6. Chat between your devices
7. Pair devices by comparing a four-digit code; no account needed
8. Works without a hub, or with your own droplet hub for devices that are off
9. Notifications for files, messages and pairing requests
10. Pause all remote control from the tray at any time
11. Follows Windows' light and dark mode
12. Free and open source, no ads, no analytics

## Search terms (up to 7)

phone to pc, file transfer, clipboard sync, kde connect, remote control, lan, tailscale

## Category

Productivity (secondary: Utilities & tools)

## Privacy policy URL

https://github.com/Ferinmtk/droplet/blob/main/docs/privacy.md

## Website and support

- Website: https://github.com/Ferinmtk/droplet
- Support: https://github.com/Ferinmtk/droplet/issues

## Notes for certification (Partner Center → Submission options)

> droplet talks to the user's own devices on the local network or their Tailscale network.
> To try it without a second device: open droplet, choose "No hub: pair directly with a
> device", and pair with a second PC running droplet (same Wi-Fi). Remote control, the
> clipboard and screenshots are only used by the user's own paired devices, and each can be
> switched off in Settings. The app declares runFullTrust because it's a desktop app (a tray
> icon, input injection with SendInput for the user's own remote control, and a local network
> listener for paired devices).

The **runFullTrust** restricted capability needs this justification in the submission.

## Screenshots checklist

At least one, up to ten; 1920×1080 or 1366×768 PNG; take them on a real PC, light and dark:

- [ ] The Devices window with a phone and a laptop listed, one "on Wi-Fi"
- [ ] The tray menu open (Send files ▸ expanded)
- [ ] Pairing: the four-digit code shown on the PC
- [ ] Setup: "Hubs on this network" with a hub found
- [ ] Settings: the Remote control section
- [ ] A notification: a file received, with Open and Show in folder
- [ ] The chat window
- [ ] The same Devices window in dark mode

Plus the Store logo (the 300×300 image Partner Center asks for; `packaging/Assets` has the
package's own images, and `static/icon-512.png` is the source).
