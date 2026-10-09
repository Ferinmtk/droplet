// Offline: after the first visit the app loads from this cache, so it works with no
// internet (only your Wi-Fi). Each load also fetches a fresh copy in the background,
// used from the next start. Bump VERSION when the list of files changes, and with each
// new version of the app, so installed iPhones fetch all of it again.

const VERSION = "droplet-app-v2";
const SHELL = [
  "./", "index.html", "app.css", "app.js", "rtc.js", "proto.js", "crypto.js", "store.js", "scan.js",
  "vendor/jsQR.js", "manifest.webmanifest",
  "icons/icon-192.png", "icons/icon-512.png", "icons/icon-maskable-512.png", "icons/apple-touch-icon.png",
];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(VERSION).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith("droplet-app-") && k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET" || new URL(req.url).origin !== location.origin) return;
  e.respondWith(
    caches.open(VERSION).then(async (cache) => {
      // the app shell ignores the fragment and query (#pair=… is read by the page itself)
      const hit = await cache.match(req, { ignoreSearch: true });
      const fresh = fetch(req).then((res) => {
        if (res.ok && res.type === "basic") cache.put(req, res.clone());
        return res;
      }).catch(() => null);
      if (hit) {
        e.waitUntil(fresh);
        return hit;
      }
      return (await fresh) || (req.mode === "navigate" ? cache.match("index.html") : Response.error());
    }),
  );
});
