# droplet.noxeratech.com

The page people land on when you send them droplet. One static file, no build step,
no framework: `index.html` with the styles inline, three screenshots, and `_redirects`
for the short links.

`app/` is droplet for iPhone: a web app (no build step either) that connects straight to
your computers over WebRTC, with no server. It's served from here once, then kept by its
service worker. See `docs/iphone.md`. It isn't linked from the landing page yet.
`app/vendor/jsQR.js` is jsQR 1.4.0 (Apache-2.0), unmodified.

## Deploying it (once)

1. Cloudflare dashboard → **Workers & Pages** → **Create** → **Pages** → **Connect to Git**,
   and pick `Ferinmtk/droplet`.
2. Build settings:
   - **Framework preset:** None
   - **Build command:** *(leave empty)*
   - **Build output directory:** `site`
   - **Production branch:** `main`
3. Deploy, then **Custom domains** → **Set up a custom domain** → `droplet.noxeratech.com`.
   Cloudflare adds the CNAME itself when the domain is on the same account.

After that every push to `main` republishes it.

## The short links

`_redirects` sends `/android` and `/windows` to the **latest** GitHub release, so the
buttons never need editing:

| Link | Goes to |
|---|---|
| `/android` | `releases/latest/download/droplet-android.apk` |
| `/windows` | `releases/latest/download/droplet-windows.exe` |
| `/linux` | the agent's README, at Install (a page to read: the install is a command) |
| `/linux.sh` | `releases/latest/download/install-agent.sh`, for `curl -fsSL <site>/linux.sh \| sh` |
| `/releases`, `/source` | GitHub |

**This only works if every release keeps those exact asset names.** A release with
`droplet-android-1.6.apk` breaks `/android`, so publish the versioned file *and* a copy
named `droplet-android.apk` (the same for Windows), or rename the assets to the plain
names and put the version in the release title.

## The screenshots

`shots/` holds rendered screens from the app's own screenshot tests, not photographs of
anyone's phone: the devices in them ("slim", "Wanjiru's Pixel") are fictitious. Regenerate
them with:

```bash
cd android
DROPLET_SHOTS=/tmp/shots ./gradlew :app:testReleaseUnitTest --tests '*ScreensTest*'
```

then copy `home-devices.png`, `setup-choose.png` and `pair-code.png` over the ones here.

## Editing it

Keep it honest: it promises no account, no cloud and nothing in the middle, and it warns
people about the SmartScreen and "unknown apps" prompts rather than letting them be
surprised. If those stop being true, change the page.
