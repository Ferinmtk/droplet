"""A pretend agent with a few devices, for trying the window without one:
`droplet-agent app --demo`. The tests and the screenshots use it too.

`DemoAgent().call` answers like control.call: the same commands, the same
answers, and NotRunning while `running` is False. Nothing leaves this process.
"""

from __future__ import annotations

import secrets
import threading
import time

PHONE_FP = "3f9a" * 16
WIN_FP = "b71c" * 16
LINUX_FP = "0d52" * 16
NEARBY_FP = "e8a4" * 16
ASKING_FP = "5c33" * 16


def _peer(pid, name, fp, os_name, link=None, on_lan=False, lan=(), source="paired", relation="own"):
    from ..mesh import perms
    return {"id": pid, "name": name, "fp": fp, "source": source, "lan": list(lan), "port": 1739,
            "tailnet_ip": None, "os": os_name, "hub": None, "link": link, "on_lan": on_lan,
            "relation": relation, "allow": perms.defaults(relation), "paused": False, "remote": None,
            "refused": None, "nickname": "", "features": ["cancel", "link", "rename"] if link else []}


class DemoAgent:
    def __init__(self, running: bool = True, incoming: bool = True, now: float | None = None,
                 folder: str = "~/Downloads/droplet", accept_after: int = 2, transfers: bool = False):
        self.running = running
        self.lock = threading.Lock()
        self.calls: list[dict] = []
        now = time.time() if now is None else now
        self.now = now
        self.folder = folder
        self.accept_after = accept_after
        self.peers = [
            _peer("redmi-note-11e", "redmi-note-11e-pro", PHONE_FP, "android", link="lan 192.168.1.23",
                  on_lan=True, lan=["192.168.1.23"]),
            _peer("maryanne", "maryanne", WIN_FP, "windows", on_lan=True, lan=["192.168.1.40"], relation="other"),
            _peer("sheffield", "sheffield", LINUX_FP, "linux", lan=["192.168.1.61"]),
        ]
        self.nearby = [{"id": "pixel-tablet", "name": "pixel-tablet", "fp": NEARBY_FP, "os": "android",
                        "addresses": ["192.168.1.77"], "port": 1739},
                       {"id": "t15", "name": "t15", "fp": "71ee" * 16, "os": "linux",
                        "addresses": ["192.168.1.12"], "port": 1739}]
        self.incoming = [{"request": "a1" * 16, "name": "anna-phone", "id": "anna-phone", "fp": ASKING_FP,
                          "os": "android", "code": "4817"}] if incoming else []
        self.chat = [
            self._msg("in", PHONE_FP, "Are you still at the office?", now - 3600 * 3),
            self._msg("out", PHONE_FP, "Leaving in ten minutes", now - 3600 * 3 + 90),
            self._msg("in", PHONE_FP, "Can you send me the slides from this morning?", now - 1500),
            self._msg("out", PHONE_FP, "Sent them, check Downloads", now - 1380),
            self._msg("in", PHONE_FP, "Got them, thanks!", now - 1200),
            self._msg("out", WIN_FP, "The printer is fixed", now - 86400 * 2),
            self._msg("out", LINUX_FP, "Backup finished?", now - 600, state="queued",
                      why="not reachable directly, and no hub knows it right now"),
        ]
        self.received = [
            self._file("IMG_20261008_093114.jpg", PHONE_FP, now - 900, 3_481_120),
            self._file("boarding-pass.pdf", PHONE_FP, now - 7200, 211_455),
            self._file("Quarterly report.xlsx", WIN_FP, now - 86400, 1_048_120),
            self._file("voice-note.m4a", PHONE_FP, now - 86400 * 3, 640_211, exists=False),
        ]
        self.jobs: dict[str, dict] = {}
        self.pairing: dict[str, dict] = {}
        self.paused_all = False
        self.name = "slim"
        self.links: list[dict] = []
        # files on their way, as the agent's `transfers` says them
        self.transfers: list[dict] = []
        if transfers:
            self.transfers = [
                {"id": "a1" * 16, "dir": "out", "fp": PHONE_FP, "peer": "redmi-note-11e-pro",
                 "name": "Holiday video.mp4", "size": 184_549_376, "done": 83_886_080, "state": "active",
                 "error": None, "route": "lan", "percent": 45, "rate": 7_340_032, "eta": 14},
                {"id": "b2" * 16, "dir": "in", "fp": PHONE_FP, "peer": "redmi-note-11e-pro",
                 "name": "IMG_20261010_101544.jpg", "size": 4_194_304, "done": 3_355_443, "state": "active",
                 "error": None, "route": "lan", "percent": 80, "rate": 2_097_152, "eta": 1},
                {"id": "c3" * 16, "dir": "out", "fp": WIN_FP, "peer": "maryanne", "name": "Budget 2027.xlsx",
                 "size": 1_048_576, "done": 1_048_576, "state": "done", "error": None, "route": "lan",
                 "percent": 100, "rate": None, "eta": None},
            ]

    def _name(self, fp):
        return next((p["name"] for p in self.peers if p["fp"] == fp), "?")

    def _msg(self, d, fp, body, ts, state=None, why=None):
        return {"id": secrets.token_hex(8), "dir": d, "fp": fp, "peer": self._name(fp), "name": self._name(fp),
                "body": body, "ts": ts, "route": "lan" if d == "out" else None,
                "state": state or ("sent" if d == "out" else "received"), "why": why}

    def _file(self, name, fp, ts, size, exists=True):
        return {"name": name, "path": f"{self.folder}/{name}", "fp": fp, "from": self._name(fp), "ts": ts,
                "size": size if exists else None, "exists": exists}

    def _find(self, query):
        for p in self.peers:
            if query in (p["fp"], p["id"], p["name"]) or (p.get("nickname") and query == p["nickname"]):
                return p
        raise ValueError(f"no trusted peer called {query!r}. See: droplet-agent peers")

    def call(self, request: dict, timeout: float = 30, path=None) -> dict:
        from ..mesh import control
        if not self.running:
            raise control.NotRunning("no agent")
        with self.lock:
            self.calls.append(request)
            try:
                return self._answer(request)
            except ValueError as e:
                return {"error": str(e)}

    def _answer(self, req: dict) -> dict:
        cmd = req.get("cmd")
        if cmd == "status":
            from ..mesh import perms
            return {"id": "slim", "name": self.name, "fp": "9be1" * 16, "port": 1739,
                    "transfers": [dict(t) for t in self.transfers],
                    "paused_all": self.paused_all, "capabilities": list(perms.CAPABILITIES),
                    "peers": [dict(p, allow=dict(p["allow"])) for p in self.peers],
                    "nearby": [dict(n) for n in self.nearby],
                    "incoming": [dict(r) for r in self.incoming], "outbox": [], "refused": 0}
        if cmd == "perm-set":
            from ..mesh import perms
            p = self._find(req.get("peer"))
            if req.get("relation") in perms.RELATIONS:
                p["relation"] = req["relation"]
                p["allow"] = perms.defaults(req["relation"])
            allow = req.get("allow") if isinstance(req.get("allow"), dict) else {}
            if req.get("capability"):
                allow = {req["capability"]: bool(req.get("on"))}
            for c, on in allow.items():
                if c not in perms.CAPABILITIES:
                    raise ValueError(f"no capability called {c!r}")
                p["allow"][c] = bool(on)
            return {k: p[k] for k in ("name", "fp", "relation", "allow", "paused")}
        if cmd in ("pause", "resume"):
            if req.get("all"):
                self.paused_all = cmd == "pause"
                return {"paused_all": self.paused_all}
            p = self._find(req.get("peer"))
            p["paused"] = cmd == "pause"
            return {k: p[k] for k in ("name", "fp", "relation", "allow", "paused")} | {"paused_all": self.paused_all}
        if cmd == "chat":
            msgs = self.chat
            if req.get("peer"):
                fp = self._find(req["peer"])["fp"]
                msgs = [m for m in msgs if m["fp"] == fp]
            return {"messages": [dict(m) for m in msgs[-(req.get("n") or 100):]]}
        if cmd == "received":
            return {"folder": self.folder, "files": [dict(f) for f in self.received[:req.get("n") or 50]]}
        if cmd == "text":
            p = self._find(req.get("peer"))
            m = self._msg("out", p["fp"], req.get("body"), time.time(),
                          state=None if p["link"] else "queued")
            self.chat.append(m)
            return {"id": m["id"], "kind": "text", "peer": p["name"], "state": "done" if p["link"] else "queued",
                    "route": "lan" if p["link"] else None, "attempts": 1, "name": None, "why": None}
        if cmd == "send-file":
            p = self._find(req.get("peer"))
            jid = secrets.token_hex(8)
            name = str(req.get("path") or "").rsplit("/", 1)[-1]
            self.jobs[jid] = {"id": jid, "kind": "file", "peer": p["name"], "state": "queued", "route": None,
                              "attempts": 0, "name": name, "why": None, "_reachable": bool(p["link"]), "_polls": 0}
            return self._job(jid)
        if cmd == "job":
            j = self.jobs.get(req.get("id"))
            if j is None:
                return {"error": "no such job"}
            j["_polls"] += 1
            if j["_polls"] >= 2 and j["state"] == "queued":
                if j["_reachable"]:
                    j.update(state="done", route="lan", attempts=1)
                else:
                    j.update(attempts=1, why="not reachable directly, and no hub knows it right now")
            return self._job(j["id"])
        if cmd == "ring":
            p = self._find(req.get("peer"))
            if not p["link"]:
                return {"error": f"{p['name']} isn't reachable directly, and not through the hub either"}
            return {"route": "lan"}
        if cmd == "clip":
            p = self._find(req.get("peer"))
            if not p["link"]:
                return {"error": f"{p['name']} isn't reachable directly, and not through the hub either"}
            return {"route": "lan"}
        if cmd == "transfers":
            return {"transfers": [dict(t) for t in self.transfers], "queued": []}
        if cmd == "cancel":
            t = next((t for t in self.transfers if t["id"].startswith(str(req.get("id") or "-")) and
                      t["state"] == "active"), None)
            if t is None:
                raise ValueError("no transfer with that id is going on (it may have finished already)")
            t.update(state="cancelled", error="cancelled here", rate=None, eta=None)
            return {"id": t["id"], "dir": t["dir"], "name": t["name"], "peer": t["peer"]}
        if cmd == "rename":
            from ..mesh.trust import check_name
            self.name = check_name(req.get("name"))
            return {"name": self.name, "told": sum(1 for p in self.peers if p["link"])}
        if cmd == "nickname":
            from ..mesh.trust import check_name
            p = self._find(req.get("peer"))
            p["nickname"] = check_name(req.get("nickname") or "", "a nickname", empty_ok=True)
            return {"name": p["name"], "fp": p["fp"], "nickname": p["nickname"]}
        if cmd == "link":
            from ..mesh.links import check_url
            p = self._find(req.get("peer"))
            url = check_url(req.get("url"))
            self.links.append({"fp": p["fp"], "url": url})
            if p["link"]:
                self.chat.append(dict(self._msg("out", p["fp"], url, time.time()), kind="link"))
                return {"how": "link", "route": "lan"}
            return {"how": "message", "id": secrets.token_hex(8), "kind": "text", "peer": p["name"],
                    "state": "queued", "route": None, "attempts": 1, "name": None,
                    "why": "not reachable directly, and no hub knows it right now"}
        if cmd == "unpair":
            p = self._find(req.get("peer"))
            self.peers.remove(p)
            return {"name": p["name"], "told": bool(p["link"])}
        if cmd == "pair-start":
            target = str(req.get("target") or "")
            n = next((n for n in self.nearby if target in (n["name"], n["id"], n["fp"])), None)
            if n is None and not any(c.isdigit() for c in target):
                raise ValueError(f"no peer called {target!r} is announcing itself on this network.")
            rid = secrets.token_hex(16)
            peer = {"id": (n or {}).get("id", target), "name": (n or {}).get("name", target),
                    "fp": (n or {}).get("fp", "77aa" * 16), "os": (n or {}).get("os", "linux")}
            self.pairing[rid] = {"state": "code", "peer": peer, "polls": 0}
            return {"request": rid, "code": "2604", "peer": peer, "address": f"{target}:1739"}
        if cmd == "pair-confirm":
            pr = self.pairing.get(req.get("request"))
            if pr is None:
                raise ValueError("no such pairing request")
            pr["state"] = "waiting" if req.get("yes") else "cancelled"
            pr["relation"] = req.get("relation") or "own"
            return {"state": pr["state"]}
        if cmd == "pair-status":
            pr = self.pairing.get(req.get("request"))
            if pr is None:
                return {"state": "expired"}
            if pr["state"] == "waiting":
                pr["polls"] += 1
                if pr["polls"] >= self.accept_after:
                    pr["state"] = "accepted"
                    p = pr["peer"]
                    self.nearby = [n for n in self.nearby if n["fp"] != p["fp"]]
                    self.peers.append(_peer(p["id"], p["name"], p["fp"], p["os"], link="lan 192.168.1.77",
                                            on_lan=True, relation=pr.get("relation") or "own"))
            return {"state": pr["state"]}
        if cmd == "pair-answer":
            r = next((r for r in self.incoming if r["request"] == req.get("request")), None)
            if r is None:
                raise ValueError("no such pairing request waiting (it may have expired)")
            self.incoming.remove(r)
            if req.get("accept"):
                self.peers.append(_peer(r["id"], r["name"], r["fp"], r["os"], on_lan=True,
                                        relation=req.get("relation") or "own"))
            return {"state": "accepted" if req.get("accept") else "denied", "name": r["name"], "fp": r["fp"]}
        if cmd == "qr":
            return {"link": "https://droplet.noxeratech.com/app/#pair=eyJ2IjoxLCJuIjoiZGVtbyJ9",
                    "expires_in": 600, "port": 1739, "fp": LINUX_FP, "addresses": ["192.168.1.20"]}
        return {"error": f"unknown command {cmd!r}"}

    def _job(self, jid):
        return {k: v for k, v in self.jobs[jid].items() if not k.startswith("_")}
