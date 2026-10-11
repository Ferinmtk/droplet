// droplet for iPhone: the screens, and keeping a connection to each paired computer
// while the app is open (it's foreground-only: iOS suspends a web app in the background).

import { identity, parsePairing } from "./crypto.js";
import { Session, checkName, webUrl } from "./proto.js";
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
const currentPeers = new Map(); // fp → the stored peer record, as last rendered
// clipboard text the computers sent, newest first: in memory only, never stored (it may be a
// password), so it's gone when iOS ends the app. { id, fp, text, ts, fresh }
const clips = [];
const CLIPS_PER_PEER = 5;

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

// what this iPhone calls a computer: its nickname here (never sent), else its own name
const shown = (p) => (p && (p.nickname || p.name)) || "the computer";

function eta(s) {
  if (s < 60) return `${Math.max(1, Math.round(s))} s left`;
  if (s < 3600) return `${Math.round(s / 60)} min left`;
  return `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min left`;
}

function show(view) {
  for (const v of document.querySelectorAll(".view")) v.hidden = v.id !== view;
  if (view !== "pair" && scanning) { scanning.stop(); scanning = null; }
}

// --- connections ---
// --- pause and permissions (docs/mesh.md §9.9) ---
// A peer record's `paused` is this iPhone's own Pause for that computer (enforced here, and
// told to it). What the computer said about this iPhone (paused, or something switched off)
// comes in its welcome and `perm`, and only greys things out here: the computer enforces it.
async function peerRecord(fp) { return (await db.peers.get(fp)) || null; }

function remoteOf(fp) {
  const s = sessions.get(fp);
  return s ? s.remote : null;
}

// why `cap` can't be used with this computer now, in words; "" if it can
function blocked(peer, cap) {
  if (peer.paused) return "You paused sharing with it.";
  const r = remoteOf(peer.fp);
  if (r && r.paused) return `${peer.name} paused sharing with this iPhone.`;
  if (r && cap && r.allow[cap] === false) {
    const noun = { clipboard: "the clipboard", files: "files", chat: "messages" }[cap] || cap;
    return `${peer.name} doesn't take ${noun} from this iPhone.`;
  }
  return "";
}

async function setPaused(peer, on) {
  peer.paused = !!on;
  await db.peers.put(peer);
  currentPeers.set(peer.fp, peer);
  const s = sessions.get(peer.fp);
  if (s) s.setPaused(peer.paused);
  toast(on ? `Paused ${peer.name}: nothing goes to it or comes from it until you resume.` : `Resumed ${peer.name}.`);
  renderDevices();
  if (current && current.fp === peer.fp) { current.paused = peer.paused; renderPeerState(); }
}

