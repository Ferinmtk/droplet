// droplet.noxeratech.com: serves site/ and counts visits and downloads for /stats.
// Nothing personal is stored: no IP address and no cookie for visitors. A visitor is a
// code made from a secret, the date, the IP and the browser, so one person counts once a
// day and can't be followed from one day to the next.

const DOWNLOADS = {
  '/android': ['android', 'https://github.com/Ferinmtk/droplet/releases/latest/download/droplet-android.apk'],
  '/windows': ['windows', 'https://github.com/Ferinmtk/droplet/releases/latest/download/droplet-windows.exe'],
  '/store': ['store', 'https://apps.microsoft.com/detail/9N8VX22MW0N4'],
  '/linux.sh': ['linux', 'https://github.com/Ferinmtk/droplet/releases/latest/download/install-agent.sh'],
  '/mac.sh': ['mac', 'https://github.com/Ferinmtk/droplet/releases/latest/download/install-agent.sh'],
};
// links to share in one place each, so the stats can say where a visitor came from
const SHARE = { '/wa': 'wa', '/status': 'status', '/in': 'in', '/fb': 'fb', '/x': 'x', '/tt': 'tt', '/ig': 'ig' };
const SHARE_NAME = { wa: 'WhatsApp', status: 'WhatsApp Status', in: 'LinkedIn', fb: 'Facebook', x: 'X (Twitter)', tt: 'TikTok', ig: 'Instagram' };
const WHAT = { android: 'Android app', windows: 'Windows app', store: 'Microsoft Store', linux: 'Linux installer', mac: 'Mac installer' };
const BOTS = /bot\b|bot\/|crawl|spider|slurp|facebookexternalhit|WhatsApp\/|preview|HeadlessChrome|Lighthouse|python-requests|Go-http|axios|node-fetch|okhttp/i;
const TZ = 3 * 3600e3; // the stats page's days are Nairobi days
const DAY = 86400e3;
const ME = 'dropletme=1'; // set on the owner's browsers by /stats, so their own visits don't count

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    const dl = DOWNLOADS[url.pathname];
    if (dl) {
      if (count(req, dl[0] === 'linux' || dl[0] === 'mac')) ctx.waitUntil(record(req, env, 'download', dl[0], ''));
      return Response.redirect(dl[1], 302);
    }
    const share = SHARE[url.pathname.replace(/\/$/, '')];
    if (share) return Response.redirect(`${url.origin}/?utm_source=${share}`, 302);
    if (url.pathname === '/hit' && req.method === 'POST') {
      if (count(req, false)) {
        let b = {};
        try { b = JSON.parse(await req.text()) } catch {}
        ctx.waitUntil(record(req, env, 'visit', String(b.p || '/').slice(0, 80), source(b.r, b.s, req)));
      }
      return new Response(null, { status: 204 });
    }
    if (url.pathname === '/stats' || url.pathname === '/stats/') return stats(req, env, ctx);
    return env.ASSETS.fetch(req);
  },
};

function count(req, terminalOk) {
  const ua = req.headers.get('user-agent') || '';
  if ((req.headers.get('cookie') || '').includes(ME)) return false;
  if (/curl|wget/i.test(ua)) return terminalOk;
  return ua !== '' && !BOTS.test(ua);
}

async function record(req, env, kind, what, src) {
  const ua = req.headers.get('user-agent') || '', ip = req.headers.get('cf-connecting-ip') || '', cf = req.cf || {};
  const now = Date.now(), day = new Date(now + TZ).toISOString().slice(0, 10);
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`${env.STATS_SALT}|${day}|${ip}|${ua}`));
  const visitor = [...new Uint8Array(hash).slice(0, 8)].map(b => b.toString(16).padStart(2, '0')).join('');
  await env.DB.prepare('INSERT INTO events (ts, kind, what, visitor, country, city, device, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?)')
    .bind(now, kind, what, visitor, cf.country || '', String(cf.city || '').slice(0, 60), device(ua), src).run();
  if (Math.random() < .01) await env.DB.prepare('DELETE FROM events WHERE ts < ?').bind(now - 400 * DAY).run();
}

