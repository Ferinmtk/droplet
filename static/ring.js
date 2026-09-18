// Find a device: ring another device (or the hub) to find it by sound, and
// ring loudly here when someone rings this one. Server side is ring.py.
(() => {
  const RING_MS = 60000;   // matches RING_SECONDS in ring.py
  const POLL_MS = 3000;

  document.head.append(el("style", { textContent: `
    #ring-card { margin-top:1rem; }
    #ring-card .ring-head { display:flex; flex-wrap:wrap; align-items:baseline; justify-content:space-between;
                            gap:.25rem .75rem; margin-bottom:.6rem; }
    #ring-card .ring-grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(10rem, 1fr)); gap:.5rem; }
    .ring-btn { display:flex; align-items:center; gap:.6rem; min-height:3rem; padding:.5rem .7rem; text-align:left; }
    .ring-btn .ico { flex:0 0 auto; font-size:1.15rem; line-height:1; }
    .ring-btn .lbl { flex:1; min-width:0; }
    .ring-btn .lbl b { display:block; font-weight:600; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .ring-btn .lbl small { display:block; color:var(--dim); font-size:.72rem; margin-top:.1rem; }
    .ring-btn .dot { margin-right:0; }
    .ring-btn.ringing { border-color:var(--danger); color:var(--danger); animation:ring-glow 1.2s ease-out infinite; }
    .ring-btn.ringing .lbl small { color:inherit; opacity:.85; }
    .ring-btn.ringing .ico { animation:ring-shake .6s ease-in-out infinite; }
    @keyframes ring-glow { from { box-shadow:0 0 0 0 color-mix(in srgb, var(--danger) 55%, transparent); }
                           to   { box-shadow:0 0 0 .6rem transparent; } }
    @keyframes ring-shake { 0%,100% { transform:rotate(0); } 25% { transform:rotate(-14deg); } 75% { transform:rotate(14deg); } }

    #ring-overlay { position:fixed; inset:0; z-index:1000; display:flex; flex-direction:column; align-items:center;
                    justify-content:center; gap:1rem; padding:max(1.5rem, env(safe-area-inset-top)) 1rem
                    max(1.5rem, env(safe-area-inset-bottom)); text-align:center; color:var(--text);
                    background:radial-gradient(circle at 50% 38%, color-mix(in srgb, var(--danger) 30%, var(--bg)) 0,
                                               var(--bg) 70%); }
    #ring-overlay .ring-waves { position:relative; width:9rem; height:9rem; display:grid; place-items:center; margin-bottom:.5rem; }
    #ring-overlay .ring-waves i { position:absolute; inset:0; border-radius:50%; border:3px solid var(--danger);
                                  opacity:0; animation:ring-wave 1.8s ease-out infinite; }
    #ring-overlay .ring-waves i:nth-child(2) { animation-delay:.6s; }
    #ring-overlay .ring-waves i:nth-child(3) { animation-delay:1.2s; }
    #ring-overlay .ring-waves span { font-size:4rem; line-height:1; animation:ring-shake .5s ease-in-out infinite; }
    @keyframes ring-wave { from { transform:scale(.45); opacity:.9; } to { transform:scale(1.25); opacity:0; } }
    #ring-overlay h2 { font-size:clamp(1.4rem, 6vw, 2rem); font-weight:700; text-transform:none; letter-spacing:0;
                       color:var(--text); margin:0; max-width:24rem; line-height:1.25; overflow-wrap:anywhere; }
    #ring-overlay .ring-sub { color:var(--dim); font-size:.95rem; margin:0; }
    #ring-stop { margin-top:1rem; width:min(20rem, 100%); min-height:4.5rem; border-radius:999px; border:none;
                 background:var(--danger); color:var(--bg); font-size:1.5rem; font-weight:700; letter-spacing:.02em;
                 box-shadow:0 .5rem 1.5rem color-mix(in srgb, var(--danger) 45%, transparent); }
    #ring-stop:focus-visible { outline:3px solid var(--text); outline-offset:4px; }
    #ring-stop:active { transform:scale(.97); }
    #ring-overlay .ring-hint { background:var(--card); border:1px solid var(--line); border-radius:999px;
                               padding:.45rem .9rem; font-size:.9rem; margin:0; }
    @media (prefers-reduced-motion: reduce) {
      .ring-btn.ringing, .ring-btn.ringing .ico, #ring-overlay .ring-waves span { animation:none; }
      #ring-overlay .ring-waves i { animation-duration:4s; }
    }
  ` }));

  // --- ringing other devices ---

  const card = el("section", { className: "card", id: "ring-card" });
  card.setAttribute("aria-label", "Find a device");
  document.getElementById("features").append(card);

  const outgoing = new Map();   // device id or "hub" -> time the ring started
  let drawn = "";

  function state(d) {
    if (d.online) return "open now";
    return d.push ? "rings via notification" : "rings only while droplet is open";
  }

  function ringButton(key, icon, name, sub) {
    const on = outgoing.has(key);
    const b = el("button", { className: "ring-btn" + (on ? " ringing" : "") },
      typeof icon === "string" ? el("span", { className: "ico", textContent: on ? "📣" : icon, ariaHidden: "true" }) : icon,
      el("span", { className: "lbl" },
        el("b", { textContent: on ? `Stop ringing ${name}` : `Ring ${name}` }),
        el("small", { textContent: on ? "ringing… tap to stop" : sub })));
    b.setAttribute("aria-pressed", on);
    b.onclick = () => (on ? stopOutgoing(key, name) : ring(key, name));
    return b;
  }

  function renderCard() {
    const devs = others();
    for (const key of outgoing.keys()) if (key !== "hub" && !devs.some(d => d.id === key)) outgoing.delete(key);
    // only redraw when something visible changed, so focus isn't lost every poll
    const sig = JSON.stringify([devs.map(d => [d.id, d.name, d.online, d.push]), [...outgoing.keys()]]);
    if (sig === drawn) return;
    drawn = sig;
    const focused = document.activeElement?.closest?.("#ring-card .ring-btn")?.dataset.key;
    const buttons = devs.map(d => {
      const icon = outgoing.has(d.id) ? "📣" : el("span", { className: "ico" }, el("span", { className: "dot" + (d.online ? " online" : "") }));
      return Object.assign(ringButton(d.id, icon, d.name, state(d)), { title: d.online ? "online" : "offline" });
    });
    buttons.push(ringButton("hub", "🔊", "the hub", "plays a sound on the hub"));
    const keys = [...devs.map(d => d.id), "hub"];
    buttons.forEach((b, i) => { b.dataset.key = keys[i]; });
    card.replaceChildren(
      el("div", { className: "ring-head" }, el("b", { textContent: "📣 Find a device" }),
        el("span", { className: "dim", textContent: "Rings for up to a minute" })),
      el("div", { className: "ring-grid" }, ...buttons));
    if (focused) card.querySelector(`[data-key="${focused}"]`)?.focus();
  }

  async function ring(key, name) {
    const url = key === "hub" ? "/api/hub/ring" : `/api/device/${key}/ring`;
    const res = await fetch(url, { method: "POST" }).catch(() => null);
    if (!res || !res.ok) {
      const err = res && (await res.json().catch(() => ({}))).error;
      flash(err ? `Can't ring ${name}: ${err}` : `Couldn't ring ${name}, is the hub reachable?`);
      return;
    }
    outgoing.set(key, Date.now());
    flash(`Ringing ${name}…`);
    renderCard();
  }

  async function stopOutgoing(key, name) {
    outgoing.delete(key);
    renderCard();
    const url = key === "hub" ? "/api/hub/ring/stop" : `/api/device/${key}/ring/stop`;
    await fetch(url, { method: "POST" }).catch(() => null);
    flash(`Stopped ringing ${name}`);
  }

  // notice when the other side answers (or the ring runs out)
  async function pollOutgoing() {
    for (const [key, started] of outgoing) {
      const url = key === "hub" ? "/api/hub/ring" : `/api/device/${key}/ring`;
      const res = await fetch(url).catch(() => null);
      if (!res || !res.ok || !outgoing.has(key)) continue;
      const d = await res.json();
      if (key === "hub" ? d.ringing : d.ring) continue;
      outgoing.delete(key);
      if (key !== "hub") {
        const name = (others().find(x => x.id === key) || {}).name || "The device";
        flash(Date.now() - started >= RING_MS - POLL_MS ? `${name} didn't answer` : `${name} answered, found it 🎉`);
      }
    }
    renderCard();
  }

  // --- this device being rung ---

  let actx = null;
  function audio() {
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!actx && AC) actx = new AC();
    return actx;
  }
  // browsers only let a page make sound after the user has touched it; any
  // touch on the page (before or during a ring) unlocks it
  const unlock = () => { const c = audio(); if (c && c.state === "suspended") c.resume().catch(() => {}); };
  for (const ev of ["pointerdown", "keydown", "touchend"]) document.addEventListener(ev, unlock, { capture: true, passive: true });

  const dismissed = new Set();   // ring ids already answered here, so a slow stop can't bring the overlay back
  let active = null;

  function startTone() {
    const c = audio();
    if (!c) return null;
    c.resume().catch(() => {});
    const out = c.createGain();
    out.gain.value = 0;
    out.connect(c.destination);
    const osc = c.createOscillator();
    osc.type = "square";   // harsh on purpose: cuts through a room better than a sine
    osc.connect(out);
    osc.start();
    // an old-phone trill: 1.2 s warbling between two pitches, then 0.6 s quiet.
    // Scheduled a couple of seconds ahead so throttled timers don't leave gaps.
    let next = c.currentTime + 0.05;
    const schedule = () => {
      while (next < c.currentTime + 2.5) {
        for (let i = 0; i < 12; i++) {
          const t = next + i * 0.1;
          osc.frequency.setValueAtTime(i % 2 ? 1400 : 1050, t);
        }
        out.gain.setValueAtTime(0.5, next);
        out.gain.setValueAtTime(0, next + 1.2);
        next += 1.8;
      }
    };
    schedule();
    const timer = setInterval(schedule, 500);
    return () => { clearInterval(timer); osc.stop(); osc.disconnect(); out.disconnect(); };
  }

  function startRinging(r) {
    if (active) stopRinging(false);
    const stopBtn = el("button", { id: "ring-stop", type: "button", textContent: "Stop" });
    const hint = el("p", { className: "ring-hint", textContent: "🔇 Tap anywhere to hear it", hidden: true });
    const left = el("p", { className: "ring-sub" });
    const overlay = el("div", { id: "ring-overlay", role: "alertdialog" },
      el("div", { className: "ring-waves", ariaHidden: "true" }, el("i"), el("i"), el("i"), el("span", { textContent: "📣" })),
      el("h2", { id: "ring-title", textContent: `${r.from} is ringing this device` }),
      left, stopBtn, hint);
    overlay.setAttribute("aria-modal", "true");
    overlay.setAttribute("aria-labelledby", "ring-title");
    stopBtn.onclick = () => stopRinging(true);
    document.body.append(overlay);

    const prevFocus = document.activeElement;
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    stopBtn.focus();

    const stopTone = startTone();
    const c = actx;
    const showHint = () => { hint.hidden = !c || c.state === "running"; };
    if (c) c.onstatechange = showHint;
    setTimeout(showHint, 300);   // give resume() a moment before saying it's blocked
    const buzz = () => navigator.vibrate?.([800, 300, 800, 300, 800]);
    buzz();
    const started = Date.now();
    const tick = () => {
      const s = Math.max(0, Math.ceil((RING_MS - (Date.now() - started)) / 1000));
      left.textContent = `Stops by itself in ${s} s`;
      if (!s) stopRinging(false);
    };
    tick();
    const timers = [setInterval(buzz, 3000), setInterval(tick, 1000)];
    const onKey = e => { if (e.key === "Escape") stopRinging(true); };
    document.addEventListener("keydown", onKey);

    active = {
      id: r.id,
      end() {
        timers.forEach(clearInterval);
        stopTone?.();
        if (c) c.onstatechange = null;
        navigator.vibrate?.(0);
        document.removeEventListener("keydown", onKey);
        overlay.remove();
        document.body.style.overflow = prevOverflow;
        prevFocus?.focus?.();
      },
    };
  }

  function stopRinging(tellHub) {
    if (!active) return;
    const { id } = active;
    dismissed.add(id);
    active.end();
    active = null;
    if (tellHub) fetch("/api/ring/stop", { method: "POST" }).catch(() => {});
    // the push notification for this ring is still up; answering here answers it too
    navigator.serviceWorker?.getRegistration().then(reg =>
      reg?.getNotifications({ tag: "ring" }).then(ns => ns.forEach(n => n.close()))).catch(() => {});
  }

  async function checkRing() {
    const res = await fetch("/api/ring").catch(() => null);
    if (!res || !res.ok) return;   // 400 = this browser isn't a named device, so nobody can ring it
    const { ring: r } = await res.json();
    if (r && !dismissed.has(r.id) && active?.id !== r.id) startRinging(r);
    else if (!r && active) stopRinging(false);
  }

  // opened from a ring notification: /#ring
  function fromHash() {
    if (location.hash !== "#ring") return;
    history.replaceState(null, "", location.pathname + location.search);
    checkRing();
  }

  renderCard();
  fromHash();
  window.addEventListener("hashchange", fromHash);
  document.addEventListener("visibilitychange", () => { if (!document.hidden && me) checkRing(); });
  setInterval(() => {
    if (document.hidden) return;
    if (me) checkRing();
    if (outgoing.size) pollOutgoing();
  }, POLL_MS);
  setInterval(renderCard, 1000);   // picks up device changes from the main page's refresh
})();