function stateText(fp) {
  const s = status.get(fp);
  const rec = currentPeers.get(fp);
  if (rec && rec.paused) return "Paused: nothing is shared with it";
  const r = remoteOf(fp);
  if (s === "connected" && r && r.paused) return `Paused by ${(rec && rec.name) || "the computer"}`;
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
  // our own Pause, kept across launches: refuse from the first message, and tell the computer
  if (peer.paused) s.setPaused(true);
  s.addEventListener("perm", () => {
    renderDevices();
    if (current && current.fp === peer.fp) renderPeerState();
  });
  s.addEventListener("refused-file", (e) => {
    if (current && current.fp === peer.fp) transferRow(`in-${e.detail.id}`, `↓ ${e.detail.name || "a file"}`, 0, e.detail.size || 0, "Not taken: you paused this computer");
  });
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
    else toast(`${shown(peer)}: ${m.body.slice(0, 80)}`);
  });
  s.addEventListener("file-progress", (e) => {
    if (current && current.fp === peer.fp) transferRow(`in-${e.detail.id}`, `↓ ${e.detail.name}`, e.detail.got, e.detail.size, null, () => s.cancel(e.detail.id));
  });
  s.addEventListener("file-cancelled", (e) => {
    const d = e.detail;
    if (current && current.fp === peer.fp) transferRow(`in-${d.id}`, `↓ ${d.name}`, 0, 0, d.here ? "Cancelled" : `Cancelled by ${shown(peer)}`);
  });
  s.addEventListener("link", async (e) => {
    const m = e.detail;
    const id = `${peer.fp}:in:${m.id}`;
    if (await db.messages.get(id)) return;
    await db.messages.put({ id, fp: peer.fp, dir: "in", kind: "link", body: m.url, ts: m.ts || Date.now() / 1000 });
    if (current && current.fp === peer.fp) renderMessages();
    else toast(`${shown(peer)} sent a link: open it from its messages`);
  });
  s.addEventListener("rename", async (e) => {
    peer.name = e.detail.name;
    await db.peers.put(peer);
    renderDevices();
    if (current && current.fp === peer.fp) { current.name = peer.name; $("peer-name").textContent = shown(peer); }
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
  s.addEventListener("clip", (e) => gotClip(peer, e.detail.text));
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
  for (let i = clips.length - 1; i >= 0; i--) if (clips[i].fp === peer.fp) clips.splice(i, 1);
  renderClips();
  await db.peers.remove(peer.fp);
  if (current && current.fp === peer.fp) { current = null; show("home"); }
  renderDevices();
}

// --- home ---
async function renderDevices() {
  const list = await db.peers.all();
  currentPeers.clear();
  for (const p of list) currentPeers.set(p.fp, p);
  const box = $("devices");
  $("me-name").textContent = myName;
  box.replaceChildren(...list.sort((a, b) => shown(a).localeCompare(shown(b))).map((p) => {
    const on = status.get(p.fp) === "connected";
    const fresh = clips.filter((c) => c.fp === p.fp && c.fresh).length;
    const r = remoteOf(p.fp);
    const pausedHere = !!p.paused, pausedThere = !!(on && r && r.paused);
    const b = el("button", { className: "device" },
      el("i", { className: "dot" + (pausedHere || pausedThere ? " paused" : on ? " on" : "") }),
      el("div", {}, el("b", { textContent: shown(p) }), el("span", { textContent: stateText(p.fp) })),
      pausedHere ? el("em", { className: "badge paused", textContent: "Paused" })
        : fresh ? el("em", { className: "badge", textContent: "New clipboard", title: `${fresh} new` }) : null);
    b.onclick = () => openPeer(p);
    const card = el("div", { className: "device-card" + (pausedHere ? " is-paused" : "") }, b);
    card.dataset.fp = p.fp;
    if (on) {
      const tools = el("div", { className: "device-tools" });
      const why = blocked(p, "clipboard");
      if (!why) {
        const send = clipButton("Send clipboard");
        send.onclick = () => sendClipboard(p, send);
        tools.append(send);
      }
      const pause = el("button", { className: "chip pause-chip", textContent: pausedHere ? "Resume" : "Pause" });
      pause.onclick = () => setPaused(p, !pausedHere);
      tools.append(pause);
      card.append(tools);
    }
    return card;
  }));
  $("empty").hidden = list.length > 0;
}

// --- the clipboard ---
// iOS lets a web app read or write the clipboard only in answer to a tap: so this iPhone sends
// its clipboard when "Send clipboard" is tapped (iOS shows its Paste button first), and what a
// computer sends waits on a card until "Copy" is tapped. Nothing here is ever sent on its own.
const CLIP_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 4h6v3H9z M7 5H5.5A1.5 1.5 0 0 0 4 6.5v13A1.5 1.5 0 0 0 5.5 21h13a1.5 1.5 0 0 0 1.5-1.5v-13A1.5 1.5 0 0 0 18.5 5H17 M12 17v-7 M9 12.5l3-3 3 3"/></svg>';

function clipButton(label) {
  const b = el("button", { className: "chip" });
  b.innerHTML = CLIP_ICON;
  b.append(el("span", { textContent: label }));
  return b;
}

async function sendClipboard(peer, button) {
  const label = button && button.querySelector("span");
  // readText first, straight from the tap: iOS asks (its Paste button) only inside the tap
  let text;
  try {
    if (!navigator.clipboard || !navigator.clipboard.readText) throw Object.assign(new Error(), { name: "Unsupported" });
    text = await navigator.clipboard.readText();
  } catch (e) {
    toast(e.name === "NotAllowedError" ? "droplet can't see the clipboard unless you tap Paste when iOS asks."
      : e.name === "Unsupported" ? "This browser doesn't let droplet read the clipboard."
        : "Couldn't read the clipboard (is there text on it?).");
    return;
  }
  if (!text) { toast("There's no text on the clipboard to send."); return; }
  const s = sessions.get(peer.fp);
  if (!s) { toast(`Not connected to ${peer.name}.`); return; }
  if (button) { button.disabled = true; label.textContent = "Sending…"; }
  try {
    await s.sendClip(text);
    toast(`Sent to ${peer.name}: it's on its clipboard.`);
  } catch (e) {
    toast(`Not sent to ${peer.name}: ${e.message}`, 5000);
  } finally {
    if (button) { button.disabled = false; label.textContent = "Send clipboard"; }
  }
}

function gotClip(peer, text) {
  const same = clips.findIndex((c) => c.fp === peer.fp && c.text === text);
  if (same >= 0) clips.splice(same, 1);     // the same text again: moves to the top
  clips.unshift({ id: Math.random().toString(16).slice(2), fp: peer.fp, name: peer.name, text, ts: Date.now() / 1000, fresh: true });
  let mine = 0;
  for (let i = 0; i < clips.length; i++) {
    if (clips[i].fp === peer.fp && ++mine > CLIPS_PER_PEER) clips.splice(i--, 1);
  }
  const here = current && current.fp === peer.fp && !$("peer").hidden;
  if (!here && $("home").hidden) toast(`${peer.name} sent its clipboard`);
  renderClips();
  if (here) clips[0].fresh = false;   // seen as it arrived: highlighted this once
  renderDevices();
}

function dropClip(c) {
  const i = clips.indexOf(c);
  if (i >= 0) clips.splice(i, 1);
  renderClips();
  renderDevices();
}

function copyFallback(text) {
  // an older iOS: a selected text field and the copy command (still inside the tap)
  const t = el("textarea", { value: text, readOnly: true });
  t.style.cssText = "position:fixed;top:0;left:0;opacity:0;font-size:16px";
  document.body.append(t);
  t.select();
  t.setSelectionRange(0, text.length);
  let ok = false;
  try { ok = document.execCommand("copy"); } catch (_) {}
  t.remove();
  return ok;
}

async function copyClip(c, button) {
  let ok = false;
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) { await navigator.clipboard.writeText(c.text); ok = true; }
  } catch (_) {}
  if (!ok) ok = copyFallback(c.text);
  if (!ok) { toast("Couldn't copy it: iOS said no."); return; }
  c.fresh = false;
  button.textContent = "Copied";
  button.classList.add("done");
  toast("Copied: paste it anywhere.");
  renderDevices();
}

