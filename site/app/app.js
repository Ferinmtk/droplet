// droplet for iPhone: the screens, and keeping a connection to each paired computer
// while the app is open (it's foreground-only: iOS suspends a web app in the background).

import { identity, parsePairing } from "./crypto.js";
import { Session } from "./proto.js";
import { scan } from "./scan.js";
import * as db from "./store.js";

const $ = (id) => document.getElementById(id);
const el = (tag, props = {}, ...kids) => {
  const e = Object.assign(document.createElement(tag), props);
  for (const k of kids) if (k != null) e.append(k);
  return e;
};

let me = null;
let myName = "iPhone";
const sessions = new Map();     // fp → Session (open, authenticated)
const status = new Map();       // fp → "connecting" | "connected" | "offline" | error text
const retry = new Map();        // fp → timer
let current = null;             // the peer being shown
let scanning = null;

// --- small things ---
function toast(text, ms = 3500) {
  const t = $("toast");
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { t.hidden = true; }, ms);
}

function size(n) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 ** 2) return `${(n / 1024).toFixed(0)} KB`;
  if (n < 1024 ** 3) return `${(n / 1024 ** 2).toFixed(1)} MB`;
  return `${(n / 1024 ** 3).toFixed(2)} GB`;
}

const when = (ts) => new Date(ts * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

function show(view) {
  for (const v of document.querySelectorAll(".view")) v.hidden = v.id !== view;
  if (view !== "pair" && scanning) { scanning.stop(); scanning = null; }
}

// --- connections ---
function stateText(fp) {
  const s = status.get(fp);
  if (s === "connected") return "Connected";
  if (s === "connecting") return "Connecting…";
  if (s && s !== "offline") return s;
  return "Not reachable: is droplet running on it, on this Wi-Fi?";
}

function setStatus(fp, s) {
  status.set(fp, s);
  renderDevices();
  if (current && current.fp === fp) renderPeerState();
}

async function connectPeer(peer) {
  if (document.hidden || sessions.has(peer.fp) || status.get(peer.fp) === "connecting") return;
  clearTimeout(retry.get(peer.fp));
  setStatus(peer.fp, "connecting");
  const s = new Session(peer, me, myName);
  try {
    await s.open();
    await s.auth();
  } catch (e) {
    s.close();
    if (e.unpaired) { setStatus(peer.fp, "This computer doesn't know this iPhone any more: pair again."); return; }
    setStatus(peer.fp, "offline");
    scheduleRetry(peer);
    return;
  }
  attach(peer, s);
}

function scheduleRetry(peer, ms = 5000) {
  clearTimeout(retry.get(peer.fp));
  if (!document.hidden) retry.set(peer.fp, setTimeout(() => connectPeer(peer), ms));
}

function attach(peer, s) {
  sessions.set(peer.fp, s);
  if (s.hello && s.hello.name && s.hello.name !== peer.name) { peer.name = s.hello.name; db.peers.put(peer); }
  if (s.address && peer.addresses[0] !== s.address) {
    // the address that worked goes first next time
    peer.addresses = [s.address, ...peer.addresses.filter((a) => a !== s.address)];
    db.peers.put(peer);
  }
  s.addEventListener("close", () => {
    if (sessions.get(peer.fp) === s) sessions.delete(peer.fp);
    setStatus(peer.fp, "offline");
    scheduleRetry(peer, 2000);
  });
  s.addEventListener("text", async (e) => {
    const m = e.detail;
    const id = `${peer.fp}:in:${m.id}`;
    if (await db.messages.get(id)) return;
    await db.messages.put({ id, fp: peer.fp, dir: "in", body: m.body, ts: m.ts || Date.now() / 1000 });
    if (current && current.fp === peer.fp) renderMessages();
    else toast(`${peer.name}: ${m.body.slice(0, 80)}`);
  });
  s.addEventListener("file-progress", (e) => {
    if (current && current.fp === peer.fp) transferRow(`in-${e.detail.id}`, `↓ ${e.detail.name}`, e.detail.got, e.detail.size);
  });
  s.keepFile = async (f) => {
    if (await db.files.get(f.id)) return;
    // kept as bytes, not a Blob: WebKit can't put a Blob in IndexedDB in every context
    const data = await f.blob.arrayBuffer();
    await db.files.put({ id: f.id, fp: peer.fp, name: f.name, size: f.size, mime: f.mime, data, ts: Date.now() / 1000 });
  };
  s.addEventListener("file", (e) => {
    const f = e.detail;
    const row = document.getElementById(`t-in-${f.id}`);
    if (row) row.remove();
    if (current && current.fp === peer.fp) renderReceived();
    else toast(`${peer.name} sent ${f.name}`);
  });
  s.addEventListener("ring", () => toast(`${peer.name} is looking for this iPhone`, 8000));
  s.addEventListener("unpair", async () => {
    await forget(peer);
    toast(`${peer.name} unpaired this iPhone.`);
  });
  setStatus(peer.fp, "connected");
}

async function connectAll() {
  for (const p of await db.peers.all()) connectPeer(p);
}

function disconnectAll() {
  for (const t of retry.values()) clearTimeout(t);
  for (const s of [...sessions.values()]) s.close();
}

async function forget(peer) {
  const s = sessions.get(peer.fp);
  sessions.delete(peer.fp);
  if (s) s.close();
  clearTimeout(retry.get(peer.fp));
  status.delete(peer.fp);
  await db.peers.remove(peer.fp);
  if (current && current.fp === peer.fp) { current = null; show("home"); }
  renderDevices();
}

// --- home ---
async function renderDevices() {
  const list = await db.peers.all();
  const box = $("devices");
  box.replaceChildren(...list.sort((a, b) => a.name.localeCompare(b.name)).map((p) => {
    const on = status.get(p.fp) === "connected";
    const b = el("button", { className: "device" },
      el("i", { className: "dot" + (on ? " on" : "") }),
      el("div", {}, el("b", { textContent: p.name }), el("span", { textContent: stateText(p.fp) })));
    b.dataset.fp = p.fp;
    b.onclick = () => openPeer(p);
    return b;
  }));
  $("empty").hidden = list.length > 0;
}

// --- pairing ---
async function startPairView() {
  $("pair-scan").hidden = false;
  $("pair-code-step").hidden = true;
  $("pair-error").hidden = true;
  $("pair-link").value = "";
  $("my-name").value = myName;
  $("scan-status").textContent = "Point the camera at the QR code on the computer.";
  show("pair");
  if (scanning) scanning.stop();
  scanning = scan($("video"));
  scanning.result.then((text) => { scanning = null; pairWith(text); }, (e) => {
    if (e.message === "stopped") return;
    const why = e.name === "NotAllowedError" ? "droplet isn't allowed to use the camera (Settings → Safari → Camera)."
      : e.name === "NotFoundError" ? "There's no camera here." : "The camera isn't available.";
    $("scan-status").textContent = `${why} Paste the pairing link instead.`;
  });
}

function pairError(text) {
  $("pair-error").textContent = text;
  $("pair-error").hidden = false;
}

async function pairWith(text) {
  $("pair-error").hidden = true;
  let qr;
  try { qr = parsePairing(text); } catch (e) { pairError(e.message); return; }
  const name = $("my-name").value.trim().slice(0, 40) || "iPhone";
  if (name !== myName) { myName = name; await db.kv.set("name", name); }
  if (scanning) { scanning.stop(); scanning = null; }
  $("pair-scan").hidden = true;
  $("pair-code-step").hidden = false;
  $("pair-code").textContent = "";
  $("pair-with").textContent = `Connecting to ${qr.name}…`;
  $("pair-detail").textContent = "";
  $("pair-match").hidden = true;
  const peer = { fp: qr.fp, id: qr.id, name: qr.name, addresses: qr.addresses, port: qr.port };
  const s = new Session(peer, me, myName);
  let userSaid = null;
  const user = new Promise((resolve) => {
    $("pair-match").onclick = () => { userSaid = true; resolve(true); };
    $("pair-cancel").onclick = () => { userSaid = false; resolve(false); };
  });
  try {
    await s.open();
    const code = await s.pair(qr.token);
    $("pair-with").textContent = `Pairing with ${s.hello.name || qr.name}`;
    $("pair-code").textContent = code;
    $("pair-detail").textContent = "Check the computer shows the same four digits, and accept it there.";
    $("pair-match").hidden = false;
    const answer = s.answer();
    const ok = await user;
    if (!ok) { s.cancelPairing(); s.close(); startPairView(); return; }
    $("pair-match").hidden = true;
    $("pair-detail").textContent = `Waiting for ${s.hello.name || qr.name} to accept…`;
    const state = await answer;
    if (state !== "accepted") {
      s.close();
      $("pair-code-step").hidden = true;
      $("pair-scan").hidden = false;
      pairError(state === "denied" ? "The computer refused." : `Not paired: the request was ${state}.`);
      return;
    }
    peer.name = s.hello.name || qr.name;
    peer.pairedAt = Date.now() / 1000;
    await db.peers.put(peer);
    await s.auth();
    attach(peer, s);
    toast(`Paired with ${peer.name}`);
    openPeer(peer);
  } catch (e) {
    s.close();
    if (userSaid === false) return;
    $("pair-code-step").hidden = true;
    $("pair-scan").hidden = false;
    pairError(e.message || String(e));
  }
}

// --- one computer ---
async function openPeer(peer) {
  current = peer;
  $("peer-name").textContent = peer.name;
  renderPeerState();
  $("transfers").replaceChildren();
  show("peer");
  await Promise.all([renderMessages(), renderReceived()]);
  if (!sessions.has(peer.fp)) connectPeer(peer);
}

function renderPeerState() {
  const st = $("peer-state");
  st.textContent = stateText(current.fp);
  st.classList.toggle("on", status.get(current.fp) === "connected");
}

async function renderMessages() {
  if (!current) return;
  const list = await db.messages.forPeer(current.fp);
  $("messages").replaceChildren(...list.map((m) => el("li", { className: m.dir + (m.state === "failed" ? " failed" : "") },
    m.body, el("small", { textContent: m.state === "failed" ? `Not sent: ${m.error || "not connected"}` : when(m.ts) }))));
  const sc = $("peer-scroll");
  sc.scrollTop = sc.scrollHeight;
}

async function renderReceived() {
  if (!current) return;
  const list = await db.files.forPeer(current.fp);
  $("received-title").hidden = list.length === 0;
  $("received").replaceChildren(...list.map((f) => {
    const save = el("button", { className: "btn", textContent: "Save" });
    save.onclick = () => saveFile(f);
    const li = el("li", {}, el("div", {}, el("b", { textContent: f.name }), el("span", { textContent: `${size(f.size)} · ${when(f.ts)}` })), save);
    li.dataset.id = f.id;
    return li;
  }));
}

async function saveFile(f) {
  const file = new File([f.data || f.blob], f.name, { type: f.mime || "application/octet-stream" });
  // on iOS the share sheet is how a file reaches Files or Photos
  if (navigator.canShare && navigator.canShare({ files: [file] })) {
    try { await navigator.share({ files: [file] }); return; } catch (e) { if (e.name === "AbortError") return; }
  }
  const url = URL.createObjectURL(file);
  const a = el("a", { href: url, download: f.name });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}

function transferRow(key, label, done, total, failed) {
  let row = document.getElementById(`t-${key}`);
  if (!row) {
    row = el("div", { className: "transfer", id: `t-${key}` }, el("div", {}, el("b"), el("span"), el("progress", { max: 1 })));
    $("transfers").append(row);
  }
  row.querySelector("b").textContent = label;
  row.querySelector("span").textContent = failed ? failed : `${size(done)} of ${size(total)}`;
  const bar = row.querySelector("progress");
  bar.max = total || 1;
  bar.value = total ? done : 1;
  return row;
}

async function sendMessage() {
  const input = $("composer");
  const body = input.value.trim();
  if (!body || !current) return;
  const peer = current;
  const s = sessions.get(peer.fp);
  input.value = "";
  const rec = { id: `${peer.fp}:out:${Date.now()}:${Math.random().toString(16).slice(2, 8)}`, fp: peer.fp, dir: "out", body, ts: Date.now() / 1000 };
  try {
    if (!s) throw new Error("not connected");
    await s.sendText(body);
  } catch (e) {
    rec.state = "failed";
    rec.error = e.message;
  }
  await db.messages.put(rec);
  if (current === peer) renderMessages();
}

async function sendFiles(list) {
  const peer = current;
  const s = peer && sessions.get(peer.fp);
  for (const file of list) {
    const key = `out-${Math.random().toString(16).slice(2)}`;
    if (!s) { transferRow(key, `↑ ${file.name}`, 0, file.size, "Not sent: not connected"); continue; }
    transferRow(key, `↑ ${file.name}`, 0, file.size);
    try {
      await s.sendFile(file, (n) => transferRow(key, `↑ ${file.name}`, n, file.size));
      const row = transferRow(key, `↑ ${file.name}`, file.size, file.size);
      row.querySelector("span").textContent = `Sent · ${size(file.size)}`;
      row.classList.add("done");
    } catch (e) {
      transferRow(key, `↑ ${file.name}`, 0, file.size, `Not sent: ${e.message}`);
    }
  }
}

// --- start ---
async function main() {
  me = await identity();
  myName = (await db.kv.get("name")) || (/iPad/.test(navigator.userAgent) ? "iPad" : "iPhone");
  for (const b of document.querySelectorAll("[data-go]")) b.onclick = () => { current = null; show(b.dataset.go); renderDevices(); };
  $("pair-start").onclick = startPairView;
  $("pair-go").onclick = () => pairWith($("pair-link").value);
  $("pair-link").addEventListener("keydown", (e) => { if (e.key === "Enter") pairWith($("pair-link").value); });
  $("send").onclick = sendMessage;
  $("composer").addEventListener("keydown", (e) => { if (e.key === "Enter") sendMessage(); });
  $("file-input").onchange = (e) => { const files = [...e.target.files]; e.target.value = ""; sendFiles(files); };
  $("peer-menu").onclick = () => { $("sheet").hidden = false; };
  $("sheet-close").onclick = () => { $("sheet").hidden = true; };
  $("unpair").onclick = async () => {
    $("sheet").hidden = true;
    if (!current) return;
    const peer = current;
    const s = sessions.get(peer.fp);
    try { s && s._send({ t: "unpair" }); } catch (_) {}
    await forget(peer);
    toast(`Unpaired ${peer.name}.`);
  };
  const standalone = window.matchMedia("(display-mode: standalone)").matches || navigator.standalone;
  $("install-hint").hidden = standalone || !/iPhone|iPad/.test(navigator.userAgent);
  document.addEventListener("visibilitychange", () => (document.hidden ? disconnectAll() : connectAll()));
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
  await renderDevices();
  show("home");
  connectAll();
  if (location.hash.startsWith("#pair=")) {
    const link = location.href;
    history.replaceState(null, "", location.pathname);
    startPairView().then(() => pairWith(link));
  }
}

main().catch((e) => { document.body.textContent = `droplet couldn't start: ${e.message || e}`; });

// for tests and debugging
window.droplet = { sessions, status, db };
