// WebRTC Direct to a droplet computer: no signalling server.
//
// The computer runs an ICE-lite responder on a known UDP port with a stable
// DTLS certificate. We know its address, port and certificate fingerprint
// from the QR code, so we write its SDP answer ourselves:
//   - a=ice-lite: it never sends checks, it only answers ours;
//   - ice-ufrag = ice-pwd = "droplet+v1/" + 24 random ice-chars. The computer
//     reads the ufrag from our first STUN check (USERNAME = its:ours) and
//     uses the same string as its password. Our own offer is never changed.
//   - a=setup:passive: we are the DTLS client, it's the server;
//   - a=fingerprint: the one from the QR, so DTLS pins its certificate;
//   - one host candidate: its address and port.
// One data channel, negotiated out of band (id 0), so there's no DCEP round trip.
// See docs/iphone.md.

export const UFRAG_PREFIX = "droplet+v1/";
const ICE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

function iceRandom(n) {
  const bytes = crypto.getRandomValues(new Uint8Array(n));
  let s = "";
  for (const b of bytes) s += ICE_CHARS[b & 63];
  return s;
}

// "ab12…" (64 hex) → "AB:12:…", the SDP form
export function sdpFingerprint(hex) {
  return hex.toUpperCase().match(/../g).join(":");
}

export function fingerprintFromSdp(sdp) {
  const m = /a=fingerprint:sha-256 ([0-9A-Fa-f:]{95})/i.exec(sdp);
  return m ? m[1].replace(/:/g, "").toLowerCase() : null;
}

export function answerSdp({ address, port, fp, mid, ufrag }) {
  const v6 = address.includes(":");
  const ipv = v6 ? "IP6" : "IP4";
  return [
    "v=0",
    `o=- 0 0 IN ${ipv} ${address}`,
    "s=-",
    "t=0 0",
    "a=ice-lite",
    `a=group:BUNDLE ${mid}`,
    `m=application ${port} UDP/DTLS/SCTP webrtc-datachannel`,
    `c=IN ${ipv} ${address}`,
    `a=mid:${mid}`,
    `a=ice-ufrag:${ufrag}`,
    `a=ice-pwd:${ufrag}`,
    "a=ice-options:ice2",
    `a=fingerprint:sha-256 ${sdpFingerprint(fp)}`,
    "a=setup:passive",
    "a=sctp-port:5000",
    "a=max-message-size:262144",
    `a=candidate:1 1 udp 2130706431 ${address} ${port} typ host`,
    "a=end-of-candidates",
    "",
  ].join("\r\n");
}

// Connect to one address. Resolves { pc, dc, localFp } once the channel is open.
export async function connect({ address, port, fp, timeout = 10000 }) {
  const pc = new RTCPeerConnection({ iceServers: [] });
  const dc = pc.createDataChannel("droplet", { negotiated: true, id: 0, ordered: true });
  dc.binaryType = "arraybuffer";
  try {
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    const local = pc.localDescription.sdp;
    const mid = (/a=mid:(\S+)/.exec(local) || [, "0"])[1];
    const localFp = fingerprintFromSdp(local);
    if (!localFp) throw new Error("this browser's offer has no SHA-256 fingerprint");
    const ufrag = UFRAG_PREFIX + iceRandom(24);
    await pc.setRemoteDescription({ type: "answer", sdp: answerSdp({ address, port, fp, mid, ufrag }) });
    await new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error(`no answer from ${address}:${port}`)), timeout);
      dc.onopen = () => { clearTimeout(t); resolve(); };
      pc.onconnectionstatechange = () => {
        if (pc.connectionState === "failed") { clearTimeout(t); reject(new Error(`couldn't connect to ${address}:${port}`)); }
      };
    });
    dc.onopen = null;
    return { pc, dc, localFp };
  } catch (e) {
    try { pc.close(); } catch (_) {}
    throw e;
  }
}

// Try each address in turn (the QR lists the computer's LAN addresses, the main one first).
export async function connectAny({ addresses, port, fp, timeout = 8000 }) {
  let last;
  for (const address of addresses) {
    try {
      return { ...(await connect({ address, port, fp, timeout })), address };
    } catch (e) { last = e; }
  }
  throw last || new Error("no address to try");
}