function clipCard(c, compact) {
  const copy = el("button", { className: "btn primary copy", textContent: "Copy" });
  copy.onclick = () => copyClip(c, copy);
  const x = el("button", { className: "x", textContent: "×", ariaLabel: "Dismiss", title: "Dismiss" });
  x.onclick = () => dropClip(c);
  const head = el("div", { className: "clip-head" },
    el("b", { textContent: `From ${c.name}` }),
    c.fresh ? el("em", { className: "badge", textContent: "New" }) : null,
    el("small", { textContent: when(c.ts) }), x);
  const card = el("div", { className: "clip" + (c.fresh ? " fresh" : "") + (compact ? " compact" : "") },
    head, el("div", { className: "clip-body" }, el("p", { className: "clip-text", textContent: c.text }), copy));
  card.dataset.id = c.id;
  return card;
}

function renderClips() {
  const home = $("home-clips");
  home.replaceChildren(...(clips.length ? [el("h3", { className: "section", textContent: "Clipboard" }), ...clips.map((c) => clipCard(c))] : []));
  const box = $("peer-clip");
  const mine = current ? clips.filter((c) => c.fp === current.fp) : [];
  box.replaceChildren(...(mine.length ? [clipCard(mine[0], true)] : []));
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
  let name;
  try { name = checkName($("my-name").value, "The name", true) || "iPhone"; } catch (_) { name = myName; }
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
  $("peer-name").textContent = shown(peer);
  $("peer-own-name").textContent = peer.nickname ? `its own name: ${peer.name}` : "";
  $("peer-own-name").hidden = !peer.nickname;
  renderPeerState();
  $("transfers").replaceChildren();
  show("peer");
  renderClips();
  for (const c of clips) if (c.fp === peer.fp) c.fresh = false;   // seen: the highlight goes next time
  await Promise.all([renderMessages(), renderReceived()]);
  if (!sessions.has(peer.fp)) connectPeer(peer);
}

