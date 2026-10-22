# droplet.noxeratech.com

The page people land on when you send them droplet. One static file, no build step,
no framework: `index.html` with the styles and script inline, the screenshots in `shots/`,
`og.png` for link previews, and `_redirects` for the short links. The only thing it loads
from elsewhere is its font (Plus Jakarta Sans, from Google Fonts).

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

## Visits and downloads (/stats)

`../site-worker/` makes the site a Cloudflare Worker: `worker.js` serves `site/`, counts each
visit (the page sends `/hit` once) and each download-link click, and shows them at `/stats`
behind a password. The counts go in a D1 database (`schema.sql`). What's kept is in
`docs/privacy.md`, under The website. Set it up once:

```
cd site-worker
npx wrangler d1 create droplet-stats          # put the id it prints in wrangler.jsonc
npx wrangler d1 execute droplet-stats --remote --file schema.sql
npx wrangler secret put STATS_PASSWORD        # the /stats password (any user name)
npx wrangler secret put STATS_SALT            # a long random string; never change it mid-day
npx wrangler deploy
```

Opening `/stats` also marks that browser as yours, so your own visits aren't counted.

Share links say where a visitor came from (WhatsApp doesn't tell the browser): `/wa`
(WhatsApp chats), `/status` (WhatsApp Status), `/in` (LinkedIn), `/fb` (Facebook), `/x`,
`/tt` (TikTok) and `/ig` (Instagram). Each opens the page with `?utm_source=`, which the page
reports and then removes from the address bar.

## The short links

`worker.js` sends `/android`, `/windows`, `/store`, `/linux.sh` and `/mac.sh` (counting the
click first), and `_redirects` the rest. Downloads go to the **latest** GitHub release, so
the buttons never need editing:

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
in WebKit (`iphone-start` is the web app's first screen, `app/` rendered in WebKit at 390×844). They're WebP, 540 px wide for phones, each well under 50 KB; convert new ones with
`magick in.png -resize 540x -quality 82 out.webp`.

`og.png` (1200×630) is the picture WhatsApp, X and the rest show for a link. It's the hero
artwork with the headline, rendered from HTML with Playwright.

## The hero

The picture at the top is a small demo you can play with: tap **Send** on the phone (or drag
the item towards the laptop) and it turns into a droplet, flies over and lands in the laptop's
Received tray. Untouched, it plays by itself every few seconds while it's on screen, and the
headline's word follows what's being passed (photos, links, PDFs, notes). It's inline SVG and
the last block of the script in `index.html`; the items are the `ITEMS` list and the
`it-*` symbols. With reduced motion it doesn't play by itself, and a tap just swaps the
picture without the flight.

## The remote section

"A remote for everything in the room" gives each of the phone's remotes its own row: music,
touchpad and keyboard, slides, find your phone, and the TV. The pictures are hand-drawn inline
SVG (no images to load), decorative and hidden from screen readers; the words are in the HTML.
Their small loops (notes floating, the pointer gliding, the phone wiggling, the slide changing)
are CSS animations that only run when the visitor hasn't asked for reduced motion, and only
while the picture is on screen (the `on` class, set by an IntersectionObserver). Each row's
chips say which devices it works with; keep them true to `android/README.md`.

## The motion

Everything that moves is plain CSS plus a little of the inline script, no libraries. The rules:

- **It's all optional.** Every animation sits under `prefers-reduced-motion: no-preference`, and
  anything that starts hidden also needs the `js` class on `<html>`. With reduced motion, or
  without JavaScript, the page is complete and still: the switches are on, the code is shown.
- **Only `transform` and `opacity` move**, so nothing shifts the layout and it stays at 60 fps.
- **Reveals** (`.rv`): an element fades and rises in when it scrolls into view; things that
  arrive together are staggered by `--d`. Once done, the `rv` class is removed so the hover
  effects take over. Some sections do more than fade: the remote pictures slide in from their
  side, the pairing code types itself onto both screens and gets its tick, the "someone else's"
  cards land and their switches flip on, the laptop's lid opens.
- **The hero** rises in word by word (the script wraps the visible headline's words; the
  screen-reader copy is separate), the devices float in over slowly drifting pastel blobs, and
  on a desktop the layers lean a little towards the pointer (`data-depth`). A pass now kicks the
  phone, trails two smaller drops and splashes onto the laptop.
- **Small touches:** buttons lift and press, the primary one shines; feature cards lift with a
  soft light following the pointer; the download tabs have a sliding pill; FAQ answers open and
  close smoothly; the header gets a shadow, a reading-progress line and an underline under the
  section you're in.

## Editing it

Keep it honest: it promises no account, no cloud and nothing in the middle, and it tells
people about Android's "unknown apps" prompt rather than letting them be surprised.
The "Get Droplet" button picks the visitor's system from the browser; add `?os=android`
(or `iphone`, `windows`, `mac`, `linux`) to the address to see another one. If those stop being true, change the page.