function device(ua) {
  if (/iPad/.test(ua)) return 'iPad';
  if (/iPhone|iPod/.test(ua)) return 'iPhone';
  if (/Android/.test(ua)) return 'Android';
  if (/Windows/.test(ua)) return 'Windows';
  if (/CrOS/.test(ua)) return 'Chromebook';
  if (/Macintosh|Mac OS X/.test(ua)) return 'Mac';
  if (/curl|wget/i.test(ua)) return 'Terminal';
  if (/Linux/.test(ua)) return 'Linux';
  return 'Other';
}

// where the visitor came from: ?utm_source=, the app that opened the link, or the site that linked here
function source(ref, utm, req) {
  if (utm) return SHARE_NAME[utm] || String(utm).slice(0, 40);
  const ua = req.headers.get('user-agent') || '';
  if (/FBAN|FBAV/.test(ua)) return 'Facebook';
  if (/Instagram/.test(ua)) return 'Instagram';
  if (/LinkedInApp/.test(ua)) return 'LinkedIn';
  let host = '';
  try { host = new URL(ref).hostname.replace(/^www\./, '') } catch { return '' }
  if (!host || host === 'droplet.noxeratech.com') return '';
  const known = { 'com.whatsapp': 'WhatsApp', 'web.whatsapp.com': 'WhatsApp', 'wa.me': 'WhatsApp', 't.co': 'X (Twitter)', 'x.com': 'X (Twitter)', 'lnkd.in': 'LinkedIn',
    'linkedin.com': 'LinkedIn', 'facebook.com': 'Facebook', 'm.facebook.com': 'Facebook', 'l.facebook.com': 'Facebook', 'lm.facebook.com': 'Facebook',
    'instagram.com': 'Instagram', 'l.instagram.com': 'Instagram', 'github.com': 'GitHub', 'com.google.android.gm': 'Gmail', 'mail.google.com': 'Gmail',
    'org.telegram.messenger': 'Telegram', 't.me': 'Telegram', 'tiktok.com': 'TikTok', 'youtube.com': 'YouTube', 'reddit.com': 'Reddit' };
  if (known[host]) return known[host];
  if (/(^|\.)google\./.test(host) || host === 'com.google.android.googlequicksearchbox') return 'Google';
  if (/(^|\.)bing\.com$/.test(host)) return 'Bing';
  return host.slice(0, 60);
}

// ---------------------------------------------------------------- the stats page

async function stats(req, env, ctx) {
  const auth = req.headers.get('authorization') || '';
  let ok = false;
  try { ok = auth.startsWith('Basic ') && atob(auth.slice(6)).split(':').slice(1).join(':') === env.STATS_PASSWORD } catch {}
  if (!ok || !env.STATS_PASSWORD) return new Response('Sign in to see the stats.', { status: 401, headers: { 'WWW-Authenticate': 'Basic realm="droplet stats", charset="UTF-8"' } });

  const now = Date.now(), today = Math.floor((now + TZ) / DAY) * DAY - TZ;
  const q = (sql, ...a) => env.DB.prepare(sql).bind(...a).all().then(r => r.results);
  const totals = (from, to) => q("SELECT kind, COUNT(DISTINCT visitor) u, COUNT(*) n FROM events WHERE ts >= ? AND ts < ? GROUP BY kind", from, to);
  const m30 = today - 29 * DAY;
  const [tToday, tYest, t7, t30, daily, byWhat, countries, devices, sources, recent, gh] = await Promise.all([
    totals(today, now + 1), totals(today - DAY, today), totals(today - 6 * DAY, now + 1), totals(m30, now + 1),
    q("SELECT CAST((ts + ?) / ? AS INTEGER) d, kind, COUNT(DISTINCT visitor) u, COUNT(*) n FROM events WHERE ts >= ? GROUP BY d, kind", TZ, DAY, m30),
    q("SELECT what, COUNT(*) n FROM events WHERE kind = 'download' AND ts >= ? GROUP BY what ORDER BY n DESC", m30),
    q("SELECT country, COUNT(DISTINCT visitor) n FROM events WHERE ts >= ? GROUP BY country ORDER BY n DESC LIMIT 10", m30),
    q("SELECT device, COUNT(DISTINCT visitor) n FROM events WHERE ts >= ? GROUP BY device ORDER BY n DESC", m30),
    q("SELECT source, COUNT(DISTINCT visitor) n FROM events WHERE kind = 'visit' AND ts >= ? GROUP BY source ORDER BY n DESC LIMIT 10", m30),
    q("SELECT ts, kind, what, visitor, country, city, device, source FROM events ORDER BY ts DESC LIMIT 25"),
    github(ctx),
  ]);
  const body = page({ now, today, tToday, tYest, t7, t30, daily, byWhat, countries, devices, sources, recent, gh });
  return new Response(body, { headers: {
    'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'x-robots-tag': 'noindex',
    'set-cookie': `${ME}; Path=/; Max-Age=31536000; SameSite=Lax; Secure; HttpOnly`,
  } });
}

