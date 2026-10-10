# droplet.noxeratech.com

The page people land on when you send them droplet. One static file, no build step,
no framework: `index.html` with the styles and script inline, the screenshots in `shots/`,
`og.png` for link previews, and `_redirects` for the short links. The only thing it loads
from elsewhere is the heading font (Bricolage Grotesque, from Google Fonts).

`app/` is droplet for iPhone: a web app (no build step either) that connects straight to
your computers over WebRTC, with no server. It's served from here once, then kept by its
service worker. See `docs/iphone.md`. The landing page's iPhone tab links to it.
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
| `/linux.sh`, `/mac.sh` | `releases/latest/download/install-agent.sh`, for `curl -fsSL <site>/linux.sh \| sh` (the same installer does both) |
| `/mac` | the agent's README, at On a Mac |
| `/store` | Droplet on the Microsoft Store |
| `/iphone` | `/app/`, the iPhone web app |
| `/privacy` | `docs/privacy.md` |
| `/releases`, `/source` | GitHub |

**This only works if every release keeps those exact asset names.** A release with
`droplet-android-1.6.apk` breaks `/android`, so publish the versioned file *and* a copy
named `droplet-android.apk` (the same for Windows), or rename the assets to the plain
names and put the version in the release title.

## The screenshots

`shots/` holds rendered screens, not photographs of anyone's phone: the devices in them
("slim", "Wanjiru's Pixel") are fictitious. The Android ones come from the app's screenshot
tests (`DROPLET_SHOTS=/tmp/shots ./gradlew :app:testReleaseUnitTest --tests '*ScreensTest*'`),
the desktop one from Droplet's window, and the iPhone ones from the web app's end-to-end test
in WebKit. They're WebP, 540 px wide for phones, each well under 50 KB; convert new ones with
`magick in.png -resize 540x -quality 82 out.webp`.

`og.png` (1200×630) is the picture WhatsApp, X and the rest show for a link. It's the hero
artwork with the headline, rendered from HTML with Playwright.

## Editing it

Keep it honest: it promises no account, no cloud and nothing in the middle, and it tells
people about Android's "unknown apps" prompt rather than letting them be surprised.
The "Get Droplet" button picks the visitor's system from the browser; add `?os=android`
(or `iphone`, `windows`, `mac`, `linux`) to the address to see another one. If those stop being true, change the page.
