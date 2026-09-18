// Phone card: battery and recent notifications mirrored from phones running
// the droplet Android app (see phone.py). Hidden until a phone reports in,
// and on the phone itself.
(() => {
  const POLL_MS = 10000;
  const SHOWN = 6;   // notifications per phone before "Show all"

  document.head.append(el("style", { textContent: `
    /* right after Now playing in the Hub tab (see the order rules in index.html) */
    #phones { order:2; display:flex; flex-direction:column; gap:var(--s4, 16px); }
    #phones .ph-batt.low { color:var(--coral, var(--danger)); font-weight:600; }
    #phones .ph-list { display:flex; flex-direction:column; gap:8px; }
    #phones .ph-n { background:var(--card-2, var(--bg)); border:1px solid var(--line); border-radius:var(--r-sm, 10px);
                    padding:10px 12px; cursor:pointer; transition:border-color .15s; }
    #phones .ph-n:hover { border-color:var(--line-2, var(--line)); }
    #phones .ph-meta { display:flex; justify-content:space-between; gap:8px; font-size:12px; color:var(--dim); }
    #phones .ph-app { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-weight:600; color:var(--accent); }
    #phones .ph-title { font-weight:600; margin-top:2px; word-break:break-word; }
    #phones .ph-text { color:var(--dim); margin-top:2px; white-space:pre-wrap; word-break:break-word;
                       display:-webkit-box; -webkit-line-clamp:3; -webkit-box-orient:vertical; overflow:hidden; }
    #phones .ph-n.open .ph-text { display:block; -webkit-line-clamp:unset; }
    #phones .ph-more { align-self:flex-start; }
  ` }));

  // hidden while no phone has reported, so the Hub tab's empty state can show
  const box = el("div", { id: "phones", hidden: true });
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
    if (p.battery == null) return "Mirrored notifications";
    const stale = Date.now() / 1000 - p.status_ts > 15 * 60;
    return `Battery ${p.battery}%${p.charging ? ", charging" : ""}${stale ? " · " + since(p.status_ts) : ""}`;
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
    const actions = [];
    if (p.notifications.length) {
      const clear = el("button", { className: "ghost", textContent: "Clear", title: "Clear this list (the phone keeps its notifications)" });
      clear.onclick = async () => {
        await fetch(`/api/phone/${p.id}/clear`, { method: "POST" }).catch(() => null);
        last = "";
        poll();
      };
      actions.push(clear);
    }
    const head = cardHead("phone", p.name, battery(p), ...actions);
    const low = p.battery != null && p.battery <= 15 && !p.charging;
    if (low) head.querySelector(".small")?.classList.add("ph-batt", "low");
    const card = el("section", { className: "card" }, head);
    if (p.notifications.length) {
      const all = expanded.has(p.id);
      const list = el("div", { className: "ph-list" }, ...(all ? p.notifications : p.notifications.slice(0, SHOWN)).map(note));
      card.append(list);
      if (p.notifications.length > SHOWN) {
        const more = el("button", { className: "ph-more soft", textContent: all ? "Show fewer" : `Show all ${p.notifications.length}` });
        more.onclick = () => { all ? expanded.delete(p.id) : expanded.add(p.id); last = ""; render(shown); };
        card.append(more);
      }
    } else {
      card.append(el("p", { className: "dim small", textContent: "No notifications from this phone yet." }));
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
    box.hidden = !phones.length;
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
