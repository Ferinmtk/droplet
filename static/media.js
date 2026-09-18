// Hub media remote: what's playing on the hub, with playback, volume and output.
// Talks to media.py. Polls only while the page is visible and the card is on screen.
(() => {
  const POLL_MS = 3000;

  const ICON = {
    play: '<path d="M8 5.5v13a1 1 0 0 0 1.5.86l10.5-6.5a1 1 0 0 0 0-1.72L9.5 4.64A1 1 0 0 0 8 5.5z"/>',
    pause: '<rect x="6.5" y="5" width="4" height="14" rx="1.2"/><rect x="13.5" y="5" width="4" height="14" rx="1.2"/>',
    next: '<path d="M5 6.5v11a1 1 0 0 0 1.55.83L14.5 13v4.5a1 1 0 0 0 2 0v-11a1 1 0 0 0-2 0V11L6.55 5.67A1 1 0 0 0 5 6.5z"/><rect x="17" y="5.5" width="2.2" height="13" rx="1.1"/>',
    prev: '<path d="M19 6.5v11a1 1 0 0 1-1.55.83L9.5 13v4.5a1 1 0 0 1-2 0v-11a1 1 0 0 1 2 0V11l7.95-5.33A1 1 0 0 1 19 6.5z"/><rect x="4.8" y="5.5" width="2.2" height="13" rx="1.1"/>',
    back10: '<path d="M12 5V2L7 6l5 4V7a6 6 0 1 1-6 6H4a8 8 0 1 0 8-8z"/><text x="12" y="16.3" font-size="6.5" font-weight="700" text-anchor="middle" font-family="system-ui,sans-serif">10</text>',
    fwd10: '<path d="M12 5V2l5 4-5 4V7a6 6 0 1 0 6 6h2a8 8 0 1 1-8-8z"/><text x="12" y="16.3" font-size="6.5" font-weight="700" text-anchor="middle" font-family="system-ui,sans-serif">10</text>',
    vol: '<path d="M4 9.5v5a1 1 0 0 0 1 1h3l4.4 3.5a.8.8 0 0 0 1.3-.62V5.62a.8.8 0 0 0-1.3-.62L8 8.5H5a1 1 0 0 0-1 1z"/><path d="M16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>',
    muted: '<path d="M4 9.5v5a1 1 0 0 0 1 1h3l4.4 3.5a.8.8 0 0 0 1.3-.62V5.62a.8.8 0 0 0-1.3-.62L8 8.5H5a1 1 0 0 0-1 1z"/><path d="M16.5 9.5l5 5m0-5l-5 5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>',
    note: '<path d="M9 17.5V6.2a1 1 0 0 1 .78-.97l9-2a1 1 0 0 1 1.22.97v11.3" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/><circle cx="6.5" cy="17.5" r="2.5"/><circle cx="17.5" cy="15.5" r="2.5"/>',
    output: '<rect x="5" y="3" width="14" height="18" rx="2.5" fill="none" stroke="currentColor" stroke-width="1.7"/><circle cx="12" cy="14" r="3.2" fill="none" stroke="currentColor" stroke-width="1.7"/><circle cx="12" cy="7.2" r="1.2"/>',
    chevron: '<path d="M7 10l5 5 5-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
  };
  const svg = (name, size = 20) =>
    `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="currentColor" aria-hidden="true">${ICON[name]}</svg>`;

  document.head.append(el("style", { textContent: `
    #hubmedia [hidden], #hubmedia[hidden] { display:none !important; }
    #hubmedia { position:relative; overflow:hidden; padding:.8rem .85rem .75rem; }
    #hubmedia button { background:none; border:none; color:var(--text); padding:0; border-radius:999px;
                       display:inline-grid; place-items:center; -webkit-tap-highlight-color:transparent; }
    #hubmedia button:disabled { opacity:.35; }
    #hubmedia .hm-glow { position:absolute; inset:-40%; background-size:cover; background-position:center;
                         filter:blur(40px) saturate(1.4); opacity:.18; pointer-events:none; transition:opacity .4s; }
    #hubmedia > :not(.hm-glow) { position:relative; }
    .hm-head { display:flex; align-items:center; gap:.5rem; font-size:.72rem; text-transform:uppercase;
               letter-spacing:.08em; color:var(--dim); margin-bottom:.6rem; }
    .hm-head .hm-live { width:.45rem; height:.45rem; border-radius:50%; background:var(--line); flex:0 0 auto; }
    .hm-head .hm-live.on { background:var(--accent); box-shadow:0 0 0 3px color-mix(in srgb, var(--accent) 25%, transparent); }
    .hm-main { display:flex; gap:.8rem; align-items:center; min-width:0; }
    .hm-art { flex:0 0 auto; width:64px; height:64px; border-radius:10px; overflow:hidden; background:var(--bg);
              border:1px solid var(--line); display:grid; place-items:center; color:var(--dim); }
    .hm-art img { width:100%; height:100%; object-fit:cover; display:block; }
    .hm-meta { flex:1; min-width:0; }
    .hm-title { font-size:1rem; font-weight:600; line-height:1.25; overflow:hidden; text-overflow:ellipsis;
                display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; word-break:break-word; }
    .hm-title.idle { font-weight:500; color:var(--dim); }
    .hm-sub { color:var(--dim); font-size:.82rem; margin-top:.15rem; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
    .hm-src { color:var(--dim); font-size:.72rem; margin-top:.2rem; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
    .hm-prog { display:flex; align-items:center; gap:.55rem; margin-top:.7rem; font-size:.7rem; color:var(--dim);
               font-variant-numeric:tabular-nums; }
    .hm-track { flex:1; height:16px; display:flex; align-items:center; cursor:default; touch-action:manipulation; }
    .hm-track.seekable { cursor:pointer; }
    .hm-track > div { flex:1; height:4px; border-radius:2px; background:var(--line); overflow:hidden; }
    .hm-track > div > div { height:100%; width:0; background:var(--accent); border-radius:2px; }
    .hm-bottom { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:.5rem .9rem; margin-top:.55rem; }
    .hm-ctrls { display:flex; align-items:center; gap:.35rem; margin:0 auto; }
    .hm-ctrls button { width:40px; height:40px; }
    .hm-ctrls button.skip { width:34px; height:34px; color:var(--dim); }
    .hm-ctrls button.big { width:46px; height:46px; background:var(--accent) !important; color:var(--bg) !important; }
    #hubmedia button:not(:disabled):hover { background:color-mix(in srgb, var(--text) 8%, transparent); }
    .hm-vol { display:flex; align-items:center; gap:.45rem; flex:1 1 200px; min-width:0; }
    .hm-vol > button { width:34px; height:34px; flex:0 0 auto; }
    .hm-vol > button.on { color:var(--danger); }
    .hm-pct { font-size:.72rem; color:var(--dim); width:2.4em; text-align:right; font-variant-numeric:tabular-nums; }
    .hm-vol input[type=range] { flex:1; min-width:0; height:28px; margin:0; background:none; -webkit-appearance:none; appearance:none; cursor:pointer; }
    .hm-vol input[type=range]::-webkit-slider-runnable-track { height:4px; border-radius:2px;
        background:linear-gradient(to right, var(--accent) var(--p, 0%), var(--line) var(--p, 0%)); }
    .hm-vol input[type=range]::-moz-range-track { height:4px; border-radius:2px; background:var(--line); }
    .hm-vol input[type=range]::-moz-range-progress { height:4px; border-radius:2px; background:var(--accent); }
    .hm-vol input[type=range]::-webkit-slider-thumb { -webkit-appearance:none; width:16px; height:16px; margin-top:-6px;
        border-radius:50%; background:var(--text); border:none; }
    .hm-vol input[type=range]::-moz-range-thumb { width:16px; height:16px; border-radius:50%; background:var(--text); border:none; }
    .hm-vol.muted input[type=range] { opacity:.45; }
    #hubmedia button.hm-outbtn { width:auto; height:30px; padding:0 .45rem 0 .5rem; gap:.25rem; display:inline-flex;
        align-items:center; color:var(--dim); font-size:.75rem; max-width:45%; border:1px solid var(--line); }
    .hm-outbtn span { white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
    .hm-outbtn svg:last-child { transition:transform .2s; flex:0 0 auto; }
    .hm-outbtn.open svg:last-child { transform:rotate(180deg); }
    .hm-list { display:flex; flex-wrap:wrap; gap:.35rem; margin-top:.6rem; }
    #hubmedia .hm-list button.chip { border:1px solid var(--line); padding:.3rem .7rem; font-size:.8rem;
        display:inline-block; max-width:100%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    #hubmedia .hm-list button.chip.on { border-color:var(--accent); color:var(--accent);
        background:color-mix(in srgb, var(--accent) 12%, transparent); }
    .hm-players { margin:0 0 .6rem; }
  ` }));

  // --- build the card once; render() only updates it ---
  const card = el("section", { className: "card", id: "hubmedia", hidden: true });
  const glow = el("div", { className: "hm-glow" });
  const live = el("span", { className: "hm-live" });
  const heading = el("span", { textContent: "Now playing" });
  const players = el("div", { className: "hm-list hm-players", hidden: true });
  const art = el("div", { className: "hm-art" });
  const title = el("div", { className: "hm-title" });
  const sub = el("div", { className: "hm-sub" });
  const src = el("div", { className: "hm-src" });
  const tNow = el("span", { textContent: "0:00" });
  const tLen = el("span", { textContent: "0:00" });
  const fill = el("div");
  const track = el("div", { className: "hm-track", title: "Tap to jump" }, el("div", {}, fill));
  const prog = el("div", { className: "hm-prog" }, tNow, track, tLen);
  const mkBtn = (icon, label, cls = "", size) => {
    const b = el("button", { className: cls, title: label, type: "button" });
    b.setAttribute("aria-label", label);
    b.innerHTML = svg(icon, size);
    return b;
  };
  const back = mkBtn("back10", "Back 10 seconds", "skip", 22);
  const prev = mkBtn("prev", "Previous", "", 22);
  const play = mkBtn("play", "Play", "big", 24);
  const next = mkBtn("next", "Next", "", 22);
  const fwd = mkBtn("fwd10", "Forward 10 seconds", "skip", 22);
  const ctrls = el("div", { className: "hm-ctrls" }, back, prev, play, next, fwd);
  const mute = mkBtn("vol", "Mute", "", 20);
  const slider = el("input", { type: "range", min: 0, max: 100, step: 1, value: 0 });
  slider.setAttribute("aria-label", "Hub volume");
  const pct = el("span", { className: "hm-pct" });
  const outBtn = el("button", { className: "hm-outbtn", type: "button", title: "Audio output" });
  const outList = el("div", { className: "hm-list", hidden: true });
  const vol = el("div", { className: "hm-vol" }, mute, slider, pct, outBtn);
  const bottom = el("div", { className: "hm-bottom" }, ctrls, vol);
  card.append(glow,
    el("div", { className: "hm-head" }, live, heading),
    players,
    el("div", { className: "hm-main" }, art, el("div", { className: "hm-meta" }, title, sub, src)),
    prog, bottom, outList);
  (document.getElementById("features") || document.body).append(card);

  let data = null;        // last snapshot from the hub
  let picked = null;      // player chosen in the picker; cleared when another starts playing
  let lastActive = null;
  let base = null;        // {pos, at}: where the active track was when we last heard
  let dragging = false;   // don't yank the slider while a finger is on it
  let pollTimer = null, onScreen = false, busy = false;

  const fmt = s => {
    if (s == null || !isFinite(s)) return "0:00";
    s = Math.max(0, Math.floor(s));
    const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, sec = String(s % 60).padStart(2, "0");
    return h ? `${h}:${String(m).padStart(2, "0")}:${sec}` : `${m}:${sec}`;
  };

  function current() {
    if (!data || !data.players.length) return null;
    return data.players.find(p => p.id === picked) || data.players.find(p => p.id === data.active) || data.players[0];
  }

  function setArt(url) {
    if (art.dataset.url === (url || "")) return;
    art.dataset.url = url || "";
    art.innerHTML = "";
    glow.style.backgroundImage = "";
    if (!url) { art.innerHTML = svg("note", 28); return; }
    const img = el("img", { alt: "", src: url, referrerPolicy: "no-referrer" });
    img.onerror = () => { art.innerHTML = svg("note", 28); glow.style.backgroundImage = ""; };
    img.onload = () => { glow.style.backgroundImage = `url("${url.replace(/"/g, "%22")}")`; };
    art.append(img);
  }

  function position(p) {
    if (!p || p.position == null) return null;
    let pos = base ? base.pos : p.position;
    if (p.status === "Playing" && base) pos += (Date.now() - base.at) / 1000;
    return p.length ? Math.min(pos, p.length) : pos;
  }

  function tick() {
    const p = current();
    if (!p || !p.length) return;
    const pos = position(p);
    fill.style.width = `${Math.min(100, (pos / p.length) * 100)}%`;
    tNow.textContent = fmt(pos);
  }

  function renderVolume() {
    const v = data.volume;
    vol.hidden = !v;
    if (!v) return;
    const level = Math.round(Math.min(v.level, 1) * 100);
    if (!dragging) slider.value = level;
    slider.style.setProperty("--p", `${slider.value}%`);
    pct.textContent = Math.round(v.level * 100) + "%";
    vol.classList.toggle("muted", v.muted);
    mute.classList.toggle("on", v.muted);
    mute.innerHTML = svg(v.muted ? "muted" : "vol", 20);
    mute.title = v.muted ? "Unmute" : "Mute";
    mute.setAttribute("aria-label", mute.title);

    const sinks = data.sinks || [];
    const def = sinks.find(s => s.default);
    outBtn.hidden = sinks.length < 2;
    if (outBtn.hidden) outList.hidden = true;
    outBtn.innerHTML = svg("output", 15);
    outBtn.append(el("span", { textContent: def ? def.name : "Output" }));
    outBtn.insertAdjacentHTML("beforeend", svg("chevron", 14));
    outBtn.classList.toggle("open", !outList.hidden);
    outList.replaceChildren(...sinks.map(s => {
      const b = el("button", { type: "button", className: "chip" + (s.default ? " on" : ""), textContent: s.name, title: s.full });
      b.onclick = () => { if (!s.default) setVolume({ sink: s.id }, () => sinks.forEach(x => (x.default = x === s))); };
      return b;
    }));
  }

  function render() {
    card.hidden = !data || !data.available;
    if (card.hidden) return;
    const p = current();
    if (p && p.id !== lastActive) { base = null; lastActive = p.id; }
    heading.textContent = `Now playing on ${data.hub || "the hub"}`;

    // picker only when there's a choice to make
    players.hidden = !data.players || data.players.length < 2;
    players.replaceChildren(...(data.players || []).map(q => {
      const b = el("button", { type: "button", className: "chip" + (p && q.id === p.id ? " on" : ""),
                               textContent: q.name + (q.status === "Playing" ? " ♪" : "") });
      b.onclick = () => { picked = q.id; base = null; render(); };
      return b;
    }));

    const hasTrack = !!(p && (p.title || p.artist));
    live.classList.toggle("on", !!p && p.status === "Playing");
    title.classList.toggle("idle", !hasTrack);
    title.textContent = hasTrack ? (p.title || "Unknown track") : "Nothing playing";
    sub.textContent = hasTrack ? [p.artist, p.album].filter(Boolean).join(" · ") : "";
    sub.hidden = !sub.textContent;
    // with a picker showing, the highlighted chip already says which player this is
    src.textContent = !p ? "No media players open on the hub" : players.hidden ? p.name : "";
    src.hidden = !src.textContent;
    setArt(hasTrack ? p.art : null);

    ctrls.hidden = !hasTrack;
    if (hasTrack) {
      const playing = p.status === "Playing";
      play.innerHTML = svg(playing ? "pause" : "play", 24);
      play.title = playing ? "Pause" : "Play";
      play.setAttribute("aria-label", play.title);
      play.disabled = !(p.can_play || p.can_pause);
      prev.disabled = !p.can_goprevious;
      next.disabled = !p.can_gonext;
      back.hidden = fwd.hidden = !p.can_seek;
    }
    prog.hidden = !hasTrack || !p.length;
    track.classList.toggle("seekable", !!(p && p.can_seek));
    if (hasTrack && p.length) {
      tLen.textContent = fmt(p.length);
      tick();
    }
    renderVolume();
  }

  // --- talking to the hub ---

  async function poll() {
    clearTimeout(pollTimer);
    if (busy) return schedule();
    const res = await fetch("/api/hub/media").catch(() => null);
    if (res && res.ok) {
      const d = await res.json().catch(() => null);
      if (d) accept(d);
    }
    schedule();
  }

  function accept(d) {
    // something else started playing: follow it, like a phone's media widget does
    if (d.active !== (data && data.active) && d.players.some(p => p.id === d.active && p.status === "Playing")) picked = null;
    data = d;
    const p = current();
    base = p && p.position != null ? { pos: p.position, at: Date.now() } : null;
    render();
  }

  function schedule(ms = POLL_MS) {
    clearTimeout(pollTimer);
    // before the first answer we don't know whether to show the card at all
    if (!document.hidden && (onScreen || !data)) pollTimer = setTimeout(poll, ms);
  }

  async function post(url, payload) {
    const res = await fetch(url, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload),
    }).catch(() => null);
    if (!res || !res.ok) {
      const err = res && (await res.json().catch(() => ({}))).error;
      flash(err ? `Hub: ${err}` : "Couldn't reach the hub's player");
      return null;
    }
    return res.json().catch(() => null);
  }

  async function media(action, extra = {}, optimistic) {
    const p = current();
    if (!p) return;
    optimistic && optimistic(p);
    render();
    busy = true;
    await post("/api/hub/media", { action, player: p.id, ...extra });
    busy = false;
    // players take a moment to report the change; the next poll shows the truth
    schedule(700);
  }

  let volTimer = null, volPending = null;
  async function setVolume(payload, optimistic) {
    if (optimistic && data) { optimistic(data); render(); }
    busy = true;
    const d = await post("/api/hub/volume", payload);
    busy = false;
    if (d && !volPending) accept(d);
    else schedule(400);
  }

  // slider: send while dragging, at most every 150 ms, then the final value
  function sendLevel() {
    volTimer = null;
    if (volPending == null) return;
    const level = volPending;
    volPending = null;
    setVolume({ level });
  }
  slider.addEventListener("input", () => {
    dragging = true;
    slider.style.setProperty("--p", `${slider.value}%`);
    pct.textContent = slider.value + "%";
    if (data && data.volume) data.volume.level = slider.value / 100;
    volPending = slider.value / 100;
    if (!volTimer) volTimer = setTimeout(sendLevel, 150);
  });
  slider.addEventListener("change", () => {
    clearTimeout(volTimer);
    volTimer = null;
    volPending = slider.value / 100;
    sendLevel();
    setTimeout(() => (dragging = false), 800);
  });

  mute.onclick = () => setVolume({ mute: "toggle" }, d => d.volume && (d.volume.muted = !d.volume.muted));
  outBtn.onclick = () => { outList.hidden = !outList.hidden; outBtn.classList.toggle("open", !outList.hidden); };

  play.onclick = () => media("play-pause", {}, p => {
    base = { pos: position(p) ?? 0, at: Date.now() };
    p.status = p.status === "Playing" ? "Paused" : "Playing";
  });
  next.onclick = () => media("next", {}, () => { base = null; title.style.opacity = ".5"; setTimeout(() => (title.style.opacity = ""), 900); });
  prev.onclick = () => media("previous", {}, () => { base = null; });
  const skip = secs => media("seek", { offset: secs }, p => {
    const pos = Math.max(0, (position(p) ?? 0) + secs);
    base = { pos: p.length ? Math.min(pos, p.length) : pos, at: Date.now() };
  });
  back.onclick = () => skip(-10);
  fwd.onclick = () => skip(10);
  track.addEventListener("click", e => {
    const p = current();
    if (!p || !p.can_seek || !p.length) return;
    const r = track.getBoundingClientRect();
    const at = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * p.length;
    media("seek", { position: Math.round(at * 100) / 100 }, () => { base = { pos: at, at: Date.now() }; });
  });

  // the progress bar moves between polls without asking the hub
  setInterval(() => { if (onScreen && !document.hidden) tick(); }, 500);

  new IntersectionObserver(entries => {
    onScreen = entries.some(e => e.isIntersecting);
    if (onScreen) poll();
    else clearTimeout(pollTimer);
  }).observe(card);
  document.addEventListener("visibilitychange", () => { if (!document.hidden && onScreen) poll(); });

  poll();
})();
