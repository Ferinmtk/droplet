// TV remote: a card in the Hub tab, a full-screen remote, and pairing.
// The hub talks to the TV (Android TV Remote protocol v2); this page only
// talks to the hub. Server side is tv.py.
(() => {
  const POLL_MS = 3000, POLL_OPEN_MS = 1500;
  const HOLD_MS = 550;              // held this long, OK / Home / Back become a long press
  const REPEAT_DELAY = 380, REPEAT_MS = 110;
  const SWIPE_STEP = 34;            // touchpad: px of travel per D-pad step
  const LS_MODE = "droplet-tv-mode", LS_HAPTICS = "droplet-tv-haptics";

  // --- glyphs (stroke icons in the page's style; app tiles are plain shapes and letters) ---
  const G = {
    tv: '<rect x="2.5" y="4.5" width="19" height="13" rx="2.5"/><path d="M9 20.5h6"/>',
    power: '<path d="M12 3.5v8"/><path d="M6.6 6.8a7.6 7.6 0 1 0 10.8 0"/>',
    up: '<path d="m6.5 14.5 5.5-5.5 5.5 5.5"/>', down: '<path d="m6.5 9.5 5.5 5.5 5.5-5.5"/>',
    left: '<path d="m14.5 6.5-5.5 5.5 5.5 5.5"/>', right: '<path d="m9.5 6.5 5.5 5.5-5.5 5.5"/>',
    back: '<path d="M9 14.5 4.5 10 9 5.5"/><path d="M4.5 10H15a5 5 0 0 1 0 10h-3.5"/>',
    home: '<path d="M4 10.5 12 4l8 6.5"/><path d="M6 9.2V19a1 1 0 0 0 1 1h3.5v-5.5h3V20H17a1 1 0 0 0 1-1V9.2"/>',
    menu: '<path d="M4.5 7h15M4.5 12h15M4.5 17h15"/>',
    plus: '<path d="M12 6v12M6 12h12"/>', minus: '<path d="M6 12h12"/>',
    vol: '<path d="M4 9.5v5a1 1 0 0 0 1 1h3l4.5 3.6a.8.8 0 0 0 1.3-.6V5.5a.8.8 0 0 0-1.3-.6L8 8.5H5a1 1 0 0 0-1 1z"/><path d="M16.5 8.8a4.5 4.5 0 0 1 0 6.4M19 6.3a8 8 0 0 1 0 11.4"/>',
    muted: '<path d="M4 9.5v5a1 1 0 0 0 1 1h3l4.5 3.6a.8.8 0 0 0 1.3-.6V5.5a.8.8 0 0 0-1.3-.6L8 8.5H5a1 1 0 0 0-1 1z"/><path d="m16.5 9.5 5 5m0-5-5 5"/>',
    playpause: '<path d="M4.5 6v12l8.5-6z" fill="currentColor"/><path d="M16.5 6.5v11M20 6.5v11"/>',
    prev: '<path d="M18.5 6.5v11L10.5 12z" fill="currentColor"/><path d="M6.5 6.5v11"/>',
    next: '<path d="M5.5 6.5v11l8-5.5z" fill="currentColor"/><path d="M17.5 6.5v11"/>',
    rew: '<path d="M11.5 7v10L4.5 12zM19.5 7v10l-7-5z" fill="currentColor"/>',
    ff: '<path d="M12.5 7v10l7-5zM4.5 7v10l7-5z" fill="currentColor"/>',
    stop: '<rect x="6.5" y="6.5" width="11" height="11" rx="2.2" fill="currentColor"/>',
    keyboard: '<rect x="2.5" y="6" width="19" height="12.5" rx="2.5"/><path d="M6.5 10h.01M10 10h.01M14 10h.01M17.5 10h.01M7.5 14.5h9"/>',
    pad: '<rect x="3.5" y="3.5" width="17" height="17" rx="4.5"/><circle cx="12" cy="12" r="2.4"/>',
    dpad: '<circle cx="12" cy="12" r="8.5"/><circle cx="12" cy="12" r="3.2"/>',
    info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5M12 7.8h.01"/>',
    guide: '<rect x="3" y="4.5" width="18" height="15" rx="2.5"/><path d="M3 9.5h18M9 9.5v10"/>',
    input: '<path d="M3.5 12h10M10 8.5l3.5 3.5-3.5 3.5"/><path d="M8 7V5.5A1.5 1.5 0 0 1 9.5 4h9A1.5 1.5 0 0 1 20 5.5v13a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 8 18.5V17"/>',
    close: '<path d="m6 9.5 6 6 6-6"/>',
    link: '<path d="M10 13.5a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M14 10.5a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>',
    del: '<path d="M9 5.5h10.5A1.5 1.5 0 0 1 21 7v10a1.5 1.5 0 0 1-1.5 1.5H9L3.5 12z"/><path d="m12.5 9.5 5 5m0-5-5 5"/>',
    enter: '<path d="M19.5 5v6.5A2.5 2.5 0 0 1 17 14H5"/><path d="m9 10-4 4 4 4"/>',
    apps: '<rect x="4" y="4" width="6.5" height="6.5" rx="1.8"/><rect x="13.5" y="4" width="6.5" height="6.5" rx="1.8"/><rect x="4" y="13.5" width="6.5" height="6.5" rx="1.8"/><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.8"/>',
    buzz: '<rect x="7.5" y="3.5" width="9" height="17" rx="2.5"/><path d="M4 9v6M20 9v6"/>',
    wake: '<path d="M12 3.5v8"/><path d="M6.6 6.8a7.6 7.6 0 1 0 10.8 0"/>',
    ch: '<rect x="3" y="6.5" width="18" height="13" rx="2.5"/><path d="m8 3 4 3.5L16 3"/>',
  };
  const T = (s, x = 12, size = 11) =>
    `<text x="${x}" y="${12 + size * 0.36}" font-size="${size}" font-weight="800" text-anchor="middle" fill="currentColor" stroke="none" font-family="ui-rounded,system-ui,sans-serif">${s}</text>`;
  const APP_GLYPH = {
    youtube: '<rect x="2.5" y="5.5" width="19" height="13" rx="4"/><path d="M10 9.3v5.4l4.6-2.7z" fill="currentColor"/>',
    netflix: T("N", 12, 15),
    prime: T("P", 12, 14) + '<path d="M6.5 19.2c3.5 1.4 7.5 1.4 11 0"/>',
    spotify: '<path d="M6 15.5v-3M9.3 15.5v-7M12.6 15.5V6.5M15.9 15.5v-5M19.2 15.5v-2"/>',
    showmax: T("S", 12, 15),
    disney: T("D+", 12, 11.5),
    plex: '<path d="m9 5 6.5 7L9 19"/>',
    home: G.home,
  };
  function svg(inner, size = 22, cls = "") {
    const t = document.createElement("template");
    t.innerHTML = `<svg class="ico ${cls}" viewBox="0 0 24 24" width="${size}" height="${size}" aria-hidden="true">${inner}</svg>`;
    return t.content.firstChild;
  }
  // own glyphs first, then the page's sprite (gear, search, trash…)
  const g = (name, size, cls) => {
    if (G[name]) return svg(G[name], size, cls);
    const i = icon(name, cls);
    i.setAttribute("width", size); i.setAttribute("height", size);
    return i;
  };
  // cardHead() takes sprite names, so the TV glyph joins the page's sprite
  const defs = document.querySelector("svg defs");
  if (defs && !document.getElementById("i-tv")) {
    const sym = document.createElementNS("http://www.w3.org/2000/svg", "symbol");
    sym.id = "i-tv";
    sym.setAttribute("viewBox", "0 0 24 24");
    sym.innerHTML = G.tv;
    defs.append(sym);
  }

  document.head.append(el("style", { textContent: `
    /* ---- the card ---- */
    #tv-card .tv-tvrow { display:flex; align-items:center; gap:14px; }
    #tv-card .tv-screen { width:58px; height:40px; border-radius:9px; flex:0 0 auto; position:relative; display:grid; place-items:center;
      background:var(--card-2); border:1.5px solid var(--line-2); color:var(--dim); transition:all .4s var(--ease); }
    #tv-card .tv-screen::after { content:""; position:absolute; bottom:-7px; left:50%; width:18px; height:3px; margin-left:-9px;
      border-radius:2px; background:var(--line-2); }
    #tv-card .tv-screen.on { background:var(--tide); border-color:transparent; color:var(--on-accent);
      box-shadow:0 8px 26px -8px var(--accent-2), 0 0 0 5px var(--accent-soft); }
    #tv-card .tv-screen.on::before { content:""; position:absolute; inset:0; border-radius:inherit; opacity:.45;
      background:radial-gradient(60% 70% at 30% 25%, rgba(255,255,255,.55), transparent 60%); }
    #tv-card .tv-screen .ico { width:18px; height:18px; position:relative; }
    #tv-card .tv-name { font-family:var(--font-display); font-size:17px; font-weight:750; letter-spacing:-.015em;
      overflow:hidden; text-overflow:ellipsis; white-space:nowrap; display:block; }
    .tv-state { display:flex; align-items:center; gap:6px; font-size:13px; color:var(--dim); margin-top:2px; min-width:0; }
    .tv-state span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .tv-dot { width:7px; height:7px; border-radius:50%; background:var(--dim); flex:0 0 auto; }
    .tv-state.on .tv-dot { background:var(--ok); box-shadow:0 0 0 3px var(--accent-soft); }
    .tv-state.on { color:var(--text); }
    .tv-state.bad .tv-dot { background:var(--coral); }
    .tv-state.bad { color:var(--coral); }
    .tv-state.wait .tv-dot { animation:tv-blink 1s ease-in-out infinite; }
    @keyframes tv-blink { 50% { opacity:.25; } }
    #tv-card .tv-pwr { width:44px; height:44px; padding:0; border-radius:50%; flex:0 0 auto; }
    #tv-card .tv-pwr.on { color:var(--coral); background:var(--coral-soft); border-color:transparent; }
    .tv-pwr.busy .ico { animation:tv-spin 1s linear infinite; }
    @keyframes tv-spin { to { transform:rotate(360deg); } }
    .tv-vol { display:flex; align-items:center; gap:10px; color:var(--dim); font-size:12px; font-variant-numeric:tabular-nums; }
    .tv-vol .ico { width:17px; height:17px; }
    .tv-bar { flex:1; height:6px; border-radius:999px; background:var(--card-2); overflow:hidden; }
    .tv-bar i { display:block; height:100%; width:0; border-radius:inherit; background:var(--tide); transition:width .35s var(--ease); }
    .tv-vol.muted .tv-bar i { background:var(--dim); opacity:.5; }
    .tv-vol.muted { color:var(--coral); }
    #tv-card .tv-open { width:100%; min-height:54px; border-radius:16px; font-size:16px; font-family:var(--font-display); letter-spacing:-.01em; }
    #tv-card .tv-open .ico { width:22px; height:22px; }
    #tv-card .tv-one + .tv-one { border-top:1px solid var(--line); padding-top:var(--s4); }
    #tv-card .tv-one > * + * { margin-top:var(--s3); }
    #tv-card .tv-foot { display:flex; justify-content:flex-end; margin-top:var(--s2); }
    #tv-card .tv-foot button { min-height:34px; font-size:13px; }

    /* finding TVs */
    .tv-found { display:grid; gap:var(--s2); }
    .tv-found button { justify-content:flex-start; gap:12px; min-height:60px; padding:8px 12px 8px 10px; border-radius:16px; text-align:left;
      background:var(--card-2); border-color:transparent; font-weight:500; }
    .tv-found button:hover { border-color:color-mix(in srgb, var(--accent) 45%, transparent); }
    .tv-found .tile { width:40px; height:40px; border-radius:12px; display:grid; place-items:center; background:var(--accent-soft); color:var(--accent); flex:0 0 auto; }
    .tv-found .lbl { flex:1; min-width:0; }
    .tv-found .lbl b { display:block; font-weight:650; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .tv-found .lbl small { display:block; color:var(--dim); font-size:12px; }
    .tv-found .go { color:var(--accent); font-weight:700; font-size:13px; }
    .tv-scan { display:flex; align-items:center; gap:14px; padding:14px; border-radius:16px; background:var(--card-2); }
    .tv-scan .radar { width:40px; height:40px; flex:0 0 auto; position:relative; display:grid; place-items:center; color:var(--accent); }
    .tv-scan .radar i { position:absolute; inset:0; border-radius:50%; border:1.5px solid var(--accent); opacity:0; animation:tv-radar 2.4s var(--ease) infinite; }
    .tv-scan .radar i:nth-child(2) { animation-delay:.8s; } .tv-scan .radar i:nth-child(3) { animation-delay:1.6s; }
    @keyframes tv-radar { from { transform:scale(.3); opacity:.9; } to { transform:scale(1.15); opacity:0; } }
    .tv-scan b { display:block; font-weight:650; }
    .tv-scan span { display:block; color:var(--dim); font-size:13px; }
    .tv-byip { display:flex; gap:var(--s2); }
    .tv-byip input { flex:1; min-width:0; }
    .tv-note { color:var(--dim); font-size:13px; }
    .tv-note code { font-size:12px; background:var(--card-2); padding:2px 6px; border-radius:6px; }

    /* ---- sheets: the remote and pairing ---- */
    html.tv-open, html.tv-open body { overflow:hidden; }
    html.tv-open #toast { z-index:960; bottom:calc(env(safe-area-inset-bottom) + 20px); }
    .tv-sheet { position:fixed; inset:0; z-index:900; display:flex; background:var(--bg); color:var(--text);
      animation:tv-up .38s var(--spring); overscroll-behavior:contain; }
    .tv-sheet::before { content:""; position:absolute; inset:0; pointer-events:none;
      background:radial-gradient(80% 45% at 50% -5%, var(--caustic-1), transparent 70%),
                 radial-gradient(60% 40% at 100% 100%, var(--caustic-2), transparent 70%); }
    .tv-sheet.closing { animation:tv-down .22s var(--ease) forwards; }
    @keyframes tv-up { from { transform:translateY(40px); opacity:0; } }
    @keyframes tv-down { to { transform:translateY(40px); opacity:0; } }
    .tv-in { position:relative; flex:1; overflow-y:auto; overflow-x:hidden; -webkit-overflow-scrolling:touch; outline:none;
      padding:0 var(--gutter) calc(env(safe-area-inset-bottom) + 28px); }
    .tv-sheet:focus { outline:none; }
    .tv-wrap { max-width:430px; margin:0 auto; }
    .tv-top { display:flex; align-items:center; gap:10px; position:sticky; top:0; z-index:3;
      margin:0 calc(-1 * var(--gutter)); padding:max(10px, env(safe-area-inset-top)) var(--gutter) 12px;
      background:linear-gradient(var(--bg) 75%, transparent); }
    .tv-top .who { flex:1; min-width:0; text-align:center; }
    .tv-top .who b { display:block; font-family:var(--font-display); font-size:17px; font-weight:750; letter-spacing:-.015em;
      overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .tv-top .tv-state { justify-content:center; }
    .tv-rb, .tv-top button { width:48px; height:48px; min-height:0; padding:0; border-radius:50%; flex:0 0 auto; }
    .tv-top .tv-pwr { color:var(--coral); background:var(--coral-soft); border-color:transparent; }
    .tv-top .tv-pwr.off { color:var(--dim); background:var(--card-2); border-color:var(--line); }
    .tv-top .tv-pwr .ico, .tv-top .tv-x .ico { width:22px; height:22px; }
    .tv-volstrip { display:flex; align-items:center; gap:10px; margin:2px 4px 14px; }
    .tv-volstrip .tv-bar { height:4px; }

    .tv-banner { display:flex; align-items:center; gap:12px; margin-bottom:14px; padding:12px 12px 12px 16px; border-radius:16px;
      background:var(--coral-soft); color:var(--text); border:1px solid color-mix(in srgb, var(--coral) 35%, transparent); font-size:14px; }
    .tv-banner span { flex:1; }
    .tv-banner button { min-height:38px; flex:0 0 auto; }

    .tv-seg { display:grid; grid-template-columns:1fr 1fr; padding:4px; gap:4px; border-radius:999px; background:var(--card-2);
      border:1px solid var(--line); width:min(100%, 280px); margin:0 auto 18px; }
    .tv-seg button { min-height:38px; border:none; border-radius:999px; background:none; color:var(--dim); font-size:13.5px; gap:6px; }
    .tv-seg button .ico { width:17px; height:17px; }
    .tv-seg button.on { background:var(--card); color:var(--text); box-shadow:0 2px 10px -4px rgba(0,0,0,.45); }

    /* the D-pad: a pebble of deep water, OK in the middle */
    .tv-stage { position:relative; width:min(74vw, 290px); aspect-ratio:1; margin:0 auto; }
    .tv-dpad { position:absolute; inset:0; border-radius:50%; isolation:isolate; overflow:hidden;
      background:radial-gradient(circle at 50% 30%, color-mix(in srgb, var(--card-2) 82%, var(--text) 7%), var(--card-2) 58%,
                 color-mix(in srgb, var(--card-2) 86%, #000 14%));
      box-shadow:inset 0 1px 0 color-mix(in srgb, var(--text) 9%, transparent), inset 0 -10px 24px -8px rgba(0,0,0,.28),
                 0 0 0 1px var(--line), 0 24px 50px -26px rgba(0,0,0,.75); }
    .tv-dpad .glow { position:absolute; inset:0; z-index:-1; opacity:0; transition:opacity .25s;
      background:radial-gradient(46% 34% at 50% 6%, color-mix(in srgb, var(--accent) 34%, transparent), transparent 72%); }
    .tv-dpad .glow.on { opacity:1; transition:opacity .05s; }
    .tv-dpad > .dir { position:absolute; border:none; background:none; color:var(--dim); padding:0; min-height:0; border-radius:0; }
    .tv-dpad > .dir:active { transform:none; color:var(--accent); }
    .tv-dpad > .dir .ico { width:30px; height:30px; stroke-width:2.2; transition:transform .12s var(--ease); }
    .tv-dpad > .dir:active .ico { transform:scale(.88); }
    .tv-dpad .d-up    { left:28%; right:28%; top:0; height:30%; align-items:flex-start; padding-top:7%; }
    .tv-dpad .d-down  { left:28%; right:28%; bottom:0; height:30%; align-items:flex-end; padding-bottom:7%; }
    .tv-dpad .d-left  { top:28%; bottom:28%; left:0; width:30%; justify-content:flex-start; padding-left:7%; }
    .tv-dpad .d-right { top:28%; bottom:28%; right:0; width:30%; justify-content:flex-end; padding-right:7%; }
    .tv-ok { position:absolute; left:31%; top:31%; width:38%; height:38%; min-height:0; padding:0; border-radius:50%; border:none; z-index:2;
      background:var(--tide); color:var(--on-accent); font-family:var(--font-display); font-size:19px; font-weight:800; letter-spacing:.02em;
      box-shadow:0 12px 30px -10px var(--accent-2), inset 0 1px 0 rgba(255,255,255,.35), 0 0 0 7px color-mix(in srgb, var(--bg) 55%, transparent); }
    .tv-ok::before { content:""; position:absolute; inset:0; border-radius:inherit; opacity:.5; pointer-events:none;
      background:radial-gradient(55% 45% at 35% 25%, rgba(255,255,255,.5), transparent 70%); }
    .tv-ok:active { transform:scale(.94) !important; filter:brightness(1.08); }
    .tv-ripple { position:absolute; width:60px; height:60px; margin:-30px 0 0 -30px; border-radius:50%; pointer-events:none; z-index:1;
      border:2px solid var(--accent); animation:tv-rip .6s var(--ease) forwards; }
    @keyframes tv-rip { from { transform:scale(.3); opacity:.8; } to { transform:scale(3.2); opacity:0; } }

    /* the touchpad */
    .tv-pad { position:absolute; inset:0; border-radius:34px; overflow:hidden; touch-action:none; cursor:pointer; isolation:isolate;
      -webkit-user-select:none; user-select:none;
      background:radial-gradient(circle, color-mix(in srgb, var(--text) 9%, transparent) 1.2px, transparent 1.6px) 0 0/22px 22px,
                 radial-gradient(90% 70% at 50% 0%, color-mix(in srgb, var(--accent) 9%, transparent), transparent 70%), var(--card-2);
      box-shadow:inset 0 1px 0 color-mix(in srgb, var(--text) 8%, transparent), 0 0 0 1px var(--line), 0 24px 50px -26px rgba(0,0,0,.75); }
    .tv-pad .hint { position:absolute; left:0; right:0; bottom:18px; text-align:center; color:var(--dim); font-size:12.5px; pointer-events:none; }
    .tv-pad .arrow { position:absolute; color:var(--accent); opacity:0; pointer-events:none; transition:opacity .3s; }
    .tv-pad .arrow.on { opacity:1; transition:opacity .03s; }
    .tv-pad .arrow .ico { width:34px; height:34px; stroke-width:2.4; }
    .tv-pad .a-up { top:10px; left:50%; margin-left:-17px; } .tv-pad .a-down { bottom:34px; left:50%; margin-left:-17px; }
    .tv-pad .a-left { left:10px; top:50%; margin-top:-17px; } .tv-pad .a-right { right:10px; top:50%; margin-top:-17px; }
    .tv-pad .dot { position:absolute; width:46px; height:46px; margin:-23px 0 0 -23px; border-radius:50%; pointer-events:none; opacity:0;
      background:radial-gradient(circle, color-mix(in srgb, var(--accent) 45%, transparent), transparent 70%); transition:opacity .2s; }
    .tv-pad .dot.on { opacity:1; transition:none; }

    .tv-row { display:flex; justify-content:center; align-items:flex-start; gap:clamp(18px, 8vw, 34px); margin-top:22px; }
    .tv-key { display:grid; justify-items:center; gap:5px; font-size:11.5px; color:var(--dim); font-weight:600; }
    .tv-rb { background:var(--card-2); border:1px solid var(--line); color:var(--text); }
    .tv-rb .ico { width:22px; height:22px; }
    .tv-rb.lg { width:56px; height:56px; }
    .tv-rb.on { color:var(--coral); background:var(--coral-soft); border-color:transparent; }

    .tv-rockers { display:flex; justify-content:space-between; align-items:center; gap:12px; margin:24px auto 0; max-width:320px; }
    .tv-rocker { width:66px; height:150px; border-radius:999px; background:var(--card-2); border:1px solid var(--line); display:grid;
      grid-template-rows:1fr auto 1fr; overflow:hidden; box-shadow:inset 0 1px 0 color-mix(in srgb, var(--text) 6%, transparent); }
    .tv-rocker button { border:none; background:none; border-radius:0; min-height:0; padding:0; color:var(--text); }
    .tv-rocker button:active { background:var(--accent-soft); color:var(--accent); transform:none; }
    .tv-rocker button .ico { width:24px; height:24px; stroke-width:2.2; }
    .tv-rocker .lbl { text-align:center; font-size:10.5px; font-weight:800; letter-spacing:.14em; color:var(--dim); padding:2px 0; }
    .tv-mid { display:grid; gap:14px; justify-items:center; }

    .tv-media { display:flex; justify-content:center; align-items:center; gap:clamp(6px, 3vw, 14px); margin-top:24px; }
    .tv-media button { width:46px; height:46px; min-height:0; padding:0; border-radius:50%; background:none; border-color:transparent; color:var(--text); }
    .tv-media button:hover { background:var(--card-2); }
    .tv-media button .ico { width:22px; height:22px; stroke-width:2; }
    .tv-media button.pp { width:62px; height:62px; background:var(--tide); color:var(--on-accent); box-shadow:0 10px 24px -10px var(--accent-2); }
    .tv-media button.pp .ico { width:26px; height:26px; stroke-width:2.2; }

    .tv-sec { margin-top:30px; }
    .tv-sec > h4 { display:flex; align-items:center; gap:8px; font-size:11.5px; font-weight:750; letter-spacing:.12em; text-transform:uppercase;
      color:var(--dim); margin:0 4px 10px; }
    .tv-sec > h4 .ico { width:15px; height:15px; }
    .tv-box { background:var(--card); border:1px solid var(--line); border-radius:var(--r); padding:14px; box-shadow:var(--shadow); }
    .tv-box > * + * { margin-top:10px; }
    .tv-typing { display:flex; gap:8px; }
    .tv-typing input { flex:1; min-width:0; }
    .tv-typekeys { display:flex; gap:8px; flex-wrap:wrap; }
    .tv-typekeys button { min-height:38px; font-size:13px; }
    .tv-typekeys button .ico { width:17px; height:17px; }

    .tv-apps { display:grid; grid-template-columns:repeat(4, 1fr); gap:14px 8px; }
    .tv-app { display:grid; justify-items:center; gap:6px; background:none; border:none; padding:4px 0; min-height:0; border-radius:14px;
      color:var(--text); font-size:11.5px; font-weight:600; text-align:center; }
    .tv-app .tile { width:58px; height:58px; border-radius:18px; display:grid; place-items:center; background:var(--card-2);
      border:1px solid var(--line); transition:background .15s, border-color .15s, color .15s, box-shadow .2s; }
    .tv-app .tile .ico { width:28px; height:28px; stroke-width:2; }
    .tv-app:hover .tile { border-color:color-mix(in srgb, var(--accent) 50%, transparent); color:var(--accent); }
    .tv-app.now .tile { background:var(--accent-soft); border-color:var(--accent); color:var(--accent); box-shadow:0 0 0 4px var(--accent-soft); }
    .tv-app span { max-width:76px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; line-height:1.2; }

    .tv-nums { display:grid; grid-template-columns:repeat(3, 1fr); gap:10px; max-width:300px; margin:0 auto; }
    .tv-nums button { min-height:50px; border-radius:16px; font-size:19px; font-family:var(--font-display); font-weight:700; }
    .tv-nums button .ico { width:19px; height:19px; }
    .tv-extras { display:grid; grid-template-columns:repeat(auto-fill, minmax(8.5rem, 1fr)); gap:8px; }
    .tv-extras button { justify-content:flex-start; min-height:46px; border-radius:14px; font-size:13.5px; }
    .tv-extras button .ico { width:18px; height:18px; color:var(--accent); }

    .tv-settings { display:flex; align-items:center; gap:12px; }
    .tv-settings .grow b { display:block; font-weight:650; font-size:14px; }
    .tv-settings .grow span { display:block; color:var(--dim); font-size:12.5px; }
    .tv-switch { appearance:none; -webkit-appearance:none; width:46px; height:28px; border-radius:999px; background:var(--line-2); position:relative;
      cursor:pointer; flex:0 0 auto; transition:background .2s; margin:0; }
    .tv-switch::after { content:""; position:absolute; top:3px; left:3px; width:22px; height:22px; border-radius:50%; background:#fff;
      box-shadow:0 1px 3px rgba(0,0,0,.3); transition:transform .25s var(--spring); }
    .tv-switch:checked { background:var(--accent); }
    .tv-switch:checked::after { transform:translateX(18px); }
    .tv-forget { color:var(--danger); }
    .tv-forget:hover { background:var(--danger-soft) !important; color:var(--danger) !important; }
    .tv-kbd { color:var(--dim); font-size:12.5px; line-height:1.9; display:none; }
    .tv-kbd kbd { font:inherit; font-size:11.5px; font-weight:700; padding:1px 7px; border-radius:6px; background:var(--card-2);
      border:1px solid var(--line); border-bottom-width:2px; color:var(--text); }
    @media (hover: hover) and (pointer: fine) { .tv-kbd { display:block; } }

    /* pairing */
    .tv-pair { text-align:center; padding-top:4vh; }
    .tv-pair .big { width:88px; height:88px; margin:8px auto 18px; border-radius:28px; display:grid; place-items:center; position:relative;
      background:var(--tide); color:var(--on-accent); box-shadow:0 18px 40px -14px var(--accent-2); }
    .tv-pair .big .ico { width:40px; height:40px; }
    .tv-pair .big.wait::after { content:""; position:absolute; inset:-10px; border-radius:34px; border:2px solid var(--accent);
      animation:tv-radar 1.8s var(--ease) infinite; }
    .tv-pair .big.bad { background:var(--coral); box-shadow:0 18px 40px -14px var(--coral); }
    .tv-pair h2 { font-family:var(--font-display); font-size:clamp(23px, 6.4vw, 28px); font-weight:800; letter-spacing:-.03em; line-height:1.15; text-wrap:balance; }
    .tv-pair p { color:var(--dim); margin-top:8px; font-size:14.5px; }
    .tv-pair p b { color:var(--text); }
    .tv-code { position:relative; display:grid; grid-template-columns:repeat(6, 1fr); gap:8px; max-width:340px; margin:26px auto 0; }
    .tv-code .box { aspect-ratio:.8; border-radius:14px; background:var(--card); border:1.5px solid var(--line-2); display:grid; place-items:center;
      font-family:ui-monospace,"SF Mono","Cascadia Mono",Menlo,monospace; font-size:clamp(24px, 7vw, 30px); font-weight:700; color:var(--text);
      transition:border-color .15s, box-shadow .15s, transform .2s var(--spring); }
    .tv-code .box.filled { transform:translateY(-2px); }
    .tv-code .box.cur { border-color:var(--accent); box-shadow:0 0 0 4px var(--accent-soft); }
    .tv-code .box.cur:empty::after { content:""; width:2px; height:42%; background:var(--accent); animation:tv-blink 1s steps(1) infinite; }
    .tv-code input { position:absolute; inset:0; width:100%; height:100%; opacity:0; border:none; font-size:16px; color:transparent;
      background:none; caret-color:transparent; min-height:0; box-shadow:none !important; }
    .tv-code.bad .box { border-color:var(--coral); }
    .tv-code.shake { animation:tv-shake .4s; }
    @keyframes tv-shake { 20%, 60% { transform:translateX(-7px); } 40%, 80% { transform:translateX(7px); } }
    .tv-err { color:var(--coral); font-weight:600; font-size:14px; min-height:22px; margin-top:14px; text-wrap:balance; }
    .tv-pair .acts { display:flex; justify-content:center; gap:10px; margin-top:14px; flex-wrap:wrap; }
    .tv-pair .acts button { min-width:120px; min-height:48px; border-radius:16px; }
    .tv-pair .left { text-align:left; margin-top:22px; }
    .tv-pair .left > * + * { margin-top:10px; }

    @media (min-width: 880px) {
      .tv-sheet { background:color-mix(in srgb, var(--bg) 55%, transparent); backdrop-filter:blur(14px) saturate(1.2);
        -webkit-backdrop-filter:blur(14px) saturate(1.2); padding:3vh 3vw; }
      .tv-sheet::before { display:none; }
      .tv-in { flex:0 1 auto; width:min(1000px, 100%); margin:auto; max-height:94vh; background:var(--bg); border:1px solid var(--line);
        border-radius:var(--r-lg); box-shadow:var(--shadow-float); padding:0 28px 28px; }
      .tv-top { margin:0 -28px; padding:14px 28px 12px; }
      .tv-wrap.remote { max-width:none; display:grid; grid-template-columns:minmax(330px, 400px) 1fr; column-gap:40px; align-items:start; }
      .tv-wrap.remote > .tv-top, .tv-wrap.remote > .tv-volstrip, .tv-wrap.remote > .tv-banner { grid-column:1 / -1; }
      .tv-side > .tv-sec:first-child { margin-top:0; }
      .tv-wrap.pairing { max-width:520px; }
      .tv-in.small { width:min(560px, 100%); }
    }
    @media (prefers-reduced-motion: reduce) { .tv-scan .radar i, .tv-pair .big.wait::after { animation:none; opacity:.4; } }
  ` }));

  // --- state ---
  let data = null;
  let pollTimer = null, onScreen = false;
  let sheet = null;          // the open sheet: {kind: "remote"|"pair", ...}
  let busyPower = new Set();

  const tvs = () => (data && data.tvs) || [];
  const tvById = id => tvs().find(t => t.id === id);
  const haptics = () => LS.get(LS_HAPTICS) !== "off";
  const buzz = ms => { if (haptics() && navigator.vibrate) try { navigator.vibrate(ms); } catch {} };

  function status(t) {
    if (!t.paired) return ["Needs pairing again", "bad"];
    if (!t.connected && t.connecting) return ["Connecting…", "wait"];
    if (!t.connected) return [t.error && /forgot/.test(t.error) ? t.error : "Can't reach it right now", "bad"];
    if (t.on === false) return ["Standby", "off"];
    return [t.app_name ? `On · ${t.app_name}` : "On", "on"];
  }
  const stateLine = t => {
    const [text, cls] = status(t);
    return el("div", { className: "tv-state " + cls }, el("i", { className: "tv-dot" }), el("span", { textContent: text }));
  };
  function volBar(t, cls = "tv-vol") {
    const v = t.volume;
    const wrap = el("div", { className: cls + (v && v.muted ? " muted" : "") });
    const fill = el("i");
    fill.style.width = v && v.max ? `${Math.round((v.level / v.max) * 100)}%` : "0%";
    wrap.append(g(v && v.muted ? "muted" : "vol", 17), el("div", { className: "tv-bar" }, fill),
                el("span", { textContent: v ? (v.muted ? "Muted" : String(v.level)) : "" }));
    wrap.title = v ? `Volume ${v.level} of ${v.max}${v.muted ? ", muted" : ""}` : "";
    return wrap;
  }

  // --- talking to the hub ---
  async function api(path, body, method = "POST") {
    const res = await fetch(path, {
      method, headers: body ? { "Content-Type": "application/json" } : {}, body: body ? JSON.stringify(body) : undefined,
    }).catch(() => null);
    if (!res) return { ok: false, status: 0, error: "Can't reach the hub." };
    const d = await res.json().catch(() => ({}));
    return { ok: res.ok, status: res.status, data: d, error: d.error || (res.ok ? null : `The hub said ${res.status}.`) };
  }

  async function poll() {
    clearTimeout(pollTimer);
    const r = await api("/api/tv", null, "GET");
    if (r.ok) accept(r.data);
    schedule();
  }
  function schedule() {
    clearTimeout(pollTimer);
    if (document.hidden) return;
    if (sheet || onScreen || !data) pollTimer = setTimeout(poll, sheet ? POLL_OPEN_MS : POLL_MS);
  }
  function accept(d) {
    data = d;
    renderCard();
    if (sheet && sheet.kind === "remote") renderRemoteState();
    if (sheet && sheet.left && sheet.finderSig !== JSON.stringify(d.discovered)) {
      sheet.finderSig = JSON.stringify(d.discovered);
      const typed = sheet.left.querySelector("input")?.value || "";
      const hadFocus = sheet.left.contains(document.activeElement);
      sheet.left.replaceChildren(...findView(true));
      const ip = sheet.left.querySelector("input");
      ip.value = typed;
      if (hadFocus) ip.focus();
    }
  }

  // --- the card in the Hub tab ---
  const card = el("section", { className: "card", id: "tv-card", hidden: true });
  card.setAttribute("aria-label", "TV remote");
  (document.getElementById("features") || document.body).append(card);
  let drawn = "";

  function renderCard() {
    card.hidden = !data;
    if (!data) return;
    const sig = JSON.stringify([data.available, data.tvs, data.discovered, [...busyPower]]);
    if (sig === drawn) return;
    drawn = sig;
    const focusKey = document.activeElement?.closest?.("#tv-card [data-k]")?.dataset.k;
    const byip = card.querySelector(".tv-byip input");
    const typed = byip ? byip.value : "";
    card.replaceChildren(...(!data.available ? unavailable() : tvs().length ? pairedView() : findView(false)));
    const ip = card.querySelector(".tv-byip input");
    if (ip && typed) ip.value = typed;
    if (focusKey) card.querySelector(`[data-k="${focusKey}"]`)?.focus();
  }

  function unavailable() {
    return [cardHead("tv", "TV remote", "Control your Google TV from here"),
      el("p", { className: "tv-note" }, "The hub needs one more library for this. On the hub, run ",
        el("code", { textContent: "pip install -r requirements.txt" }), " and restart droplet.")];
  }

  function pairedView() {
    const parts = [cardHead("tv", tvs().length > 1 ? "TVs" : "TV remote", `through ${data.hub || HUB_NAME}`)];
    for (const t of tvs()) {
      const on = t.connected && t.on;
      const screen = el("span", { className: "tv-screen" + (on ? " on" : "") }, g("tv", 18));
      const pwr = el("button", { className: "tv-pwr" + (on ? " on" : "") + (busyPower.has(t.id) ? " busy" : ""), type: "button" });
      pwr.dataset.k = "pwr-" + t.id;
      pwr.append(g("power", 20));
      pwr.title = on ? `Turn ${t.name} off` : `Turn ${t.name} on`;
      pwr.setAttribute("aria-label", pwr.title);
      pwr.onclick = () => power(t.id);
      pwr.hidden = !t.paired;
      const row = el("div", { className: "tv-tvrow" }, screen,
        el("div", { className: "grow" }, el("span", { className: "tv-name", textContent: t.name }), stateLine(t)), pwr);
      const one = el("div", { className: "tv-one" }, row);
      if (t.connected && t.volume) one.append(volBar(t));
      if (t.paired) {
        const open = el("button", { className: "primary tv-open", type: "button" }, g("dpad", 22), "Open remote");
        open.dataset.k = "open-" + t.id;
        open.onclick = () => openRemote(t.id);
        one.append(open);
      } else {
        const again = el("button", { className: "primary tv-open", type: "button" }, g("tv", 22), "Pair again");
        again.dataset.k = "again-" + t.id;
        again.onclick = () => startPairing(t.host, t.name);
        const forget = el("button", { className: "ghost tv-forget", type: "button", textContent: "Forget" });
        forget.onclick = () => forgetTV(t);
        one.append(again, el("div", { className: "tv-foot" }, forget));
      }
      parts.push(one);
    }
    const add = el("button", { className: "ghost", type: "button" }, g("plus", 16), "Add a TV");
    add.dataset.k = "add";
    add.onclick = () => openFinder();
    parts.push(el("div", { className: "tv-foot" }, add));
    return parts;
  }

  // "Find my TV": discovered TVs first, add-by-IP as the fallback
  function findView(inSheet) {
    const found = (data.discovered || []).filter(d => !d.paired);
    const parts = [];
    if (!inSheet) parts.push(cardHead("tv", "TV remote", "Pair your Google TV or Android TV"));
    if (found.length) {
      parts.push(el("div", { className: "tv-found" }, ...found.map(d => {
        const b = el("button", { type: "button" }, el("span", { className: "tile" }, g("tv", 20)),
          el("span", { className: "lbl" }, el("b", { textContent: d.name }), el("small", { textContent: d.host })),
          el("span", { className: "go", textContent: "Pair" }));
        b.dataset.k = "found-" + d.host;
        b.onclick = () => startPairing(d.host, d.name);
        return b;
      })));
    } else {
      parts.push(el("div", { className: "tv-scan" },
        el("span", { className: "radar" }, el("i"), el("i"), el("i"), g("tv", 18)),
        el("div", {}, el("b", { textContent: data.discovery === false ? "Can't look for TVs" : "Looking for TVs…" }),
          el("span", { textContent: `Turn the TV on, and make sure it's on the same network as ${data.hub || HUB_NAME}.` }))));
    }
    const ip = el("input", { type: "text", placeholder: "e.g. 192.168.1.40", autocomplete: "off" });
    ip.setAttribute("inputmode", "decimal");
    ip.setAttribute("aria-label", "TV's IP address");
    ip.setAttribute("autocapitalize", "off");
    const go = el("button", { className: "soft", type: "button", textContent: "Add" });
    go.dataset.k = "byip";
    const submit = () => {
      const host = ip.value.trim();
      if (!host) { ip.focus(); return; }
      startPairing(host, null);
    };
    go.onclick = submit;
    ip.onkeydown = e => { if (e.key === "Enter") submit(); };
    parts.push(el("div", { className: "tv-note", textContent: found.length ? "Not in the list? Add it by IP address:" :
      "Or add it by IP address (on the TV: Settings → Network & Internet → your network):" }),
      el("div", { className: "tv-byip" }, ip, go));
    return parts;
  }

  // --- sheets ---
  function openSheet(kind, build, small = false) {
    const returnFocus = sheet ? sheet.returnFocus : document.activeElement;
    closeSheet({ replacing: true });
    const inner = el("div", { className: "tv-in" + (small ? " small" : "") });
    const root = el("div", { className: "tv-sheet", tabIndex: -1 }, inner);
    root.setAttribute("role", "dialog");
    root.setAttribute("aria-modal", "true");
    root.addEventListener("click", e => { if (e.target === root) closeSheet(); });   // desktop backdrop
    root.addEventListener("keydown", e => { if (e.key === "Escape") { e.preventDefault(); closeSheet(); } });
    sheet = { kind, root, inner, returnFocus };
    build(sheet);
    document.body.append(root);
    document.documentElement.classList.add("tv-open");
    if (!history.state || !history.state.tvSheet) history.pushState({ tvSheet: true }, "");
    (root.querySelector("[data-autofocus]") || root).focus({ preventScroll: true });
    root.setAttribute("aria-label", kind === "remote" ? "TV remote" : "Pair a TV");
    schedule();
  }

  // replacing: another sheet takes this one's place, so history and focus stay put
  function closeSheet({ replacing = false, fromHistory = false } = {}) {
    if (!sheet) return;
    const s = sheet;
    sheet = null;
    s.cleanup && s.cleanup(replacing);
    if (replacing) { s.root.remove(); return; }
    document.documentElement.classList.remove("tv-open");
    if (!fromHistory && history.state && history.state.tvSheet) history.back();
    s.root.classList.add("closing");
    setTimeout(() => s.root.remove(), 220);
    s.returnFocus?.focus?.({ preventScroll: true });
    schedule();
  }
  // the phone's back gesture closes the sheet rather than leaving droplet
  window.addEventListener("popstate", () => { if (sheet) closeSheet({ fromHistory: true }); });

  function topBar(title, sub, ...right) {
    const x = el("button", { className: "tv-x", type: "button" }, g("close", 22));
    x.title = "Close";
    x.setAttribute("aria-label", "Close");
    x.onclick = () => closeSheet();
    const who = el("div", { className: "who" }, el("b", { textContent: title }), ...(sub ? [sub] : []));
    // keep the title centred: an empty slot balances the close button
    return el("div", { className: "tv-top" }, x, who, ...(right.length ? right : [el("span", { style: "width:48px" })]));
  }

  // --- pairing ---
  function openFinder() {
    openSheet("pair", s => {
      s.inner.append(el("div", { className: "tv-wrap pairing" }, topBar("Add a TV", null),
        el("div", { className: "tv-pair" },
          el("div", { className: "big" }, g("tv", 40)),
          el("h2", { textContent: "Find my TV" }),
          el("p", { textContent: "Pick your TV. It'll show a code to type in here." }),
          s.left = el("div", { className: "left" }, ...findView(true)))));
      s.finderSig = JSON.stringify(data.discovered);
    }, true);
  }

  async function startPairing(host, name) {
    const label = name || host;
    openSheet("pair", s => {
      s.host = host;
      s.name = label;
      s.inner.append(el("div", { className: "tv-wrap pairing" }, topBar("Pair a TV", null), s.body = el("div", { className: "tv-pair" })));
      s.cleanup = replacing => { if (s.step === "code" && !replacing) api("/api/tv/pair/cancel", {}); };
      pairStep(s, "asking");
    }, true);
    const s = sheet;
    const r = await api("/api/tv/pair/start", { host });
    if (sheet !== s) {
      // closed while we waited: take the code off the TV, unless another pairing has started since
      if (r.ok && !(sheet && sheet.kind === "pair" && sheet.host)) api("/api/tv/pair/cancel", {});
      return;
    }
    if (!r.ok) return pairStep(s, "failed", r.error);
    s.name = r.data.name || label;
    pairStep(s, "code");
  }

  function pairStep(s, step, error) {
    s.step = step;
    const b = s.body;
    b.replaceChildren();
    if (step === "asking") {
      b.append(el("div", { className: "big wait" }, g("tv", 40)),
        el("h2", { textContent: "Asking your TV for a code…" }),
        el("p", {}, "Connecting to ", el("b", { textContent: s.name }), "."));
      return;
    }
    if (step === "failed") {
      const again = el("button", { className: "primary", type: "button", textContent: "Try again" });
      again.onclick = () => startPairing(s.host, s.name);
      const back = el("button", { type: "button", textContent: "Find my TV" });
      back.onclick = () => openFinder();
      b.append(el("div", { className: "big bad" }, g("tv", 40)),
        el("h2", { textContent: "Couldn't start pairing" }),
        el("p", { textContent: error || "Something went wrong." }),
        el("div", { className: "acts" }, back, again));
      again.dataset.autofocus = "";
      again.focus();
      return;
    }
    if (step === "code") {
      // no maxLength: a pasted "A1 B2 C3" has to survive until the filter below tidies it
      const input = el("input", { type: "text", autocomplete: "one-time-code", spellcheck: false });
      input.setAttribute("autocapitalize", "characters");
      input.setAttribute("autocorrect", "off");
      input.setAttribute("aria-label", "Pairing code, 6 characters");
      const boxes = Array.from({ length: 6 }, () => el("span", { className: "box" }));
      const code = el("div", { className: "tv-code" }, ...boxes, input);
      const err = el("div", { className: "tv-err", role: "alert" });
      const pair = el("button", { className: "primary", type: "button", textContent: "Pair", disabled: true });
      const cancel = el("button", { type: "button", textContent: "Cancel" });
      cancel.onclick = () => closeSheet();
      let sending = false;
      const paint = () => {
        const v = input.value;
        boxes.forEach((bx, i) => {
          bx.textContent = v[i] || "";
          bx.classList.toggle("filled", !!v[i]);
          bx.classList.toggle("cur", document.activeElement === input && (i === v.length || (i === 5 && v.length === 6)));
        });
        pair.disabled = v.length !== 6 || sending;
      };
      const submit = async () => {
        if (input.value.length !== 6 || sending) return;
        sending = true;
        paint();
        pair.textContent = "Pairing…";
        err.textContent = "";
        const r = await api("/api/tv/pair/finish", { code: input.value });
        if (sheet !== s) return;
        sending = false;
        pair.textContent = "Pair";
        if (r.ok) {
          buzz([12, 40, 12]);
          s.step = "done";
          s.tvId = r.data.id;
          poll();
          return renderDone(s);
        }
        buzz(60);
        code.classList.add("bad", "shake");
        setTimeout(() => code.classList.remove("shake"), 450);
        err.textContent = r.error || "That didn't work. Try again.";
        if (r.status === 409) {   // the TV's screen is gone: only a new code will do
          const again = el("button", { className: "primary", type: "button", textContent: "Get a new code" });
          again.onclick = () => startPairing(s.host, s.name);
          b.querySelector(".acts").replaceChildren(cancel, again);
          input.disabled = true;
          return;
        }
        input.value = "";
        input.focus();
        paint();
      };
      input.addEventListener("input", () => {
        input.value = input.value.toUpperCase().replace(/[^0-9A-F]/g, "").slice(0, 6);
        code.classList.remove("bad");
        err.textContent = "";
        paint();
        if (input.value.length === 6) submit();
      });
      input.addEventListener("keydown", e => { if (e.key === "Enter") submit(); });
      input.addEventListener("focus", paint);
      input.addEventListener("blur", paint);
      pair.onclick = submit;
      b.append(el("div", { className: "big" }, g("tv", 40)),
        el("h2", { textContent: "Enter the code shown on your TV" }),
        el("p", {}, el("b", { textContent: s.name }), " is showing a 6-character code. Letters are A to F."),
        code, err, el("div", { className: "acts" }, cancel, pair));
      input.dataset.autofocus = "";
      setTimeout(() => input.focus(), 60);
      paint();
    }
  }

  function renderDone(s) {
    const b = s.body;
    const open = el("button", { className: "primary", type: "button" }, g("dpad", 20), "Open remote");
    open.onclick = () => openRemote(s.tvId);
    b.replaceChildren(el("div", { className: "big" }, icon("check")),
      el("h2", { textContent: "Paired" }),
      el("p", {}, el("b", { textContent: s.name }), " now takes orders from every droplet device."),
      el("div", { className: "acts" }, open));
    open.focus();
  }

  // --- sending to the TV, in order ---
  const queue = [];
  let pumping = false, lastFail = 0;

  function press(key, action = "short", repeat = false) {
    const s = sheet;
    if (!s || s.kind !== "remote") return;
    if (queue.length > (repeat ? 2 : 10)) return;    // don't build a backlog the TV plays out later
    buzz(action === "long" ? 28 : 9);
    optimistic(s, key);
    queue.push({ id: s.tvId, key, action });
    pump();
  }
  async function pump() {
    if (pumping) return;
    pumping = true;
    while (queue.length) {
      const k = queue.shift();
      const r = await api(`/api/tv/${k.id}/key`, { key: k.key, action: k.action });
      if (!r.ok) { queue.length = 0; failed(r); }
    }
    pumping = false;
  }
  function failed(r) {
    if (Date.now() - lastFail > 2500) flash(r.error || "The TV didn't get that.");
    lastFail = Date.now();
    poll();
  }
  function optimistic(s, key) {
    const t = tvById(s.tvId);
    if (!t || !t.volume) return;
    const v = t.volume;
    if (key === "VOLUME_UP") { v.level = Math.min(v.max, v.level + 1); v.muted = false; }
    else if (key === "VOLUME_DOWN") v.level = Math.max(0, v.level - 1);
    else if (key === "MUTE") v.muted = !v.muted;
    else return;
    renderRemoteState();
  }

  async function power(id) {
    if (busyPower.has(id)) return;
    const t = tvById(id);
    busyPower.add(id);
    drawn = "";
    renderCard();
    if (sheet && sheet.kind === "remote") renderRemoteState();
    buzz(20);
    const r = await api(`/api/tv/${id}/power`, {});
    busyPower.delete(id);
    drawn = "";
    if (!r.ok) flash(r.error);
    else if (r.data.woke && !r.data.connected) flash(`Sent ${t ? t.name : "the TV"} a wake-up call. Give it a few seconds.`);
    await poll();
    if (sheet && sheet.kind === "remote") renderRemoteState();
  }

  async function forgetTV(t) {
    if (!confirm(`Forget ${t.name}? You'll need its code to pair it again.`)) return;
    const r = await api(`/api/tv/${t.id}`, null, "DELETE");
    if (!r.ok) return flash(r.error);
    if (sheet && sheet.tvId === t.id) closeSheet();
    flash(`Forgot ${t.name}. You can also remove droplet from the TV's own settings.`);
    poll();
  }

  // a button that sends on press, repeats while held, or long-presses when held
  function keyButton(btn, key, { repeat = false, hold = false, ripple = null } = {}) {
    let timer = null, held = false, down = false;
    const start = e => {
      if (e.button > 0) return;
      e.preventDefault();
      try { btn.setPointerCapture(e.pointerId); } catch {}   // keeps the press when the finger drifts off
      down = true;
      held = false;
      if (ripple) ripple();
      if (repeat) {
        press(key);
        timer = setTimeout(function again() { press(key, "short", true); timer = setTimeout(again, REPEAT_MS); }, REPEAT_DELAY);
      } else if (hold) {
        timer = setTimeout(() => { held = true; press(key, "long"); }, HOLD_MS);
      } else press(key);
    };
    const end = () => {
      if (!down) return;
      down = false;
      clearTimeout(timer);
      if (hold && !held) press(key);
    };
    btn.addEventListener("pointerdown", start);
    btn.addEventListener("pointerup", end);
    btn.addEventListener("pointercancel", () => { down = false; clearTimeout(timer); });
    btn.addEventListener("contextmenu", e => e.preventDefault());
    // keyboard users: Enter/Space on a focused button
    btn.addEventListener("keydown", e => { if ((e.key === "Enter" || e.key === " ") && !e.repeat) { e.preventDefault(); e.stopPropagation(); press(key); } });
    return btn;
  }
  const rb = (name, key, label, opts = {}) => {
    const b = el("button", { className: "tv-rb" + (opts.lg ? " lg" : ""), type: "button" }, g(name, 22));
    b.setAttribute("aria-label", label);
    b.title = label;
    keyButton(b, key, opts);
    return opts.caption === false ? b : el("div", { className: "tv-key" }, b, el("span", { textContent: opts.caption || label }));
  };

  // --- the remote ---
  function openRemote(id) {
    const t = tvById(id);
    if (!t) return;
    openSheet("remote", s => {
      s.tvId = id;
      s.mode = LS.get(LS_MODE) === "pad" ? "pad" : "buttons";
      const wrap = el("div", { className: "tv-wrap remote" });
      s.inner.append(wrap);

      s.name = el("b");
      s.state = el("div");
      s.pwr = el("button", { className: "tv-pwr", type: "button" }, g("power", 22));
      s.pwr.onclick = () => power(s.tvId);
      s.vol = el("div", { className: "tv-volstrip" });
      s.banner = el("div", { className: "tv-banner", hidden: true });
      const top = topBar("", null, s.pwr);
      top.querySelector(".who").replaceChildren(s.name, s.state);

      // main column: D-pad or touchpad, nav keys, rockers, media
      const seg = el("div", { className: "tv-seg", role: "tablist" });
      const segB = el("button", { type: "button", role: "tab" }, g("dpad", 17), "Buttons");
      const segP = el("button", { type: "button", role: "tab" }, g("pad", 17), "Touchpad");
      seg.append(segB, segP);
      const stage = el("div", { className: "tv-stage" });
      const setMode = m => {
        s.mode = m;
        LS.set(LS_MODE, m);
        segB.classList.toggle("on", m === "buttons");
        segP.classList.toggle("on", m === "pad");
        segB.setAttribute("aria-selected", m === "buttons");
        segP.setAttribute("aria-selected", m === "pad");
        stage.replaceChildren(m === "pad" ? touchpad() : dpad());
      };
      segB.onclick = () => setMode("buttons");
      segP.onclick = () => setMode("pad");

      const nav = el("div", { className: "tv-row" },
        rb("back", "BACK", "Back", { hold: true, lg: true }),
        rb("home", "HOME", "Home", { hold: true, lg: true }),
        rb("menu", "MENU", "Menu", { lg: true }));

      const rocker = (label, upKey, downKey, upName, downName, upIcon, downIcon) => {
        const u = el("button", { type: "button" }, g(upIcon, 24));
        const d = el("button", { type: "button" }, g(downIcon, 24));
        u.setAttribute("aria-label", upName); u.title = upName;
        d.setAttribute("aria-label", downName); d.title = downName;
        keyButton(u, upKey, { repeat: true });
        keyButton(d, downKey, { repeat: true });
        return el("div", { className: "tv-rocker" }, u, el("div", { className: "lbl", textContent: label }), d);
      };
      s.mute = rb("muted", "MUTE", "Mute", { caption: "Mute" });
      const rockers = el("div", { className: "tv-rockers" },
        rocker("VOL", "VOLUME_UP", "VOLUME_DOWN", "Volume up", "Volume down", "plus", "minus"),
        el("div", { className: "tv-mid" }, s.mute, rb("input", "TV_INPUT", "Input", { caption: "Input" })),
        rocker("CH", "CHANNEL_UP", "CHANNEL_DOWN", "Channel up", "Channel down", "up", "down"));

      const mk = (name, key, label, cls = "") => {
        const b = el("button", { type: "button", className: cls }, g(name, 22));
        b.setAttribute("aria-label", label);
        b.title = label;
        return keyButton(b, key);
      };
      const media = el("div", { className: "tv-media" },
        mk("rew", "MEDIA_REWIND", "Rewind"), mk("prev", "MEDIA_PREVIOUS", "Previous"),
        mk("playpause", "MEDIA_PLAY_PAUSE", "Play / pause", "pp"),
        mk("next", "MEDIA_NEXT", "Next"), mk("ff", "MEDIA_FAST_FORWARD", "Fast forward"));

      const main = el("div", { className: "tv-main" }, seg, stage, nav, rockers, media);
      const side = el("div", { className: "tv-side" }, keyboardSec(s), appsSec(s), moreSec(s), settingsSec(s));
      wrap.append(top, s.vol, s.banner, main, side);
      setMode(s.mode);
      renderRemoteState();

      const onKey = e => keyboardShortcut(e);
      document.addEventListener("keydown", onKey);
      s.cleanup = () => document.removeEventListener("keydown", onKey);
    });
  }

  function renderRemoteState() {
    const s = sheet;
    if (!s || s.kind !== "remote") return;
    const t = tvById(s.tvId);
    if (!t) { closeSheet(); return; }
    s.name.textContent = t.name;
    s.state.replaceChildren(stateLine(t));
    if (busyPower.has(t.id)) s.state.firstChild.classList.add("wait");
    const on = t.connected && t.on;
    s.pwr.classList.toggle("off", !on);
    s.pwr.classList.toggle("busy", busyPower.has(t.id));
    s.pwr.title = on ? "Turn off" : "Turn on";
    s.pwr.setAttribute("aria-label", s.pwr.title);
    s.vol.replaceChildren(...(t.connected && t.volume ? volBar(t, "tv-vol").childNodes : []));
    s.vol.className = "tv-volstrip tv-vol" + (t.volume && t.volume.muted ? " muted" : "");
    s.vol.hidden = !(t.connected && t.volume);
    const muteBtn = s.mute.querySelector("button");
    muteBtn.classList.toggle("on", !!(t.volume && t.volume.muted));
    // banner when the TV can't be reached or wants pairing again
    s.banner.hidden = t.connected || (t.connecting && t.paired);
    if (!s.banner.hidden) {
      const [text] = status(t);
      const act = t.paired
        ? Object.assign(el("button", { className: "soft", type: "button" }, g("wake", 17), "Turn on"), { onclick: () => power(t.id) })
        : Object.assign(el("button", { className: "primary", type: "button", textContent: "Pair again" }), { onclick: () => startPairing(t.host, t.name) });
      if (busyPower.has(t.id)) { act.disabled = true; act.lastChild.textContent = "Waking…"; }
      s.banner.replaceChildren(el("span", { textContent: t.paired ? `${t.name} isn't answering. Is it on?` : text }), act);
    }
    for (const a of s.inner.querySelectorAll(".tv-app")) a.classList.toggle("now", !!(t.connected && t.app && a.dataset.pkg === t.app));
  }

  function dpad() {
    const pad = el("div", { className: "tv-dpad" });
    const glow = el("div", { className: "glow" });
    pad.append(glow);
    const ripple = () => {
      const r = el("span", { className: "tv-ripple" });
      r.style.left = "50%"; r.style.top = "50%";
      pad.append(r);
      r.addEventListener("animationend", () => r.remove());
    };
    const dirs = [["up", "DPAD_UP", 0], ["right", "DPAD_RIGHT", 90], ["down", "DPAD_DOWN", 180], ["left", "DPAD_LEFT", 270]];
    for (const [d, key, deg] of dirs) {
      const b = el("button", { className: "dir d-" + d, type: "button" }, g(d, 30));
      b.setAttribute("aria-label", d[0].toUpperCase() + d.slice(1));
      b.addEventListener("pointerdown", () => { glow.style.transform = `rotate(${deg}deg)`; glow.classList.add("on"); });
      for (const ev of ["pointerup", "pointercancel", "pointerleave"]) b.addEventListener(ev, () => glow.classList.remove("on"));
      keyButton(b, key, { repeat: true });
      pad.append(b);
    }
    const ok = el("button", { className: "tv-ok", type: "button", textContent: "OK" });
    ok.setAttribute("aria-label", "OK (hold for more options)");
    keyButton(ok, "DPAD_CENTER", { hold: true, ripple });
    pad.append(ok);
    return pad;
  }

  // swipes become D-pad presses, a tap is OK, holding is a long OK
  function touchpad() {
    const pad = el("div", { className: "tv-pad", tabIndex: 0 });
    pad.setAttribute("role", "application");
    pad.setAttribute("aria-label", "Touchpad: swipe to move, tap for OK. Arrow keys and Enter work too.");
    const arrows = {};
    for (const d of ["up", "down", "left", "right"]) pad.append(arrows[d] = el("span", { className: "arrow a-" + d }, g(d, 34)));
    const dot = el("span", { className: "dot" });
    pad.append(dot, el("div", { className: "hint", textContent: "Swipe to move · Tap for OK · Hold for options" }));
    const flashArrow = d => {
      const a = arrows[d];
      a.classList.add("on");
      clearTimeout(a._t);
      a._t = setTimeout(() => a.classList.remove("on"), 120);
    };
    const rippleAt = (x, y) => {
      const r = el("span", { className: "tv-ripple" });
      r.style.left = x + "px"; r.style.top = y + "px";
      pad.append(r);
      r.addEventListener("animationend", () => r.remove());
    };
    let p = null;
    pad.addEventListener("pointerdown", e => {
      if (e.button > 0) return;
      try { pad.setPointerCapture(e.pointerId); } catch {}
      const box = pad.getBoundingClientRect();
      p = { id: e.pointerId, x0: e.clientX, y0: e.clientY, ax: e.clientX, ay: e.clientY, t0: Date.now(), moved: false, steps: 0, box, held: false };
      p.hold = setTimeout(() => {
        if (!p || p.moved) return;
        p.held = true;
        press("DPAD_CENTER", "long");
        rippleAt(p.x0 - box.left, p.y0 - box.top);
      }, 600);
      dot.style.left = e.clientX - box.left + "px";
      dot.style.top = e.clientY - box.top + "px";
      dot.classList.add("on");
    });
    pad.addEventListener("pointermove", e => {
      if (!p || e.pointerId !== p.id) return;
      dot.style.left = e.clientX - p.box.left + "px";
      dot.style.top = e.clientY - p.box.top + "px";
      if (Math.hypot(e.clientX - p.x0, e.clientY - p.y0) > 10) { p.moved = true; clearTimeout(p.hold); }
      const dx = e.clientX - p.ax, dy = e.clientY - p.ay;
      if (Math.max(Math.abs(dx), Math.abs(dy)) < SWIPE_STEP) return;
      const d = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? "right" : "left") : (dy > 0 ? "down" : "up");
      press("DPAD_" + d.toUpperCase(), "short", p.steps > 0);
      flashArrow(d);
      p.steps++;
      p.ax = e.clientX; p.ay = e.clientY;
    });
    const end = e => {
      if (!p || e.pointerId !== p.id) return;
      clearTimeout(p.hold);
      dot.classList.remove("on");
      if (e.type === "pointerup" && !p.moved && !p.held && Date.now() - p.t0 < 600) {
        press("DPAD_CENTER");
        rippleAt(p.x0 - p.box.left, p.y0 - p.box.top);
      }
      p = null;
    };
    pad.addEventListener("pointerup", end);
    pad.addEventListener("pointercancel", end);
    pad.addEventListener("contextmenu", e => e.preventDefault());
    return pad;
  }

  function sec(iconName, title, ...kids) {
    return el("section", { className: "tv-sec" }, el("h4", {}, g(iconName, 15), title), ...kids);
  }

  function keyboardSec(s) {
    const input = el("input", { type: "text", placeholder: "Type here, then Send", autocomplete: "off", enterKeyHint: "send" });
    input.setAttribute("aria-label", "Text to type on the TV");
    const send = el("button", { className: "primary", type: "button", textContent: "Send" });
    const go = async () => {
      const text = input.value;
      if (!text.trim()) { input.focus(); return; }
      send.disabled = true;
      const r = await api(`/api/tv/${s.tvId}/text`, { text });
      send.disabled = false;
      if (!r.ok) return flash(r.error);
      buzz(10);
      input.value = "";
      flash("Typed on the TV");
    };
    send.onclick = go;
    input.addEventListener("keydown", e => { if (e.key === "Enter") { e.preventDefault(); go(); } });
    const small = (name, key, label) => {
      const b = el("button", { type: "button" }, g(name, 17), label);
      return keyButton(b, key, { repeat: key === "DEL" });
    };
    return sec("keyboard", "Keyboard",
      el("div", { className: "tv-box" },
        el("div", { className: "tv-typing" }, input, send),
        el("div", { className: "tv-typekeys" }, small("del", "DEL", "Delete"), small("enter", "ENTER", "Enter"),
          small("search", "SEARCH", "Search")),
        el("div", { className: "tv-note", textContent: "Pick a text box on the TV first, like search." })));
  }

  function appsSec(s) {
    const tiles = (data.apps || []).map(a => {
      const b = el("button", { className: "tv-app", type: "button" },
        el("span", { className: "tile" }, svg(APP_GLYPH[a.id] || G.apps, 28)),
        el("span", { textContent: a.id === "home" ? "Home" : a.name }));
      if (a.package) b.dataset.pkg = a.package;
      b.title = `Open ${a.name}`;
      b.onclick = async () => {
        buzz(12);
        const r = await api(`/api/tv/${s.tvId}/launch`, { app: a.id });
        if (!r.ok) return failed(r);
        flash(`Opening ${a.name}…`);
        setTimeout(poll, 1200);
      };
      return b;
    });
    const link = el("input", { type: "text", placeholder: "Paste a link, e.g. a YouTube video", autocomplete: "off", enterKeyHint: "go" });
    link.setAttribute("inputmode", "url");
    link.setAttribute("autocapitalize", "off");
    link.setAttribute("aria-label", "Link to open on the TV");
    const open = el("button", { className: "soft", type: "button", textContent: "Open" });
    const go = async () => {
      const url = link.value.trim();
      if (!/^https:\/\/\S+$/i.test(url)) { flash("Links start with https://"); link.focus(); return; }
      const r = await api(`/api/tv/${s.tvId}/launch`, { app: url });
      if (!r.ok) return flash(r.error);
      link.value = "";
      flash("Opening the link on the TV…");
    };
    open.onclick = go;
    link.addEventListener("keydown", e => { if (e.key === "Enter") { e.preventDefault(); go(); } });
    return sec("apps", "Apps",
      el("div", { className: "tv-box" }, el("div", { className: "tv-apps" }, ...tiles),
        el("div", { className: "tv-typing" }, link, open)));
  }

  function moreSec() {
    const num = n => {
      const b = el("button", { type: "button", textContent: n });
      b.setAttribute("aria-label", `Number ${n}`);
      return keyButton(b, n);
    };
    const tool = (name, key, label) => keyButton(el("button", { type: "button" }, g(name, 18), label), key);
    const info = el("button", { type: "button" }, g("info", 19));
    info.setAttribute("aria-label", "Info");
    keyButton(info, "INFO");
    const guide = el("button", { type: "button" }, g("guide", 19));
    guide.setAttribute("aria-label", "Guide");
    keyButton(guide, "GUIDE");
    return sec("menu", "More buttons",
      el("div", { className: "tv-box" },
        el("div", { className: "tv-nums" }, ..."123456789".split("").map(num), info, num("0"), guide),
        el("div", { className: "tv-extras" }, tool("gear", "SETTINGS", "Settings"), tool("search", "SEARCH", "Search"),
          tool("input", "TV_INPUT", "Input"), tool("stop", "MEDIA_STOP", "Stop"), tool("info", "INFO", "Info"),
          tool("guide", "GUIDE", "Guide"))));
  }

  function settingsSec(s) {
    const sw = el("input", { type: "checkbox", className: "tv-switch", checked: haptics() });
    sw.setAttribute("aria-label", "Vibrate on press");
    sw.onchange = () => { LS.set(LS_HAPTICS, sw.checked ? "on" : "off"); if (sw.checked) buzz(15); };
    const hapticRow = el("label", { className: "tv-settings" }, el("span", { className: "ico-tile" }, g("buzz", 20)),
      el("div", { className: "grow" }, el("b", { textContent: "Vibrate on press" }),
        el("span", { textContent: "on phones that support it" })), sw);
    hapticRow.hidden = !("vibrate" in navigator);
    const forget = el("button", { className: "ghost tv-forget", type: "button" }, icon("trash"), "Forget this TV");
    forget.onclick = () => { const t = tvById(s.tvId); if (t) forgetTV(t); };
    const kbd = el("div", { className: "tv-kbd" });
    kbd.innerHTML = "<kbd>←</kbd> <kbd>↑</kbd> <kbd>→</kbd> <kbd>↓</kbd> move · <kbd>Enter</kbd> OK · <kbd>Backspace</kbd> back · " +
      "<kbd>H</kbd> home · <kbd>M</kbd> mute · <kbd>+</kbd> <kbd>−</kbd> volume · <kbd>Space</kbd> play/pause · <kbd>Esc</kbd> close";
    return sec("gear", "This remote", el("div", { className: "tv-box" }, hapticRow, kbd, forget));
  }

  // desktop: drive the TV from the keyboard while the remote is open
  const SHORTCUTS = {
    ArrowUp: "DPAD_UP", ArrowDown: "DPAD_DOWN", ArrowLeft: "DPAD_LEFT", ArrowRight: "DPAD_RIGHT",
    Enter: "DPAD_CENTER", Backspace: "BACK", h: "HOME", H: "HOME", m: "MUTE", M: "MUTE",
    "+": "VOLUME_UP", "=": "VOLUME_UP", "-": "VOLUME_DOWN", "_": "VOLUME_DOWN", " ": "MEDIA_PLAY_PAUSE",
    PageUp: "CHANNEL_UP", PageDown: "CHANNEL_DOWN",
  };
  function keyboardShortcut(e) {
    if (!sheet || sheet.kind !== "remote" || e.ctrlKey || e.metaKey || e.altKey) return;
    if (e.key === "Escape") { e.preventDefault(); closeSheet(); return; }
    const tag = e.target && e.target.tagName;
    if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;
    if (e.target && e.target.tagName === "BUTTON" && (e.key === "Enter" || e.key === " ")) return;  // the button handles it
    const key = SHORTCUTS[e.key];
    if (!key) return;
    e.preventDefault();
    const arrow = key.startsWith("DPAD_") && key !== "DPAD_CENTER";
    if (e.repeat && !arrow && !key.startsWith("VOLUME")) return;
    press(key, "short", e.repeat);
  }

  new IntersectionObserver(entries => {
    onScreen = entries.some(e => e.isIntersecting);
    if (onScreen) poll();
    else schedule();
  }).observe(card);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) poll(); else clearTimeout(pollTimer); });
  poll();
})();