function renderPeerState() {
  const st = $("peer-state");
  st.textContent = stateText(current.fp);
  // what can't be used now, and why: one line above the composer
  const rec = currentPeers.get(current.fp) || current;
  const why = blocked(rec, null);
  st.classList.toggle("on", status.get(current.fp) === "connected" && !why);
  st.classList.toggle("paused", !!why);
  const clipWhy = blocked(rec, "clipboard"), chatWhy = blocked(rec, "chat"), fileWhy = blocked(rec, "files");
  const note = $("peer-paused");
  const text = why ? `${why} ${rec.paused ? "Nothing goes to it or comes from it until you resume." : "What you send waits."}`
    : [clipWhy, chatWhy, fileWhy].filter(Boolean).join(" ");
  note.hidden = !text;
  note.textContent = text;
  note.classList.toggle("info", !why);
  $("peer-resume").hidden = !rec.paused;
  $("send-clip").disabled = !!clipWhy;
  $("send-clip").title = clipWhy;
  $("composer").disabled = $("send").disabled = !!chatWhy;
  $("composer").placeholder = chatWhy ? "Paused" : "Message";
  $("file-input").disabled = !!fileWhy;
  $("file-label").classList.toggle("disabled", !!fileWhy);
  $("pause-toggle").textContent = rec.paused ? `Resume sharing with ${rec.name}` : `Pause sharing with ${rec.name}`;
}

