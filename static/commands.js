// Hub commands: one-tap buttons for the preset commands in the hub's
// commands.json. The page only ever sends a command's id; what runs is
// decided on the hub.
(() => {
  document.head.append(el("style", { textContent: `
    #hub-commands .cmd-grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(6.75rem, 1fr)); gap:var(--s2); }
    #hub-commands .cmd { position:relative; flex-direction:column; gap:6px; min-height:84px; padding:12px 8px;
                         background:var(--card-2); border-color:transparent; border-radius:16px; text-align:center; line-height:1.2; }
    #hub-commands .cmd:hover:not(:disabled) { border-color:color-mix(in srgb, var(--accent) 50%, transparent); }
    #hub-commands .cmd .emoji { font-size:26px; line-height:1; }
    #hub-commands .cmd .emoji .ico { width:24px; height:24px; color:var(--accent); }
    #hub-commands .cmd .nm { font-size:13px; font-weight:600; overflow-wrap:anywhere; }
    #hub-commands .cmd.busy { opacity:1; border-color:var(--accent); background:var(--accent-soft); }
    #hub-commands .cmd.busy .emoji { visibility:hidden; }
    #hub-commands .cmd.busy::after { content:""; position:absolute; top:14px; left:50%; width:24px; height:24px;
                                     margin-left:-12px; border-radius:50%; border:2.5px solid var(--line-2);
                                     border-top-color:var(--accent); animation:cmd-spin .8s linear infinite; }
    @keyframes cmd-spin { to { transform:rotate(360deg); } }
    #hub-commands > :empty { display:none; }
    /* the result reads like a small console */
    #hub-commands details { background:var(--bg); border-color:var(--line); border-radius:14px; overflow:hidden; }
    #hub-commands summary { display:flex; align-items:center; gap:8px; padding:10px 14px; color:var(--text); font-weight:600;
                            list-style:none; }
    #hub-commands summary::-webkit-details-marker { display:none; }
    #hub-commands summary .grow { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    #hub-commands .exit { flex:0 0 auto; border-radius:999px; font-size:11px; font-weight:800; padding:3px 8px; line-height:1;
                          color:var(--accent); background:var(--accent-soft); }
    #hub-commands .exit.bad { color:#fff; background:var(--coral); }
    #hub-commands summary .dim { flex:0 0 auto; font-size:12px; font-weight:500; font-variant-numeric:tabular-nums; }
    #hub-commands pre { margin:0; padding:2px 14px 14px; max-height:50vh; overflow:auto; white-space:pre;
                        font:12px/1.5 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace; color:var(--text); }
    @media (prefers-reduced-motion: reduce) { #hub-commands .cmd.busy::after { animation-duration:2.4s !important; } }
  ` }));

  const card = el("section", { className: "card", id: "hub-commands", hidden: true });
  const grid = el("div", { className: "cmd-grid" });
  const out = el("div");
  card.append(cardHead("terminal", "Hub commands", `Run on ${HUB_NAME}`), grid, out);
  document.getElementById("features").append(card);

  const busy = new Set();   // ids running from this page
  let shown = "";           // last rendered list, so polling doesn't rebuild the buttons

  async function load() {
    let d;
    try {
      const r = await fetch("/api/hub/commands");
      if (!r.ok) return;
      d = await r.json();
    } catch { return; }   // hub unreachable: keep what's on screen
    const key = JSON.stringify([d.configured, d.commands]);
    if (key === shown) return;
    shown = key;
    card.hidden = !d.configured || !d.commands.length;
    grid.replaceChildren(...d.commands.map(button));
  }

  function button(c) {
    const b = el("button", { className: "cmd", title: c.name },
                 el("span", { className: "emoji" }, c.icon || icon("terminal")),
                 el("span", { className: "nm", textContent: c.name }));
    b.dataset.id = c.id;
    if (busy.has(c.id)) { b.disabled = true; b.classList.add("busy"); }
    b.onclick = () => run(c);
    return b;
  }

  async function run(c) {
    if (busy.has(c.id)) return;
    if (c.confirm && !confirm(`Run "${c.name}" on the hub?`)) return;
    busy.add(c.id);
    setBusy(c.id, true);
    try {
      // the header is what lets the hub tell this page from a cross-site form
      const r = await fetch(`/api/hub/commands/${encodeURIComponent(c.id)}/run`,
                            { method: "POST", headers: { "X-Droplet-Run": "1" } });
      if (r.status === 409) return flash(`${c.name} is already running`);
      if (r.status === 404) { flash(`${c.name} is no longer on the hub`); shown = ""; return load(); }
      if (!r.ok) return flash(`Couldn't run ${c.name} (${r.status})`);
      show(c, await r.json());
    } catch {
      flash(`Couldn't reach the hub to run ${c.name}`);
    } finally {
      busy.delete(c.id);
      setBusy(c.id, false);
    }
  }

  function setBusy(id, on) {
    // look the button up again: a config reload may have rebuilt the grid
    const b = [...grid.children].find(b => b.dataset.id === id);
    if (b) { b.disabled = on; b.classList.toggle("busy", on); }
  }

  function show(c, res) {
    const ok = res.code === 0 && !res.timed_out;
    const badge = el("span", { className: "exit" + (ok ? "" : " bad"),
                               textContent: res.timed_out ? "timed out" : `exit ${res.code}` });
    const text = (res.truncated ? "…(earlier output cut)\n" : "") + (res.output || "(no output)");
    out.replaceChildren(el("details", { open: true },
      el("summary", {}, el("span", { className: "grow", textContent: c.icon ? `${c.icon} ${c.name}` : c.name }), badge,
         el("span", { className: "dim", textContent: `${res.seconds}s` })),
      el("pre", { textContent: text })));
  }

  load();
  setInterval(() => { if (!document.hidden) load(); }, 30000);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) load(); });
})();
