// End to end: the iPhone web app (site/app) in a real browser engine, against a real agent.
// Not collected by pytest.
//
//   npm install playwright@1.64 && npx playwright install webkit chromium-headless-shell
//   PLAYWRIGHT_DIR=<where it's installed> PYTHON=<a python with aiortc> node agent/tests/e2e_iphone.mjs webkit
//
// The agent is `droplet-agent run --dry-run` with its own HOME and XDG directories under
// $E2E_TMP (default /tmp; Unix socket paths are short), the iPhone link on. The page is
// served from site/ on localhost (a secure context, as the real site is over HTTPS). The
// camera isn't used: the pairing link from the agent's QR code is pasted instead.
//
// It pairs (the code on both sides must match), sends a message each way, a file each way
// (checked by SHA-256), reconnects after a reload (the key survives in IndexedDB), and
// unpairs from the computer. Screenshots go to $SHOTS if it's set.

import { createRequire } from "node:module";
import { spawn } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(process.env.PLAYWRIGHT_DIR ? path.join(process.env.PLAYWRIGHT_DIR, "x.js") : import.meta.url);
const playwright = require("playwright");

const browserName = process.argv[2] || "webkit";
const AGENT_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const SITE = path.resolve(AGENT_DIR, "..", "site");
const PYTHON = process.env.PYTHON || "python3";
const SHOTS = process.env.SHOTS || "";
const ROOT = fs.mkdtempSync(path.join(process.env.E2E_TMP || "/tmp", "di-"));
const checks = [];

function check(name, ok, detail = "") {
  checks.push([name, !!ok]);
  console.log(`  ${ok ? "PASS" : "FAIL"}  ${name}${!ok && detail ? "  " + String(detail).slice(0, 400) : ""}`);
  return ok;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitUntil(fn, timeout = 20000, every = 200) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    try { const v = await fn(); if (v) return v; } catch (_) {}
    await sleep(every);
  }
  return null;
}

// --- the agent ---
const home = path.join(ROOT, "h");
const runtime = path.join(ROOT, "r");
const downloads = path.join(ROOT, "Downloads");
fs.mkdirSync(path.join(home, ".config/droplet-agent"), { recursive: true });
fs.mkdirSync(runtime, { mode: 0o700 });
const env = { ...process.env, HOME: home, XDG_CONFIG_HOME: path.join(home, ".config"), XDG_DATA_HOME: path.join(home, ".local/share"),
  XDG_RUNTIME_DIR: runtime, PYTHONPATH: AGENT_DIR };
for (const k of ["DBUS_SESSION_BUS_ADDRESS", "WAYLAND_DISPLAY", "DISPLAY"]) delete env[k];

function control(req, timeout = 30000) {
  return new Promise((resolve, reject) => {
    const s = net.createConnection(path.join(runtime, "droplet-agent/control.sock"));
    let buf = "";
    const t = setTimeout(() => { s.destroy(); reject(new Error("control timeout")); }, timeout);
    s.on("connect", () => s.write(JSON.stringify(req) + "\n"));
    s.on("data", (d) => { buf += d; if (buf.includes("\n")) { clearTimeout(t); s.end(); resolve(JSON.parse(buf.slice(0, buf.indexOf("\n")))); } });
    s.on("error", (e) => { clearTimeout(t); reject(e); });
  });
}

