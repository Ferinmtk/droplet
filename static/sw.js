// droplet service worker: makes the app installable, catches Android's
// "Share → droplet" POST, shows notifications for items sent to this device,
// and shows a friendly page when the hub is unreachable.

const INBOX = "droplet-share-inbox";

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", event => event.waitUntil(self.clients.claim()));

self.addEventListener("fetch", event => {
  const req = event.request;
  const url = new URL(req.url);
  if (req.method === "POST" && url.pathname === "/share") {
    event.respondWith(stash(req));
  } else if (req.mode === "navigate") {
    event.respondWith(fetch(req).catch(offline));
  }
});

// Something was sent to this device. The payload is written by deliver() in app.py.
self.addEventListener("push", event => {
  let d = {};
  try { d = event.data ? event.data.json() : {}; } catch {}
  event.waitUntil(self.registration.showNotification(d.title || "droplet", {
    body: d.body || "",
    icon: "/static/icon-192.png",
    tag: d.tag,
    renotify: !!d.tag,  // a new chat message replaces the last one from that sender, but still rings
    data: { url: d.url || "/" },
  }));
});

// Tap: a sent link opens directly; anything else opens droplet on its inbox.
self.addEventListener("notificationclick", event => {
  event.notification.close();
  const url = event.notification.data?.url || "/";
  event.waitUntil((async () => {
    if (/^https?:\/\//.test(url)) return self.clients.openWindow(url);
    const wins = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    for (const w of wins) {
      if (new URL(w.url).origin === self.location.origin) {
        await w.focus();
        return w.navigate(url).catch(() => {});
      }
    }
    return self.clients.openWindow(url);
  })());
});

// Park shared items in a cache and open the page, which uploads them with the
// normal progress bar. Parking (rather than posting straight to the hub) means
// a PIN login in between doesn't lose them.
async function stash(req) {
  const form = await req.formData();
  const cache = await caches.open(INBOX);
  const stamp = Date.now();
  let n = 0;
  for (const f of form.getAll("files")) {
    if (!(f instanceof File) || (!f.size && !f.name)) continue;
    await cache.put(`/inbox/${stamp}-${n++}`, new Response(f, {
      headers: { "X-Kind": "file", "X-Name": encodeURIComponent(f.name || "shared") },
    }));
  }
  // apps fill title/text/url inconsistently, often repeating the link in text
  const parts = [];
  for (const key of ["title", "text", "url"]) {
    const v = (form.get(key) || "").trim();
    if (v && !parts.some(p => p.includes(v))) parts.push(v);
  }
  if (parts.length) {
    await cache.put(`/inbox/${stamp}-text`, new Response(parts.join("\n"), { headers: { "X-Kind": "text" } }));
  }
  return Response.redirect("/?shared=1", 303);
}

function offline() {
  // same look as the app: a dry, outlined drop instead of the filled one
  const html = `<!doctype html><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#f5f2ec" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#061419" media="(prefers-color-scheme: dark)">
<title>droplet · offline</title>
<style>
:root{color-scheme:dark;--bg:#061419;--text:#e3f1ef;--dim:#7d9da0;--accent:#4de8d4;--coral:#ff8468;--on:#032824;
--tide:linear-gradient(140deg,#6af2dc,#40c6ec 55%,#3a9ff5)}
@media (prefers-color-scheme: light){:root{color-scheme:light;--bg:#f5f2ec;--text:#0c2a30;--dim:#5e7478;--accent:#0a7d84;
--coral:#e2563f;--on:#fff;--tide:linear-gradient(140deg,#12a5a0,#127fa8 55%,#1a62ad)}}
*{box-sizing:border-box;margin:0}
body{background:var(--bg);color:var(--text);font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;display:grid;
place-items:center;min-height:100vh;min-height:100dvh;padding:24px;text-align:center;-webkit-font-smoothing:antialiased}
svg{width:64px;height:76px;margin-bottom:18px}
.drop{fill:none;stroke:var(--dim);stroke-width:1.6;stroke-dasharray:4 4}
.ring{fill:none;stroke:var(--coral);stroke-width:1.4;opacity:.7;transform-origin:30px 70px;animation:r 2.8s ease-out infinite}
@keyframes r{0%{transform:scale(.4);opacity:.8}100%{transform:scale(1.4);opacity:0}}
h1{font-family:ui-rounded,system-ui,sans-serif;font-size:24px;font-weight:800;letter-spacing:-.035em}
p{color:var(--dim);max-width:21rem;margin:8px auto 0}
button{margin-top:24px;font:inherit;font-weight:700;min-height:48px;padding:0 26px;border-radius:16px;border:none;
background:var(--tide);color:var(--on);cursor:pointer}
button:focus-visible{outline:2px solid var(--accent);outline-offset:3px}
@media (prefers-reduced-motion: reduce){.ring{animation:none}}
</style>
<div><svg viewBox="0 0 60 76" aria-hidden="true"><path class="drop" d="M30 4C24 13 11 27 11 40a19 19 0 0 0 38 0C49 27 36 13 30 4Z"/>
<ellipse class="ring" cx="30" cy="70" rx="16" ry="3.5"/></svg>
<h1>Can't reach the hub</h1>
<p>Is Tailscale switched on, and is the hub machine awake? Anything you shared is kept and will send once it's back.</p>
<button onclick="location.reload()">Try again</button></div>`;
  return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8" } });
}
