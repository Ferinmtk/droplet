// Hub commands: one-tap buttons for the preset commands in the hub's
// commands.json. The page only ever sends a command's id; what runs is
// decided on the hub.
(() => {
  document.head.append(el("style", { textContent: `
    #hub-commands .cmd-grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(6.5rem, 1fr));
                              gap:.5rem; margin-top:.6rem; }
    #hub-commands .cmd { position:relative; display:flex; flex-direction:column; align-items:center;
                         justify-content:center; gap:.3rem; min-height:4.75rem; padding:.6rem .4rem;
                         background:var(--bg); text-align:center; line-height:1.2; }
    #hub-commands .cmd:hover:not(:disabled) { border-color:var(--accent); }
    #hub-commands .cmd .ico { font-size:1.6rem; line-height:1; }
    #hub-commands .cmd .nm { font-size:.8rem; overflow-wrap:anywhere; }
    #hub-commands .cmd.busy { opacity:1; border-color:var(--accent); }
    #hub-commands .cmd.busy .ico { visibility:hidden; }
    #hub-commands .cmd.busy::after { content:""; position:absolute; top:.75rem; left:50%; width:1.3rem; height:1.3rem;
                                     margin-left:-.65rem; border-radius:50%; border:2px solid var(--line);
                                     border-top-color:var(--accent); animation:cmd-spin .8s linear infinite; }
    @keyframes cmd-spin { to { transform:rotate(360deg); } }
    #hub-commands details { margin-top:.75rem; background:var(--bg); }
    #hub-commands summary .exit, #hub-commands summary .dim { margin-left:.45rem; }
    #hub-commands .exit { display:inline-block; border-radius:999px; font-size:.7rem; font-weight:700; padding:.05rem .45rem;
                          color:var(--bg); background:#4ade80; }
    #hub-commands .exit.bad { background:var(--danger); }
    #hub-commands pre { margin:0; padding:.2rem .8rem .8rem; max-height:50vh; overflow:auto; white-space:pre;
                        font:.75rem/1.4 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace; color:var(--text); }
  ` }));

  const card = el("section", { className: "card", id: "hub-commands", hidden: true });
  const grid = el("div", { className: "cmd-grid" });
  const out = el("div");
  card.append(el("b", { textContent: "Hub commands" }),
              el("span", { className: "dim", textContent: " · run on the hub" }), grid, out);
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
                 el("span", { className: "ico", textContent: c.icon || "▶️" }),
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
      el("summary", {}, el("span", { textContent: `${c.icon || "▶️"} ${c.name}` }), badge,
         el("span", { className: "dim", textContent: `${res.seconds}s` })),
      el("pre", { textContent: text })));
  }

  load();
  setInterval(() => { if (!document.hidden) load(); }, 30000);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) load(); });
})();
