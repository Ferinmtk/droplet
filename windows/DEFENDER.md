# droplet.exe and antivirus warnings

Windows Defender, or another antivirus, may flag `droplet.exe` as a threat.
Usually the report names a generic machine-learning detection, such as
`Trojan:Win32/Wacatac…!ml` or a name ending in `!ml`, instead of a known piece
of malware. This page explains why that happens, how to check that your copy
is genuine, and what to do about it without switching your protection off.

## Why droplet gets flagged

Heuristic scanners don't know what a program is for. They score what it
*can do* and how it *looks*, and droplet looks a lot like remote-access
software, because it is remote-access software. It's just yours:

- **It controls the PC.** Your other devices can move the mouse and type
  (`SendInput`), take screenshots, lock the screen, and set the volume.
- **It reads the clipboard**, if you turn on clipboard sync (it's off at
  first).
- **It talks to another machine** (your hub) and keeps a live connection
  open for remote control.
- **It registers a `droplet:` link handler** so notification buttons work.
- **It can start with Windows and add Explorer "Send to" shortcuts.** Both
  are off until you turn them on in Settings.
- **It's new and rare.** Few people run it, so Microsoft has no reputation
  data for the file. Each new version is a new file with no history.
- **It isn't code-signed** (see [Code signing](#code-signing) for why, and
  what it would take).

droplet avoids the things that look like malware without doing anything
useful for you:

- It starts no hidden helper processes. Notifications and "what's playing"
  call Windows' own APIs directly.
- The exe isn't packed or obfuscated, keeps its symbol table, and carries
  full version information and an application manifest that asks for no
  admin rights (`asInvoker`).
- It writes only to your own user profile (`HKCU`, `%APPDATA%`). Nothing
  needs admin rights, and `droplet uninstall` removes all of it.

droplet's source is public at <https://github.com/Ferinmtk/droplet>, and the
exe is built from it with `windows/build.sh`. You can read exactly what it
does, or build it yourself.

## Check that your copy is genuine

A detection on a file that is byte-for-byte the released one is a false
positive. A file that doesn't match could be anything, so don't allow it.

