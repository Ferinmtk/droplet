// Shared clipboard: a 📋 button in the chat that sends this device's
// clipboard as a message, and a "Hub clipboard" card (while Hub is picked)
// for moving text between this device and the hub's own clipboard.
(() => {
  // the async clipboard API only exists in a secure context (the tailnet's
  // https or localhost); on the plain-http LAN URL the card falls back to
  // a text box to paste into and a block of text to select
  const clip = window.isSecureContext ? navigator.clipboard : null;
  const canRead = !!clip?.readText;
  const canWrite = !!clip?.writeText;
  const PREVIEW = 400;   // characters shown before "…"

  document.head.append(el("style", { textContent: `
    #clip-card { margin-top:1rem; }
    #clip-card .clip-head { justify-content:space-between; margin-bottom:.5rem; }
    #clip-card .clip-text { background:var(--bg); border:1px solid var(--line); border-radius:8px; padding:.5rem .6rem;
      margin-bottom:.6rem; font-family:ui-monospace,monospace; font-size:.8rem; line-height:1.4;
      white-space:pre-wrap; word-break:break-word; max-height:7.5rem; overflow:auto; }
    #clip-card .clip-text.dim { font-family:inherit; font-style:italic; }
    #clip-card .clip-text.full { max-height:14rem; user-select:all; -webkit-user-select:all; }
    #clip-card textarea { margin-bottom:.5rem; }
    #clip-card .clip-extra:not(:empty) { margin-top:.6rem; }
    #chat-paste { flex:0 0 auto; }
  ` }));

  async function readLocal() {
    try {
      return await clip.readText();
    } catch {
      flash("This browser didn't allow reading the clipboard");
      return null;
    }
  }

  async function errorOf(res, fallback) {
    const j = res ? await res.json().catch(() => ({})) : {};
    return j.reason || j.error || fallback;
  }

  // --- 📋 in the chat: send what's on this device's clipboard ---

  const chatSend = document.getElementById("chat-send");
  if (chatSend && canRead) {
    const paste = el("button", { id: "chat-paste", type: "button", textContent: "📋",
                                 title: "Send what's on this device's clipboard" });
    paste.onclick = async () => {
      const to = chatWith;   // the chat could change while the clipboard prompt is up
      if (!to) return;
      const text = await readLocal();
      if (text == null) return;
      if (!text.trim()) { flash("Nothing to send, the clipboard has no text"); return; }
      const body = new FormData();
      body.append("text", text);
      body.append("to", to);
      paste.disabled = true;
      const res = await fetch("/text", { method: "POST", body }).catch(() => null);
      paste.disabled = false;
      if (res && res.ok) loadChat();
      else flash(await errorOf(res, "Didn't send, is the hub reachable?"));
    };
    chatSend.before(paste);
  }

  // --- Hub clipboard card ---

  const refreshBtn = el("button", { type: "button", textContent: "↻", title: "Check the hub's clipboard again" });
  const preview = el("div", { className: "clip-text dim", textContent: "Checking…" });
  const sendBtn = el("button", { type: "button", className: "primary", textContent: "Send my clipboard to the hub" });
  const copyBtn = el("button", { type: "button", textContent: "Copy hub clipboard here" });
  // without clipboard access, a box to paste into stands in for sendBtn
  const pasteBox = el("textarea", { placeholder: "Paste text here to put it on the hub's clipboard" });
  const pasteSend = el("button", { type: "button", className: "primary", textContent: "Send to the hub's clipboard" });
  const pasteForm = el("div", { className: "clip-extra", hidden: true }, pasteBox, el("div", { className: "row" }, pasteSend));
  const actions = el("div", { className: "row" }, ...(canRead ? [sendBtn, copyBtn] : [copyBtn]));
  const full = el("div", { className: "clip-extra" });   // hub text to select by hand
  const card = el("section", { className: "card", id: "clip-card", hidden: true },
    el("div", { className: "row clip-head" }, el("b", { textContent: "Hub clipboard" }), refreshBtn),
    preview, actions, pasteForm, full);
  (document.getElementById("features") || document.body).append(card);

  let seq = 0;              // only the newest check gets to draw
  let off = false;          // the hub has the feature turned off
  let shown = false;

  function show(j) {
    full.innerHTML = "";
    const usable = !!j && j.available;
    actions.hidden = !usable;
    pasteForm.hidden = !usable || canRead;
    const hubHasText = usable && j.text != null;
    copyBtn.disabled = !hubHasText;
    preview.classList.toggle("dim", !hubHasText);
    if (!hubHasText) {
      preview.textContent = !j ? "Couldn't reach the hub." : j.reason || "Nothing on the hub's clipboard.";
      return;
    }
    const t = j.text;
    preview.textContent = t.length > PREVIEW
      ? `${t.slice(0, PREVIEW)}… (${t.length.toLocaleString()} characters${j.truncated ? ", cut at 1 MB" : ""})`
      : t;
  }

  async function load() {
    const n = ++seq;
    refreshBtn.disabled = true;
    const res = await fetch("/api/hub/clipboard", { cache: "no-store" }).catch(() => null);
    const j = res && res.ok ? await res.json().catch(() => null) : null;   // a PIN redirect isn't JSON
    if (n !== seq) return j;
    refreshBtn.disabled = false;
    if (res && res.status === 404) { off = true; card.remove(); return null; }   // turned off on the hub
    show(j);
    return j;
  }

  async function sendToHub(text, btn) {
    if (!text || !text.trim()) { flash("Nothing to send, the clipboard has no text"); return false; }
    btn.disabled = true;
    const res = await fetch("/api/hub/clipboard", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ text }),
    }).catch(() => null);
    btn.disabled = false;
    if (!res || !res.ok) { flash(await errorOf(res, "Couldn't reach the hub")); return false; }
    flash("Copied to the hub's clipboard");
    load();
    return true;
  }

  // shown for selecting by hand when this browser won't write the clipboard
  function showFull(text) {
    full.innerHTML = "";
    const block = el("div", { className: "clip-text full", textContent: text });
    full.append(block, el("p", { className: "dim", textContent: "Select the text above and copy it." }));
    const range = document.createRange();
    range.selectNodeContents(block);
    const sel = getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
    // the legacy copy command still works on plain http in most browsers
    let copied = false;
    try { copied = document.execCommand("copy"); } catch {}
    flash(copied ? "Copied from the hub" : "Select the text and copy it");
  }

  sendBtn.onclick = async () => {
    const text = await readLocal();
    if (text != null) sendToHub(text, sendBtn);
  };
  pasteSend.onclick = async () => {
    if (await sendToHub(pasteBox.value, pasteSend)) pasteBox.value = "";
  };
  copyBtn.onclick = async () => {
    copyBtn.disabled = true;
    const j = await load();   // fresh, not whatever the preview showed
    if (!j || j.text == null) { if (j) flash(j.reason || "Nothing to copy"); return; }
    if (canWrite) {
      try {
        await clip.writeText(j.text);
        flash(`Copied ${j.text.length.toLocaleString()} characters from the hub`);
        return;
      } catch {}   // e.g. Safari, which wants the write right inside the tap
    }
    showFull(j.text);
  };
  refreshBtn.onclick = load;

  // the card belongs to "Send to: Hub"; check the pick cheaply rather than
  // hooking the page's own render functions
  function sync() {
    if (off) return;
    const want = target === "hub";
    if (want === shown) return;
    shown = want;
    card.hidden = !want;
    if (want) load();
  }
  setInterval(sync, 400);
  // coming back to the app is the usual moment the hub's clipboard changed
  document.addEventListener("visibilitychange", () => { if (!document.hidden && shown && !off) load(); });
  sync();
})();