// all-time download counts from GitHub (they include the site's downloads, which go there)
async function github(ctx) {
  const key = new Request('https://droplet.noxeratech.com/__gh-releases');
  let res = await caches.default.match(key);
  if (!res) {
    try {
      const r = await fetch('https://api.github.com/repos/Ferinmtk/droplet/releases?per_page=100', { headers: { 'user-agent': 'droplet-stats', accept: 'application/vnd.github+json' } });
      if (!r.ok) return null;
      res = new Response(await r.text(), { headers: { 'content-type': 'application/json', 'cache-control': 'max-age=900' } });
      ctx.waitUntil(caches.default.put(key, res.clone()));
    } catch { return null }
  }
  const out = { android: 0, windows: 0, installer: 0 };
  for (const rel of await res.json()) for (const a of rel.assets || []) {
    if (a.name.endsWith('.apk')) out.android += a.download_count;
    else if (a.name.endsWith('.exe')) out.windows += a.download_count;
    else if (a.name === 'install-agent.sh') out.installer += a.download_count;
  }
  return out;
}

const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const names = new Intl.DisplayNames(['en'], { type: 'region' });
const country = c => { try { return c && c !== 'XX' && c !== 'T1' ? names.of(c) : 'Unknown' } catch { return c || 'Unknown' } };
const flag = c => /^[A-Z]{2}$/.test(c || '') && c !== 'XX' && c !== 'T1' ? String.fromCodePoint(...[...c].map(x => 0x1F1A5 + x.charCodeAt(0))) : '🌐';
const pick = (rows, kind, f) => (rows.find(r => r.kind === kind) || { u: 0, n: 0 })[f];
const ago = (now, ts) => { const s = Math.round((now - ts) / 1000); return s < 60 ? 'just now' : s < 3600 ? `${Math.floor(s / 60)} min ago` : s < DAY / 1000 ? `${Math.floor(s / 3600)} h ago` : `${Math.floor(s / 86400)} d ago` };
const clock = ts => new Date(ts + TZ).toISOString().slice(11, 16);

function bars(rows, label, total) {
  if (!rows.length) return '<p class="empty">Nothing yet.</p>';
  const max = Math.max(...rows.map(r => r.n));
  return '<ul class="bars">' + rows.map(r => `<li><span class="bl">${label(r)}</span><span class="bt"><i style="width:${(100 * r.n / max).toFixed(1)}%"></i></span><b>${r.n}</b></li>`).join('') + '</ul>';
}

