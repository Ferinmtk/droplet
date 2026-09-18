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
  const html = `<!doctype html><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>droplet — offline</title>
<style>*{box-sizing:border-box}body{background:#0f172a;color:#e2e8f0;font-family:system-ui,sans-serif;display:grid;place-items:center;
min-height:100vh;margin:0;padding:1rem;text-align:center}p{color:#94a3b8;max-width:22rem;line-height:1.5}
button{margin-top:1rem;font:inherit;padding:.6rem 1.2rem;border-radius:8px;border:none;background:#38bdf8;color:#0f172a;font-weight:600}</style>
<div><h1>💧 Can't reach the hub</h1>
<p>Is Tailscale switched on, and is the hub machine awake? Anything you shared is kept and will send once it's back.</p>
<button onclick="location.reload()">Try again</button></div>`;
  return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8" } });
}
