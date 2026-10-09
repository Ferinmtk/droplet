// One connection to a computer: who we are, pairing, then messages and files.
// The wire protocol is docs/iphone.md (and agent/droplet_agent/webrtc/protocol.py).

import { connectAny } from "./rtc.js";
import { authTranscript, commitment, hex, pairCode, pairTranscript, randomHex, sign, unhex } from "./crypto.js";

const CHUNK = 16 * 1024;
const HIGH = 1024 * 1024;
const LOW = 256 * 1024;
export const MAX_FRAME = 256 * 1024;   // bytes in one message on the channel (its max-message-size)

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
    this._send({ t: "auth", v: 1, key: this.id.spki, name: this.myName, sig });
    const m = await this._expect(["welcome", "auth-failed"], 10000);
    if (m.t !== "welcome") {
      const err = new Error(m.paired ? "The computer didn't accept this device's proof." : "This computer doesn't know this device any more: pair again.");
      err.unpaired = !m.paired;
      this.close();
      throw err;
    }
    this.ready = true;
    this.emit("ready", m);
    return m;
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
    const id = randomHex(12);
    const done = this._ack(id, 10000);
    this._send({ t: "text", id, body, ts: Date.now() / 1000 });
    const r = await done;
    if (!r.ok) throw new Error(r.error || "not delivered");
    return id;
  }

  // this iPhone's clipboard text, sent on a tap: resolves once the computer has put it on its clipboard
  async sendClip(text) {
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

  // one file at a time; onProgress(sentBytes)
  sendFile(file, onProgress) {
    const run = () => this._sendFile(file, onProgress);
    const p = this._sendQueue.then(run, run);
    this._sendQueue = p.catch(() => {});
    return p;
  }

  async _sendFile(file, onProgress) {
    if (!this.ready) throw new Error("Not connected.");
    const id = randomHex(16);
    const prefix = unhex(id.slice(0, 16));
    const done = this._ack(id, 0);
    this._send({ t: "file", id, name: file.name, size: file.size, mime: file.type || "application/octet-stream" });
    let sent = 0;
    while (sent < file.size) {
      if (this.closed) throw new Error("The connection closed.");
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
    this._send({ t: "file-end", id });
    const r = await Promise.race([done, new Promise((res) => setTimeout(() => res({ ok: false, error: "no answer" }), 60000))]);
    if (!r.ok) throw new Error(r.error || "not delivered");
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
    switch (m.t) {
      case "ack": case "nack": {
        const r = this._wait.get(m.id);
        if (r) { this._wait.delete(m.id); r({ ok: m.t === "ack", error: m.error }); }
        break;
      }
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
