// The iPhone link's transport against real browser engines: the web app's own
// rtc.js connects to the Go echo peer (cmd/echo), as the iPhone connects to
// the Android app.
//
//   go build -o /tmp/echo ./cmd/echo
//   PLAYWRIGHT_DIR=<node_modules with playwright> ECHO=/tmp/echo node test_browser.mjs webkit|chromium [address]
//
// (HEADLESS or WEBKIT: a browser binary other than the one Playwright expects.) Use a LAN
// address: browsers won't send WebRTC to 127.0.0.1.
import { createRequire } from "module";
import { spawn } from "child_process";
import { createServer } from "http";
import { readFileSync } from "fs";
import path from "path";

const require = createRequire(path.join(process.env.PLAYWRIGHT_DIR, "x.js"));
const pw = require("playwright-core");
const engine = process.argv[2] || "chromium";
const address = process.argv[3] || "127.0.0.1";
const site = path.resolve(path.dirname(new URL(import.meta.url).pathname), "../../site");

const echo = spawn(process.env.ECHO, [], { stdio: ["ignore", "pipe", "inherit"] });
const info = await new Promise(r => echo.stdout.once("data", d => r(JSON.parse(d.toString()))));
const web = createServer((req, res) => {
  if (req.url === "/blank.html") { res.setHeader("content-type", "text/html"); return res.end("<!doctype html><title>t</title>"); }
  try {
    const body = readFileSync(path.join(site, decodeURIComponent(req.url.split("?")[0])));
    res.setHeader("content-type", req.url.endsWith(".js") ? "text/javascript" : "application/octet-stream");
    res.end(body);
  } catch { res.statusCode = 404; res.end(); }
}).listen(0, "127.0.0.1");
await new Promise(r => web.once("listening", r));

const exe = engine === "chromium" ? process.env.HEADLESS : process.env.WEBKIT;
const opts = exe ? { executablePath: exe } : {};
const browser = await pw[engine].launch(opts);
const page = await browser.newPage();
await page.goto(`http://127.0.0.1:${web.address().port}/blank.html`);
const results = await page.evaluate(async ({ port, fp, address }) => {
  const rtc = await import("/app/rtc.js");
  const out = [];
  const ok = (name, cond, extra = "") => out.push(`${cond ? "PASS" : "FAIL"} ${name}${extra ? " " + extra : ""}`);
  const next = dc => new Promise((res, rej) => { const t = setTimeout(() => rej(new Error("timeout")), 10000); dc.onmessage = e => { clearTimeout(t); res(e.data); }; });
  const t0 = performance.now();
  const { pc, dc, localFp } = await rtc.connect({ address, port, fp });
  ok("connects", true, `${Math.round(performance.now() - t0)} ms`);
  let p = next(dc); dc.send("fp?"); ok("sees the browser's DTLS fingerprint", (await p) === localFp);
  p = next(dc); dc.send("hello ✓"); ok("text both ways", (await p) === "hello ✓");
  const big = "é".repeat(120 * 1024); // 240 KB of UTF-8
  p = next(dc); dc.send(big); ok("a 240 KB frame both ways", (await p) === big);
  // 2 MB in 16 KB binary frames, echoed
  const N = 128, got = []; let done;
  const all = new Promise(r => (done = r));
  dc.onmessage = e => { got.push(new Uint8Array(e.data)); if (got.length === N) done(); };
  const t1 = performance.now();
  for (let i = 0; i < N; i++) {
    const b = new Uint8Array(16 * 1024 + 8); b.fill(i & 255); b[0] = i;
    while (dc.bufferedAmount > 1024 * 1024) await new Promise(r => setTimeout(r, 5));
    dc.send(b);
  }
  await all;
  const secs = (performance.now() - t1) / 1000;
  ok("2 MB of binary frames, in order", got.every((b, i) => b[0] === i && b.length === 16 * 1024 + 8 && b[100] === (i & 255)), `${(4 / secs).toFixed(1)} MB/s round trip`);
  // several browsers at once
  const more = await Promise.all([0, 1, 2].map(() => rtc.connect({ address, port, fp })));
  const echoes = await Promise.all(more.map((c, i) => { const q = next(c.dc); c.dc.send("n" + i); return q; }));
  ok("three more at once", echoes.join() === "n0,n1,n2");
  more.forEach(c => c.pc.close());
  // a wrong fingerprint never opens
  let refused = false;
  try { await rtc.connect({ address, port, fp: "00".repeat(32), timeout: 4000 }); } catch { refused = true; }
  ok("a wrong fingerprint is refused (DTLS pinning)", refused);
  pc.close();
  return out;
}, { port: info.port, fp: info.fp, address });
console.log(`${engine} → ${address}:${info.port}`);
results.forEach(r => console.log("  " + r));
await browser.close(); web.close(); echo.kill();
process.exit(results.every(r => r.startsWith("PASS")) ? 0 : 1);