1. Get the SHA-256 checksum of `droplet.exe` from the
   [release page](https://github.com/Ferinmtk/droplet/releases) you
   downloaded it from.
2. Compute the checksum of your copy. In Command Prompt:

   ```
   certutil -hashfile "%USERPROFILE%\Downloads\droplet.exe" SHA256
   ```

   Or in PowerShell:

   ```
   Get-FileHash "$env:USERPROFILE\Downloads\droplet.exe" -Algorithm SHA256
   ```

   Use the path where your `droplet.exe` actually is.
3. The two must be identical (case doesn't matter). If they differ, delete
   the file and download it again from the release page.

## Allow just this file (don't turn Defender off)

Never turn off real-time protection or Defender as a whole to run droplet.
Allow the one detection instead:

1. Open **Windows Security** → **Virus & threat protection** →
   **Protection history**.
2. Select the droplet detection. Check that the affected item is your
   `droplet.exe`, in the place you put it. Note the detection name (you'll
   want it for the report below).
3. Choose **Actions** → **Allow on device** (on some versions it's
   **Restore**, then allow). Windows asks for confirmation, and needs an
   administrator's approval if you aren't one.

This allows that one detection of that one file. Defender keeps protecting
everything else. If a later version of droplet is flagged, check it and allow
it again. Better still, report it (next section) so the detection is fixed
for everyone.

Avoid folder exclusions (such as excluding the whole `Downloads` folder).
They hide anything else that lands there.

## Report the false positive to Microsoft

Microsoft reviews false-positive reports and fixes the detection, usually in
a few days. That helps every droplet user, not just you.

1. Go to <https://www.microsoft.com/en-us/wdsi/filesubmission>.
2. Choose who you are:
   - **Home customer**, if you're a droplet user. Signing in is optional,
     but it lets you track the result.
   - **Software developer**, if you publish droplet (this needs a Microsoft
     account, and lets you follow up on the analysis).
3. Upload `droplet.exe` (the limit is 50 MB).
4. Choose **Incorrectly detected as malware/malicious**. Enter the detection
   name from Protection history, and add a short note, for example: *"Open-source
   tray companion for a self-hosted file-sharing hub,
   https://github.com/Ferinmtk/droplet. Remote control and clipboard sync are
   user-enabled features. SHA-256: …"*.
5. Once the result says the detection was removed, update Defender's
   definitions (**Virus & threat protection** → **Protection updates** →
   **Check for updates**) and try again.

## SmartScreen is a different warning

"Windows protected your PC" when you first run a download is SmartScreen,
not an antivirus detection. It means the file has no reputation yet, and it
appears for every unsigned app that few people have run. Click **More info →
Run anyway**. Signing (below) helps SmartScreen reputation build across
releases, but no certificate removes the warning at once any more.

## Code signing

Signing proves who published the file and that it hasn't changed since. It
doesn't make droplet trusted by itself, but a signed file with a consistent
publisher builds reputation over releases, and antivirus heuristics weigh
signed files differently from anonymous ones. These are the options as
checked in September 2026:

### Azure Artifact Signing (formerly Trusted Signing)

Microsoft's own signing service. It costs about **US$9.99 a month**
(Microsoft's figure; the pricing page itself doesn't list a price without a
quote). It needs a paid Azure subscription, and Microsoft verifies your
identity first.

**Eligibility is limited by country.** For public-trust certificates, which
are the ones that count for downloads:

- **Individual developers must be in the United States or Canada.**
- **Organizations** must be in the United States, Canada, the European
  Union, the United Kingdom, Australia, New Zealand, Japan, South Korea,
  Singapore, Switzerland, Norway or Israel.

**Kenya isn't on either list**, so an individual developer in Kenya, or a
Kenyan organization, can't use it today. Sources: the
[Artifact Signing quickstart, "Prerequisites"](https://learn.microsoft.com/en-us/azure/artifact-signing/quickstart)
(updated 15 September 2026) and Microsoft's
[code signing options for Windows app developers](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options)
(updated 29 August 2026). That second page gives a shorter organization list
(USA, Canada, EU, UK). The quickstart is the newer and longer one, and
neither includes Kenya.

### An OV code-signing certificate from a certificate authority

A traditional certificate from a CA such as Sectigo, DigiCert, GlobalSign or
Certum. It's available worldwide, including to individuals in Kenya (the CA
verifies your identity; individual validation is offered by several CAs and
their resellers). Microsoft puts the cost at **about US$150–300 a year**.
Since June 2023 the private key must live on a hardware token or a cloud
HSM, which the CA provides, so signing in CI needs a cloud-HSM option.

For SmartScreen it's equivalent to Artifact Signing: reputation builds over
releases signed with the same identity. EV certificates (US$400+ a year) no
longer skip SmartScreen, since 2024, so they aren't worth the premium for
that. Source:
[code signing options for Windows app developers](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options).

### SignPath Foundation (free for open-source projects)

SignPath Foundation signs qualifying open-source projects for free, with a
certificate issued to SignPath Foundation, the key held in SignPath's HSM,
and signing done from CI (GitHub Actions is supported). Its
[conditions](https://signpath.org/terms.html) include:

- an **OSI-approved open-source license** for all components, without
  commercial dual-licensing;
- no malware or potentially unwanted programs, and no features designed to
  exploit vulnerabilities or get around security measures;
- actively maintained, and already released in the form to be signed, with
  the functionality described on the download page;
- the signing team is the development team and owns the repository;
- multi-factor authentication for everyone involved, defined roles (authors,
  reviewers, approvers), and a published code signing policy that credits
  "Free code signing provided by SignPath.io, certificate by SignPath
  Foundation";
- a privacy policy and opt-out for any data collection, warnings for system
  changes, and a way to uninstall.

**droplet doesn't qualify yet:** the repository has no license file. Adding an
OSI-approved license (for example MIT or Apache-2.0) is the first step. After
that, the remote-control features would need a clear description so they
aren't mistaken for "circumventing security measures". Everything droplet
does is opt-in or visible (the tray icon changes while it's being
controlled), and `droplet uninstall` exists. Apply at
<https://signpath.io/solutions/open-source-community>.

### Which to pick

For a Kenyan individual developer today: apply to **SignPath Foundation**
(free) once the repository has an open-source license, or buy an **OV
certificate** with individual validation. Azure Artifact Signing is out of
reach until Microsoft extends it to Kenya. Whichever you pick, sign every
release with the same identity, so reputation carries over from one release
to the next.
