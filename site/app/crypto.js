// This device's identity, and the pairing maths (docs/mesh.md §9.3, docs/iphone.md).
//
// The key is ECDSA P-256, made by WebCrypto as non-extractable: its private half can sign,
// and can be kept in IndexedDB, but can never be read out, not even by this page.

import { kv } from "./store.js";

const enc = new TextEncoder();

export const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
export const unhex = (s) => new Uint8Array(s.match(/../g).map((h) => parseInt(h, 16)));
export const b64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)));
export function b64urlDecode(s) {
  const t = s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4);
  return Uint8Array.from(atob(t), (c) => c.charCodeAt(0));
}

export async function sha256(data) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", typeof data === "string" ? enc.encode(data) : data));
}

export const randomHex = (n) => hex(crypto.getRandomValues(new Uint8Array(n)));

export async function identity() {
  let id = await kv.get("identity");
  if (!id) {
    const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, false, ["sign", "verify"]);
    const spki = new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey));
    id = { privateKey: pair.privateKey, spki: b64(spki), fp: hex(await sha256(spki)) };
    await kv.set("identity", id);
  }
  return id;
}

// ECDSA P-256 / SHA-256: WebCrypto gives the raw r‖s (64 bytes); the computer takes that form
export async function sign(id, text) {
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, id.privateKey, enc.encode(text));
  return b64(sig);
}

export const pairTranscript = (fpI, fpR, nI, nR) => ["droplet-pair-v1", fpI, fpR, nI, nR].join("\n");

export const authTranscript = (fpKey, fpServer, fpDtls, nonce) =>
  ["droplet-webrtc-auth-v1", fpKey, fpServer, fpDtls, nonce].join("\n");

export async function commitment(nonceHex) {
  return hex(await sha256(unhex(nonceHex)));
}

// the 4-digit code both screens show
export async function pairCode(fpI, fpR, nI, nR) {
  const h = await sha256("droplet-pair-code-v1\n" + pairTranscript(fpI, fpR, nI, nR));
  let n = 0n;
  for (const b of h.slice(0, 8)) n = (n << 8n) | BigInt(b);
  return (n % 10000n).toString().padStart(4, "0");
}

// The QR code: https://…/app/#pair=<base64url JSON> (or just the base64url).
export function parsePairing(text) {
  let s = String(text || "").trim();
  const i = s.indexOf("#pair=");
  if (i >= 0) s = s.slice(i + 6);
  let d;
  try { d = JSON.parse(new TextDecoder().decode(b64urlDecode(s))); } catch (_) { throw new Error("That isn't a droplet pairing code."); }
  if (!d || d.v !== 1 || !d.f || !Array.isArray(d.a) || !d.p) throw new Error("That isn't a droplet pairing code.");
  const fp = hex(b64urlDecode(d.f));
  if (fp.length !== 64) throw new Error("That pairing code is damaged.");
  return { name: String(d.n || "computer"), id: String(d.i || ""), fp, addresses: d.a.map(String), port: Number(d.p), token: String(d.t || "") };
}
