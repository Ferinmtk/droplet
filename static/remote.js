// Remote control: drive another device's pointer, keyboard, slides and media,
// lock it or grab a screenshot, and read a phone's messages and files.
// Everything travels over the hub's live connection (/ws, see docs/remote.md);
// native helpers on the other device do the acting. This script also owns that
// connection for the whole page: window.dropletLive.
(() => {
  // ---------------------------------------------------------------------------
  // icons this feature adds to the page's sprite, so icon(name) works for them
  // ---------------------------------------------------------------------------
  const ICONS = {
    remote: '<path d="M5.5 3.5 18.5 10l-5.6 1.9-2 5.6z"/><path d="m13 12 5.5 5.5"/>',
    touchpad: '<rect x="3" y="4" width="18" height="16" rx="3"/><path d="M3 15h18M12 15v5"/>',
    keyboard: '<rect x="2.5" y="6" width="19" height="12" rx="2.5"/><path d="M6.5 10h.01M10 10h.01M14 10h.01M17.5 10h.01M8 14h8"/>',
    lock: '<rect x="5" y="10.5" width="14" height="10" rx="2.5"/><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5"/>',
    screenshot: '<path d="M3 8V5.5A2.5 2.5 0 0 1 5.5 3H8M16 3h2.5A2.5 2.5 0 0 1 21 5.5V8M21 16v2.5a2.5 2.5 0 0 1-2.5 2.5H16M8 21H5.5A2.5 2.5 0 0 1 3 18.5V16"/><circle cx="12" cy="12" r="3.5"/>',
    dots: '<circle cx="5.5" cy="12" r="1.1"/><circle cx="12" cy="12" r="1.1"/><circle cx="18.5" cy="12" r="1.1"/>',
    "chev-r": '<path d="m9 5.5 6.5 6.5L9 18.5"/>',
    "chev-l": '<path d="M15 5.5 8.5 12l6.5 6.5"/>',
    "arrow-l": '<path d="M19.5 12h-15M10.5 6 4.5 12l6 6"/>',
    "arrow-r": '<path d="M4.5 12h15M13.5 6l6 6-6 6"/>',
    "arrow-u": '<path d="M12 19.5v-15M6 10.5l6-6 6 6"/>',
    "arrow-d": '<path d="M12 4.5v15M6 13.5l6 6 6-6"/>',
    laser: '<circle cx="12" cy="12" r="3"/><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3"/>',
    link: '<path d="M10 14a4.5 4.5 0 0 0 6.4 0l3-3a4.5 4.5 0 0 0-6.4-6.4l-1.2 1.2M14 10a4.5 4.5 0 0 0-6.4 0l-3 3a4.5 4.5 0 0 0 6.4 6.4l1.2-1.2"/>',
    copy: '<rect x="8.5" y="8.5" width="12" height="12" rx="2.5"/><path d="M15.5 8.5V6A2.5 2.5 0 0 0 13 3.5H6A2.5 2.5 0 0 0 3.5 6v7A2.5 2.5 0 0 0 6 15.5h2.5"/>',
    sliders: '<path d="M4 7h10M18 7h2M4 17h4M12 17h8"/><circle cx="16" cy="7" r="2"/><circle cx="10" cy="17" r="2"/>',
    enter: '<path d="M20 5v6.5a2.5 2.5 0 0 1-2.5 2.5H5M9 9.5 4.5 14 9 18.5"/>',
    backspace: '<path d="M8.5 5H19a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H8.5L3 12z"/><path d="m11.5 9.5 5 5m0-5-5 5"/>',
    mouse: '<rect x="6.5" y="3" width="11" height="18" rx="5.5"/><path d="M12 7v3"/>',
    stop: '<rect x="6" y="6" width="12" height="12" rx="2"/>',
    blank: '<rect x="2.5" y="4" width="19" height="13" rx="2.5" fill="currentColor" fill-opacity=".85"/><path d="M12 17v3.5M8 20.5h8"/>',
    windows: '<rect x="3" y="4.5" width="18" height="13" rx="2"/><path d="M3 9h18M8 21h8"/>',
    linux: '<path d="M4 17.5 10 12 4 6.5M12.5 18h7.5"/>',
    power: '<path d="M12 3v8.5M6.3 6.8a8 8 0 1 0 11.4 0"/>',
  };
  const sprite = document.querySelector("svg defs");
  if (sprite) {
    const NS = "http://www.w3.org/2000/svg";
    for (const [name, body] of Object.entries(ICONS)) {
      if (document.getElementById("i-" + name)) continue;
      const s = document.createElementNS(NS, "symbol");
      s.id = "i-" + name;
      s.setAttribute("viewBox", "0 0 24 24");
      s.innerHTML = body;
      sprite.append(s);
    }
  }

  // filled glyphs for the media controls, the same as the hub's Now playing card
  const MEDIA_ICON = {
    play: '<path d="M8 5.5v13a1 1 0 0 0 1.5.86l10.5-6.5a1 1 0 0 0 0-1.72L9.5 4.64A1 1 0 0 0 8 5.5z"/>',
    pause: '<rect x="6.5" y="5" width="4" height="14" rx="1.2"/><rect x="13.5" y="5" width="4" height="14" rx="1.2"/>',
    next: '<path d="M5 6.5v11a1 1 0 0 0 1.55.83L14.5 13v4.5a1 1 0 0 0 2 0v-11a1 1 0 0 0-2 0V11L6.55 5.67A1 1 0 0 0 5 6.5z"/><rect x="17" y="5.5" width="2.2" height="13" rx="1.1"/>',
    prev: '<path d="M19 6.5v11a1 1 0 0 1-1.55.83L9.5 13v4.5a1 1 0 0 1-2 0v-11a1 1 0 0 1 2 0V11l7.95-5.33A1 1 0 0 1 19 6.5z"/><rect x="4.8" y="5.5" width="2.2" height="13" rx="1.1"/>',
    vol: '<path d="M4 9.5v5a1 1 0 0 0 1 1h3l4.4 3.5a.8.8 0 0 0 1.3-.62V5.62a.8.8 0 0 0-1.3-.62L8 8.5H5a1 1 0 0 0-1 1z"/><path d="M16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>',
    muted: '<path d="M4 9.5v5a1 1 0 0 0 1 1h3l4.4 3.5a.8.8 0 0 0 1.3-.62V5.62a.8.8 0 0 0-1.3-.62L8 8.5H5a1 1 0 0 0-1 1z"/><path d="M16.5 9.5l5 5m0-5l-5 5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>',
    note: '<path d="M9 17.5V6.2a1 1 0 0 1 .78-.97l9-2a1 1 0 0 1 1.22.97v11.3" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/><circle cx="6.5" cy="17.5" r="2.5"/><circle cx="17.5" cy="15.5" r="2.5"/>',
  };
  const glyph = (name, size = 20) =>
    `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="currentColor" aria-hidden="true">${MEDIA_ICON[name]}</svg>`;

  // ---------------------------------------------------------------------------
  // styles
  // ---------------------------------------------------------------------------
  document.head.append(el("style", { textContent: `
    /* ---- entry points ---- */
    #remote-card { order:0; }
    #remote-card.none { order:6; }
    .rc-grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(15rem, 1fr)); gap:var(--s2); }
    .rc-dev { justify-content:flex-start; gap:12px; min-height:64px; padding:8px 10px 8px 12px; text-align:left;
              background:var(--card-2); border-color:transparent; border-radius:16px; font-weight:500; }
    .rc-dev:hover { border-color:color-mix(in srgb, var(--accent) 45%, transparent); }
    .rc-dev .lbl { flex:1; min-width:0; }
    .rc-dev .lbl b { display:block; font-weight:700; font-family:var(--font-display); font-size:15.5px; letter-spacing:-.01em;
                     overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .rc-dev .lbl small { display:flex; gap:6px; align-items:center; color:var(--dim); font-size:12px; margin-top:2px;
                         white-space:nowrap; overflow:hidden; }
    .rc-dev .lbl small .ico { width:14px; height:14px; color:var(--accent); }
    .rc-dev > .ico:last-child { color:var(--dim); width:18px; height:18px; }
    .rc-none { color:var(--dim); font-size:13.5px; }
    .rc-down { display:flex; align-items:center; color:var(--coral); font-size:13px; font-weight:600; }
    .rc-down .dot { background:transparent; box-shadow:inset 0 0 0 1.5px var(--coral); }
    #devices .rc-ctl { min-height:38px; padding:0 12px 0 10px; border-radius:999px; font-size:13px; flex:0 0 auto; }
    #devices .rc-ctl .ico { width:17px; height:17px; }
    #devices .from .rc-ready { color:var(--accent); font-weight:600; }
    .chat-head .rc-ctl { min-height:36px; padding:0 13px 0 10px; border-radius:999px; font-size:13px; flex:0 0 auto; }
    .chat-head .rc-ctl .ico { width:17px; height:17px; }
    .rc-me { display:flex; align-items:center; gap:12px; width:100%; min-height:58px; padding:8px 12px 8px 8px; text-align:left;
             justify-content:flex-start; background:var(--card-2); border-color:transparent; border-radius:16px; font-weight:500; }
    .rc-me:hover { border-color:color-mix(in srgb, var(--accent) 45%, transparent); }
    .rc-me .ico-tile { width:38px; height:38px; border-radius:12px; }
    .rc-me .lbl { flex:1; min-width:0; line-height:1.25; }
    .rc-me .lbl b { display:block; font-weight:650; }
    .rc-me .lbl small { display:block; color:var(--dim); font-size:12.5px; margin-top:1px; }
    .rc-me > .ico:last-child { color:var(--dim); width:18px; height:18px; }
    .rc-me.ready .ico-tile { background:var(--tide); color:var(--on-accent); }

    /* ---- the sheet: full screen on a phone, a large panel on a desktop ---- */
    .rc-veil { position:fixed; inset:0; z-index:54; background:rgba(2,10,13,.5);
               -webkit-backdrop-filter:blur(6px); backdrop-filter:blur(6px); opacity:0; transition:opacity .25s; }
    .rc-veil.show { opacity:1; }
    .rc-sheet { position:fixed; inset:0; z-index:55; display:flex; flex-direction:column; background:var(--bg); color:var(--text);
                isolation:isolate; overflow:hidden; transform:translateY(104%); transition:transform .38s var(--ease);
                padding:env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left); }
    .rc-sheet.show { transform:none; }
    .rc-sheet::before { content:""; position:absolute; inset:0; z-index:-1; pointer-events:none;
      background:radial-gradient(70% 40% at 0% -5%, var(--caustic-1), transparent 70%),
                 radial-gradient(60% 40% at 105% 8%, var(--caustic-2), transparent 70%); }
    .rc-sheet.small { top:auto; max-height:94vh; max-height:94dvh; border-radius:var(--r-lg) var(--r-lg) 0 0;
                      border-top:1px solid var(--line); box-shadow:var(--shadow-float); }
    .rc-sheet.small::after { content:""; position:absolute; top:8px; left:50%; width:38px; height:4.5px; margin-left:-19px;
                             border-radius:999px; background:var(--line-2); }
    @media (min-width: 880px) {
      .rc-sheet, .rc-sheet.small { inset:auto; left:50%; top:50%; width:min(980px, calc(100vw - 64px)); height:min(800px, calc(100vh - 56px));
                  border-radius:var(--r-lg); border:1px solid var(--line); box-shadow:var(--shadow-float);
                  transform:translate(-50%, -47%) scale(.97); opacity:0; transition:transform .32s var(--spring), opacity .2s; }
      .rc-sheet.show { transform:translate(-50%, -50%); opacity:1; }
      .rc-sheet.small { width:min(580px, calc(100vw - 64px)); height:auto; max-height:calc(100vh - 56px); }
      .rc-sheet.small::after { display:none; }
    }
    .rc-head { display:flex; align-items:center; gap:12px; padding:12px 12px 8px 16px; flex:0 0 auto; }
    .rc-head .who { flex:1; min-width:0; line-height:1.2; }
    .rc-head .who b { display:block; font-family:var(--font-display); font-size:20px; font-weight:800; letter-spacing:-.03em;
                      overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .rc-state { display:flex; align-items:center; font-size:12.5px; color:var(--dim); margin-top:2px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
    .rc-state.live { color:var(--accent); font-weight:600; }
    .rc-state.live .dot { background:var(--ok); box-shadow:0 0 8px -1px var(--ok); }
    .rc-state.down { color:var(--coral); font-weight:600; }
    .rc-state.down .dot { background:transparent; box-shadow:inset 0 0 0 1.5px var(--coral); }
    .rc-close { flex:0 0 auto; }
    .rc-body { flex:1; min-height:0; display:flex; flex-direction:column; }
    .rc-tabs { display:flex; gap:6px; overflow-x:auto; scrollbar-width:none; padding:2px 16px 10px; flex:0 0 auto; }
    .rc-tabs::-webkit-scrollbar { display:none; }
    .rc-tab { flex:0 0 auto; border-radius:999px; min-height:40px; padding:0 15px 0 12px; background:var(--card); border:1px solid var(--line);
              color:var(--dim); font-weight:650; font-size:13.5px; gap:7px; }
    .rc-tab .ico { width:18px; height:18px; }
    .rc-tab.on { color:var(--text); background:var(--accent-soft); border-color:var(--accent); box-shadow:0 0 0 1px var(--accent) inset; }
    .rc-tab.on .ico { color:var(--accent); }
    .rc-tab.off { opacity:.55; }
    .rc-panes { flex:1; min-height:0; position:relative; display:flex; flex-direction:column; }
    .rc-pane { flex:1; min-height:0; display:flex; flex-direction:column; gap:12px; padding:4px 16px 16px; overflow-y:auto; overflow-x:hidden;
               overscroll-behavior:contain; animation:panel-in .25s var(--ease); }
    .rc-pane[hidden] { display:none; }
    .rc-pane.fill { overflow:hidden; }
    @media (min-width: 880px) {
      .rc-body { flex-direction:row; }
      .rc-tabs { flex-direction:column; overflow:visible; width:196px; padding:6px 12px 16px; border-right:1px solid var(--line); gap:4px; }
      .rc-tab { justify-content:flex-start; border-radius:14px; background:none; border-color:transparent; min-height:46px; font-size:14px; padding:0 14px; }
      .rc-tab:hover { background:var(--card-2); }
      .rc-tab.on { box-shadow:none; border-color:transparent; }
      .rc-pane { padding:6px 24px 24px; }
      .rc-head { padding:16px 16px 12px 20px; border-bottom:1px solid var(--line); margin-bottom:10px; }
    }
    .rc-gone { flex:1; display:grid; place-content:center; justify-items:center; gap:6px; text-align:center; color:var(--dim); padding:24px; }
    .rc-gone b { color:var(--text); font-family:var(--font-display); font-size:17px; font-weight:750; letter-spacing:-.015em; margin-top:var(--s2); }
    .rc-gone span { max-width:32ch; font-size:13.5px; }
    .rc-gone.down .splash { color:var(--coral); }
    .rc-sec { display:flex; align-items:center; gap:var(--s2); font-size:11.5px; font-weight:750; letter-spacing:.12em;
              text-transform:uppercase; color:var(--dim); padding:4px 2px 0; }
    .rc-sec .grow { flex:1; }

    /* ---- touchpad ---- */
    .rc-pad { position:relative; flex:1; min-height:240px; border-radius:var(--r-lg); overflow:hidden; isolation:isolate;
              background:radial-gradient(120% 70% at 50% -10%, var(--accent-soft), transparent 65%), var(--card);
              border:1px solid var(--line); box-shadow:var(--shadow); touch-action:none; cursor:crosshair;
              -webkit-user-select:none; user-select:none; -webkit-touch-callout:none; transition:border-color .2s, box-shadow .2s; }
    .rc-pad::before { content:""; position:absolute; inset:0; z-index:-1; pointer-events:none; opacity:.7;
                      background-image:radial-gradient(var(--line-2) 1px, transparent 1.6px); background-size:22px 22px;
                      -webkit-mask:radial-gradient(ellipse at center, #000 30%, transparent 80%); mask:radial-gradient(ellipse at center, #000 30%, transparent 80%); }
    .rc-pad.dragging { border-color:var(--coral); box-shadow:0 0 0 4px var(--coral-soft), var(--shadow); }
    .rc-pad.captured { border-color:var(--accent); box-shadow:0 0 0 4px var(--accent-soft), var(--shadow); cursor:none; }
    .rc-hint { position:absolute; inset:0; display:grid; place-content:center; justify-items:center; gap:10px; pointer-events:none;
               color:var(--dim); font-size:13px; text-align:center; transition:opacity .5s; padding:24px; }
    .rc-hint .big { width:40px; height:40px; color:var(--accent); opacity:.8; stroke-width:1.5; }
    .rc-hint ul { display:grid; gap:4px; }
    .rc-hint li b { color:var(--text); font-weight:650; }
    .rc-pad.used .rc-hint { opacity:0; }
    .rc-finger { position:absolute; left:0; top:0; width:64px; height:64px; margin:-32px 0 0 -32px; border-radius:50%; pointer-events:none;
                 background:radial-gradient(circle, color-mix(in srgb, var(--accent) 38%, transparent) 0, transparent 68%); }
    .rc-pad.dragging .rc-finger { background:radial-gradient(circle, color-mix(in srgb, var(--coral) 45%, transparent) 0, transparent 68%); }
    .rc-rip { position:absolute; width:90px; height:90px; margin:-45px 0 0 -45px; border-radius:50%; pointer-events:none;
              border:1.5px solid var(--accent); animation:rc-rip .6s var(--ease) forwards; }
    .rc-rip.r { border-color:var(--accent-2); } .rc-rip.m { border-color:var(--coral); }
    @keyframes rc-rip { from { transform:scale(.15); opacity:.95; } to { transform:scale(1.35); opacity:0; } }
    .rc-mode { position:absolute; top:12px; left:50%; transform:translate(-50%, -8px); opacity:0; pointer-events:none;
               padding:5px 12px; border-radius:999px; background:var(--text); color:var(--bg); font-size:12px; font-weight:700;
               transition:opacity .2s, transform .25s var(--spring); white-space:nowrap; }
    .rc-mode.show { opacity:1; transform:translate(-50%, 0); }
    .rc-padtools { position:absolute; top:8px; right:8px; left:8px; display:flex; justify-content:flex-end; gap:6px; pointer-events:none; }
    .rc-padtools button { pointer-events:auto; background:color-mix(in srgb, var(--card-2) 80%, transparent); border-color:transparent;
                          min-height:36px; border-radius:999px; font-size:12.5px; color:var(--dim); padding:0 12px; }
    .rc-padtools button.icon-btn { width:36px; padding:0; }
    .rc-padtools button .ico { width:17px; height:17px; }
    .rc-padtools .sp { flex:1; }
    .rc-settings { position:absolute; left:8px; right:8px; bottom:8px; background:var(--card); border:1px solid var(--line);
                   border-radius:18px; padding:12px 14px; box-shadow:var(--shadow-float); display:grid; gap:10px; cursor:default;
                   animation:rise .25s var(--spring); }
    .rc-settings label { display:flex; align-items:center; gap:12px; font-size:13.5px; font-weight:600; }
    .rc-settings label span:first-child { flex:0 0 auto; white-space:nowrap; }
    .rc-settings output { width:2.8em; text-align:right; color:var(--dim); font-size:12.5px; font-variant-numeric:tabular-nums; }
    .rc-btns { display:grid; grid-template-columns:1fr 1fr; gap:8px; flex:0 0 auto; }
    .rc-btns button { min-height:62px; background:var(--card); border-radius:10px 10px 10px 24px; font-size:13px; color:var(--dim);
                      touch-action:none; }
    .rc-btns button + button { border-radius:10px 10px 24px 10px; }
    .rc-btns button.down { background:var(--accent-soft); border-color:var(--accent); color:var(--accent); transform:scale(.98); }

    /* ---- sliders (speed, volume) ---- */
    .rc-range { flex:1; min-width:0; height:32px; min-height:0; margin:0; padding:0; border:none; box-shadow:none; background:none;
                -webkit-appearance:none; appearance:none; cursor:pointer; --p:50%; }
    .rc-range:focus { box-shadow:none; }
    .rc-range::-webkit-slider-runnable-track { height:6px; border-radius:999px;
        background:linear-gradient(to right, var(--accent) var(--p), var(--card-2) var(--p)); }
    .rc-range::-moz-range-track { height:6px; border-radius:999px; background:var(--card-2); }
    .rc-range::-moz-range-progress { height:6px; border-radius:999px; background:var(--accent); }
    .rc-range::-webkit-slider-thumb { -webkit-appearance:none; width:20px; height:20px; margin-top:-7px; border-radius:50%;
        background:#fff; border:none; box-shadow:0 1px 4px rgba(0,0,0,.35), 0 0 0 4px var(--accent-soft); }
    .rc-range::-moz-range-thumb { width:20px; height:20px; border-radius:50%; background:#fff; border:none;
        box-shadow:0 1px 4px rgba(0,0,0,.35), 0 0 0 4px var(--accent-soft); }
    .rc-switch { appearance:none; -webkit-appearance:none; width:42px; height:26px; min-height:0; padding:0; border-radius:999px; margin:0 0 0 auto;
                 background:var(--card-2); border:1px solid var(--line-2); position:relative; cursor:pointer; transition:background .2s; flex:0 0 auto; }
    .rc-switch::after { content:""; position:absolute; top:2px; left:2px; width:20px; height:20px; border-radius:50%; background:#fff;
                        box-shadow:0 1px 3px rgba(0,0,0,.3); transition:transform .25s var(--spring); }
    .rc-switch:checked { background:var(--accent); border-color:var(--accent); }
    .rc-switch:checked::after { transform:translateX(16px); }

    /* ---- keyboard ---- */
    .rc-type { position:relative; }
    .rc-input { width:100%; min-height:60px; max-height:140px; resize:none; font-size:17px; border-radius:18px; background:var(--card);
                padding:17px 18px; line-height:1.4; }
    .rc-ph { position:absolute; left:19px; top:18px; right:18px; color:var(--dim); pointer-events:none; font-size:16px;
             white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
    .rc-echo { display:flex; align-items:center; gap:8px; min-height:20px; font-size:12.5px; color:var(--dim); padding:0 4px; }
    /* the newest keys are at the right; older ones fade out on the left */
    .rc-echo > span:last-child { flex:1; min-width:0; overflow:hidden; display:flex; justify-content:flex-end;
                                 -webkit-mask:linear-gradient(90deg, transparent, #000 40px); mask:linear-gradient(90deg, transparent, #000 40px); }
    .rc-echo i { font-style:normal; white-space:pre; color:var(--accent); font-family:ui-monospace,SFMono-Regular,Menlo,monospace; }
    .rc-keygrid { display:grid; grid-template-columns:repeat(4, minmax(0,1fr)); gap:8px; }
    .rc-key { min-height:48px; border-radius:14px; background:var(--card); font-weight:650; font-size:13.5px; padding:0 6px; gap:5px;
              touch-action:manipulation; font-variant-numeric:tabular-nums; }
    .rc-key .ico { width:19px; height:19px; }
    .rc-key.pressed { background:var(--accent-soft); border-color:var(--accent); color:var(--accent); }
    .rc-key.mod.on { background:var(--accent-soft); border-color:var(--accent); color:var(--accent); box-shadow:0 0 0 1px var(--accent) inset; }
    .rc-key.mod.lock { background:var(--tide); border-color:transparent; color:var(--on-accent); box-shadow:0 6px 16px -8px var(--accent-2); }
    .rc-key.mod small { font-size:10px; font-weight:700; opacity:.8; }
    .rc-nav { display:grid; grid-template-columns:repeat(4, minmax(0,1fr)); grid-template-rows:48px 48px; gap:8px; }
    .rc-arrows { grid-column:1 / span 3; grid-row:1 / span 2; display:grid; grid-template-columns:repeat(3, minmax(0,1fr)); gap:8px; }
    .rc-arrows .up { grid-column:2; grid-row:1; }
    .rc-arrows .left { grid-column:1; grid-row:2; }
    .rc-arrows .down { grid-column:2; grid-row:2; }
    .rc-arrows .right { grid-column:3; grid-row:2; }
    .rc-nav .enter { grid-column:4; grid-row:1 / span 2; min-height:0; background:var(--accent-soft); color:var(--accent); border-color:transparent; }
    .rc-fkeys { display:flex; gap:6px; overflow-x:auto; scrollbar-width:none; margin:0 -16px; padding:2px 16px; }
    .rc-fkeys::-webkit-scrollbar { display:none; }
    .rc-fkeys .rc-key { flex:0 0 auto; min-width:52px; min-height:42px; }
    .rc-fkeys .rc-key svg { display:block; }
    @media (min-width: 880px) { .rc-fkeys { flex-wrap:wrap; margin:0; padding:0; overflow:visible; } }
    .rc-shorts { display:grid; grid-template-columns:repeat(auto-fill, minmax(9.5rem, 1fr)); gap:8px; }
    .rc-short { flex-direction:column; align-items:flex-start; justify-content:center; gap:1px; min-height:52px; border-radius:14px;
                background:var(--card); font-weight:650; font-size:13.5px; padding:6px 12px; text-align:left; }
    .rc-short span { max-width:100%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .rc-short kbd { font:inherit; font-size:11.5px; font-weight:600; color:var(--dim); white-space:nowrap; }
    .rc-short.warn:hover { border-color:color-mix(in srgb, var(--coral) 50%, transparent); }
    @media (min-width: 880px) { .rc-kb-cols { display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr); gap:20px; align-items:start; }
                                .rc-kb-cols > div { display:grid; gap:12px; } }
    @media (max-width: 879px) { .rc-kb-cols, .rc-kb-cols > div { display:grid; gap:12px; } }

    /* ---- slides ---- */
    .rc-slides { flex:1; min-height:0; display:grid; gap:10px; grid-template-rows:auto minmax(0,1fr) auto auto auto; }
    .rc-timer { display:flex; align-items:center; justify-content:space-between; gap:12px; background:none; border:none; padding:0 4px;
                min-height:0; border-radius:14px; text-align:left; font-weight:500; color:var(--text); }
    .rc-timer:active:not(:disabled) { transform:none; }
    .rc-timer .t { font-family:var(--font-display); font-size:40px; font-weight:800; letter-spacing:-.04em; font-variant-numeric:tabular-nums; line-height:1.1; }
    .rc-timer .t.idle { color:var(--dim); }
    .rc-timer small { display:block; color:var(--dim); font-size:12px; font-weight:600; }
    .rc-timer .now { text-align:right; }
    .rc-timer .now b { display:block; font-size:17px; font-variant-numeric:tabular-nums; font-weight:700; }
    .rc-flip { display:grid; grid-template-rows:minmax(0,2.2fr) minmax(0,1fr); gap:10px; min-height:0; }
    .rc-next, .rc-prev { flex-direction:column; gap:6px; border-radius:var(--r-lg); min-height:0; touch-action:manipulation; }
    .rc-next { background:var(--tide); border-color:transparent; color:var(--on-accent); font-family:var(--font-display); font-size:24px;
               font-weight:800; letter-spacing:-.02em; box-shadow:0 14px 34px -16px var(--accent-2); }
    .rc-next .ico { width:52px; height:52px; stroke-width:2.2; }
    .rc-prev { background:var(--card); font-size:17px; font-family:var(--font-display); font-weight:750; color:var(--text); }
    .rc-prev .ico { width:30px; height:30px; }
    .rc-next.hit { animation:rc-hit .35s var(--spring); }
    @keyframes rc-hit { 40% { transform:scale(.97); filter:brightness(1.12); } }
    .rc-show { display:grid; grid-template-columns:repeat(3, minmax(0,1fr)); gap:8px; }
    .rc-show button { min-height:52px; flex-direction:column; gap:2px; font-size:12px; border-radius:16px; background:var(--card); }
    .rc-show .ico { width:19px; height:19px; }
    .rc-laser { position:relative; min-height:64px; border-radius:999px; background:var(--coral-soft); color:var(--coral);
                border:1.5px dashed color-mix(in srgb, var(--coral) 55%, transparent); font-weight:700; font-size:15px; touch-action:none;
                -webkit-user-select:none; user-select:none; -webkit-touch-callout:none; }
    .rc-laser .ico { width:22px; height:22px; }
    .rc-laser.on { background:var(--coral); color:#fff; border-style:solid; border-color:var(--coral); animation:rc-laser 1.2s var(--ease) infinite; }
    @keyframes rc-laser { from { box-shadow:0 0 0 0 color-mix(in srgb, var(--coral) 50%, transparent); } to { box-shadow:0 0 0 14px transparent; } }
    .rc-laser-note { text-align:center; font-size:12px; color:var(--dim); margin-top:-4px; }
    @media (min-width: 880px) {
      .rc-flip { grid-template-rows:none; grid-template-columns:minmax(0,1fr) minmax(0,2fr); }
      .rc-flip .rc-prev { order:-1; }
    }

    /* ---- media ---- */
    .rc-media { position:relative; display:flex; flex-direction:column; gap:14px; isolation:isolate; }
    .rc-glow { position:absolute; inset:-30% -20% auto; height:120%; z-index:-1; background-size:cover; background-position:center;
               filter:blur(60px) saturate(1.6); opacity:.28; pointer-events:none; transition:opacity .4s; }
    @media (prefers-color-scheme: light) { .rc-glow { opacity:.13; } }
    .rc-players { display:flex; flex-wrap:wrap; gap:var(--s2); justify-content:center; }
    .rc-players .chip { min-height:36px; font-size:13px; padding:0 14px; }
    .rc-now { display:flex; flex-direction:column; align-items:center; gap:16px; text-align:center; }
    .rc-art { width:min(62vw, 250px); aspect-ratio:1; border-radius:24px; overflow:hidden; background:var(--card-2); display:grid;
              place-items:center; color:var(--dim); box-shadow:0 24px 48px -20px rgba(0,0,0,.6); flex:0 0 auto; }
    .rc-art img { width:100%; height:100%; object-fit:cover; display:block; }
    .rc-meta { min-width:0; width:100%; }
    .rc-title { font-family:var(--font-display); font-size:22px; font-weight:800; letter-spacing:-.025em; line-height:1.2;
                overflow:hidden; text-overflow:ellipsis; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; word-break:break-word; }
    .rc-title.idle { color:var(--dim); font-weight:650; font-size:18px; }
    .rc-artist { color:var(--dim); font-size:14.5px; margin-top:4px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
    .rc-src { color:var(--dim); font-size:12.5px; margin-top:4px; opacity:.85; }
    .rc-prog { display:flex; align-items:center; gap:10px; font-size:11.5px; color:var(--dim); font-variant-numeric:tabular-nums; width:100%; }
    .rc-track { flex:1; height:24px; display:flex; align-items:center; touch-action:manipulation; }
    .rc-track.seekable { cursor:pointer; }
    .rc-track > div { flex:1; height:6px; border-radius:999px; background:var(--card-2); overflow:hidden; }
    .rc-track > div > div { height:100%; width:0; background:var(--tide); border-radius:inherit; }
    .rc-ctrls { display:flex; align-items:center; justify-content:center; gap:14px; }
    .rc-ctrls button { width:56px; height:56px; border-radius:50%; padding:0; background:none; border:none; color:var(--text); }
    .rc-ctrls button:hover:not(:disabled) { background:color-mix(in srgb, var(--text) 8%, transparent); }
    .rc-ctrls button:disabled { opacity:.3; }
    .rc-ctrls button.big { width:76px; height:76px; background:var(--tide); color:var(--on-accent); box-shadow:0 12px 28px -12px var(--accent-2); }
    .rc-ctrls button.big:hover { background:var(--tide); filter:brightness(1.06); }
    .rc-vol { display:flex; align-items:center; gap:10px; width:100%; }
    .rc-vol > button { width:44px; height:44px; padding:0; border-radius:50%; background:none; border:none; color:var(--dim); flex:0 0 auto; }
    .rc-vol > button.on { color:var(--coral); }
    .rc-vol .pct { font-size:12px; color:var(--dim); width:2.8em; text-align:right; font-variant-numeric:tabular-nums; }
    .rc-vol.muted .rc-range { opacity:.45; }
    .rc-live { display:inline-flex; align-items:flex-end; gap:2px; height:14px; }
    .rc-live i { width:3px; border-radius:2px; background:var(--accent); animation:rc-eq 1s ease-in-out infinite; transform-origin:50% 100%; }
    .rc-live i:nth-child(1) { height:60%; } .rc-live i:nth-child(2) { height:100%; animation-delay:-.4s; } .rc-live i:nth-child(3) { height:45%; animation-delay:-.7s; }
    @keyframes rc-eq { 50% { transform:scaleY(.35); } }
    @media (min-width: 880px) {
      .rc-now { flex-direction:row; text-align:left; align-items:center; gap:28px; }
      .rc-art { width:240px; }
      .rc-players { justify-content:flex-start; }
      .rc-media { max-width:720px; }
    }

    /* ---- lists (messages, files) ---- */
    .rc-rows { background:var(--card); border:1px solid var(--line); border-radius:var(--r); overflow:hidden; box-shadow:var(--shadow); flex:0 0 auto; }
    .rc-row { display:flex; align-items:center; gap:14px; width:100%; min-height:68px; padding:10px 8px 10px 14px; border:none; border-radius:0;
              background:none; text-align:left; font-weight:500; position:relative; justify-content:flex-start; color:var(--text); }
    .rc-row + .rc-row::before { content:""; position:absolute; top:0; left:74px; right:16px; border-top:1px solid var(--line); }
    .rc-row:active:not(:disabled) { transform:none; background:var(--card-2); }
    @media (hover: hover) { .rc-row:hover { background:color-mix(in srgb, var(--card-2) 60%, transparent); border-color:transparent; } }
    .rc-row .txt { flex:1; min-width:0; }
    .rc-row .txt b { display:block; font-weight:650; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .rc-row .txt small { display:block; color:var(--dim); font-size:12.5px; margin-top:2px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .rc-row .side { flex:0 0 auto; display:flex; flex-direction:column; align-items:flex-end; gap:4px; font-size:11.5px; color:var(--dim);
                    font-variant-numeric:tabular-nums; padding-right:6px; }
    .rc-row.unread .txt b, .rc-row.unread .side span { color:var(--text); }
    .rc-row.unread .txt small { color:var(--text); }
    .rc-row .avatar { width:44px; height:44px; font-size:17px; }
    .rc-row .ftile { width:44px; height:44px; }
    .rc-row .get { flex:0 0 auto; min-height:38px; border-radius:999px; font-size:12.5px; padding:0 12px 0 10px; }
    .rc-row .get .ico { width:17px; height:17px; }
    .rc-row .get.busy { pointer-events:none; }
    .rc-row .get.done { color:var(--ok); background:none; }
    .rc-skel { height:68px; border-top:1px solid var(--line); background:linear-gradient(90deg, transparent, var(--card-2), transparent) 0 0/200% 100%;
               animation:rc-skel 1.3s linear infinite; }
    .rc-skel:first-child { border-top:none; }
    @keyframes rc-skel { to { background-position:-200% 0; } }
    .rc-crumbs { display:flex; align-items:center; gap:4px; overflow-x:auto; scrollbar-width:none; flex:0 0 auto; min-height:40px; }
    .rc-crumbs::-webkit-scrollbar { display:none; }
    .rc-crumbs button { flex:0 0 auto; min-height:34px; border-radius:999px; padding:0 12px; font-size:13px; background:var(--card); }
    .rc-crumbs button.here { background:var(--accent-soft); color:var(--accent); border-color:transparent; }
    .rc-crumbs .ico.sep { width:14px; height:14px; color:var(--dim); flex:0 0 auto; }
    .rc-crumbs .icon-btn { width:36px; }
    .rc-thread { flex:1; min-height:0; display:flex; flex-direction:column; background:var(--card); border:1px solid var(--line);
                 border-radius:var(--r); overflow:hidden; box-shadow:var(--shadow); }
    .rc-thead { display:flex; align-items:center; gap:10px; padding:10px 12px 10px 6px; border-bottom:1px solid var(--line); }
    .rc-thead .who { flex:1; min-width:0; line-height:1.2; }
    .rc-thead .who b { display:block; font-family:var(--font-display); font-size:16px; font-weight:750; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .rc-thead .who small { color:var(--dim); font-size:12.5px; }
    .rc-log { flex:1; min-height:160px; overflow-y:auto; overscroll-behavior:contain; display:flex; flex-direction:column; gap:2px; padding:var(--s3);
              background:linear-gradient(180deg, var(--bg), color-mix(in srgb, var(--bg) 70%, var(--card))); }
    .rc-log .msg.sending { opacity:.6; }
    .rc-log .msg.failed { background:var(--coral-soft); color:var(--coral); border:1px solid var(--coral); }
    .rc-compose { display:flex; align-items:flex-end; gap:var(--s2); padding:10px 10px 10px var(--s3); border-top:1px solid var(--line); background:var(--card); }
    .rc-compose textarea { flex:1; min-height:46px; max-height:8rem; resize:none; border-radius:23px; padding:12px 18px; }
    .rc-compose button { width:46px; min-width:46px; height:46px; padding:0; border-radius:50%; }
    .rc-compose button .ico { transform:translate(-1px, 1px); }

    /* ---- more ---- */
    .rc-actions { display:grid; grid-template-columns:repeat(auto-fill, minmax(15rem, 1fr)); gap:10px; }
    .rc-action { justify-content:flex-start; gap:14px; min-height:84px; padding:14px 16px; text-align:left; background:var(--card);
                 border-radius:var(--r); font-weight:500; }
    .rc-action .ico-tile { width:48px; height:48px; border-radius:15px; }
    .rc-action .ico-tile .ico { width:23px; height:23px; }
    .rc-action.lock .ico-tile { background:var(--coral-soft); color:var(--coral); }
    .rc-action .lbl { flex:1; min-width:0; }
    .rc-action .lbl b { display:block; font-family:var(--font-display); font-size:16px; font-weight:750; letter-spacing:-.01em; }
    .rc-action .lbl small { display:block; color:var(--dim); font-size:12.5px; margin-top:2px; }
    .rc-shot { background:var(--card); border:1px solid var(--line); border-radius:var(--r); padding:12px; display:grid; gap:12px; box-shadow:var(--shadow);
               animation:rise .3s var(--spring); }
    .rc-shot img { width:100%; max-height:46vh; object-fit:contain; border-radius:12px; background:var(--card-2); display:block; }
    .rc-shot .name { font-size:12.5px; color:var(--dim); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; padding:0 4px; }
    .rc-shot .row { justify-content:flex-end; }
    .rc-shot .wait { display:flex; align-items:center; gap:10px; color:var(--dim); font-size:13.5px; padding:6px 4px; }
    .rc-spin { width:18px; height:18px; border-radius:50%; border:2px solid var(--accent-soft); border-top-color:var(--accent);
               animation:rc-spin .8s linear infinite; flex:0 0 auto; }
    @keyframes rc-spin { to { transform:rotate(360deg); } }
    .rc-info { display:flex; gap:12px; align-items:flex-start; color:var(--dim); font-size:13px; padding:4px 4px; }
    .rc-info .ico { color:var(--accent); width:18px; height:18px; margin-top:1px; }

    /* ---- link a helper ---- */
    .rc-link { padding:22px 20px 20px; overflow-y:auto; display:grid; gap:16px;
               grid-template-columns:minmax(0, 1fr); min-width:0; }
    .rc-link > * { min-width:0; }
    .rc-link h2 { font-family:var(--font-display); font-size:23px; font-weight:800; letter-spacing:-.035em; line-height:1.15; margin-top:4px; }
    .rc-link p { color:var(--dim); font-size:14px; }
    .rc-codebox { display:grid; justify-items:center; gap:10px; padding:18px 12px 16px; border-radius:var(--r); background:var(--card);
                  border:1px solid var(--line); box-shadow:var(--shadow); }
    .rc-code { display:flex; gap:6px; align-items:center; background:none; border:none; padding:0; min-height:0; cursor:copy; }
    .rc-code:hover { border:none; }
    .rc-code span { width:44px; height:58px; display:grid; place-items:center; border-radius:13px; background:var(--accent-soft); color:var(--text);
                    font-family:var(--font-display); font-size:32px; font-weight:800; font-variant-numeric:tabular-nums; }
    .rc-code i { width:10px; }
    .rc-codebox.expired .rc-code span { color:var(--dim); background:var(--card-2); text-decoration:line-through; text-decoration-thickness:2px; }
    .rc-expiry { display:flex; align-items:center; gap:8px; font-size:13px; color:var(--dim); font-variant-numeric:tabular-nums; }
    .rc-expiry svg { width:18px; height:18px; transform:rotate(-90deg); }
    .rc-expiry circle { fill:none; stroke-width:3; }
    .rc-expiry .bg { stroke:var(--card-2); }
    .rc-expiry .fg { stroke:var(--accent); stroke-linecap:round; transition:stroke-dashoffset 1s linear; }
    .rc-codebox.expired .rc-expiry { color:var(--coral); font-weight:600; }
    .rc-seg { display:grid; grid-template-columns:repeat(3, 1fr); gap:4px; padding:4px; border-radius:16px; background:var(--card-2); }
    .rc-seg button { min-height:40px; border:none; background:none; color:var(--dim); border-radius:12px; font-size:13.5px; }
    .rc-seg button.on { background:var(--card); color:var(--text); box-shadow:var(--shadow); }
    .rc-seg button .ico { width:17px; height:17px; }
    .rc-steps { display:grid; grid-template-columns:minmax(0, 1fr); gap:10px; counter-reset:step; padding:0; list-style:none; }
    .rc-steps li { display:flex; gap:12px; align-items:flex-start; font-size:14px; line-height:1.45; }
    .rc-steps li::before { counter-increment:step; content:counter(step); flex:0 0 auto; width:24px; height:24px; border-radius:50%;
                           display:grid; place-items:center; background:var(--accent-soft); color:var(--accent); font-size:12px; font-weight:800; margin-top:-1px; }
    .rc-steps li > div { flex:1; min-width:0; }
    .rc-steps b { font-weight:700; }
    .rc-cmd { display:flex; align-items:center; gap:6px; margin-top:8px; background:var(--card-2); border-radius:14px; padding:4px 4px 4px 14px; }
    .rc-cmd code { flex:1; min-width:0; font-family:ui-monospace,SFMono-Regular,Menlo,monospace; font-size:12.5px; line-height:1.5;
                   overflow-x:auto; white-space:nowrap; scrollbar-width:thin; padding:8px 0; }
    .rc-done { display:grid; justify-items:center; gap:10px; text-align:center; padding:12px 0 6px; }
    .rc-done .ok { width:72px; height:72px; border-radius:50%; background:var(--tide); color:var(--on-accent); display:grid; place-items:center;
                   box-shadow:0 0 0 10px var(--accent-soft); animation:pop .45s var(--spring); }
    .rc-done .ok .ico { width:36px; height:36px; stroke-width:2.6; }
    .rc-done b { font-family:var(--font-display); font-size:20px; font-weight:800; letter-spacing:-.02em; }
    .rc-done span { color:var(--dim); font-size:14px; max-width:34ch; }
    .rc-foot { display:flex; gap:var(--s2); justify-content:flex-end; }

    @media (prefers-reduced-motion: reduce) { .rc-sheet, .rc-veil { transition:none; } .rc-live i, .rc-laser.on { animation:none; } }
  ` }));

  // ---------------------------------------------------------------------------
  // the live connection, shared by the whole page
  // ---------------------------------------------------------------------------
  const live = (() => {
    const handlers = {};
    const pending = new Map();   // rpc id -> {resolve, reject, timer}
    let ws = null, backoff = 1000, retryTimer = null, pingTimer = null, deadTimer = null, seq = 0;
    const api = {
      presence: {},   // device id -> {caps, apps}
      state: {},      // device id -> kind -> data
      status: "off",  // off (no named device) | connecting | open | down
      conn: null, device: null,
    };

    function emit(type, msg) {
      for (const fn of [...(handlers[type] || [])]) {
        try { fn(msg); } catch (e) { console.error(e); }
      }
    }
    function setStatus(s) {
      if (api.status === s) return;
      api.status = s;
      emit("status", s);
    }

    function connect() {
      clearTimeout(retryTimer);
      retryTimer = null;
      if (ws || !me || !("WebSocket" in window)) return;
      setStatus(api.status === "open" ? "down" : api.status === "down" ? "down" : "connecting");
      let sock;
      try {
        sock = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/ws");
      } catch {
        return retry();
      }
      ws = sock;
      sock.onopen = () => sock.send(JSON.stringify({ t: "hello", caps: [], platform: "web", app: "droplet-web/1.0" }));
      sock.onmessage = e => {
        if (sock !== ws) return;
        clearTimeout(deadTimer);   // anything from the hub proves the link is alive
        let m;
        try { m = JSON.parse(e.data); } catch { return; }
        if (m && typeof m === "object") handle(m);
      };
      sock.onclose = () => { if (sock === ws) lost(); };
      sock.onerror = () => {};   // onclose follows
    }

    function lost() {
      const sock = ws;
      ws = null;
      try { sock && sock.close(); } catch {}
      clearInterval(pingTimer);
      clearTimeout(deadTimer);
      for (const [id, p] of pending) {
        clearTimeout(p.timer);
        p.reject(new Error("The live link to the hub dropped"));
        pending.delete(id);
      }
      retry();
    }

    function retry() {
      if (!me) { setStatus("off"); return; }
      setStatus("down");
      clearTimeout(retryTimer);
      retryTimer = setTimeout(connect, backoff * (0.8 + Math.random() * 0.4));
      backoff = Math.min(backoff * 2, 30000);
    }

    function handle(m) {
      switch (m.t) {
        case "welcome":
          api.conn = m.conn;
          api.device = m.device;
          api.presence = m.devices || {};
          api.state = m.state || {};
          backoff = 1000;
          clearInterval(pingTimer);
          // browsers can't see the hub's protocol-level pings, so ask for a pong now and then
          pingTimer = setInterval(() => {
            if (!send({ t: "ping" })) return;
            clearTimeout(deadTimer);
            deadTimer = setTimeout(lost, 10000);
          }, 25000);
          setStatus("open");
          emit("welcome", m);
          emit("presence", api.presence);
          emit("state", null);
          break;
        case "presence":
          api.presence = m.devices || {};
          // a device whose last helper left takes its state with it (the hub forgets it too)
          for (const id of Object.keys(api.state)) {
            if (!(api.presence[id] && api.presence[id].caps.length)) delete api.state[id];
          }
          emit("presence", api.presence);
          break;
        case "state":
          if (m.device) (api.state[m.device] = api.state[m.device] || {})[m.kind] = m.data;
          emit("state", m);
          break;
        case "rpc-result": {
          const p = pending.get(String(m.id));
          if (!p) break;
          pending.delete(String(m.id));
          clearTimeout(p.timer);
          if (m.error) p.reject(new Error(String(m.error)));
          else p.resolve(m.result || {});
          break;
        }
        case "pong":
          break;
        default:
          emit(m.t, m);   // error, clip, and whatever newer hubs send
      }
    }

    function send(msg) {
      if (!ws || ws.readyState !== 1 || api.status !== "open") return false;
      try { ws.send(JSON.stringify(msg)); return true; } catch { return false; }
    }

    function rpc(to, method, params = {}, timeout = 30000) {
      return new Promise((resolve, reject) => {
        const id = "w" + (++seq);
        if (!send({ t: "rpc", id, to, method, params })) {
          reject(new Error("Not connected to the hub right now"));
          return;
        }
        const timer = setTimeout(() => {
          pending.delete(id);
          const err = new Error("timeout");
          err.timeout = true;
          reject(err);
        }, timeout);
        pending.set(id, { resolve, reject, timer });
      });
    }

    function on(type, fn) {
      (handlers[type] = handlers[type] || []).push(fn);
      return () => off(type, fn);
    }
    function off(type, fn) {
      const list = handlers[type] || [];
      const i = list.indexOf(fn);
      if (i >= 0) list.splice(i, 1);
    }

    // what a device can do right now; nothing while the link is down
    function caps(id) {
      return api.status === "open" ? ((api.presence[id] || {}).caps || []) : [];
    }

    // come back quickly when the network or the page does
    const now = () => { if (!ws && me) { backoff = 1000; connect(); } };
    window.addEventListener("online", now);
    document.addEventListener("visibilitychange", () => { if (!document.hidden) now(); });
    // `me` is set by the page once this browser has a name
    setInterval(() => { if (me && !ws && !retryTimer) connect(); if (!me && ws) { const s = ws; ws = null; s.close(); setStatus("off"); } }, 1000);

    return Object.assign(api, { send, rpc, on, off, caps, connect });
  })();
  window.dropletLive = live;

  // ---------------------------------------------------------------------------
  // small helpers
  // ---------------------------------------------------------------------------
  const CONTROL_CAPS = ["input", "media", "lock", "screenshot", "sms", "files"];
  const devName = id => ((latest.devices || []).find(d => d.id === id) || {}).name || "That device";
  const controllable = id => live.caps(id).some(c => CONTROL_CAPS.includes(c));
  const isLive = id => live.status === "open" && !!live.presence[id];
  const haptic = (ms = 5) => { try { navigator.vibrate && navigator.vibrate(ms); } catch {} };

  const recent = new Map();   // a message -> when it was last shown, so a burst shows once
  function toast(msg) {
    const now = Date.now();
    if (now - (recent.get(msg) || 0) < 4000) return;
    recent.set(msg, now);
    flash(msg);
  }

  // turn hub and helper errors into something a person can act on
  function friendly(err, name) {
    const text = String((err && err.message) || err || "");
    if (err && err.timeout) return `${name} didn't answer. Its droplet app may be busy or asleep.`;
    if (/isn't connected for this|Not available on that device/i.test(text)) return `${name}'s droplet app isn't running, or can't do that right now`;
    if (/live link|Not connected to the hub/i.test(text)) return "Lost the live link to the hub. Reconnecting…";
    if (/permission/i.test(text)) return `${name}: ${text.replace(/\.$/, "")}. Allow it in the droplet app there.`;
    return text ? `${name}: ${text}` : `Something went wrong on ${name}`;
  }

  live.on("error", m => {
    // errors for the device being controlled are shown by the sheet; anything else here
    const name = m.to ? devName(m.to) : "The hub";
    toast(friendly(m.error, name));
  });

  const capLabel = { input: "Mouse & keys", media: "Media", sms: "Messages", files: "Files", screenshot: "Screenshot", lock: "Lock" };
  function capsSummary(caps) {
    const out = [];
    for (const c of ["input", "media", "sms", "files", "screenshot", "lock"]) if (caps.includes(c)) out.push(capLabel[c]);
    // lock and screenshot only when there's nothing bigger to say
    return (out.length > 2 ? out.filter(x => x !== "Lock" && x !== "Screenshot") : out).join(" · ");
  }

  const OS = { linux: "Linux", windows: "Windows", android: "Android", web: "browser" };
  function via(id) {
    const apps = ((live.presence[id] || {}).apps || []).map(a => OS[a.platform] || a.platform).filter(Boolean);
    return apps.length ? ` · ${[...new Set(apps)].join(" + ")} app` : "";
  }

  const pct = (v, lo, hi) => `${((v - lo) / (hi - lo)) * 100}%`;
  function range(min, max, step, value, label) {
    const r = el("input", { type: "range", min, max, step, value, className: "rc-range" });
    r.setAttribute("aria-label", label);
    const paint = () => r.style.setProperty("--p", pct(Number(r.value), min, max));
    r.addEventListener("input", paint);
    paint();
    r.paint = paint;
    return r;
  }

  const prefs = {
    get speed() { const v = Number(LS.get("droplet-rc-speed")); return v >= 0.3 && v <= 3 ? v : 1; },
    set speed(v) { LS.set("droplet-rc-speed", String(v)); },
    get natural() { return LS.get("droplet-rc-natural") !== "0"; },
    set natural(v) { LS.set("droplet-rc-natural", v ? "1" : "0"); },
    get momentum() { return LS.get("droplet-rc-momentum") !== "0"; },
    set momentum(v) { LS.set("droplet-rc-momentum", v ? "1" : "0"); },
  };

  // ---------------------------------------------------------------------------
  // input events: batched to one frame per animation frame, in order
  // ---------------------------------------------------------------------------
  function makeInput(getTarget, onFail) {
    let queue = [], raf = 0, guard = 0;
    const carry = { move: [0, 0], scroll: [0, 0] };   // what rounding held back

    function push(ev) {
      const last = queue[queue.length - 1];
      if ((ev.k === "move" || ev.k === "scroll") && last && last.k === ev.k) {
        last.dx += ev.dx;
        last.dy += ev.dy;
      } else {
        queue.push({ ...ev });
      }
      if (!raf) {
        raf = requestAnimationFrame(flush);
        guard = setTimeout(flush, 100);   // rAF stops in background tabs; a button-up mustn't wait for that
      }
    }

    function flush() {
      cancelAnimationFrame(raf);
      clearTimeout(guard);
      raf = 0;
      if (!queue.length) return;
      const out = [];
      for (const e of queue) {
        if (e.k === "move" || e.k === "scroll") {
          const c = carry[e.k];
          const x = e.dx + c[0], y = e.dy + c[1];
          const rx = Math.round(x * 100) / 100, ry = Math.round(y * 100) / 100;
          c[0] = x - rx;
          c[1] = y - ry;
          if (rx || ry) out.push({ k: e.k, dx: rx, dy: ry });
        } else {
          out.push(e);
        }
      }
      queue = [];
      if (!out.length) return;
      const to = getTarget();
      if (!to || !live.send({ t: "input", to, ev: out })) onFail();
    }

    return {
      move: (dx, dy) => push({ k: "move", dx, dy }),
      scroll: (dx, dy) => push({ k: "scroll", dx, dy }),
      click: (b = "left", n = 1) => push({ k: "click", b, n }),
      button: (b, down) => push({ k: "button", b, down }),
      key: (key, mods = []) => push({ k: "key", key, mods }),
      text: s => s && push({ k: "text", s }),
      flush,
    };
  }

  // pointer acceleration: slow, small movements stay precise; quick swipes travel far
  function accel(dx, dy, dtMs) {
    const dist = Math.hypot(dx, dy);
    if (!dist) return [0, 0];
    const speed = dist / Math.max(dtMs, 8);   // CSS px per ms; no event is really closer than a 120 Hz frame
    const gain = prefs.speed * (0.85 + 2.6 * (1 - Math.exp(-speed / 0.7)));
    return [dx * gain, dy * gain];
  }

  // ---------------------------------------------------------------------------
  // the sheet shell
  // ---------------------------------------------------------------------------
  let sheet = null;   // {root, veil, close()}

  function openShell(small, label) {
    // swapping one sheet for another keeps the one history entry
    const replacing = !!sheet;
    if (sheet) sheet.close(true);
    const veil = el("div", { className: "rc-veil" });
    const root = el("div", { className: "rc-sheet" + (small ? " small" : ""), role: "dialog" });
    root.setAttribute("aria-modal", "true");
    root.setAttribute("aria-label", label);
    document.body.append(veil, root);
    const prevFocus = document.activeElement;
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const behind = [...document.querySelectorAll(".appbar, main.layout, .tabbar")];
    behind.forEach(n => (n.inert = true));
    requestAnimationFrame(() => requestAnimationFrame(() => { veil.classList.add("show"); root.classList.add("show"); }));
    // the Android back button (and the browser's) closes the sheet
    if (!replacing || !(history.state && history.state.rcSheet)) history.pushState({ rcSheet: true }, "");
    const s = {
      root, veil, cleanup: [],
      close(fromHistory) {
        if (sheet !== s) return;
        sheet = null;
        for (const fn of s.cleanup) { try { fn(); } catch (e) { console.error(e); } }
        behind.forEach(n => (n.inert = false));
        document.body.style.overflow = prevOverflow;
        veil.classList.remove("show");
        root.classList.remove("show");
        setTimeout(() => { veil.remove(); root.remove(); }, 400);
        if (!fromHistory && history.state && history.state.rcSheet) history.back();
        prevFocus && prevFocus.focus && prevFocus.focus({ preventScroll: true });
      },
    };
    veil.onclick = () => s.close();
    sheet = s;
    return s;
  }
  window.addEventListener("popstate", () => { if (sheet) sheet.close(true); });
  document.addEventListener("keydown", e => {
    if (e.key !== "Escape" || !sheet || e.defaultPrevented) return;
    if (document.pointerLockElement) return;   // Esc releases the captured mouse first
    sheet.close();
  });

  function closeButton(s) {
    const b = el("button", { className: "ghost icon-btn rc-close", title: "Close", type: "button" }, icon("x"));
    b.setAttribute("aria-label", "Close");
    b.onclick = () => s.close();
    return b;
  }

  function gone(dev, cap) {
    const down = live.status !== "open";
    const what = { input: "mouse and keyboard", media: "media", sms: "messages", files: "files", lock: "locking", screenshot: "screenshots" }[cap] || "this";
    return el("div", { className: "rc-gone" + (down ? " down" : "") }, SPLASH.cloneNode(true),
      el("b", { textContent: down ? "Live link to the hub is down" : `${dev.name}'s droplet app isn't connected` }),
      el("span", { textContent: down ? "Reconnecting… Controls come back on their own."
        : `Controls for ${what} come back as soon as it reconnects.` }));
  }

  // ---------------------------------------------------------------------------
  // pane: touchpad
  // ---------------------------------------------------------------------------
  function touchpadPane(ctx) {
    const { input } = ctx;
    const pad = el("div", { className: "rc-pad", tabIndex: 0 });
    pad.setAttribute("aria-label", `Touchpad for ${ctx.dev.name}`);
    const hint = el("div", { className: "rc-hint" }, icon("touchpad", "big"),
      el("ul", {},
        el("li", {}, el("b", { textContent: "Slide" }), " to move · ", el("b", { textContent: "tap" }), " to click"),
        el("li", {}, el("b", { textContent: "Two fingers" }), " to scroll or right-click"),
        el("li", {}, el("b", { textContent: "Double-tap and hold" }), " to drag")));
    const mode = el("div", { className: "rc-mode" });
    const gear = el("button", { className: "icon-btn", type: "button", title: "Touchpad settings" }, icon("sliders"));
    gear.setAttribute("aria-label", "Touchpad settings");
    const tools = el("div", { className: "rc-padtools" });
    const fine = matchMedia("(pointer: fine)").matches && "requestPointerLock" in pad;
    let capture = null;
    if (fine) {
      capture = el("button", { type: "button", title: "Use this computer's mouse and keyboard on the other device" }, icon("mouse"), "Capture mouse");
      tools.append(capture, el("span", { className: "sp" }));
    }
    tools.append(gear);
    pad.append(hint, mode, tools);

    // settings: speed, scrolling direction, momentum
    const speed = range(0.4, 2.5, 0.05, prefs.speed, "Pointer speed");
    const speedOut = el("output", { textContent: prefs.speed.toFixed(2) + "×" });
    speed.addEventListener("input", () => { prefs.speed = Number(speed.value); speedOut.textContent = Number(speed.value).toFixed(2) + "×"; });
    const natural = el("input", { type: "checkbox", className: "rc-switch", checked: prefs.natural });
    natural.onchange = () => (prefs.natural = natural.checked);
    const momentum = el("input", { type: "checkbox", className: "rc-switch", checked: prefs.momentum });
    momentum.onchange = () => (prefs.momentum = momentum.checked);
    const settings = el("div", { className: "rc-settings", hidden: true },
      el("label", {}, el("span", { textContent: "Speed" }), speed, speedOut),
      el("label", {}, el("span", { textContent: "Natural scrolling" }), natural),
      el("label", {}, el("span", { textContent: "Glide after scrolling" }), momentum));
    pad.append(settings);
    for (const n of [tools, settings]) {
      for (const ev of ["pointerdown", "pointerup", "pointermove", "wheel", "contextmenu"]) n.addEventListener(ev, e => e.stopPropagation());
    }
    gear.onclick = () => { settings.hidden = !settings.hidden; };

    const left = el("button", { type: "button" }, "Left");
    const right = el("button", { type: "button" }, "Right");
    const btns = el("div", { className: "rc-btns" }, left, right);
    for (const [b, name] of [[left, "left"], [right, "right"]]) {
      b.setAttribute("aria-label", `${name} mouse button`);
      let held = false;
      b.addEventListener("pointerdown", e => {
        e.preventDefault();
        b.setPointerCapture(e.pointerId);
        held = true;
        b.classList.add("down");
        input.button(name, true);
        haptic();
      });
      const up = () => { if (!held) return; held = false; b.classList.remove("down"); input.button(name, false); };
      b.addEventListener("pointerup", up);
      b.addEventListener("pointercancel", up);
      b.addEventListener("contextmenu", e => e.preventDefault());
      b.addEventListener("keydown", e => { if (e.key === " " || e.key === "Enter") { e.preventDefault(); input.click(name, 1); } });
    }

    const root = el("div", { className: "rc-pane fill" }, pad, btns);

    // --- feedback ---
    let modeTimer = null;
    function showMode(text, sticky) {
      mode.textContent = text;
      mode.classList.add("show");
      clearTimeout(modeTimer);
      if (!sticky) modeTimer = setTimeout(() => mode.classList.remove("show"), 900);
    }
    function hideMode() { clearTimeout(modeTimer); mode.classList.remove("show"); }
    function ripple(x, y, cls = "") {
      const r = pad.getBoundingClientRect();
      const d = el("span", { className: "rc-rip " + cls });
      d.style.left = x - r.left + "px";
      d.style.top = y - r.top + "px";
      pad.append(d);
      setTimeout(() => d.remove(), 650);
    }
    const fingers = new Map();
    function finger(id, x, y) {
      let f = fingers.get(id);
      if (!f) { f = el("span", { className: "rc-finger" }); pad.append(f); fingers.set(id, f); }
      const r = pad.getBoundingClientRect();
      f.style.transform = `translate(${x - r.left}px, ${y - r.top}px)`;
    }
    function unfinger(id) { const f = fingers.get(id); if (f) { f.remove(); fingers.delete(id); } }

    // --- touch gestures ---
    const SLOP = 7;         // px a finger may wander and still be a tap
    const TAP_MS = 260;     // longest tap
    const DOUBLE_MS = 230;  // a second touch this soon after a tap may become a double-click or a drag
    const HOLD_MS = 180;    // ...and holding it this long starts a drag
    const LINE_PX = 18;     // finger travel per scrolled line
    const pts = new Map();
    let g = null;           // the gesture in progress
    let pendingTap = null;  // a single tap waiting to see if a second one follows
    let glide = 0;          // momentum scrolling frame

    function stopGlide() { cancelAnimationFrame(glide); glide = 0; }
    function sendPendingTap() {
      if (!pendingTap) return;
      clearTimeout(pendingTap.timer);
      pendingTap = null;
      input.click("left", 1);
    }

    function startDrag() {
      if (!g || g.mode === "drag") return;
      clearTimeout(g.hold);
      g.mode = "drag";
      pad.classList.add("dragging");
      showMode("Dragging", true);
      input.button("left", true);
      haptic(12);
    }

    pad.addEventListener("pointerdown", e => {
      if (e.pointerType === "mouse") return mouseDown(e);
      e.preventDefault();
      pad.classList.add("used");
      settings.hidden = true;
      try { pad.setPointerCapture(e.pointerId); } catch {}
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY, t: e.timeStamp });
      finger(e.pointerId, e.clientX, e.clientY);
      stopGlide();
      if (pts.size === 1) {
        g = { t0: e.timeStamp, maxPts: 1, moved: 0, mode: "pending", held: [0, 0], second: false, vx: 0, vy: 0, lastT: e.timeStamp };
        if (pendingTap) {
          // a second touch soon after a tap: a double-click, or hold/slide to drag
          clearTimeout(pendingTap.timer);
          pendingTap = null;
          g.second = true;
          g.hold = setTimeout(startDrag, HOLD_MS);
        }
      } else if (g) {
        g.maxPts = Math.max(g.maxPts, pts.size);
        if (g.mode !== "drag") {
          clearTimeout(g.hold);
          if (g.second) { g.second = false; input.click("left", 1); }   // the earlier tap still counts
          g.mode = "multi";
        }
      }
    });

    pad.addEventListener("pointermove", e => {
      const p = pts.get(e.pointerId);
      if (!p || !g) return;
      const dx = e.clientX - p.x, dy = e.clientY - p.y;
      const dt = e.timeStamp - p.t;
      p.x = e.clientX; p.y = e.clientY; p.t = e.timeStamp;
      finger(e.pointerId, e.clientX, e.clientY);
      g.moved += Math.hypot(dx, dy);

      if (g.mode === "multi" || g.mode === "scroll") {
        if (g.mode === "multi" && g.moved > SLOP * 2 && pts.size >= 2) { g.mode = "scroll"; showMode("Scrolling"); }
        if (g.mode !== "scroll" || pts.size < 2) return;
        // each finger's share of the centroid's movement, in lines
        const k = (prefs.natural ? -1 : 1) / (LINE_PX * pts.size) * Math.sqrt(prefs.speed);
        const sx = dx * k, sy = dy * k;
        input.scroll(sx, sy);
        const w = Math.min(1, dt / 40);   // a smoothed velocity for the glide
        if (dt > 0) { g.vx = g.vx * (1 - w) + (sx / dt) * w; g.vy = g.vy * (1 - w) + (sy / dt) * w; }
        g.lastT = e.timeStamp;
        return;
      }
      if (pts.size !== 1) return;
      const [ax, ay] = accel(dx, dy, dt);
      if (g.mode === "pending") {
        g.held[0] += ax; g.held[1] += ay;
        if (g.moved <= SLOP) return;
        if (g.second) startDrag();
        else g.mode = "move";
        input.move(g.held[0], g.held[1]);   // what moved inside the slop isn't lost
        return;
      }
      if (g.mode === "move" || g.mode === "drag") input.move(ax, ay);
    });

    function endTouch(e, cancelled) {
      if (!pts.has(e.pointerId)) return;
      pts.delete(e.pointerId);
      unfinger(e.pointerId);
      if (pts.size || !g) return;
      const gest = g;
      g = null;
      clearTimeout(gest.hold);
      const dur = e.timeStamp - gest.t0;
      if (gest.mode === "drag") {
        input.button("left", false);
        pad.classList.remove("dragging");
        hideMode();
        return;
      }
      if (cancelled) { if (gest.second) input.click("left", 1); return; }
      const tap = dur < TAP_MS && gest.moved < SLOP * gest.maxPts;
      if (tap && gest.maxPts === 1) {
        ripple(e.clientX, e.clientY);
        haptic();
        if (gest.second) { input.click("left", 2); return; }
        // wait a moment: this may be the first half of a double-tap or a tap-and-drag
        pendingTap = { timer: setTimeout(sendPendingTap, DOUBLE_MS) };
        return;
      }
      if (gest.second) input.click("left", 1);
      if (tap && gest.mode === "multi") {
        const b = gest.maxPts === 2 ? "right" : "middle";
        ripple(e.clientX, e.clientY, b === "right" ? "r" : "m");
        showMode(b === "right" ? "Right click" : "Middle click");
        input.click(b, 1);
        haptic();
        return;
      }
      if (gest.mode === "scroll" && prefs.momentum && e.timeStamp - gest.lastT < 80) {
        let vx = gest.vx, vy = gest.vy, last = performance.now();
        if (Math.hypot(vx, vy) < 0.004) return;   // lines per ms
        const step = now => {
          const dt = Math.min(40, now - last);
          last = now;
          vx *= Math.pow(0.994, dt); vy *= Math.pow(0.994, dt);
          if (Math.hypot(vx, vy) < 0.002) { glide = 0; return; }
          input.scroll(vx * dt, vy * dt);
          glide = requestAnimationFrame(step);
        };
        glide = requestAnimationFrame(step);
      }
    }
    pad.addEventListener("pointerup", e => { if (e.pointerType === "mouse") return mouseUp(e); endTouch(e, false); });
    pad.addEventListener("pointercancel", e => { if (e.pointerType === "mouse") return; endTouch(e, true); });

    // --- a mouse or a laptop trackpad on the pad ---
    let mouse = null;   // {button, moved, x, y, t}
    function mouseDown(e) {
      pad.classList.add("used");
      if (document.pointerLockElement === pad) return;   // handled by the captured-mouse listeners
      e.preventDefault();
      pad.focus({ preventScroll: true });
      stopGlide();
      try { pad.setPointerCapture(e.pointerId); } catch {}
      mouse = { button: e.button, moved: 0, t: e.timeStamp, x: e.clientX, y: e.clientY };
    }
    pad.addEventListener("pointermove", e => {
      if (e.pointerType !== "mouse" || document.pointerLockElement === pad) return;
      if (!mouse || mouse.button !== 0) return;
      // with the button held, the mouse slides the pointer like a finger does
      // (client coordinates: movementX is unreliable without pointer lock)
      const dx = e.clientX - mouse.x, dy = e.clientY - mouse.y;
      mouse.x = e.clientX; mouse.y = e.clientY;
      mouse.moved += Math.hypot(dx, dy);
      const [ax, ay] = accel(dx, dy, e.timeStamp - mouse.t);
      mouse.t = e.timeStamp;
      input.move(ax, ay);
    });
    function mouseUp(e) {
      if (document.pointerLockElement === pad || !mouse) return;
      const m = mouse;
      mouse = null;
      if (m.moved > 4) return;
      const b = ["left", "middle", "right"][m.button] || "left";
      input.click(b, 1);
      ripple(e.clientX, e.clientY, b === "right" ? "r" : b === "middle" ? "m" : "");
    }
    pad.addEventListener("contextmenu", e => e.preventDefault());
    pad.addEventListener("wheel", e => {
      e.preventDefault();
      pad.classList.add("used");
      if (e.ctrlKey) return;   // a pinch on a trackpad: not a scroll
      const unit = e.deltaMode === 1 ? 1 : e.deltaMode === 2 ? 20 : 1 / 33;
      input.scroll(e.deltaX * unit, e.deltaY * unit);
    }, { passive: false });

    // --- capture mode: this computer's mouse and keyboard drive the other device ---
    const BTN = ["left", "middle", "right"];
    const onLockMove = e => {
      if (document.pointerLockElement !== pad) return;
      input.move(e.movementX * prefs.speed, e.movementY * prefs.speed);
    };
    const onLockDown = e => { if (document.pointerLockElement === pad && BTN[e.button]) { e.preventDefault(); input.button(BTN[e.button], true); } };
    const onLockUp = e => { if (document.pointerLockElement === pad && BTN[e.button]) { e.preventDefault(); input.button(BTN[e.button], false); } };
    const onLockKey = e => {
      if (document.pointerLockElement !== pad) return;
      forwardKey(e, input, []);
    };
    const onLockChange = () => {
      const on = document.pointerLockElement === pad;
      pad.classList.toggle("captured", on);
      if (on) showMode(`Captured · Esc to release`, true);
      else hideMode();
      if (capture) capture.hidden = on;
    };
    if (capture) {
      capture.onclick = () => {
        try {
          const r = pad.requestPointerLock({ unadjustedMovement: false });
          if (r && r.catch) r.catch(() => toast("This browser didn't allow capturing the mouse"));
        } catch { toast("This browser didn't allow capturing the mouse"); }
      };
      document.addEventListener("mousemove", onLockMove);
      document.addEventListener("mousedown", onLockDown);
      document.addEventListener("mouseup", onLockUp);
      document.addEventListener("keydown", onLockKey, true);
      document.addEventListener("pointerlockchange", onLockChange);
    }

    return {
      el: root, cap: "input",
      hide() {
        if (document.pointerLockElement === pad) document.exitPointerLock();
        if (g && g.mode === "drag") input.button("left", false);
        g = null; pts.clear();
        for (const id of [...fingers.keys()]) unfinger(id);
        pad.classList.remove("dragging");
        stopGlide();
        sendPendingTap();
      },
      destroy() {
        this.hide();
        document.removeEventListener("mousemove", onLockMove);
        document.removeEventListener("mousedown", onLockDown);
        document.removeEventListener("mouseup", onLockUp);
        document.removeEventListener("keydown", onLockKey, true);
        document.removeEventListener("pointerlockchange", onLockChange);
      },
    };
  }

  // ---------------------------------------------------------------------------
  // keys from a physical keyboard, forwarded as protocol key events
  // ---------------------------------------------------------------------------
  const NAMED = new Set(["Enter", "Backspace", "Tab", "Escape", "Delete", "Insert", "Home", "End", "PageUp", "PageDown",
    "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "ContextMenu", "PrintScreen",
    "MediaPlayPause", "MediaTrackNext", "MediaTrackPrevious", "MediaStop", "AudioVolumeUp", "AudioVolumeDown", "AudioVolumeMute",
    ...Array.from({ length: 12 }, (_, i) => "F" + (i + 1))]);
  const KEY_ALIAS = { MediaTrackNext: "MediaNext", MediaTrackPrevious: "MediaPrevious", " ": "Space" };
  function modsOf(e) {
    const m = [];
    if (e.ctrlKey) m.push("ctrl");
    if (e.altKey) m.push("alt");
    if (e.shiftKey) m.push("shift");
    if (e.metaKey) m.push("meta");
    return m;
  }
  // returns true when the key was sent (and the default stopped)
  function forwardKey(e, input, sticky, { allowText = true } = {}) {
    if (e.isComposing || e.keyCode === 229) return false;
    const mods = [...new Set([...modsOf(e), ...sticky])];
    if (NAMED.has(e.key)) {
      e.preventDefault();
      input.key(KEY_ALIAS[e.key] || e.key, mods);
      return true;
    }
    if (e.key.length === 1 || e.key === " ") {
      const altGr = e.getModifierState && e.getModifierState("AltGraph");
      const chord = !altGr && mods.some(m => m !== "shift");
      if (chord) {
        e.preventDefault();
        input.key(KEY_ALIAS[e.key] || e.key.toLowerCase(), mods);
        return true;
      }
      if (allowText) {
        e.preventDefault();
        if (e.key === " ") input.key("Space", []);
        else input.text(e.key);
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------------------
  // pane: keyboard
  // ---------------------------------------------------------------------------
  function keyboardPane(ctx) {
    const { input, dev } = ctx;
    // An invisible character sits at the start of the field, standing for
    // "whatever is already on the other screen": deleting it means Backspace
    // even when the field looks empty (phone keyboards send no key for that).
    const SENTINEL = "​";
    const field = el("textarea", { className: "rc-input", rows: 1, value: SENTINEL, autocomplete: "off", spellcheck: false });
    field.setAttribute("autocapitalize", "off");
    field.setAttribute("autocorrect", "off");
    field.setAttribute("aria-label", `Type on ${dev.name}`);
    field.setAttribute("enterkeyhint", "enter");
    const ph = el("span", { className: "rc-ph", textContent: `Type here, it types on ${dev.name}` });
    const echoText = el("i");
    const echo = el("div", { className: "rc-echo" }, el("span", { textContent: "Sent" }), el("span", {}, echoText));
    echo.hidden = true;
    let echoed = "";
    function note(s) {
      echoed = (echoed + s).slice(-80);
      echoText.textContent = echoed;
      echo.hidden = false;
    }

    let mirror = SENTINEL;   // what the other side has been told about
    let composing = false;
    let enterAt = 0;
    const seg = typeof Intl !== "undefined" && Intl.Segmenter ? new Intl.Segmenter(undefined, { granularity: "grapheme" }) : null;
    const graphemes = s => seg ? [...seg.segment(s)].map(x => x.segment) : [...s];

    // --- sticky modifiers: tap for the next key, double-tap to keep them on ---
    const MODS = [["ctrl", "Ctrl"], ["alt", "Alt"], ["shift", "Shift"], ["meta", "Super"]];
    const modState = {};   // name -> 0 off | 1 once | 2 locked
    const modBtns = {};
    let modTap = {};
    for (const [name, label] of MODS) {
      const b = el("button", { type: "button", className: "rc-key mod" }, label);
      b.setAttribute("aria-pressed", "false");
      b.addEventListener("mousedown", e => e.preventDefault());   // keep the phone keyboard up
      b.onclick = () => {
        const now = Date.now();
        const s = modState[name] || 0;
        if (s === 1 && now - (modTap[name] || 0) < 350) modState[name] = 2;
        else modState[name] = s ? 0 : 1;
        modTap[name] = now;
        paintMods();
        haptic();
      };
      modBtns[name] = b;
    }
    function paintMods() {
      for (const [name, label] of MODS) {
        const s = modState[name] || 0, b = modBtns[name];
        b.classList.toggle("on", s === 1);
        b.classList.toggle("lock", s === 2);
        b.setAttribute("aria-pressed", String(!!s));
        b.replaceChildren(label, ...(s === 2 ? [el("small", { textContent: " ●" })] : []));
        b.title = s === 2 ? `${label} held: tap to release` : s === 1 ? `${label} for the next key` : `${label}: tap for the next key, double-tap to hold`;
      }
    }
    paintMods();
    const sticky = () => MODS.map(m => m[0]).filter(n => modState[n]);
    function useMods() {
      const m = sticky();
      let changed = false;
      for (const n of m) if (modState[n] === 1) { modState[n] = 0; changed = true; }
      if (changed) paintMods();
      return m;
    }

    function sendKey(key, extra = []) {
      sync();
      const mods = [...new Set([...extra, ...useMods()])];
      input.key(key, mods);
      note(keyGlyph(key, mods));
    }
    function keyGlyph(key, mods) {
      const g = { Enter: "⏎", Backspace: "⌫", Tab: "⇥", Escape: "⎋", ArrowLeft: "←", ArrowRight: "→", ArrowUp: "↑", ArrowDown: "↓", Space: "␣" }[key] || `[${key}]`;
      return mods.length ? `[${mods.join("+")}+${g.replace(/^\[|\]$/g, "")}]` : g;
    }

    // compare the field with what was sent, and send only the difference
    function sync() {
      const now = graphemes(field.value), was = graphemes(mirror);
      let p = 0;
      while (p < now.length && p < was.length && now[p] === was[p]) p++;
      const removed = was.length - p;
      const added = now.slice(p).join("").replace(/​/g, "");
      for (let i = 0; i < removed; i++) input.key("Backspace");
      if (removed) note("⌫".repeat(Math.min(removed, 6)));
      if (added) {
        const mods = sticky();
        if (mods.length) {
          // a modifier is waiting: the typed character is a shortcut, not text
          for (const ch of graphemes(added)) {
            input.key(ch === " " ? "Space" : ch.toLowerCase(), mods);
            note(keyGlyph(ch.toLowerCase(), mods));
          }
          useMods();
          field.value = now.slice(0, p).join("");
        } else {
          input.text(added);
          note(added);
        }
      }
      mirror = field.value;
      tidy();
    }
    function tidy() {
      if (composing) return;
      // the invisible marker was deleted, or the field grew long: start afresh
      if (!field.value.startsWith(SENTINEL) || graphemes(field.value).length > 80) {
        field.value = SENTINEL;
        mirror = SENTINEL;
      }
      ph.hidden = field.value !== SENTINEL;
      toEnd();
    }
    function toEnd() {
      const n = field.value.length;
      if (document.activeElement === field && (field.selectionStart !== n || field.selectionEnd !== n)) field.setSelectionRange(n, n);
    }

    field.addEventListener("compositionstart", () => { composing = true; });
    field.addEventListener("compositionend", () => { composing = false; sync(); });
    field.addEventListener("input", e => {
      ph.hidden = field.value !== SENTINEL;
      if (e.inputType === "insertLineBreak" || e.inputType === "insertParagraph" || /\n/.test(field.value)) {
        field.value = field.value.replace(/\n/g, "");
        if (Date.now() - enterAt > 150) { sync(); sendKey("Enter"); }
        field.value = SENTINEL; mirror = SENTINEL; tidy();
        return;
      }
      // mid-composition text (a word the phone keyboard is still guessing) waits until it's settled
      if (e.isComposing || composing) return;
      sync();
    });
    field.addEventListener("keydown", e => {
      if (e.isComposing || e.keyCode === 229) return;
      if (e.key === "Enter" && !modsOf(e).some(m => m !== "shift")) {
        e.preventDefault();
        enterAt = Date.now();
        sync();
        sendKey("Enter", modsOf(e));
        field.value = SENTINEL; mirror = SENTINEL; tidy();
        return;
      }
      // plain Backspace edits the field and goes out as a difference (so it's never sent twice)
      if (e.key === "Backspace" && !modsOf(e).length && !sticky().length) return;
      if (e.key === "Escape" && !sticky().length && !modsOf(e).length) {
        // Esc is useful on the other side; closing the sheet is the X button
        e.preventDefault(); e.stopPropagation(); sendKey("Escape"); return;
      }
      if (NAMED.has(e.key) || modsOf(e).some(m => m !== "shift") || (sticky().length && e.key.length === 1)) {
        sync();
        const extra = useMods();
        if (forwardKey(e, input, extra, { allowText: false })) note(keyGlyph(KEY_ALIAS[e.key] || (e.key.length === 1 ? e.key.toLowerCase() : e.key), [...new Set([...modsOf(e), ...extra])]));
      }
    });
    field.addEventListener("blur", () => { if (!composing) sync(); });
    field.addEventListener("focus", () => setTimeout(toEnd, 0));
    field.addEventListener("pointerup", () => setTimeout(toEnd, 0));

    // --- key buttons; the repeating ones repeat while held ---
    function keyBtn(label, key, { repeat = false, cls = "", title } = {}) {
      const b = el("button", { type: "button", className: "rc-key " + cls, title: title || key });
      b.append(...(Array.isArray(label) ? label : [label]));
      b.setAttribute("aria-label", title || key);
      let t1 = null, t2 = null;
      const stop = () => { clearTimeout(t1); clearInterval(t2); b.classList.remove("pressed"); };
      b.addEventListener("mousedown", e => e.preventDefault());
      b.addEventListener("pointerdown", e => {
        if (e.button !== 0) return;
        b.classList.add("pressed");
        haptic();
        sendKey(key);
        if (repeat) t1 = setTimeout(() => { t2 = setInterval(() => input.key(key), 60); }, 420);
      });
      for (const ev of ["pointerup", "pointerleave", "pointercancel"]) b.addEventListener(ev, stop);
      b.addEventListener("keydown", e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); sendKey(key); } });
      return b;
    }
    const arrows = el("div", { className: "rc-arrows" },
      keyBtn(icon("arrow-u"), "ArrowUp", { repeat: true, cls: "up", title: "Up" }),
      keyBtn(icon("arrow-l"), "ArrowLeft", { repeat: true, cls: "left", title: "Left" }),
      keyBtn(icon("arrow-d"), "ArrowDown", { repeat: true, cls: "down", title: "Down" }),
      keyBtn(icon("arrow-r"), "ArrowRight", { repeat: true, cls: "right", title: "Right" }));
    const nav = el("div", { className: "rc-nav" }, arrows, keyBtn([icon("enter"), "Enter"], "Enter", { cls: "enter", title: "Enter" }));
    const grid = el("div", { className: "rc-keygrid" },
      keyBtn("Esc", "Escape", { title: "Escape" }), keyBtn([icon("arrow-r"), "Tab"], "Tab"),
      keyBtn("Del", "Delete", { repeat: true, title: "Delete" }), keyBtn(icon("backspace"), "Backspace", { repeat: true, title: "Backspace" }),
      keyBtn("Home", "Home"), keyBtn("End", "End"),
      keyBtn("PgUp", "PageUp", { repeat: true, title: "Page up" }), keyBtn("PgDn", "PageDown", { repeat: true, title: "Page down" }));
    const fkeys = el("div", { className: "rc-fkeys" }, ...Array.from({ length: 12 }, (_, i) => keyBtn("F" + (i + 1), "F" + (i + 1))));

    const SHORTS = [
      ["Copy", "c", ["ctrl"], "Ctrl C"], ["Paste", "v", ["ctrl"], "Ctrl V"], ["Cut", "x", ["ctrl"], "Ctrl X"],
      ["Undo", "z", ["ctrl"], "Ctrl Z"], ["Select all", "a", ["ctrl"], "Ctrl A"], ["Switch window", "Tab", ["alt"], "Alt Tab"],
      ["Show desktop", "d", ["meta"], "Super D"], ["Close window", "F4", ["alt"], "Alt F4", true],
    ];
    const shorts = el("div", { className: "rc-shorts" }, ...SHORTS.map(([label, key, mods, hint, warn]) => {
      const b = el("button", { type: "button", className: "rc-short" + (warn ? " warn" : "") }, el("span", { textContent: label }), el("kbd", { textContent: hint }));
      b.addEventListener("mousedown", e => e.preventDefault());
      b.onclick = () => {
        if (warn && !confirm(`Close the active window on ${dev.name}?`)) return;
        sync();
        input.key(key, mods);
        note(keyGlyph(key, mods));
        haptic();
      };
      return b;
    }));
    const g = name => { const x = el("span"); x.innerHTML = glyph(name, 18); return x; };
    const MEDIA_KEYS = [["AudioVolumeDown", "Vol −", "Volume down"], ["AudioVolumeMute", g("muted"), "Mute"], ["AudioVolumeUp", "Vol +", "Volume up"],
                        ["MediaPrevious", g("prev"), "Previous track"], ["MediaPlayPause", [g("play"), g("pause")], "Play or pause"], ["MediaNext", g("next"), "Next track"]];
    const mediaKeys = el("div", { className: "rc-fkeys" }, ...MEDIA_KEYS.map(([k, l, t]) =>
      keyBtn(l, k, { repeat: k === "AudioVolumeDown" || k === "AudioVolumeUp", title: t })));

    const root = el("div", { className: "rc-pane" },
      el("div", { className: "rc-type" }, field, ph), echo,
      el("div", { className: "rc-kb-cols" },
        el("div", {}, el("div", { className: "rc-keygrid" }, ...MODS.map(m => modBtns[m[0]])), grid, nav),
        el("div", {}, el("div", { className: "rc-sec", textContent: "Function keys" }), fkeys,
          el("div", { className: "rc-sec", textContent: "Shortcuts" }), shorts,
          el("div", { className: "rc-sec", textContent: "Volume and media keys" }), mediaKeys)));
    return {
      el: root, cap: "input",
      show() { if (matchMedia("(pointer: fine)").matches) setTimeout(() => field.focus({ preventScroll: true }), 50); },
      hide() { if (!composing) sync(); },
    };
  }

  // ---------------------------------------------------------------------------
  // pane: slides (the presentation remote)
  // ---------------------------------------------------------------------------
  function slidesPane(ctx) {
    const { input, dev } = ctx;
    let started = 0, ticker = null, wake = null, visible = false;
    const tNum = el("span", { className: "t idle", textContent: "0:00" });
    const clock = el("b");
    const timer = el("button", { type: "button", className: "rc-timer", title: "Tap to reset the timer" },
      el("span", {}, el("small", { textContent: "Elapsed · tap to reset" }), tNum),
      el("span", { className: "now" }, el("small", { textContent: "Now" }), clock));
    const fmt = s => { s = Math.floor(s); const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = String(s % 60).padStart(2, "0"); return h ? `${h}:${String(m).padStart(2, "0")}:${x}` : `${m}:${x}`; };
    function tick() {
      tNum.textContent = started ? fmt((Date.now() - started) / 1000) : "0:00";
      tNum.classList.toggle("idle", !started);
      clock.textContent = new Date().toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
    }
    const go = () => { if (!started) { started = Date.now(); tick(); } };
    timer.onclick = () => { started = 0; tick(); haptic(); };

    function act(key, btn) {
      input.key(key);
      go();
      haptic(8);
      if (btn) { btn.classList.remove("hit"); void btn.offsetWidth; btn.classList.add("hit"); }
    }
    const next = el("button", { type: "button", className: "rc-next" }, icon("arrow-r"), "Next");
    const prev = el("button", { type: "button", className: "rc-prev" }, icon("arrow-l"), "Previous");
    next.setAttribute("aria-label", "Next slide");
    prev.setAttribute("aria-label", "Previous slide");
    next.onclick = () => act("ArrowRight", next);
    prev.onclick = () => act("ArrowLeft", prev);
    const showBtn = (ico, label, key, title) => {
      const b = el("button", { type: "button", title }, icon(ico), label);
      b.onclick = () => { if (key === "F5") { started = Date.now(); tick(); } act(key); };
      return b;
    };
    const row = el("div", { className: "rc-show" },
      showBtn("play", "Start", "F5", "Start from the beginning (F5)"),
      showBtn("blank", "Black", "b", "Black screen (B)"),
      showBtn("stop", "End", "Escape", "End the show (Esc)"));

    // --- the laser pointer: hold, and point the phone ---
    const laser = el("button", { type: "button", className: "rc-laser" }, icon("laser"), el("span", { textContent: "Hold to point" }));
    const laserNote = el("div", { className: "rc-laser-note", textContent: "Hold and move the phone to move the pointer. Or drag on the button." });
    let pointing = false, gotMotion = false, noSensorTimer = null, grav = [0, 0, 9.8];
    let lastTouch = null;
    const K = 26;   // pointer pixels per degree turned
    function onMotion(e) {
      if (!pointing) return;
      const g = e.accelerationIncludingGravity;
      if (g && g.x != null) grav = [g.x, g.y, g.z];
      const r = e.rotationRate;
      if (!r || r.alpha == null) return;
      gotMotion = true;
      let dt = e.interval || 16;
      if (dt < 1) dt *= 1000;   // older browsers report seconds
      dt = Math.min(dt, 50) / 1000;
      // turning about the world's vertical moves left/right, whichever way the phone is held
      const n = Math.hypot(...grav) || 9.8;
      const yaw = (r.beta * grav[0] + r.gamma * grav[1] + r.alpha * grav[2]) / n;
      const pitch = r.beta;
      const dz = v => (Math.abs(v) < 0.8 ? 0 : v);   // hands shake a little
      const dx = -dz(yaw) * dt * K * prefs.speed, dy = -dz(pitch) * dt * K * prefs.speed;
      if (dx || dy) input.move(dx, dy);
    }
    async function laserOn(e) {
      e.preventDefault();
      try { laser.setPointerCapture(e.pointerId); } catch {}
      lastTouch = { x: e.clientX, y: e.clientY, t: e.timeStamp };
      if (pointing) return;
      pointing = true;
      laser.classList.add("on");
      laser.lastChild.textContent = "Pointing…";
      haptic(10);
      const DM = window.DeviceMotionEvent;
      if (DM && typeof DM.requestPermission === "function") {
        try { await DM.requestPermission(); } catch {}   // iOS asks once
      }
      if (DM) window.addEventListener("devicemotion", onMotion);
      clearTimeout(noSensorTimer);
      noSensorTimer = setTimeout(() => {
        if (!gotMotion) laserNote.textContent = "No motion sensors here: drag on the button to move the pointer.";
      }, 700);
    }
    function laserMove(e) {
      if (!pointing || !lastTouch) return;
      const dx = e.clientX - lastTouch.x, dy = e.clientY - lastTouch.y;
      const [ax, ay] = accel(dx, dy, e.timeStamp - lastTouch.t);
      lastTouch = { x: e.clientX, y: e.clientY, t: e.timeStamp };
      input.move(ax, ay);
    }
    function laserOff() {
      if (!pointing) return;
      pointing = false;
      lastTouch = null;
      laser.classList.remove("on");
      laser.lastChild.textContent = "Hold to point";
      window.removeEventListener("devicemotion", onMotion);
    }
    laser.addEventListener("pointerdown", laserOn);
    laser.addEventListener("pointermove", laserMove);
    for (const ev of ["pointerup", "pointercancel", "lostpointercapture"]) laser.addEventListener(ev, laserOff);
    laser.addEventListener("contextmenu", e => e.preventDefault());

    // --- keep the screen on while presenting ---
    async function holdWake() {
      if (!visible || document.hidden || wake || !("wakeLock" in navigator)) return;
      try {
        wake = await navigator.wakeLock.request("screen");
        wake.addEventListener("release", () => { wake = null; });
      } catch { wake = null; }
    }
    const onVis = () => { if (!document.hidden) holdWake(); };
    document.addEventListener("visibilitychange", onVis);

    // a clicker or a keyboard works too, on a desktop
    const onKey = e => {
      if (!visible || e.target.closest?.("input, textarea") || e.ctrlKey || e.altKey || e.metaKey) return;
      const map = { ArrowRight: "ArrowRight", PageDown: "ArrowRight", " ": "ArrowRight", ArrowLeft: "ArrowLeft", PageUp: "ArrowLeft", b: "b", B: "b", F5: "F5" };
      const k = map[e.key];
      if (!k) return;
      e.preventDefault();
      act(k, k === "ArrowRight" ? next : k === "ArrowLeft" ? prev : null);
    };
    document.addEventListener("keydown", onKey);

    const root = el("div", { className: "rc-pane fill" },
      el("div", { className: "rc-slides" }, timer, el("div", { className: "rc-flip" }, next, prev), row, laser, laserNote));
    tick();
    return {
      el: root, cap: "input",
      show() { visible = true; tick(); clearInterval(ticker); ticker = setInterval(tick, 1000); holdWake(); },
      hide() {
        visible = false;
        laserOff();
        clearInterval(ticker);
        if (wake) { wake.release().catch(() => {}); wake = null; }
      },
      destroy() { this.hide(); document.removeEventListener("visibilitychange", onVis); document.removeEventListener("keydown", onKey); },
    };
  }

  // ---------------------------------------------------------------------------
  // pane: media
  // ---------------------------------------------------------------------------
  function mediaPane(ctx) {
    const { dev } = ctx;
    const glow = el("div", { className: "rc-glow" });
    const players = el("div", { className: "rc-players", hidden: true });
    const art = el("div", { className: "rc-art" });
    const title = el("div", { className: "rc-title" });
    const artist = el("div", { className: "rc-artist" });
    const src = el("div", { className: "rc-src" });
    const tNow = el("span", { textContent: "0:00" }), tLen = el("span", { textContent: "0:00" });
    const fill = el("div");
    const track = el("div", { className: "rc-track", title: "Tap to jump" }, el("div", {}, fill));
    const prog = el("div", { className: "rc-prog" }, tNow, track, tLen);
    const mk = (g, label, cls = "", size = 26) => {
      const b = el("button", { type: "button", className: cls, title: label });
      b.setAttribute("aria-label", label);
      b.innerHTML = glyph(g, size);
      return b;
    };
    const prev = mk("prev", "Previous"), play = mk("play", "Play", "big", 32), next = mk("next", "Next");
    const ctrls = el("div", { className: "rc-ctrls" }, prev, play, next);
    const mute = mk("vol", "Mute", "", 22);
    const slider = range(0, 100, 1, 0, `${dev.name} volume`);
    const pctEl = el("span", { className: "pct" });
    const vol = el("div", { className: "rc-vol" }, mute, slider, pctEl);
    const liveEq = el("span", { className: "rc-live", hidden: true, title: "Playing" }, el("i"), el("i"), el("i"));
    const empty = el("div", { className: "rc-gone" }, SPLASH.cloneNode(true), el("b", { textContent: "Waiting for what's playing" }),
      el("span", { textContent: `${dev.name} hasn't said what's playing yet.` }));
    const body = el("div", { className: "rc-media" }, glow, players,
      el("div", { className: "rc-now" }, art, el("div", { className: "rc-meta" }, title, artist, src)),
      prog, ctrls, vol);
    const root = el("div", { className: "rc-pane" }, el("div", { className: "rc-sec" }, el("span", { className: "grow", textContent: `Playing on ${dev.name}` }), liveEq), empty, body);

    let data = null, picked = null, base = null, dragging = false, ticker = null, lastKey = "";
    const fmt = s => { if (s == null || !isFinite(s)) return "0:00"; s = Math.max(0, Math.floor(s)); const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = String(s % 60).padStart(2, "0"); return h ? `${h}:${String(m).padStart(2, "0")}:${x}` : `${m}:${x}`; };
    const current = () => data && data.players && data.players.length
      ? data.players.find(p => p.id === picked) || data.players.find(p => p.id === data.active) || data.players[0] : null;
    const position = p => {
      if (!p || p.position == null) return null;
      let pos = base ? base.pos : p.position;
      if (p.status === "Playing" && base) pos += (Date.now() - base.at) / 1000;
      return p.length ? Math.min(pos, p.length) : pos;
    };
    function tick() {
      const p = current();
      if (!p || !p.length) return;
      const pos = position(p);
      fill.style.width = `${Math.min(100, (pos / p.length) * 100)}%`;
      tNow.textContent = fmt(pos);
    }
    function setArt(url) {
      if (art.dataset.url === (url || "")) return;
      art.dataset.url = url || "";
      art.innerHTML = "";
      glow.style.backgroundImage = "";
      if (!url || !/^(data:image\/|https:\/\/)/.test(url)) { art.innerHTML = glyph("note", 56); return; }
      const img = el("img", { alt: "", src: url, referrerPolicy: "no-referrer" });
      img.onerror = () => { art.innerHTML = glyph("note", 56); glow.style.backgroundImage = ""; };
      img.onload = () => { glow.style.backgroundImage = `url("${url.replace(/"/g, "%22")}")`; };
      art.append(img);
    }
    function accept(d) {
      if (d && data && d.active !== data.active && (d.players || []).some(p => p.id === d.active && p.status === "Playing")) picked = null;
      data = d;
      const p = current();
      base = p && p.position != null ? { pos: p.position, at: Date.now() } : null;
      render();
    }
    function render() {
      empty.hidden = !!data;
      body.hidden = !data;
      if (!data) return;
      const list = data.players || [];
      const p = current();
      players.hidden = list.length < 2;
      players.replaceChildren(...list.map(q => {
        const b = el("button", { type: "button", className: "chip" + (p && q.id === p.id ? " on" : ""), textContent: q.name + (q.status === "Playing" ? " ♪" : "") });
        b.onclick = () => { picked = q.id; base = null; accept(data); };
        return b;
      }));
      const has = !!(p && (p.title || p.artist));
      liveEq.hidden = !(p && p.status === "Playing");
      title.classList.toggle("idle", !has);
      title.textContent = has ? p.title || "Unknown track" : "Nothing playing";
      artist.textContent = has ? [p.artist, p.album].filter(Boolean).join(" · ") : "";
      artist.hidden = !artist.textContent;
      src.textContent = !p ? `No media players open on ${dev.name}` : players.hidden ? p.name : "";
      src.hidden = !src.textContent;
      setArt(has ? p.art : null);
      ctrls.hidden = !p;
      if (p) {
        const playing = p.status === "Playing";
        const key = playing ? "pause" : "play";
        if (play.dataset.g !== key) { play.innerHTML = glyph(key, 32); play.dataset.g = key; }
        play.title = playing ? "Pause" : "Play";
        play.setAttribute("aria-label", play.title);
        prev.disabled = p.can_previous === false;
        next.disabled = p.can_next === false;
      }
      prog.hidden = !has || !p.length;
      track.classList.toggle("seekable", !!(p && p.can_seek));
      if (has && p.length) { tLen.textContent = fmt(p.length); tick(); }
      const v = data.volume;
      vol.hidden = !v;
      if (v) {
        if (!dragging) { slider.value = Math.round(Math.min(v.level, 1) * 100); slider.paint(); }
        pctEl.textContent = Math.round(Number(slider.value)) + "%";
        vol.classList.toggle("muted", !!v.muted);
        mute.classList.toggle("on", !!v.muted);
        const g = v.muted ? "muted" : "vol";
        if (mute.dataset.g !== g) { mute.innerHTML = glyph(g, 22); mute.dataset.g = g; }
        mute.title = v.muted ? "Unmute" : "Mute";
        mute.setAttribute("aria-label", mute.title);
      }
    }
    function media(action, value, optimistic) {
      const p = current();
      const msg = { t: "media", to: dev.id, action };
      if (p) msg.player = p.id;
      if (value !== undefined) msg.value = value;
      if (!live.send(msg)) { toast(friendly("Not connected to the hub", dev.name)); return; }
      haptic();
      if (optimistic && data) { optimistic(p); render(); }
    }
    play.onclick = () => media("play-pause", undefined, p => {
      if (!p) return;
      base = { pos: position(p) ?? 0, at: Date.now() };
      p.status = p.status === "Playing" ? "Paused" : "Playing";
    });
    next.onclick = () => media("next", undefined, () => { base = null; title.style.opacity = ".5"; setTimeout(() => (title.style.opacity = ""), 800); });
    prev.onclick = () => media("previous", undefined, () => { base = null; });
    track.addEventListener("click", e => {
      const p = current();
      if (!p || !p.can_seek || !p.length) return;
      const r = track.getBoundingClientRect();
      const at = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * p.length;
      media("seek", Math.round(at * 100) / 100, () => { base = { pos: at, at: Date.now() }; });
      tick();
    });
    mute.onclick = () => media("mute", undefined, () => { if (data.volume) data.volume.muted = !data.volume.muted; });
    let volTimer = null, volPending = null;
    const sendVol = () => {
      volTimer = null;
      if (volPending == null) return;
      const v = volPending;
      volPending = null;
      media("volume", v);
    };
    slider.addEventListener("input", () => {
      dragging = true;
      pctEl.textContent = slider.value + "%";
      if (data && data.volume) data.volume.level = slider.value / 100;
      volPending = slider.value / 100;
      if (!volTimer) volTimer = setTimeout(sendVol, 150);
    });
    slider.addEventListener("change", () => {
      clearTimeout(volTimer);
      volPending = slider.value / 100;
      sendVol();
      setTimeout(() => (dragging = false), 800);
    });

    const off = live.on("state", m => {
      if (m === null) { const s = (live.state[dev.id] || {}).media; if (s) accept(s); return; }
      if (m.device !== dev.id || m.kind !== "media") return;
      // unchanged snapshots (a playing track re-reports each second) don't need a redraw
      const key = JSON.stringify(m.data);
      if (key === lastKey) return;
      lastKey = key;
      accept(m.data);
    });
    const initial = (live.state[dev.id] || {}).media;
    if (initial) { lastKey = JSON.stringify(initial); accept(initial); } else render();
    return {
      el: root, cap: "media",
      show() { clearInterval(ticker); ticker = setInterval(tick, 500); },
      hide() { clearInterval(ticker); },
      destroy() { clearInterval(ticker); off(); },
    };
  }

  // ---------------------------------------------------------------------------
  // pane: messages (a phone's SMS)
  // ---------------------------------------------------------------------------
  function when(ts) {
    const d = new Date(ts * 1000), now = new Date();
    if (d.toDateString() === now.toDateString()) return d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
    if (now - d < 6 * 86400000) return d.toLocaleDateString([], { weekday: "short" });
    return d.toLocaleDateString([], { day: "numeric", month: "short" });
  }
  function skeleton(n) { return el("div", { className: "rc-rows" }, ...Array.from({ length: n }, () => el("div", { className: "rc-skel" }))); }
  function problem(title, text, retry) {
    const b = el("button", { type: "button", className: "soft" }, icon("refresh"), "Try again");
    b.onclick = retry;
    return el("div", { className: "rc-gone" }, SPLASH.cloneNode(true), el("b", { textContent: title }), el("span", { textContent: text }), b);
  }

  function messagesPane(ctx) {
    const { dev } = ctx;
    const root = el("div", { className: "rc-pane" });
    let view = null, token = 0, pollTimer = null, visible = false;

    async function showList() {
      view = { kind: "list" };
      const my = ++token;
      clearInterval(pollTimer);
      const reload = el("button", { type: "button", className: "ghost icon-btn", title: "Refresh" }, icon("refresh"));
      reload.setAttribute("aria-label", "Refresh messages");
      reload.onclick = showList;
      const head = el("div", { className: "rc-sec" }, el("span", { className: "grow", textContent: `Messages on ${dev.name}` }), reload);
      root.replaceChildren(head, skeleton(5));
      let res;
      try {
        res = await ctx.rpc("sms.threads", { limit: 50 });
      } catch (e) {
        if (my !== token) return;
        root.replaceChildren(head, problem("Couldn't load messages", friendly(e, dev.name), showList));
        return;
      }
      if (my !== token) return;
      const threads = res.threads || [];
      if (!threads.length) {
        root.replaceChildren(head, el("div", { className: "rc-gone" }, SPLASH.cloneNode(true), el("b", { textContent: "No messages" }),
          el("span", { textContent: `${dev.name} has no text messages to show.` })));
        return;
      }
      root.replaceChildren(head, el("div", { className: "rc-rows" }, ...threads.map(t => {
        const who = t.name || t.address || "Unknown";
        const side = el("span", { className: "side" }, el("span", { textContent: t.ts ? when(t.ts) : "" }));
        if (t.unread) side.append(el("span", { className: "badge", textContent: t.unread > 99 ? "99+" : t.unread }));
        const b = el("button", { type: "button", className: "rc-row" + (t.unread ? " unread" : "") },
          avatar(who), el("span", { className: "txt" }, el("b", { textContent: who }), el("small", { textContent: t.snippet || "" })), side);
        b.onclick = () => showThread(t);
        return b;
      })));
    }

    async function showThread(t) {
      view = { kind: "thread", t };
      const my = ++token;
      const who = t.name || t.address || "Unknown";
      const back = el("button", { type: "button", className: "ghost icon-btn", title: "All messages" }, icon("chev-l"));
      back.setAttribute("aria-label", "Back to all messages");
      back.onclick = showList;
      const log = el("div", { className: "rc-log" });
      log.setAttribute("aria-live", "polite");
      const box = el("textarea", { rows: 1, placeholder: `Text ${who}` });
      box.setAttribute("aria-label", `Reply to ${who}`);
      const sendBtn = el("button", { type: "button", className: "primary", title: "Send" }, icon("send"));
      sendBtn.setAttribute("aria-label", "Send message");
      sendBtn.addEventListener("mousedown", e => e.preventDefault());
      const compose = el("div", { className: "rc-compose" }, box, sendBtn);
      compose.hidden = !t.address;
      root.replaceChildren(el("div", { className: "rc-thread" },
        el("div", { className: "rc-thead" }, back, avatar(who), el("div", { className: "who" }, el("b", { textContent: who }),
          el("small", { textContent: t.name && t.address ? t.address : `on ${dev.name}` }))),
        log, compose));
      log.append(el("div", { className: "empty-state" }, el("span", { className: "rc-spin" })));
      let lastSig = "", shown = [], first = true;
      async function load(quiet) {
        let res;
        try {
          res = await ctx.rpc("sms.thread", { id: t.id, limit: 100 });
        } catch (e) {
          if (my !== token) return;
          if (!quiet) log.replaceChildren(problem("Couldn't load this conversation", friendly(e, dev.name), () => load(false)));
          return;
        }
        if (my !== token) return;
        const msgs = res.messages || [];
        const sig = msgs.map(m => m.id).join(",");
        if (sig === lastSig) return;
        lastSig = sig;
        draw(msgs);
      }
      function draw(msgs, extra = []) {
        shown = msgs;
        const nearBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 80;
        log.replaceChildren();
        if (!msgs.length && !extra.length) log.append(el("div", { className: "empty-state chat-empty" }, el("span", { textContent: "No messages yet" })));
        let lastDay = "", prev = null, prevEl = null;
        for (const m of [...msgs, ...extra]) {
          const d = new Date(m.ts * 1000);
          if (d.toDateString() !== lastDay) {
            lastDay = d.toDateString();
            log.append(el("div", { className: "day", textContent: lastDay === new Date().toDateString() ? "Today" : d.toLocaleDateString() }));
            prev = null;
          }
          const cont = prev && prev.out === m.out && m.ts - prev.ts < 300;
          const b = el("div", { className: "msg " + (m.out ? "mine" : "theirs") + (cont ? " cont" : "") + (m.cls ? " " + m.cls : ""), title: "Tap to copy" },
            linkify(m.body || ""), el("span", { className: "time", textContent: m.cls === "sending" ? "sending…" : m.cls === "failed" ? "not sent" : d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" }) }));
          if (cont && prevEl) prevEl.classList.add("cont-next");
          b.onclick = e => { if (e.target.tagName !== "A") navigator.clipboard?.writeText(m.body || "").then(() => flash("Copied"), () => {}); };
          log.append(b);
          prev = m; prevEl = b;
        }
        if (nearBottom || extra.length || first) log.scrollTop = log.scrollHeight;
        first = false;
      }
      async function send() {
        const body = box.value.trim();
        if (!body || !t.address) return;
        box.value = "";
        box.style.height = "";
        const temp = { id: "tmp", body, ts: Date.now() / 1000, out: true, cls: "sending" };
        draw(shown, [temp]);
        try {
          await ctx.rpc("sms.send", { address: t.address, body });
          if (my !== token) return;
          lastSig = "";
          await load(true);
        } catch (e) {
          if (my !== token) return;
          temp.cls = "failed";
          draw(shown, [temp]);
          box.value = body;
          toast(friendly(e, dev.name));
        }
      }
      sendBtn.onclick = send;
      box.addEventListener("input", () => { box.style.height = ""; box.style.height = box.scrollHeight + "px"; });
      box.addEventListener("keydown", e => {
        if (e.key === "Enter" && !e.shiftKey && !matchMedia("(pointer: coarse)").matches) { e.preventDefault(); send(); }
      });
      await load(false);
      clearInterval(pollTimer);
      pollTimer = setInterval(() => { if (visible && !document.hidden && my === token) load(true); }, 8000);
    }

    return {
      el: root, cap: "sms",
      show() { visible = true; if (!view) showList(); },
      hide() { visible = false; },
      reset() { view = null; },
      destroy() { clearInterval(pollTimer); token++; },
    };
  }

  // ---------------------------------------------------------------------------
  // pane: files (browse a phone's folders, fetch a file here)
  // ---------------------------------------------------------------------------
  function filesPane(ctx) {
    const { dev } = ctx;
    const root = el("div", { className: "rc-pane" });
    let roots = null, token = 0, started = false;

    const folderTile = () => {
      const t = el("span", { className: "ftile" });
      t.dataset.kind = "folder";
      t.style.setProperty("--k", 200);
      t.append(icon("folder"));
      return t;
    };
    const tileFor = name => (typeof fileTile === "function" ? fileTile(name) : el("span", { className: "ftile" }, icon("file")));

    async function start() {
      started = true;
      const my = ++token;
      root.replaceChildren(el("div", { className: "rc-sec", textContent: `Files on ${dev.name}` }), skeleton(3));
      try {
        roots = (await ctx.rpc("files.roots")).roots || [];
      } catch (e) {
        if (my !== token) return;
        root.replaceChildren(problem("Couldn't open files", friendly(e, dev.name), start));
        return;
      }
      if (my !== token) return;
      if (roots.length === 1) return open(roots[0].path);
      showRoots();
    }

    function showRoots() {
      token++;
      if (!roots.length) {
        root.replaceChildren(el("div", { className: "rc-gone" }, SPLASH.cloneNode(true), el("b", { textContent: "Nothing shared" }),
          el("span", { textContent: `${dev.name} doesn't share any folders.` })));
        return;
      }
      root.replaceChildren(el("div", { className: "rc-sec", textContent: `Files on ${dev.name}` }),
        el("div", { className: "rc-rows" }, ...roots.map(r => {
          const b = el("button", { type: "button", className: "rc-row" }, folderTile(),
            el("span", { className: "txt" }, el("b", { textContent: r.name || r.path }), el("small", { textContent: r.path })), icon("chev-r"));
          b.onclick = () => open(r.path);
          return b;
        })));
    }

    function crumbs(path) {
      const rootOf = (roots || []).filter(r => path === r.path || path.startsWith(r.path.replace(/\/$/, "") + "/"))
        .sort((a, b) => b.path.length - a.path.length)[0];
      const bar = el("div", { className: "rc-crumbs" });
      const parts = [];
      if (rootOf) {
        parts.push([rootOf.name || rootOf.path, rootOf.path]);
        const rest = path.slice(rootOf.path.replace(/\/$/, "").length).split("/").filter(Boolean);
        let p = rootOf.path.replace(/\/$/, "");
        for (const seg of rest) { p += "/" + seg; parts.push([seg, p]); }
      } else {
        let p = "";
        for (const seg of path.split("/").filter(Boolean)) { p += "/" + seg; parts.push([seg, p]); }
      }
      const up = el("button", { type: "button", className: "ghost icon-btn", title: "Up" }, icon("chev-l"));
      up.setAttribute("aria-label", "Up one folder");
      up.onclick = () => (parts.length > 1 ? open(parts[parts.length - 2][1]) : roots && roots.length > 1 ? showRoots() : null);
      up.disabled = parts.length <= 1 && !(roots && roots.length > 1);
      bar.append(up);
      if (roots && roots.length > 1) {
        const all = el("button", { type: "button", textContent: "All" });
        all.onclick = showRoots;
        bar.append(all, icon("chev-r", "sep"));
      }
      parts.forEach(([name, p], i) => {
        const b = el("button", { type: "button", className: i === parts.length - 1 ? "here" : "", textContent: name });
        b.onclick = () => open(p);
        if (i) bar.append(icon("chev-r", "sep"));
        bar.append(b);
      });
      requestAnimationFrame(() => (bar.scrollLeft = bar.scrollWidth));
      return bar;
    }

    async function open(path) {
      const my = ++token;
      const bar = crumbs(path);
      root.replaceChildren(bar, skeleton(4));
      let res;
      try {
        res = await ctx.rpc("files.list", { path });
      } catch (e) {
        if (my !== token) return;
        root.replaceChildren(bar, problem("Couldn't open this folder", friendly(e, dev.name), () => open(path)));
        return;
      }
      if (my !== token) return;
      const entries = (res.entries || []).slice().sort((a, b) => (b.dir - a.dir) || a.name.localeCompare(b.name, undefined, { numeric: true }));
      if (!entries.length) {
        root.replaceChildren(bar, el("div", { className: "rc-gone" }, SPLASH.cloneNode(true), el("b", { textContent: "Empty folder" }),
          el("span", { textContent: "Nothing in here." })));
        return;
      }
      const base = (res.path || path).replace(/\/$/, "");
      root.replaceChildren(bar, el("div", { className: "rc-rows" }, ...entries.map(f => {
        const full = base + "/" + f.name;
        if (f.dir) {
          const b = el("button", { type: "button", className: "rc-row" }, folderTile(),
            el("span", { className: "txt" }, el("b", { textContent: f.name }), el("small", { textContent: "Folder" })), icon("chev-r"));
          b.onclick = () => open(full);
          return b;
        }
        const get = el("button", { type: "button", className: "soft get", title: "Send to this device" }, icon("download"), "Get");
        get.setAttribute("aria-label", `Send ${f.name} to this device`);
        const row = el("div", { className: "rc-row" }, tileFor(f.name),
          el("span", { className: "txt" }, el("b", { textContent: f.name }),
            el("small", { textContent: [f.size != null ? fmtSize(f.size) : "", f.mtime ? ago(f.mtime) : ""].filter(Boolean).join(" · ") })), get);
        get.onclick = () => fetchFile(full, f.name, get);
        return row;
      })));
    }

    async function fetchFile(path, name, btn) {
      btn.classList.add("busy");
      btn.replaceChildren(el("span", { className: "rc-spin" }), "Sending");
      try {
        const r = await ctx.rpc("files.get", { path });
        btn.classList.remove("busy");
        btn.classList.add("done");
        btn.replaceChildren(icon("check"), "Sent");
        flash(`${r.name || name} is on its way to this device's Files`);
        refreshSoon();
      } catch (e) {
        btn.classList.remove("busy");
        btn.replaceChildren(icon("download"), "Get");
        toast(friendly(e, dev.name));
      }
    }

    return {
      el: root, cap: "files",
      show() { if (!started) start(); },
      reset() { started = false; },
      destroy() { token++; },
    };
  }

  // a file sent here shows up on the next poll; ask a little sooner
  function refreshSoon() {
    if (typeof refresh !== "function") return;
    for (const ms of [800, 2500, 5000]) setTimeout(refresh, ms);
  }

  // ---------------------------------------------------------------------------
  // pane: more (lock, screenshot)
  // ---------------------------------------------------------------------------
  function morePane(ctx) {
    const { dev } = ctx;
    const root = el("div", { className: "rc-pane" });
    const actions = el("div", { className: "rc-actions" });
    const shot = el("div");
    const info = el("div");
    root.append(el("div", { className: "rc-sec", textContent: `More for ${dev.name}` }), actions, shot, info);
    let watch = null;

    function tile(cls, ico, title, sub, fn) {
      const b = el("button", { type: "button", className: "rc-action " + cls },
        el("span", { className: "ico-tile" }, icon(ico)), el("span", { className: "lbl" }, el("b", { textContent: title }), el("small", { textContent: sub })));
      b.onclick = fn;
      return b;
    }
    function draw() {
      const caps = live.caps(dev.id);
      const list = [];
      if (caps.includes("screenshot")) list.push(tile("shot", "screenshot", "Take a screenshot", "It lands in this device's Files", screenshot));
      if (caps.includes("lock")) list.push(tile("lock", "lock", "Lock the screen", `Locks ${dev.name} straight away`, lock));
      actions.replaceChildren(...list);
      actions.hidden = !list.length;
      info.replaceChildren(...(caps.includes("clipboard")
        ? [el("div", { className: "rc-info" }, icon("clipboard"), el("span", { textContent: `Clipboard sync is on: copy on ${dev.name}, paste on your other devices with droplet's app, and back.` }))]
        : []));
    }
    function lock() {
      if (!confirm(`Lock ${dev.name}'s screen now?`)) return;
      if (live.send({ t: "cmd", to: dev.id, cmd: "lock" })) { haptic(10); flash(`Locking ${dev.name}…`); }
      else toast(friendly("Not connected to the hub", dev.name));
    }
    function screenshot() {
      if (!me) return;
      const before = new Set((latest.inbox || []).map(f => f.name));
      if (!live.send({ t: "cmd", to: dev.id, cmd: "screenshot" })) { toast(friendly("Not connected to the hub", dev.name)); return; }
      haptic(10);
      const open = el("button", { type: "button", className: "soft" }, icon("inbox"), "Open Files");
      open.onclick = () => { sheet && sheet.close(); showTab("files"); setTimeout(() => document.getElementById("inbox-h")?.scrollIntoView({ block: "start" }), 50); };
      const waitRow = el("div", { className: "wait" }, el("span", { className: "rc-spin" }),
        el("span", { textContent: `Asking ${dev.name} for a screenshot. It arrives in this device's inbox (For this device, under Files) with a notification.` }));
      const box = el("div", { className: "rc-shot" }, waitRow, el("div", { className: "row" }, open));
      shot.replaceChildren(box);
      clearInterval(watch);
      const t0 = Date.now();
      // watch the inbox for it, so it can be shown right here
      watch = setInterval(async () => {
        if (Date.now() - t0 > 30000) {
          clearInterval(watch);
          waitRow.replaceChildren(icon("inbox"), el("span", { textContent: `No screenshot yet. If it doesn't show up in Files soon, check droplet's app on ${dev.name}.` }));
          return;
        }
        if (typeof refresh === "function") await refresh();
        const fresh = (latest.inbox || []).find(f => !before.has(f.name) && /^screenshot-/i.test(f.name));
        if (!fresh) return;
        clearInterval(watch);
        const url = "/raw/inbox/" + encodeURIComponent(fresh.name);
        const img = el("img", { src: url, alt: `Screenshot of ${dev.name}` });
        const dl = el("a", { href: "/d/inbox/" + encodeURIComponent(fresh.name), className: "" });
        const dlBtn = el("button", { type: "button", className: "primary" }, icon("download"), "Download");
        dlBtn.onclick = () => dl.click();
        box.replaceChildren(img, el("div", { className: "name", textContent: fresh.name, title: fresh.name }),
          el("div", { className: "row" }, open, dlBtn));
      }, 1500);
    }
    draw();
    return {
      el: root, cap: ["lock", "screenshot"],
      show: draw,
      presence: draw,
      destroy() { clearInterval(watch); },
    };
  }

  // ---------------------------------------------------------------------------
  // the control sheet
  // ---------------------------------------------------------------------------
  const PANES = [
    { id: "touchpad", label: "Touchpad", icon: "touchpad", caps: ["input"], make: touchpadPane },
    { id: "keyboard", label: "Keyboard", icon: "keyboard", caps: ["input"], make: keyboardPane },
    { id: "slides", label: "Slides", icon: "slides", caps: ["input"], make: slidesPane },
    { id: "media", label: "Media", icon: "music", caps: ["media"], make: mediaPane },
    { id: "messages", label: "Messages", icon: "chat", caps: ["sms"], make: messagesPane },
    { id: "files", label: "Files", icon: "folder", caps: ["files"], make: filesPane },
    { id: "more", label: "More", icon: "dots", caps: ["lock", "screenshot"], make: morePane },
  ];

  let control = null;   // the open control session: {dev, …}

  function openControl(id, want) {
    const dev0 = (latest.devices || []).find(d => d.id === id);
    if (!dev0) return;
    const dev = { id: dev0.id, name: dev0.name };
    const s = openShell(false, `Control ${dev.name}`);
    const seen = new Set(live.caps(id));   // caps this session has seen; tabs never vanish under a finger
    const state = el("div", { className: "rc-state" });
    const head = el("div", { className: "rc-head" }, avatar(dev.name, { size: "lg", online: isLive(id) }),
      el("div", { className: "who" }, el("b", { textContent: dev.name }), state), closeButton(s));
    const tabs = el("div", { className: "rc-tabs", role: "tablist" });
    const panes = el("div", { className: "rc-panes" });
    s.root.append(head, el("div", { className: "rc-body" }, tabs, panes));

    let lastErrShown = 0;
    const input = makeInput(() => dev.id, () => {
      if (Date.now() - lastErrShown > 3000) { lastErrShown = Date.now(); toast(friendly("Not connected to the hub", dev.name)); }
    });
    const ctx = {
      dev, input,
      rpc: (method, params) => live.rpc(dev.id, method, params),
    };
    const made = {};     // pane id -> {pane, wrap, goneEl}
    let current = null;

    function available(p) { return p.caps.some(c => seen.has(c)); }
    function usable(p) { const caps = live.caps(dev.id); return p.caps.some(c => caps.includes(c)); }

    function drawHead() {
      const caps = live.caps(dev.id);
      const down = live.status !== "open";
      state.className = "rc-state " + (down ? "down" : caps.length ? "live" : "down");
      state.replaceChildren(el("span", { className: "dot" }),
        down ? (live.status === "connecting" ? "Connecting to the hub…" : "Live link down · reconnecting…")
          : caps.length ? `Connected${via(dev.id)}` : "droplet app not connected");
      const a = head.querySelector(".avatar");
      a.replaceWith(avatar(dev.name, { size: "lg", online: isLive(dev.id) }));
    }

    function drawTabs() {
      tabs.replaceChildren(...PANES.filter(available).map(p => {
        const b = el("button", { type: "button", className: "rc-tab" + (current === p.id ? " on" : "") + (usable(p) ? "" : " off"), role: "tab" },
          icon(p.icon), p.label);
        b.dataset.pane = p.id;
        b.setAttribute("aria-selected", String(current === p.id));
        b.onclick = () => select(p.id);
        return b;
      }));
    }

    function mount(p) {
      if (made[p.id]) return made[p.id];
      panes.querySelector(":scope > .rc-gone")?.remove();
      const pane = p.make(ctx);
      const goneBox = el("div", { className: "rc-pane", hidden: true });
      const m = { pane, goneBox, ok: true };
      panes.append(pane.el, goneBox);
      pane.el.hidden = true;
      made[p.id] = m;
      return m;
    }

    // a pane whose capability left shows why, and picks up where it was when it's back
    function paint(id) {
      const p = PANES.find(x => x.id === id);
      const m = made[id];
      if (!p || !m) return;
      const ok = usable(p), visible = current === id;
      const back = ok && !m.ok;
      if (m.ok && !ok && m.pane.hide) m.pane.hide();
      if (back && m.pane.reset) m.pane.reset();   // a phone's lists are reloaded, not trusted
      m.ok = ok;
      if (!ok) m.goneBox.replaceChildren(gone(dev, p.caps[0]));
      m.pane.el.hidden = !visible || !ok;
      m.goneBox.hidden = !visible || ok;
      if (back && visible && m.pane.show) m.pane.show();
    }

    function select(id) {
      if (current === id) return;
      const old = current && made[current];
      if (old && old.ok && old.pane.hide) old.pane.hide();
      current = id;
      LS.set("droplet-rc-pane-" + dev.id, id);
      const p = PANES.find(x => x.id === id);
      const fresh = !made[id];
      const m = mount(p);
      if (fresh) m.ok = usable(p);
      for (const k of Object.keys(made)) paint(k);
      if (m.ok && m.pane.show) m.pane.show();
      drawTabs();
      tabs.querySelector(".rc-tab.on")?.scrollIntoView({ block: "nearest", inline: "nearest" });
    }

    function onPresence() {
      for (const c of live.caps(dev.id)) seen.add(c);
      drawHead();
      drawTabs();
      if (!current) {
        const first = PANES.find(available);
        if (first) select(first.id);
      }
      for (const k of Object.keys(made)) { paint(k); made[k].pane.presence && made[k].pane.presence(); }
      if (!PANES.some(available)) panes.replaceChildren(gone(dev, "input"));
    }

    // errors about this device, from the hub (a helper that just left)
    const offErr = live.on("error", m => { if (m.to === dev.id && m.re) lastErrShown = Date.now(); });
    const offPres = live.on("presence", onPresence);
    const offStatus = live.on("status", onPresence);
    s.cleanup.push(() => {
      offErr(); offPres(); offStatus();
      input.flush();
      for (const m of Object.values(made)) {
        try { m.pane.hide && m.pane.hide(); m.pane.destroy && m.pane.destroy(); } catch (e) { console.error(e); }
      }
      input.flush();
      control = null;
    });

    control = { dev, select };
    drawHead();
    const saved = want || LS.get("droplet-rc-pane-" + dev.id);
    const start = PANES.find(p => p.id === saved && available(p)) || PANES.find(available);
    drawTabs();
    if (start) select(start.id);
    else panes.replaceChildren(gone(dev, "input"));
    setTimeout(() => head.querySelector(".rc-close")?.focus({ preventScroll: true }), 60);
  }

  // ---------------------------------------------------------------------------
  // linking a native helper to this device
  // ---------------------------------------------------------------------------
  function openLink() {
    if (!me) return;
    const s = openShell(true, "Set up remote control");
    const box = el("div", { className: "rc-link" });
    const x = closeButton(s);
    x.style.cssText = "position:absolute; top:10px; right:10px;";
    s.root.append(x, box);

    const hub = (typeof hubUrl === "string" && hubUrl) || location.origin;
    const ua = navigator.userAgent;
    let platform = LS.get("droplet-rc-linkos") || (/Android/i.test(ua) ? "android" : /Windows/i.test(ua) ? "windows" : "linux");
    const appsBefore = ((live.presence[me.id] || {}).apps || []).length;
    let code = null, expires = 0, timer = null, busy = false;

    const codeBtn = el("button", { type: "button", className: "rc-code", title: "Copy the code" });
    const ring = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    ring.setAttribute("viewBox", "0 0 20 20");
    ring.innerHTML = '<circle class="bg" cx="10" cy="10" r="8"/><circle class="fg" cx="10" cy="10" r="8" stroke-dasharray="50.27" stroke-dashoffset="0"/>';
    const left = el("span");
    const again = el("button", { type: "button", className: "soft", hidden: true }, icon("refresh"), "New code");
    const codebox = el("div", { className: "rc-codebox" }, codeBtn, el("div", { className: "rc-expiry" }, ring, left), again);
    const seg = el("div", { className: "rc-seg", role: "tablist" });
    const steps = el("ol", { className: "rc-steps" });

    function spaced() { return code ? code.slice(0, 3) + " " + code.slice(3) : "••• •••"; }
    function drawCode() {
      const digits = (code || "------").split("");
      codeBtn.replaceChildren(...digits.slice(0, 3).map(d => el("span", { textContent: d === "-" ? "·" : d })), el("i"),
        ...digits.slice(3).map(d => el("span", { textContent: d === "-" ? "·" : d })));
      codeBtn.setAttribute("aria-label", code ? `Link code ${code.split("").join(" ")}` : "Getting a code");
    }
    codeBtn.onclick = () => code && navigator.clipboard?.writeText(code).then(() => flash("Code copied"), () => {});

    function tickCode() {
      const secs = Math.max(0, Math.round((expires - Date.now()) / 1000));
      const fg = ring.querySelector(".fg");
      fg.setAttribute("stroke-dashoffset", String(50.27 * (1 - secs / 600)));
      const expired = !!code && secs <= 0;
      codebox.classList.toggle("expired", expired);
      again.hidden = !expired;
      left.textContent = !code ? "Getting a code…" : expired ? "This code has expired"
        : `Expires in ${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, "0")} · works once`;
      if (expired) clearInterval(timer);
    }

    async function newCode() {
      if (busy) return;
      busy = true;
      code = null;
      drawCode(); tickCode(); drawSteps();
      const res = await fetch("/api/device/link-code", { method: "POST" }).catch(() => null);
      busy = false;
      if (!res || !res.ok) {
        left.textContent = "Couldn't get a code. Is the hub reachable?";
        again.hidden = false;
        return;
      }
      const d = await res.json();
      code = String(d.code);
      expires = Date.now() + (d.expires_in || 600) * 1000;
      drawCode(); drawSteps(); tickCode();
      clearInterval(timer);
      timer = setInterval(tickCode, 1000);
    }
    again.onclick = newCode;

    function copyable(text) {
      const b = el("button", { type: "button", className: "ghost icon-btn", title: "Copy" }, icon("copy"));
      b.setAttribute("aria-label", "Copy the command");
      b.onclick = () => navigator.clipboard?.writeText(text).then(() => flash("Command copied"), () => flash("Couldn't copy, select it instead"));
      return el("div", { className: "rc-cmd" }, el("code", { textContent: text }), b);
    }
    const li = (...kids) => el("li", {}, el("div", {}, ...kids));
    function drawSteps() {
      seg.replaceChildren(...[["linux", "Linux", "linux"], ["windows", "Windows", "windows"], ["android", "Android", "phone"]].map(([id, label, ico]) => {
        const b = el("button", { type: "button", className: platform === id ? "on" : "", role: "tab" }, icon(ico), label);
        b.setAttribute("aria-selected", String(platform === id));
        b.onclick = () => { platform = id; LS.set("droplet-rc-linkos", id); drawSteps(); };
        return b;
      }));
      const c = spaced();
      const B = t => el("b", { textContent: t });
      if (platform === "linux") {
        steps.replaceChildren(
          li("In a terminal on this machine, run:", copyable(`curl -fsSL ${hub}/agent/install.sh | sh -s -- --code ${code || "123456"}`)),
          li("It installs droplet's agent for your user, links it to ", B(me.name), " with the code, and starts it."));
      } else if (platform === "windows") {
        const files = el("button", { type: "button", className: "soft", style: "min-height:34px; margin-top:6px; font-size:13px" }, icon("folder"), "Open Files");
        files.onclick = () => { s.close(); showTab("files"); };
        steps.replaceChildren(
          li("On this PC, download ", B("droplet-windows.exe"), " from ", B("Shared"), " in Files.", el("br"), files),
          li("Run it, open its settings and choose ", B("Link with code"), "."),
          li("Enter ", B(c), "."));
      } else {
        steps.replaceChildren(
          li("Open the ", B("droplet app"), " on this phone."),
          li("Tap ", B("⚙"), " at the top right, then ", B("Link with code"), "."),
          li("Enter ", B(c), "."));
      }
    }

    function success(app) {
      clearInterval(timer);
      const done = el("button", { type: "button", className: "primary" }, "Done");
      done.onclick = () => s.close();
      box.replaceChildren(el("div", { className: "rc-done" },
        el("span", { className: "ok" }, icon("check")),
        el("b", { textContent: "Linked" }),
        el("span", { textContent: `droplet's ${(app && OS[app.platform]) || ""} app is connected to ${me.name}. Your other devices can control it now.`.replace("  ", " ") })),
        el("div", { className: "rc-foot" }, done));
      haptic(20);
    }
    const offPres = live.on("presence", () => {
      const apps = (live.presence[me.id] || {}).apps || [];
      if (apps.length > appsBefore) success(apps[apps.length - 1]);
    });
    s.cleanup.push(() => { clearInterval(timer); offPres(); });

    box.append(
      el("div", {}, el("span", { className: "eyebrow", textContent: "Remote control" }),
        el("h2", { textContent: `Set up remote control of ${me.name}` })),
      el("p", { textContent: `Link droplet's app on this machine to ${me.name}, so it shows up once and your other devices can control it.` }),
      codebox, seg, steps,
      el("p", { className: "small", textContent: "The code works once, for 10 minutes. The link lasts until you remove the device." }));
    drawCode();
    drawSteps();
    newCode();
    setTimeout(() => x.focus({ preventScroll: true }), 60);
  }

  // ---------------------------------------------------------------------------
  // entry points
  // ---------------------------------------------------------------------------
  function ctlButton(d, cls = "soft rc-ctl") {
    const b = el("button", { type: "button", className: cls, title: `Control ${d.name}` }, icon("remote"), "Control");
    b.setAttribute("aria-label", `Control ${d.name}`);
    b.onclick = e => { e.stopPropagation(); openControl(d.id); };
    return b;
  }

  // Devices tab: a Control button and a "remote ready" note on each device that has a helper
  hooks.deviceRow.push((d, meta) => {
    if (d.self) {
      if (live.caps(d.id).length) meta.append(" · ", el("span", { className: "rc-ready", textContent: "remote control on" }));
      return [];
    }
    if (!controllable(d.id)) return [];
    meta.append(" · ", el("span", { className: "rc-ready", textContent: "remote ready", title: capsSummary(live.caps(d.id)) }));
    return [ctlButton(d)];
  });

  // chat: a Control button in the header when that device can be controlled
  hooks.chat.push(dev => {
    const head = document.querySelector("#chat .chat-head");
    if (!head) return;
    head.querySelector(".rc-ctl")?.remove();
    if (dev && controllable(dev.id)) head.append(ctlButton(dev));
  });

  // This device: set up remote control (link a native helper)
  hooks.meCard.push(box => {
    if (!me) return;
    const mine = live.presence[me.id] || {};
    const ready = live.status === "open" && (mine.caps || []).length > 0;
    const b = el("button", { type: "button", className: "rc-me" + (ready ? " ready" : "") },
      el("span", { className: "ico-tile" }, icon(ready ? "check" : "remote")),
      el("span", { className: "lbl" },
        el("b", { textContent: ready ? "Remote control is on" : "Set up remote control" }),
        el("small", { textContent: ready ? `droplet's ${OS[(mine.apps || [])[0]?.platform] || ""} app is connected · link another`.replace("  ", " ")
          : `So your other devices can control ${me.name}` })),
      icon("chev-r"));
    b.onclick = openLink;
    box.append(b);
  });

  // Hub tab: a card listing every device that can be controlled
  const card = el("section", { className: "card none", id: "remote-card" });
  card.setAttribute("aria-label", "Remote control");
  (document.getElementById("features") || document.body).append(card);
  let drawn = "";
  function renderCard() {
    const devs = others().filter(d => controllable(d.id));
    const sig = JSON.stringify([live.status, devs.map(d => [d.id, d.name, live.caps(d.id)]), !!me]);
    if (sig === drawn) return;
    drawn = sig;
    card.classList.toggle("none", !devs.length);
    const focused = document.activeElement?.closest?.("#remote-card .rc-dev")?.dataset.id;
    const kids = [cardHead("remote", "Remote control", devs.length ? "Touchpad, keyboard, slides, media and more" : "Control your other devices from here")];
    if (me && live.status === "down") kids.push(el("div", { className: "rc-down" }, el("span", { className: "dot" }), "Live link down · reconnecting…"));
    if (devs.length) {
      kids.push(el("div", { className: "rc-grid" }, ...devs.map(d => {
        const b = el("button", { type: "button", className: "rc-dev" }, avatar(d.name, { online: true }),
          el("span", { className: "lbl" }, el("b", { textContent: d.name }), el("small", { textContent: capsSummary(live.caps(d.id)) })),
          icon("chev-r"));
        b.dataset.id = d.id;
        b.setAttribute("aria-label", `Control ${d.name}`);
        b.onclick = () => openControl(d.id);
        return b;
      })));
    } else {
      kids.push(el("p", { className: "rc-none", textContent: me
        ? "Nothing to control yet. On the computer or phone you want to control, open droplet and choose Set up remote control under This device."
        : "Name this device first (on the Send tab), then set up droplet's app on the devices you want to control." }));
    }
    card.replaceChildren(...kids);
    if (focused) card.querySelector(`[data-id="${focused}"]`)?.focus();
  }

  // presence changes: the page's own lists pick up caps right away
  let lastMine = "";
  function onPresence() {
    for (const d of latest.devices || []) {
      const p = live.presence[d.id];
      d.caps = live.status === "open" && p ? p.caps : [];
      d.apps = live.status === "open" && p ? p.apps : [];
    }
    renderCard();
    if (typeof renderDevices === "function") renderDevices();
    if (typeof renderChatMode === "function" && document.getElementById("chat") && !document.getElementById("chat").hidden) {
      runHooks("chat", others().find(d => d.id === target) || null);
    }
    const mine = me ? JSON.stringify([live.status === "open", live.presence[me.id] || null]) : "";
    if (mine !== lastMine) {
      lastMine = mine;
      // the "This device" card shows whether this machine's helper is connected
      if (me && typeof renderMe === "function" && !document.querySelector("#me :focus")) renderMe();
    }
  }
  live.on("presence", onPresence);
  live.on("status", onPresence);
  setInterval(renderCard, 1500);   // picks up renames and removals from the page's own refresh
  renderCard();
  live.connect();
})();