function page(d) {
  const card = (title, rows, sub) => `<div class="card"><h3>${title}</h3><div class="nums"><div><b>${pick(rows, 'visit', 'u')}</b><span>visitors</span></div><div><b>${pick(rows, 'download', 'n')}</b><span>downloads</span></div></div>${sub ? `<p class="sub">${sub}</p>` : ''}</div>`;
  // 30-day chart
  const first = Math.floor((d.today + TZ) / DAY) - 29, W = 720, H = 180, bw = W / 30;
  const vis = Array(30).fill(0), dls = Array(30).fill(0);
  for (const r of d.daily) { const i = r.d - first; if (i >= 0 && i < 30) (r.kind === 'visit' ? vis : dls)[i] = r.kind === 'visit' ? r.u : r.n }
  const max = Math.max(4, ...vis, ...dls);
  let chart = `<svg viewBox="0 0 ${W} ${H + 24}" role="img" aria-label="Visitors and downloads per day, last 30 days">`;
  for (const g of [0, .5, 1]) chart += `<line x1="0" x2="${W}" y1="${H - H * g}" y2="${H - H * g}" class="grid"/><text x="0" y="${H - H * g - 4}" class="ax">${Math.round(max * g) || ''}</text>`;
  for (let i = 0; i < 30; i++) {
    const x = i * bw, hv = H * vis[i] / max, hd = H * dls[i] / max, day = new Date((first + i) * DAY);
    chart += `<g><title>${day.toISOString().slice(5, 10)}: ${vis[i]} visitors, ${dls[i]} downloads</title><rect x="${x + 3}" y="${H - hv}" width="${bw * .5 - 3}" height="${hv}" rx="2" class="cv"/><rect x="${x + bw * .5}" y="${H - hd}" width="${bw * .5 - 3}" height="${hd}" rx="2" class="cd"/></g>`;
    if (i % 5 === 4 || i === 29) chart += `<text x="${x + bw / 2}" y="${H + 18}" text-anchor="middle" class="ax">${i === 29 ? 'today' : day.getUTCDate() + ' ' + day.toLocaleString('en', { month: 'short', timeZone: 'UTC' })}</text>`;
  }
  chart += '</svg>';
  // newcomers: one line per event, newest first
  const feed = d.recent.length ? '<ol class="feed">' + d.recent.map(e => {
    const where = [e.city, country(e.country)].filter(Boolean).join(', ');
    const act = e.kind === 'download' ? `<span class="tag dl">downloaded the ${esc(WHAT[e.what] || e.what)}</span>` : `<span class="tag">visited${e.what !== '/' ? ' ' + esc(e.what) : ''}</span>`;
    return `<li><time title="${clock(e.ts)}">${ago(d.now, e.ts)}</time><span class="who">${flag(e.country)} ${esc(where)} · ${esc(e.device)}</span>${act}${e.source ? `<span class="from">from ${esc(e.source)}</span>` : ''}<code title="Same code = same person, that day">${esc(e.visitor.slice(0, 4))}</code></li>`;
  }).join('') + '</ol>' : '<p class="empty">No visits yet. They show up here within a few seconds.</p>';
  const gh = d.gh ? `<p class="sub">All time on GitHub: <b>${d.gh.android}</b> Android · <b>${d.gh.windows}</b> Windows · <b>${d.gh.installer}</b> Linux/Mac installer. Store installs are in <a href="https://partner.microsoft.com/dashboard" rel="noopener">Partner Center</a>.</p>` : '';
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">
<title>droplet stats</title><link rel="icon" href="/favicon-32.png"><meta http-equiv="refresh" content="60">
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;600;700;800&display=swap">
<style>
:root{--bg:#f5f7fa;--card:#fff;--ink:#1d2433;--muted:#5a6273;--line:#e6e9ef;--v:#2f6fdb;--d:#1f9e6a;--dbg:#d5f5e6;--dt:#14694a;--vbg:#eef5ff;--font:"Plus Jakarta Sans",ui-sans-serif,system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
@media (prefers-color-scheme:dark){:root{--bg:#0f141c;--card:#171e29;--ink:#e8edf5;--muted:#9aa5b8;--line:#263142;--v:#6ea1ff;--d:#3fd39a;--dbg:#123a2b;--dt:#7ee8bd;--vbg:#18263d;color-scheme:dark}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 var(--font);-webkit-font-smoothing:antialiased}
main{max-width:1100px;margin:0 auto;padding:28px max(16px,env(safe-area-inset-left)) 48px}
header{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:20px}header img{width:36px;height:36px;border-radius:9px}
h1{font-size:24px;margin:0;letter-spacing:-.02em}header p{margin:0 0 0 auto;color:var(--muted);font-size:13px}
h2{font-size:16px;margin:0 0 12px;letter-spacing:-.01em}h3{font-size:13px;margin:0;color:var(--muted);font-weight:700;text-transform:uppercase;letter-spacing:.06em}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;margin-bottom:12px}
.card,.panel{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:18px;min-width:0}
.nums{display:flex;gap:22px;margin-top:8px}.nums b{display:block;font-size:30px;font-weight:800;letter-spacing:-.03em;line-height:1.1}.nums div:last-child b{color:var(--d)}.nums span{color:var(--muted);font-size:13px}
.sub{color:var(--muted);font-size:13px;margin:10px 0 0}.sub b{color:var(--ink)}a{color:var(--v)}
.grid2{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:12px;margin-top:12px}
.legend{display:flex;gap:16px;font-size:13px;color:var(--muted);margin:-4px 0 8px}.legend i{display:inline-block;width:10px;height:10px;border-radius:3px;margin-right:6px;vertical-align:-1px}
svg{width:100%;height:auto;display:block}.grid{stroke:var(--line)}.ax{fill:var(--muted);font-size:11px}.cv{fill:var(--v)}.cd{fill:var(--d)}
.bars{list-style:none;margin:0;padding:0;display:grid;gap:8px}.bars li{display:grid;grid-template-columns:minmax(0,1.3fr) 2fr auto;gap:10px;align-items:center;font-size:14px}
.bl{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.bt{height:8px;background:var(--vbg);border-radius:4px;overflow:hidden}.bt i{display:block;height:100%;background:var(--v);border-radius:4px}.bars b{font-variant-numeric:tabular-nums;min-width:2ch;text-align:right}
.feed{list-style:none;margin:0;padding:0}.feed li{display:flex;flex-wrap:wrap;gap:4px 10px;align-items:baseline;padding:10px 0;border-top:1px solid var(--line);font-size:14px}.feed li:first-child{border-top:0}
.feed time{color:var(--muted);font-size:12.5px;min-width:72px}.who{font-weight:600}.tag{background:var(--vbg);color:var(--v);border-radius:999px;padding:1px 10px;font-size:12.5px;font-weight:700}.tag.dl{background:var(--dbg);color:var(--dt)}
.from{color:var(--muted);font-size:13px}.feed code{margin-left:auto;color:var(--muted);font-size:11.5px}
.empty{color:var(--muted);margin:0}.note{color:var(--muted);font-size:12.5px;margin-top:18px}
</style></head><body><main>
<header><img src="/apple-touch-icon.png" alt=""><h1>droplet stats</h1><p>Nairobi time · ${clock(d.now)} · refreshes every minute</p></header>
<div class="cards">${card('Today', d.tToday)}${card('Yesterday', d.tYest)}${card('Last 7 days', d.t7)}${card('Last 30 days', d.t30, gh)}</div>
<section class="panel"><h2>Last 30 days</h2><div class="legend"><span><i style="background:var(--v)"></i>Visitors</span><span><i style="background:var(--d)"></i>Downloads</span></div>${chart}</section>
<div class="grid2">
<section class="panel"><h2>Newcomers</h2>${feed}</section>
<div style="display:grid;gap:12px;align-content:start">
<section class="panel"><h2>Downloads, last 30 days</h2>${bars(d.byWhat, r => esc(WHAT[r.what] || r.what))}</section>
<section class="panel"><h2>Countries</h2>${bars(d.countries, r => `${flag(r.country)} ${esc(country(r.country))}`)}</section>
<section class="panel"><h2>Devices</h2>${bars(d.devices, r => esc(r.device))}</section>
<section class="panel"><h2>Where they came from</h2>${bars(d.sources, r => esc(r.source || 'Direct (typed, or a link in WhatsApp or SMS)'))}</section>
</div></div>
<p class="note">Counts visits to droplet.noxeratech.com and clicks on its download links. No IP addresses or cookies are kept for visitors. The four-letter code shows the same person within one day. Your own visits from this browser aren't counted. Search engines and link previews are left out.</p>
</main></body></html>`;
}
