// One connection to a computer: who we are, pairing, then messages and files.
// The wire protocol is docs/iphone.md (and agent/droplet_agent/webrtc/protocol.py).

import { connectAny } from "./rtc.js";
import { authTranscript, commitment, hex, pairCode, pairTranscript, randomHex, sign, unhex } from "./crypto.js";

const CHUNK = 16 * 1024;
const HIGH = 1024 * 1024;
const LOW = 256 * 1024;
export const MAX_FRAME = 256 * 1024;   // bytes in one message on the channel (its max-message-size)

// per-device permissions (docs/mesh.md §9.9): the capability each message needs
const CAPS = { text: "chat", link: "chat", file: "files", "file-end": "files", clip: "clipboard", ring: "ring", "ring-stop": "ring" };
// what this app understands besides v1 (docs/mesh.md §9.10), told to the computer in its auth
export const FEATURES = ["cancel", "link", "rename"];

// a web link that may be sent and opened: http or https, with a host, no spaces or control
// characters, at most 2048 characters. Anything else (file:, javascript:, an app's scheme) never is.
export function webUrl(v) {
  if (typeof v !== "string") return null;
  const t = v.trim();
  if (!/^https?:\/\//i.test(t) || t.length > 2048) return null;
  if (/[\s\u0000-\u001f\u007f-\u009f\u00ad\u200b-\u200f\u202a-\u202e\u2066-\u2069\ufeff]/.test(t)) return null;
  try {
    const u = new URL(t);
    return (u.protocol === "http:" || u.protocol === "https:") && u.hostname ? t : null;
  } catch (_) { return null; }
}

// a name typed here (this iPhone's, or a nickname): spaces collapsed, 1–40 characters, no
// control characters. Throws with the reason.
export function checkName(v, what = "The name", emptyOk = false) {
  if (/[\u0000-\u0008\u000b-\u001f\u007f-\u009f\u00ad\u200b-\u200f\u202a-\u202e\u2066-\u2069\ufeff]/.test(v)) throw new Error(`${what} can't hold control characters.`);
  const t = String(v || "").split(/\s+/).filter(Boolean).join(" ");
  if (!t && !emptyOk) throw new Error(`${what} can't be empty.`);
  if ([...t].length > 40) throw new Error(`${what} can be 40 characters at most.`);
  return t;
}
const NOUNS = { files: "files", chat: "messages", clipboard: "the clipboard", ring: "ringing" };

export function cleanPerm(p) {
  if (!p || typeof p !== "object") return null;
  const allow = {};
  if (p.allow && typeof p.allow === "object") for (const [k, v] of Object.entries(p.allow)) if (typeof v === "boolean") allow[k] = v;
  return { paused: p.paused === true, allow };
}

export class Session extends EventTarget {
  // peer: { fp, addresses, port, name }; id: this device's identity (crypto.js)
  constructor(peer, id, myName) {
    super();
    this.peer = peer;
    this.id = id;
    this.myName = myName;
    this.dc = null;
    this.pc = null;
    this.hello = null;
    this.ready = false;
    this.closed = false;
    this._wait = new Map();       // message id → resolve({ok, error})
    this._next = [];               // [{t, resolve, reject}] waiting for a message type
    this._in = new Map();          // 16-hex id prefix → incoming file
    this._sendQueue = Promise.resolve();
    // how the computer treats this iPhone ({paused, allow}): what it said, a hint for the screens
    this.remote = null;
    // this iPhone paused sharing with the computer: nothing goes, nothing is taken (enforced here)
    this.paused = false;
    this._refusedAt = new Map();   // message type → when we last said no
    this.features = [];            // what the computer understands besides v1 (its welcome)
    this._stop = new Map();        // id of a file being sent → why it stopped ("cancelled here", or the computer's word)
  }

  emit(type, detail) { this.dispatchEvent(new CustomEvent(type, { detail })); }

  async open() {
    const { pc, dc, localFp, address } = await connectAny(this.peer);
    this.pc = pc; this.dc = dc; this.localFp = localFp; this.address = address;
    dc.bufferedAmountLowThreshold = LOW;
    dc.onmessage = (e) => this._message(e.data);
    dc.onclose = () => this.close();
    pc.onconnectionstatechange = () => {
      if (["failed", "closed", "disconnected"].includes(pc.connectionState)) this.close();
    };
    this.hello = await this._expect("server-hello", 8000);
    // DTLS already pinned this; the computer says it too
    if (this.hello.fp !== this.peer.fp) { this.close(); throw new Error("That isn't the computer you paired with."); }
    return this;
  }

  // a paired computer: prove who we are, bound to this DTLS session
  async auth() {
    const sig = await sign(this.id, authTranscript(this.id.fp, this.peer.fp, this.localFp, this.hello.nonce));
    this._send({ t: "auth", v: 1, key: this.id.spki, name: this.myName, sig, features: FEATURES });
    const m = await this._expect(["welcome", "auth-failed"], 10000);
    if (m.t !== "welcome") {
      const err = new Error(m.paired ? "The computer didn't accept this device's proof." : "This computer doesn't know this device any more: pair again.");
      err.unpaired = !m.paired;
      this.close();
      throw err;
    }
    this.ready = true;
    this.features = Array.isArray(m.features) ? m.features.filter((x) => typeof x === "string") : [];
    this.remote = cleanPerm(m.perm);
    this.emit("ready", m);
    return m;
  }

  // Pause on this iPhone: tell the computer (so it holds what it would send), and refuse
  // whatever comes while paused. The computer enforces its own settings; this is the iPhone's.
  setPaused(on) {
    this.paused = !!on;
    try { this._send({ t: "perm", paused: this.paused, allow: {} }); } catch (_) {}
  }

  // what the computer said it won't take from this iPhone: "paused", "denied" or null
  refuses(cap) {
    if (!this.remote) return null;
    if (this.remote.paused) return "paused";
    if (cap && this.remote.allow[cap] === false) return "denied";
    return null;
  }

  _check(cap) {
    if (this.paused) throw Object.assign(new Error("You paused sharing with this computer. Resume to send."), { paused: true });
    const why = this.refuses(cap);
    const name = (this.hello && this.hello.name) || "The computer";
    if (why === "paused") throw Object.assign(new Error(`${name} paused sharing with this iPhone.`), { paused: true });
    if (why === "denied") throw new Error(`${name} doesn't allow ${NOUNS[cap] || cap} from this iPhone.`);
  }

  _refuse(m, cap) {
    // paused here: say so, so the computer waits instead of failing (an id gets its own answer)
    const error = `${this.myName} paused sharing with you`;
    if (typeof m.id === "string" && m.id.length <= 64) {
      this._send({ t: "refused", re: m.t, id: m.id, cap, why: "paused", error });
      return;
    }
    const now = Date.now();
    if (now - (this._refusedAt.get(m.t) || 0) < 5000) return;
    this._refusedAt.set(m.t, now);
    this._send({ t: "refused", re: m.t, cap, why: "paused", error });
  }

  // pairing, steps 1–2: returns the code to show; then confirmed() waits for the computer's answer
  async pair(token) {
    const nI = randomHex(32);
    this._send({ t: "pair", v: 1, key: this.id.spki, name: this.myName, os: "ios", token, commit: await commitment(nI) });
    const m = await this._expect(["pair-nonce", "pair-failed"], 10000);
    if (m.t === "pair-failed") throw new Error(m.error || "The computer refused.");
    const sig = await sign(this.id, pairTranscript(this.id.fp, this.peer.fp, nI, m.nonce));
    this._send({ t: "pair-confirm", nonce: nI, sig });
    const s = await this._expect(["pair-state", "pair-failed"], 10000);
    if (s.t === "pair-failed") throw new Error(s.error || "The computer refused.");
    return pairCode(this.id.fp, this.peer.fp, nI, m.nonce);
  }

  // waits for the computer's owner: "accepted", "denied", "expired" or "cancelled"
  async answer() {
    for (;;) {
      const s = await this._expect("pair-state", 5 * 60 * 1000);
      if (s.state !== "waiting") return s.state;
    }
  }

  cancelPairing() { this._send({ t: "pair-cancel" }); }

  close() {
    if (this.closed) return;
    this.closed = true;
    this.ready = false;
    for (const w of this._next) w.reject(new Error("The connection closed."));
    this._next = [];
    for (const r of this._wait.values()) r({ ok: false, error: "the connection closed" });
    this._wait.clear();
    try { this.dc && this.dc.close(); } catch (_) {}
    try { this.pc && this.pc.close(); } catch (_) {}
    this.emit("close");
  }

  // --- messages ---
  async sendText(body) {
    this._check("chat");
    const id = randomHex(12);
    const done = this._ack(id, 10000);
    this._send({ t: "text", id, body, ts: Date.now() / 1000 });
    const r = await done;
    if (!r.ok) throw Object.assign(new Error(r.error || "not delivered"), { paused: !!r.paused });
    return id;
  }

  // this iPhone's clipboard text, sent on a tap: resolves once the computer has put it on its clipboard
  async sendClip(text) {
    this._check("clipboard");
    const id = randomHex(12);
    const frame = JSON.stringify({ t: "clip", id, text });
    if (new TextEncoder().encode(frame).length > MAX_FRAME) throw new Error("It's too long to send (256 KB at most).");
    if (this.closed || !this.dc || this.dc.readyState !== "open") throw new Error("Not connected.");
    const done = this._ack(id, 10000);
    this.dc.send(frame);
    const r = await done;
    if (!r.ok) throw new Error(r.error || "not delivered");
    return id;
  }

  // a web link: it opens on the computer if it's yours (the computer decides), else waits there
  // with an Open button. To an older computer, it goes as a message.
  async sendLink(url) {
    const u = webUrl(url);
    if (!u) throw new Error("Only web links (http:// or https://) can be sent.");
    if (!this.features.includes("link")) { await this.sendText(u); return "message"; }
    this._check("chat");
    const id = randomHex(12);
    const done = this._ack(id, 10000);
    this._send({ t: "link", id, url: u, ts: Date.now() / 1000 });
    const r = await done;
    if (!r.ok) throw Object.assign(new Error(r.error || "not delivered"), { paused: !!r.paused });
    return "link";
  }

  // this iPhone has a new name: the computer shows it at once
  sendRename(name) {
    this.myName = name;
    try { this._send({ t: "rename", name }); } catch (_) {}
  }

  // stop a file on its way: one being sent (it stops, and the computer drops what came), or one
  // arriving (dropped here, and the computer stops sending it)
  cancel(id) {
    if (this._in.has(String(id).slice(0, 16))) {
      const f = this._in.get(String(id).slice(0, 16));
      this._in.delete(f.id.slice(0, 16));
      try { this._send({ t: "cancel", id: f.id }); } catch (_) {}
      this.emit("file-cancelled", { id: f.id, name: f.name, here: true });
      return true;
    }
    if (this._sending === id) {
      this._stop.set(id, "Cancelled");
      try { this._send({ t: "cancel", id }); } catch (_) {}
      const r = this._wait.get(id);
      if (r) { this._wait.delete(id); r({ ok: false, error: "Cancelled", cancelled: true }); }
      return true;
    }
    return false;
  }

  // one file at a time; onProgress(sentBytes); onStart(id), so it can be cancelled
  sendFile(file, onProgress, onStart) {
    const run = () => this._sendFile(file, onProgress, onStart);
    const p = this._sendQueue.then(run, run);
    this._sendQueue = p.catch(() => {});
    return p;
  }

  async _sendFile(file, onProgress, onStart) {
    if (!this.ready) throw new Error("Not connected.");
    this._check("files");
    const id = randomHex(16);
    const prefix = unhex(id.slice(0, 16));
    const done = this._ack(id, 0);
    this._sending = id;
    onStart && onStart(id);
    try {
      return await this._sendBody(file, id, prefix, done, onProgress);
    } finally {
      this._sending = null;
      this._stop.delete(id);
    }
  }

  async _sendBody(file, id, prefix, done, onProgress) {
    this._send({ t: "file", id, name: file.name, size: file.size, mime: file.type || "application/octet-stream" });
    let sent = 0;
    while (sent < file.size) {
      if (this.closed) throw new Error("The connection closed.");
      if (this._stop.has(id)) throw Object.assign(new Error(this._stop.get(id)), { cancelled: true });
      if (this.dc.bufferedAmount > HIGH) {
        await new Promise((r) => { this.dc.addEventListener("bufferedamountlow", r, { once: true }); });
        continue;
      }
      const body = new Uint8Array(await file.slice(sent, sent + CHUNK).arrayBuffer());
      const frame = new Uint8Array(8 + body.length);
      frame.set(prefix, 0);
      frame.set(body, 8);
      this.dc.send(frame);
      sent += body.length;
      onProgress && onProgress(sent);
    }
    if (this._stop.has(id)) throw Object.assign(new Error(this._stop.get(id)), { cancelled: true });
    this._send({ t: "file-end", id });
    const r = await Promise.race([done, new Promise((res) => setTimeout(() => res({ ok: false, error: "no answer" }), 60000))]);
    if (!r.ok) throw Object.assign(new Error(r.error || "not delivered"), { cancelled: !!r.cancelled });
    return id;
  }

  // --- the wire ---
  _send(msg) {
    if (this.closed || !this.dc || this.dc.readyState !== "open") throw new Error("Not connected.");
    this.dc.send(JSON.stringify(msg));
  }

  _ack(id, timeout) {
    return new Promise((resolve) => {
      this._wait.set(id, resolve);
      if (timeout) setTimeout(() => { if (this._wait.get(id) === resolve) { this._wait.delete(id); resolve({ ok: false, error: "no answer" }); } }, timeout);
    });
  }

  _expect(types, timeout) {
    types = Array.isArray(types) ? types : [types];
    return new Promise((resolve, reject) => {
      const w = { types, resolve, reject };
      this._next.push(w);
      if (timeout) setTimeout(() => {
        const i = this._next.indexOf(w);
        if (i >= 0) { this._next.splice(i, 1); reject(new Error("The computer didn't answer.")); }
      }, timeout);
    });
  }

  _message(data) {
    if (typeof data !== "string") { this._chunk(new Uint8Array(data)); return; }
    let m;
    try { m = JSON.parse(data); } catch (_) { return; }
    const i = this._next.findIndex((w) => w.types.includes(m.t));
    if (i >= 0) { const [w] = this._next.splice(i, 1); w.resolve(m); return; }
    // paused here: refuse what needs a capability (ring, text, files, clipboard), keep the rest
    const cap = CAPS[m.t];
    if (this.paused && cap) {
      if (m.t === "file") this.emit("refused-file", m);
      if (m.t !== "file-end") this._refuse(m, cap);
      return;
    }
    switch (m.t) {
      case "ack": case "nack": {
        const r = this._wait.get(m.id);
        if (r) { this._wait.delete(m.id); r({ ok: m.t === "ack", error: m.error }); }
        break;
      }
      case "refused": {
        // the computer is paused (for us, or everything): what we sent wasn't taken, for now
        const r = typeof m.id === "string" && this._wait.get(m.id);
        if (r) { this._wait.delete(m.id); r({ ok: false, error: m.error || "paused", paused: m.why === "paused" }); }
        this.emit("refused", m);
        break;
      }
      case "perm":
        this.remote = cleanPerm(m);
        this.emit("perm", this.remote);
        break;
      case "text":
        if (typeof m.id === "string" && typeof m.body === "string") {
          this._send({ t: "ack", id: m.id });
          this.emit("text", m);
        }
        break;
      case "file":
        if (/^[0-9a-f]{32}$/.test(m.id) && Number.isInteger(m.size) && m.size >= 0)
          this._in.set(m.id.slice(0, 16), { id: m.id, name: String(m.name || "file"), size: m.size, mime: m.mime || "application/octet-stream", parts: [], got: 0 });
        this.emit("file-start", m);
        break;
      case "file-end": {
        const f = this._in.get(String(m.id).slice(0, 16));
        if (!f || f.id !== m.id) break;
        this._in.delete(f.id.slice(0, 16));
        if (f.got !== f.size) { this._send({ t: "nack", id: f.id, error: `got ${f.got} of ${f.size} bytes` }); break; }
        const blob = new Blob(f.parts, { type: f.mime });
        // acknowledged only once it's kept: the computer keeps it in its outbox until then
        const file = { id: f.id, name: f.name, size: f.size, mime: f.mime, blob };
        Promise.resolve(this.keepFile ? this.keepFile(file) : null).then(
          () => { this._send({ t: "ack", id: f.id }); this.emit("file", file); },
          (e) => { try { this._send({ t: "nack", id: f.id, error: `couldn't keep it: ${e.message || e}` }); } catch (_) {} },
        );
        break;
      }
      case "cancel": {
        // the computer cancelled a file: one it was sending here, or one this iPhone was sending it
        const f = this._in.get(String(m.id).slice(0, 16));
        if (f && f.id === m.id) {
          this._in.delete(f.id.slice(0, 16));
          this.emit("file-cancelled", { id: f.id, name: f.name, here: false });
        }
        if (this._sending === m.id) {
          const who = (this.hello && this.hello.name) || "The computer";
          this._stop.set(m.id, `${who} cancelled it`);
          const r = this._wait.get(m.id);
          if (r) { this._wait.delete(m.id); r({ ok: false, error: `${who} cancelled it`, cancelled: true }); }
        }
        break;
      }
      case "link": {
        // a web link: shown with an Open button (a web app can't open it by itself: no tap)
        if (typeof m.id !== "string") break;
        const url = webUrl(m.url);
        if (!url) { this._send({ t: "nack", id: m.id, error: "only web links can be opened" }); break; }
        this._send({ t: "ack", id: m.id });
        this.emit("link", { id: m.id, url, ts: m.ts });
        break;
      }
      case "rename":
        try { const name = checkName(m.name); this.hello.name = name; this.emit("rename", { name }); } catch (_) {}
        break;
      case "ping": this._send({ t: "pong" }); break;
      case "ring": this.emit("ring", m); break;
      case "clip": if (typeof m.text === "string" && m.text) this.emit("clip", m); break;
      case "unpair": this.emit("unpair", m); break;
    }
  }

  _chunk(bytes) {
    if (bytes.length < 8) return;
    const f = this._in.get(hex(bytes.slice(0, 8)));
    if (!f) return;
    f.parts.push(bytes.slice(8));
    f.got += bytes.length - 8;
    this.emit("file-progress", { id: f.id, got: f.got, size: f.size, name: f.name });
  }
}