// --- the site, as the real one would serve it ---
const TYPES = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".png": "image/png", ".svg": "image/svg+xml", ".webmanifest": "application/manifest+json" };
const server = http.createServer((req, res) => {
  const p = path.normalize(decodeURIComponent(new URL(req.url, "http://x").pathname));
  let f = path.join(SITE, p);
  if (!f.startsWith(SITE)) { res.writeHead(403).end(); return; }
  if (fs.existsSync(f) && fs.statSync(f).isDirectory()) f = path.join(f, "index.html");
  if (!fs.existsSync(f)) { res.writeHead(404).end(); return; }
  res.writeHead(200, { "Content-Type": TYPES[path.extname(f)] || "application/octet-stream" });
  fs.createReadStream(f).pipe(res);
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const siteUrl = `http://localhost:${server.address().port}/app/`;

fs.writeFileSync(path.join(home, ".config/droplet-agent/config.json"), JSON.stringify({
  device: { id: "", name: "t15-e2e" },
  mesh: { announce: false, port: 1771, downloads },
  iphone: { enabled: true, app_url: siteUrl },
}));
const logFile = fs.openSync(path.join(ROOT, "agent.log"), "a");
const agent = spawn(PYTHON, ["-m", "droplet_agent", "run", "--dry-run", "-v"], { env, cwd: AGENT_DIR, stdio: ["ignore", logFile, logFile] });
const agentLog = () => fs.readFileSync(path.join(ROOT, "agent.log"), "utf8");

let browser;
const consoleErrors = [];
const shot = async (page, name) => { if (SHOTS) { fs.mkdirSync(SHOTS, { recursive: true }); await page.screenshot({ path: path.join(SHOTS, `${browserName}-${name}.png`) }); } };

try {
  const st = await waitUntil(async () => { const s = await control({ cmd: "status" }); return s.webrtc ? s : null; }, 30000);
  check("the agent runs with the iPhone link on", st && st.webrtc && st.webrtc.port, st && JSON.stringify(st.webrtc));
  console.log(`  agent: mesh port ${st.port}, UDP port ${st.webrtc.port}, fingerprint ${st.fp.slice(0, 16)}…`);

  browser = await playwright[browserName].launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: browserName === "chromium" });
  const page = await context.newPage();
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });
  page.on("pageerror", (e) => consoleErrors.push(String(e)));
  await page.goto(siteUrl);
  await page.waitForSelector("#home:not([hidden])");
  check("the app starts with no computers", await page.isVisible("#empty"));
  await shot(page, "1-home-empty");
  const sw = await page.evaluate(async () => { const r = await navigator.serviceWorker.ready; return !!r.active; });
  check("the service worker is installed (offline after the first visit)", sw);

  // --- pair ---
  const qr = await control({ cmd: "qr" });
  check("the agent makes a QR link", qr.link && qr.link.startsWith(siteUrl + "#pair="), JSON.stringify(qr));
  await page.click("#pair-start");
  await page.waitForSelector("#pair:not([hidden])");
  await page.click("#pair summary");
  await sleep(300);
  await shot(page, "2-pair-scan");
  await page.fill("#pair-link", qr.link);
  await page.click("#pair-go");
  const code = await waitUntil(async () => { const t = await page.textContent("#pair-code"); return /^\d{4}$/.test(t) ? t : null; }, 20000);
  const waiting = await waitUntil(async () => (await control({ cmd: "status" })).incoming.find((r) => r.kind === "browser"), 10000);
  check("both sides show the same 4-digit code", code && waiting && waiting.code === code, `${code} vs ${waiting && waiting.code} ${await page.textContent("#pair-error")}`);
  await shot(page, "3-pair-code");
  await page.click("#pair-match");
  const ans = await control({ cmd: "pair-answer", request: waiting.request, accept: true });
  check("the computer accepts", ans.state === "accepted", JSON.stringify(ans));
  await page.waitForSelector("#peer:not([hidden])", { timeout: 15000 });
  const connected = await waitUntil(async () => (await page.textContent("#peer-state")) === "Connected", 15000);
  check("the app is paired and connected", connected, await page.textContent("#peer-state"));
  const st2 = await control({ cmd: "status" });
  const me = st2.peers.find((p) => p.source === "browser");
  check("the computer lists it as a peer, linked over WebRTC", me && me.link && me.link.startsWith("webrtc"), JSON.stringify(st2.peers));

  // --- messages ---
  await page.fill("#composer", "hello from the iPhone");
  await page.press("#composer", "Enter");
  const got = await waitUntil(async () => (await control({ cmd: "chat" })).messages.find((m) => m.dir === "in" && m.body === "hello from the iPhone"), 10000);
  check("a message from the app reaches the computer's chat", got);
  const sent = await control({ cmd: "text", peer: me.name, body: "hello from linux ✓", wait: 15 });
  check("a message from the computer is delivered directly", sent.state === "done" && sent.route === "webrtc", JSON.stringify(sent));
  const shown = await waitUntil(async () => (await page.textContent("#messages")).includes("hello from linux ✓"), 10000);
  check("…and shows in the app", shown);

  // --- files ---
  const up = crypto.randomBytes(3 * 1024 * 1024 + 123);
  const t0 = Date.now();
  await page.setInputFiles("#file-input", { name: "photo from iphone.jpg", mimeType: "image/jpeg", buffer: up });
  const upDone = await waitUntil(async () => (await page.textContent("#transfers")).includes("Sent"), 60000);
  const upMs = Date.now() - t0;
  const saved = path.join(downloads, "photo from iphone.jpg");
  const upOk = upDone && fs.existsSync(saved) && crypto.createHash("sha256").update(fs.readFileSync(saved)).digest("hex") === crypto.createHash("sha256").update(up).digest("hex");
  check(`a 3 MB file from the app lands in the computer's downloads, intact (${upMs} ms)`, upOk, await page.textContent("#transfers"));
  const rec = await control({ cmd: "received" });
  check("…and in its list of received files", rec.files.some((f) => f.name === "photo from iphone.jpg" && f.from === me.name), JSON.stringify(rec.files));

  const down = crypto.randomBytes(2 * 1024 * 1024 + 77);
  const src = path.join(ROOT, "report from linux.pdf");
  fs.writeFileSync(src, down);
  const t1 = Date.now();
  const job = await control({ cmd: "send-file", peer: me.name, path: src, wait: 60 }, 70000);
  const downMs = Date.now() - t1;
  check(`a 2 MB file from the computer is delivered directly (${downMs} ms)`, job.state === "done" && job.route === "webrtc", JSON.stringify(job));
  await page.waitForSelector("#received li", { timeout: 10000 });
  const hash = await page.evaluate(async () => {
    const list = await window.droplet.db.files.forPeer([...window.droplet.sessions.keys()][0]);
    const f = list.find((x) => x.name === "report from linux.pdf");
    if (!f) return null;
    const buf = f.data;
    return [...new Uint8Array(await crypto.subtle.digest("SHA-256", buf))].map((b) => b.toString(16).padStart(2, "0")).join("");
  });
  check("…and the app has it, intact", hash === crypto.createHash("sha256").update(down).digest("hex"), hash);
  await shot(page, "4-peer");

  // --- the key survives, and it reconnects ---
  await page.reload();
  await page.waitForSelector("#home:not([hidden])");
  const again = await waitUntil(async () => (await page.textContent("#devices")).includes("Connected"), 20000);
  check("after a reload it reconnects and proves itself with the stored key", again, await page.textContent("#devices"));
  await shot(page, "5-home-connected");

  // a browser with another key can't get in
  const other = await browser.newContext();
  const op = await other.newPage();
  await op.goto(siteUrl);
  const refused = await op.evaluate(async ({ fp, addresses, port }) => {
    const { Session } = await import("./proto.js");
    const { identity } = await import("./crypto.js");
    const s = new Session({ fp, addresses, port }, await identity(), "intruder");
    await s.open();
    try { await s.auth(); return "accepted"; } catch (e) { return e.unpaired ? "refused, unpaired" : "refused: " + e.message; }
  }, { fp: qr.fp, addresses: qr.addresses, port: qr.port });
  check("a browser that isn't paired is refused", refused === "refused, unpaired", refused);
  await other.close();

  // --- unpair from the computer ---
  const un = await control({ cmd: "unpair", peer: me.name });
  check("unpairing on the computer tells the app", un.told, JSON.stringify(un));
  const gone = await waitUntil(async () => page.isVisible("#empty"), 10000);
  check("…which forgets the computer", gone);
  check("no errors in the page", consoleErrors.length === 0, consoleErrors.join(" | "));
} catch (e) {
  check("the run finished", false, e.stack || e);
} finally {
  if (browser) await browser.close();
  agent.kill("SIGTERM");
  await new Promise((r) => { agent.on("exit", r); setTimeout(r, 8000); });
  server.close();
  const failed = checks.filter(([, ok]) => !ok);
  if (failed.length && consoleErrors.length) console.log("\n--- page errors ---\n" + consoleErrors.join("\n"));
  if (failed.length) console.log("\n--- agent log (tail) ---\n" + agentLog().split("\n").slice(-40).join("\n"));
  if (!process.env.KEEP) fs.rmSync(ROOT, { recursive: true, force: true });
  console.log(`\n${browserName}: ${checks.length - failed.length}/${checks.length} passed`);
  process.exit(failed.length ? 1 : 0);
}
