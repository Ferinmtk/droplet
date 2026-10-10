// Scanning a QR code with the camera: BarcodeDetector where the browser has it,
// else jsQR (vendored, loaded only when needed). iOS Safari has no BarcodeDetector.

let jsQRLoading = null;
function loadJsQR() {
  if (window.jsQR) return Promise.resolve(window.jsQR);
  if (!jsQRLoading) {
    jsQRLoading = new Promise((resolve, reject) => {
      const s = document.createElement("script");
      s.src = new URL("./vendor/jsQR.js", import.meta.url).href;
      s.onload = () => resolve(window.jsQR);
      s.onerror = () => reject(new Error("couldn't load the QR reader"));
      document.head.appendChild(s);
    });
  }
  return jsQRLoading;
}

// Scan into `video`; resolves with the code's text. stop() ends it early.
export function scan(video) {
  let stream = null;
  let stopped = false;
  let timer = null;
  const stop = () => {
    stopped = true;
    clearTimeout(timer);
    if (stream) stream.getTracks().forEach((t) => t.stop());
    video.srcObject = null;
  };
  const result = (async () => {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      throw new Error("This browser can't use the camera here. Paste the pairing link instead.");
    }
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" }, audio: false });
    if (stopped) { stop(); throw new Error("stopped"); }
    video.srcObject = stream;
    video.setAttribute("playsinline", "");
    video.muted = true;
    await video.play();
    let detect;
    if ("BarcodeDetector" in window && (await BarcodeDetector.getSupportedFormats?.() || []).includes("qr_code")) {
      const d = new BarcodeDetector({ formats: ["qr_code"] });
      detect = async () => { const r = await d.detect(video); return r.length ? r[0].rawValue : null; };
    } else {
      const jsQR = await loadJsQR();
      const canvas = document.createElement("canvas");
      const ctx = canvas.getContext("2d", { willReadFrequently: true });
      detect = async () => {
        const w = video.videoWidth, h = video.videoHeight;
        if (!w || !h) return null;
        const scale = Math.min(1, 720 / Math.max(w, h));
        canvas.width = Math.round(w * scale); canvas.height = Math.round(h * scale);
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        const img = ctx.getImageData(0, 0, canvas.width, canvas.height);
        const r = jsQR(img.data, img.width, img.height, { inversionAttempts: "dontInvert" });
        return r ? r.data : null;
      };
    }
    return new Promise((resolve, reject) => {
      const tick = async () => {
        if (stopped) { reject(new Error("stopped")); return; }
        try {
          const text = await detect();
          if (text && text.includes("pair=")) { stop(); resolve(text); return; }
        } catch (_) { /* a frame that couldn't be read */ }
        timer = setTimeout(tick, 150);
      };
      tick();
    });
  })();
  result.catch(() => stop());
  return { result, stop };
}
