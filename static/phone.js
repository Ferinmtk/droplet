// Phone card: battery and recent notifications mirrored from phones running
// the droplet Android app (see phone.py). Hidden until a phone reports in,
// and on the phone itself.
(() => {
  const POLL_MS = 10000;
  const SHOWN = 6;   // notifications per phone before "Show all"

  document.head.append(el("style", { textContent: `
    #phones { margin-top:1rem; }
    .ph-head { display:flex; align-items:center; gap:.5rem; flex-wrap:wrap; }
    .ph-name { font-weight:600; flex:1; min-width:6rem; }
    .ph-batt { font-size:.8rem; color:var(--dim); white-space:nowrap; }
    .ph-batt.low { color:var(--danger); }
    .ph-clear { font-size:.75rem; padding:.2rem .55rem; }
    .ph-list { margin-top:.6rem; display:flex; flex-direction:column; gap:.4rem; }
    .ph-n { background:var(--bg); border:1px solid var(--line); border-radius:8px; padding:.45rem .6rem; cursor:pointer; }
    .ph-meta { display:flex; justify-content:space-between; gap:.5rem; font-size:.72rem; color:var(--dim); }
    .ph-app { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .ph-title { font-weight:600; margin-top:.1rem; word-break:break-word; }
    .ph-text { color:var(--dim); margin-top:.1rem; white-space:pre-wrap; word-break:break-word;
               display:-webkit-box; -webkit-line-clamp:3; -webkit-box-orient:vertical; overflow:hidden; }
    .ph-n.open .ph-text { display:block; -webkit-line-clamp:unset; }
    .ph-more { margin-top:.5rem; font-size:.8rem; }
    .ph-empty { color:var(--dim); margin-top:.5rem; }
  ` }));

  const box = el("div", { id: "phones" });
  document.getElementById("features").append(box);

  const expanded = new Set();   // phone ids showing every notification
  let last = "";
  let shown = [];

  function since(ts) {
    const s = Math.max(0, Date.now() / 1000 - ts);
    if (s < 60) return "just now";
    if (s < 3600) return Math.floor(s / 60) + "m ago";
    if (s < 86400) return Math.floor(s / 3600) + "h ago";
    return Math.floor(s / 86400) + "d ago";
  }

  function battery(p) {
    if (p.battery == null) return null;
    const stale = Date.now() / 1000 - p.status_ts > 15 * 60;
    return el("span", {
      className: "ph-batt" + (p.battery <= 15 && !p.charging ? " low" : ""),
      title: "reported " + since(p.status_ts),
      textContent: `${p.charging ? "⚡" : "🔋"} ${p.battery}%${p.charging ? " charging" : ""}${stale ? " · " + since(p.status_ts) : ""}`,
    });
  }

  function note(n) {
    const card = el("div", { className: "ph-n", title: "Tap to expand" },
      el("div", { className: "ph-meta" },
        el("span", { className: "ph-app", textContent: n.app || n.package }),
        el("span", { textContent: since(n.time) })));
    if (n.title) card.append(el("div", { className: "ph-title", textContent: n.title }));
    if (n.text) card.append(el("div", { className: "ph-text", textContent: n.text }));
    card.onclick = () => card.classList.toggle("open");
    return card;
  }

  function phoneCard(p) {
    const head = el("div", { className: "ph-head" }, el("span", { className: "ph-name", textContent: "📱 " + p.name }));
    const batt = battery(p);
    if (batt) head.append(batt);
    const card = el("section", { className: "card" }, head);
    if (p.notifications.length) {
      const clear = el("button", { className: "ph-clear", textContent: "Clear", title: "Clear this list (the phone keeps its notifications)" });
      clear.onclick = async () => {
        await fetch(`/api/phone/${p.id}/clear`, { method: "POST" }).catch(() => null);
        last = "";
        poll();
      };
      head.append(clear);
      const all = expanded.has(p.id);
      const list = el("div", { className: "ph-list" }, ...(all ? p.notifications : p.notifications.slice(0, SHOWN)).map(note));
      card.append(list);
      if (p.notifications.length > SHOWN) {
        const more = el("button", { className: "ph-more", textContent: all ? "Show fewer" : `Show all ${p.notifications.length}` });
        more.onclick = () => { all ? expanded.delete(p.id) : expanded.add(p.id); last = ""; render(shown); };
        card.append(more);
      }
    } else {
      card.append(el("p", { className: "ph-empty", textContent: "No notifications from this phone yet." }));
    }
    return card;
  }

  function render(phones) {
    shown = phones;
    // redraw when something changed, or once a minute to age the "3m ago"s
    const key = JSON.stringify(phones) + [...expanded].join() + Math.floor(Date.now() / 60000);
    if (key === last) return;
    last = key;
    box.replaceChildren(...phones.map(phoneCard));
  }

  let busy = false;
  async function poll() {
    if (document.hidden || busy) return;
    busy = true;
    try {
      const res = await fetch("/api/phone/notifications");
      if (res.ok) render(((await res.json()).phones || []).filter(p => !p.self));
    } catch {
      // hub briefly unreachable: keep what's showing
    } finally {
      busy = false;
    }
  }

  poll();
  setInterval(poll, POLL_MS);
  document.addEventListener("visibilitychange", poll);
})();