async function renderMessages() {
  if (!current) return;
  const list = await db.messages.forPeer(current.fp);
  $("messages").replaceChildren(...list.map((m) => {
    const url = webUrl(m.body);
    // a link (sent as one, or a message that's only a link): Open, in a new tab, on a tap
    const open = url && m.body.trim() === url ? el("a", { className: "open-link", href: url, target: "_blank", rel: "noopener noreferrer", textContent: "Open" }) : null;
    return el("li", { className: m.dir + (m.state === "failed" ? " failed" : "") + (open ? " link" : "") },
      open ? el("span", { className: "body", textContent: m.body }) : m.body, open, el("small", { textContent: m.state === "failed" ? `Not sent: ${m.error || "not connected"}` : when(m.ts) }));
  }));
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

const rates = new Map();   // transfer key → { t0, d0, at, rate }

// one file on its way: its name, how far (bytes, %, speed, time left), and Cancel while it goes.
// Redrawn at most 4 times a second, except when it ends.
function transferRow(key, label, done, total, ended, cancel) {
  let row = document.getElementById(`t-${key}`);
  if (!row) {
    const x = el("button", { className: "chip cancel", textContent: "Cancel" });
    row = el("div", { className: "transfer", id: `t-${key}` }, el("div", {}, el("b"), el("span"), el("progress", { max: 1 })), x);
    $("transfers").append(row);
  }
  const now = performance.now();
  const r = rates.get(key) || { t0: now, d0: done, at: 0, rate: 0 };
  if (!ended && done < total && now - r.at < 250) return row;
  if (now - r.t0 > 500) { r.rate = (done - r.d0) / ((now - r.t0) / 1000); r.t0 = now; r.d0 = done; }
  r.at = now;
  rates.set(key, r);
  row.querySelector("b").textContent = label;
  const x = row.querySelector(".cancel");
  x.hidden = !!ended || !cancel || (total && done >= total);
  if (cancel) x.onclick = () => { x.disabled = true; cancel(); };
  let text;
  if (ended) text = ended;
  else {
    const pct = total ? Math.floor((done * 100) / total) : 0;
    const bits = [`${pct}% of ${size(total)}`];
    if (r.rate > 0 && done < total) bits.push(`${size(r.rate)}/s`, eta((total - done) / r.rate));
    // a line breaks only between the bits, never inside "5 s left"
    text = bits.map((b) => b.replace(/ /g, " ")).join(" · ");
  }
  row.querySelector("span").textContent = text;
  row.classList.toggle("ended", !!ended);
  const bar = row.querySelector("progress");
  bar.max = total || 1;
  bar.value = total ? done : 1;
  bar.hidden = !!ended;
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

async function sendLink() {
  const peer = current;
  const s = peer && sessions.get(peer.fp);
  const input = $("link-url");
  let url;
  try {
    url = webUrl(input.value);
    if (!url) throw new Error("Only web links (http:// or https://) can be sent.");
  } catch (e) { $("link-why").textContent = e.message; $("link-why").hidden = false; return; }
  $("link-form").hidden = true;
  input.value = "";
  const rec = { id: `${peer.fp}:out:${Date.now()}:${Math.random().toString(16).slice(2, 8)}`, fp: peer.fp, dir: "out", kind: "link", body: url, ts: Date.now() / 1000 };
  try {
    if (!s) throw new Error("not connected");
    const how = await s.sendLink(url);
    toast(how === "link" ? `Sent to ${shown(peer)}: it opens there if it's yours.` : `Sent to ${shown(peer)} as a message.`);
  } catch (e) {
    rec.state = "failed";
    rec.error = e.message;
  }
  await db.messages.put(rec);
  if (current === peer) renderMessages();
}

function openLinkForm() {
  $("link-why").hidden = true;
  $("link-form").hidden = false;
  $("link-url").focus();
}

async function pasteLink() {
  try {
    const t = await navigator.clipboard.readText();
    if (t) $("link-url").value = t.trim();
  } catch (_) { toast("droplet can't see the clipboard unless you tap Paste when iOS asks."); }
}

// --- names: this iPhone's, and a nickname for a computer (only here) ---
let naming = null;   // { kind: "me" } | { kind: "nick", peer }

function openNameSheet(kind, peer) {
  naming = { kind, peer };
  $("name-title").textContent = kind === "me" ? "Rename this iPhone" : `What do you call ${peer.name}?`;
  $("name-help").textContent = kind === "me" ? "Your computers show this name. Those connected now see it at once."
    : `Shown here instead of “${peer.name}”. Only on this iPhone: it's never sent. Leave it empty for its own name.`;
  $("name-input").value = kind === "me" ? myName : peer.nickname || "";
  $("name-input").placeholder = kind === "me" ? "iPhone" : peer.name;
  $("name-why").hidden = true;
  $("name-sheet").hidden = false;
  $("name-input").focus();
}

async function saveName() {
  let name;
  try { name = checkName($("name-input").value, naming.kind === "me" ? "The name" : "A nickname", naming.kind !== "me"); } catch (e) {
    $("name-why").textContent = e.message;
    $("name-why").hidden = false;
    return;
  }
  $("name-sheet").hidden = true;
  if (naming.kind === "me") {
    myName = name;
    await db.kv.set("name", name);
    for (const s of sessions.values()) s.sendRename(name);
    toast(`This iPhone is called ${name} now.`);
  } else {
    const rec = (await peerRecord(naming.peer.fp)) || naming.peer;
    rec.nickname = name;
    await db.peers.put(rec);
    currentPeers.set(rec.fp, rec);
    if (current && current.fp === rec.fp) {
      current.nickname = name;
      $("peer-name").textContent = shown(rec);
      $("peer-own-name").textContent = name ? `its own name: ${rec.name}` : "";
      $("peer-own-name").hidden = !name;
    }
    toast(name ? `${rec.name} is called ${name} on this iPhone.` : `${rec.name} goes by its own name again.`);
  }
  renderDevices();
}

async function sendFiles(list) {
  const peer = current;
  const s = peer && sessions.get(peer.fp);
  for (const file of list) {
    const key = `out-${Math.random().toString(16).slice(2)}`;
    if (!s) { transferRow(key, `↑ ${file.name}`, 0, file.size, "Not sent: not connected"); continue; }
    let id = null;
    const stop = () => { if (id) s.cancel(id); };
    transferRow(key, `↑ ${file.name}`, 0, file.size, null, stop);
    try {
      await s.sendFile(file, (n) => transferRow(key, `↑ ${file.name}`, n, file.size, null, stop), (got) => { id = got; });
      const row = transferRow(key, `↑ ${file.name}`, file.size, file.size, `Sent · ${size(file.size)}`);
      row.classList.add("done");
    } catch (e) {
      transferRow(key, `↑ ${file.name}`, 0, file.size, e.cancelled ? e.message : `Not sent: ${e.message}`);
    }
  }
}

// --- start ---
async function main() {
  me = await identity();
  myName = (await db.kv.get("name")) || (/iPad/.test(navigator.userAgent) ? "iPad" : "iPhone");
  for (const b of document.querySelectorAll("[data-go]")) b.onclick = () => { current = null; show(b.dataset.go); renderDevices(); renderClips(); };
  $("pair-start").onclick = startPairView;
  $("pair-go").onclick = () => pairWith($("pair-link").value);
  $("pair-link").addEventListener("keydown", (e) => { if (e.key === "Enter") pairWith($("pair-link").value); });
  $("send").onclick = sendMessage;
  $("send-link").onclick = openLinkForm;
  $("link-go").onclick = sendLink;
  $("link-paste").onclick = pasteLink;
  $("link-cancel").onclick = () => { $("link-form").hidden = true; };
  $("link-url").addEventListener("keydown", (e) => { if (e.key === "Enter") sendLink(); });
  $("rename-me").onclick = () => openNameSheet("me");
  $("nickname").onclick = () => { $("sheet").hidden = true; if (current) openNameSheet("nick", currentPeers.get(current.fp) || current); };
  $("name-save").onclick = saveName;
  $("name-cancel").onclick = () => { $("name-sheet").hidden = true; };
  $("name-input").addEventListener("keydown", (e) => { if (e.key === "Enter") saveName(); });
  $("send-clip").onclick = () => current && sendClipboard(current, $("send-clip"));
  $("composer").addEventListener("keydown", (e) => { if (e.key === "Enter") sendMessage(); });
  $("file-input").onchange = (e) => { const files = [...e.target.files]; e.target.value = ""; sendFiles(files); };
  $("peer-menu").onclick = () => { $("sheet").hidden = false; };
  $("sheet-close").onclick = () => { $("sheet").hidden = true; };
  $("pause-toggle").onclick = async () => {
    $("sheet").hidden = true;
    if (!current) return;
    const rec = (await peerRecord(current.fp)) || current;
    await setPaused(rec, !rec.paused);
  };
  $("peer-resume").onclick = async () => {
    if (!current) return;
    const rec = (await peerRecord(current.fp)) || current;
    await setPaused(rec, false);
  };
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
window.droplet = { sessions, status, db, clips, webUrl, checkName };
