"""droplet-agent: setup, run, status, doctor, uninstall."""

from __future__ import annotations

import argparse
import getpass
import logging
import os
import shutil
import signal
import socket
import sys
import threading
from pathlib import Path
from urllib.parse import urlsplit

from . import APP_ID, __version__, clip, config, discovery, env, hub, lock, mediastate, pairing, routes, screenshot
from .inject import manager as input_manager

SERVICE = "droplet-agent.service"
DISCOVER_FOR = 3  # seconds setup listens for hubs on the LAN


def data_dir() -> Path:
    return config.data_dir()


def unit_path() -> Path:
    return Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config") / "systemd/user" / SERVICE


def desktop_file_path() -> Path:
    return Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share") / "applications" / f"{APP_ID}.desktop"


def _setup_logging(verbose: bool):
    under_systemd = bool(os.environ.get("INVOCATION_ID"))
    fmt = "%(levelname)s %(name)s: %(message)s" if under_systemd else "%(asctime)s %(levelname)s %(name)s: %(message)s"
    logging.basicConfig(level=logging.DEBUG if verbose else logging.INFO, format=fmt, datefmt="%H:%M:%S")
    logging.getLogger("websockets").setLevel(logging.WARNING)


# --- setup -------------------------------------------------------------------

def _interactive() -> bool:
    return sys.stdin.isatty() and sys.stdout.isatty()


def _choose_hub() -> discovery.Found | None:
    """Look for hubs on the LAN; pick one (asking when there's a choice)."""
    print("Looking for droplet hubs on this network…", flush=True)
    found = discovery.browse(timeout=DISCOVER_FOR)
    if not found:
        print("No droplet hub answered on this network. Is this computer on the same Wi-Fi as the hub?\n"
              "Or say which hub: droplet-agent setup --hub http://<hub's address>:8000 "
              "(or its tailnet URL)", file=sys.stderr)
        return None
    for i, f in enumerate(found, 1):
        where = ", ".join(hub.host_url("https", a, f.https_port).split("//")[1] for a in f.addresses)
        print(f"  {i}. {f.name}  {where}  (id {f.id})")
    sys.stdout.flush()
    if len(found) == 1:
        if _interactive() and input(f"Use {found[0].name}? [Y/n] ").strip().lower() not in ("", "y", "yes"):
            return None
        return found[0]
    if not _interactive():
        print("More than one hub answered. Say which, with one of:", file=sys.stderr)
        for f in found:
            if f.http_port:
                print(f"  --hub {hub.host_url('http', f.addresses[0], f.http_port)}   ({f.name}, id {f.id})",
                      file=sys.stderr)
        return None
    answer = input(f"Which one? [1-{len(found)}] ").strip()
    if not answer.isdigit() or not 1 <= int(answer) <= len(found):
        return None
    return found[int(answer) - 1]


def _describe_trust(info: dict, route: hub.Route, how: str):
    name = info.get("name") or "the hub"
    print(f"Hub: {name} (id {info['id']}), reached over {route.describe()}")
    if how == "first-use":
        print(f"First contact over the LAN: pinned its certificate {info['fingerprint']}.\n"
              "From now on the agent only talks to a hub with that certificate. Pair only with a hub you know\n"
              "is yours: when it asks, check the code on your other device matches the one here.")
    elif how == "plain":
        print("Warning: this hub has no LAN HTTPS, so the agent's token will cross the network "
              "unencrypted. Its tailnet URL is safer, if it has one.")
    sys.stdout.flush()


def _save_link(cfg: dict, hub_url: str, route: hub.Route, info: dict, got: dict, pending: bool) -> Path:
    cfg.update(hub=hub_url, token=got["token"], device={"id": got["id"], "name": got["name"]}, pending=pending)
    cfg["hub_identity"] = routes.identity_from(info, route)
    return config.save(cfg)


def _refresh_identity(cfg: dict) -> int:
    """`setup` with nothing else on a linked agent: read the hub's identity again, over a
    route that proves who it is (this machine, if it's the hub, or the tailnet), and keep
    the token. This is how a regenerated LAN certificate gets pinned again."""
    ident = cfg["hub_identity"]
    candidates = [hub.Route("hub-local", f"http://127.0.0.1:{ident.get('http_port') or routes.DEFAULT_HTTP_PORT}")]
    if ident.get("tailnet"):
        candidates.append(hub.Route("tailnet", ident["tailnet"]))
    try:
        configured = hub.normalize(cfg["hub"])
        host = urlsplit(configured).hostname or ""
        if configured.startswith("https://") and configured != ident.get("tailnet") and not routes.is_ip(host):
            candidates.append(hub.Route("direct", configured))
    except hub.HubError:
        pass
    for route in candidates:
        try:
            info = hub.info(route, timeout=routes.REMOTE_TIMEOUT)
        except hub.HubError:
            continue
        if ident.get("id") and info["id"] != ident["id"]:
            continue
        try:
            dev = hub.me(route, cfg["token"])
        except hub.HubError as e:
            print(f"Couldn't check this device's token: {e}", file=sys.stderr)
            return 1
        if dev is None or dev.get("pending"):
            print("The hub doesn't know this device any more. Pair again: droplet-agent setup --name NAME "
                  "(or --code 123456).", file=sys.stderr)
            return 1
        new = routes.identity_from(info, route)
        new["lan"] = routes.dedupe(new["lan"] + list(ident.get("lan") or []))[:routes.MAX_LAN_HINTS]
        old_fp = ident.get("fingerprint")
        cfg["hub_identity"] = new
        config.save_identity(new)
        print(f"Read the hub's identity over {route.describe()}: {new['name'] or '?'} (id {new['id']}).")
        if new["fingerprint"] and old_fp and new["fingerprint"] != old_fp:
            print(f"Its LAN certificate changed: pinned {new['fingerprint']} (was {old_fp}).")
        elif new["fingerprint"]:
            print(f"LAN certificate pinned: {new['fingerprint']}")
        print("Restart the agent to use it: systemctl --user restart droplet-agent")
        return 0
    print("Couldn't reach the hub over a route that proves who it is (its tailnet URL, or this\n"
          "machine if it's the hub). To pair again over the LAN instead:\n"
          "  droplet-agent setup --name NAME     (or --code 123456 from a browser that's already in)\n"
          f"If the hub still lists {cfg['device'].get('name') or 'this computer'}, remove it there first, "
          "or use another name.", file=sys.stderr)
    return 1


def cmd_setup(args) -> int:
    code = None
    if args.code is not None:
        code = "".join(ch for ch in args.code if ch.isdigit())
        if len(code) != 6:
            print("The link code is six digits, from droplet on this computer's browser "
                  "(Devices → Link an app).", file=sys.stderr)
            return 2
        if args.pin is not None:
            print("Use --code or --pin, not both.", file=sys.stderr)
            return 2
    name = " ".join((args.name or "").split())[:40] or socket.gethostname().split(".")[0][:40]
    pin = args.pin
    if pin == "":
        pin = getpass.getpass("The hub's PIN: ")
    cfg = config.load()
    try:
        if config.is_set_up(cfg) and not (args.hub or code or args.name or pin is not None):
            return _refresh_identity(cfg)
        resuming = bool(cfg.get("pending") and cfg.get("token") and cfg["hub_identity"].get("id") and not code)
        if args.hub:
            hub_url = hub.normalize(args.hub)
            route, info, how = routes.establish(hub_url)
        elif resuming:
            # the request to join from last time: find the same hub again
            route = routes.Router(cfg, persist=False).select()
            info, how, hub_url = hub.info(route, timeout=routes.REMOTE_TIMEOUT), "known", cfg["hub"]
        else:
            found = _choose_hub()
            if found is None:
                return 1
            route, info, how = routes.establish_found(found)
            hub_url = route.url
        _describe_trust(info, route, how)
        resuming = resuming and cfg["hub_identity"]["id"] == info["id"]

        if code:
            got = hub.link(route, code)
            state = pairing.APPROVED
        else:
            got, state = None, None
            if resuming:
                dev = hub.me(route, cfg["token"])
                state = pairing.state_of(dev)
                if state == pairing.DENIED:
                    print("The last request to join was denied, or expired. Asking again.")
                else:
                    got = {"id": dev["id"], "name": dev["name"], "token": cfg["token"], "code": dev.get("code")}
            if got is None:
                if pin is not None and not info["pin"]:
                    print("This hub has no PIN. Leave out --pin, and allow this computer from one of "
                          "your devices instead.", file=sys.stderr)
                    return 2
                got = hub.register(route, name)
                state = pairing.PENDING if got.get("pending") else pairing.APPROVED
            if state == pairing.PENDING:
                # kept, so a second `setup` picks the same request up again
                _save_link(cfg, hub_url, route, info, got, pending=True)
                if pin is not None:
                    if not hub.login_pin(route, got["token"], pin):
                        print("Wrong PIN. Try again: droplet-agent setup --pin", file=sys.stderr)
                        return 1
                    state = pairing.state_of(hub.me(route, got["token"]))
                else:
                    print()
                    print(f"    {got.get('code') or '????'}")
                    print()
                    print(f"On one of your devices, allow {got['name']} — check the code matches.")
                    print("Waiting… (Ctrl+C to stop; running setup again picks up where this left off)",
                          flush=True)
                    state = pairing.wait_for_approval(
                        lambda: hub.me(route, got["token"]),
                        on_error=lambda e: logging.getLogger("droplet_agent").debug("%s", e))
        if state == pairing.DENIED:
            cfg.update(token="", pending=False)
            config.save(cfg)
            print("The hub said no: the request was denied, or it expired.", file=sys.stderr)
            return 1
        if state == pairing.TIMED_OUT:
            print("Still not allowed in. The request stays open for a day: allow it, then run "
                  "droplet-agent setup again to finish.", file=sys.stderr)
            return 1
        if state != pairing.APPROVED:
            print("The hub didn't let this computer in.", file=sys.stderr)
            return 1
    except hub.HubError as e:
        print(f"Setup failed: {e}", file=sys.stderr)
        return 1
    path = _save_link(cfg, hub_url, route, info, got, pending=False)
    print(f"Linked to {info.get('name') or hub_url} as \"{got['name']}\" over {route.describe()}. "
          f"Settings saved in {path}")
    return 0


# --- run ---------------------------------------------------------------------

def cmd_run(args) -> int:
    _setup_logging(args.verbose)
    log = logging.getLogger("droplet_agent")
    cfg = config.load()
    mesh_on = (cfg.get("mesh") or {}).get("enabled", True) is not False
    linked = config.is_set_up(cfg)
    if not linked:
        if cfg.get("pending"):
            why = ("This computer is still waiting to be let in. Run droplet-agent setup to see the code "
                   "and finish.")
        else:
            why = "Not linked to a hub. Run: droplet-agent setup"
        if not mesh_on:
            print(why, file=sys.stderr)
            return 2
        log.info("%s Until then, only the mesh runs: devices you pair with directly still work.", why)
    from .agent import Agent
    from .connection import Connection

    agent = Agent(cfg, dry_run=args.dry_run, input_backend=args.input)
    conn = Connection(agent, routes.Router(cfg), cfg["token"]) if linked else None
    stop = agent.stop

    def bye(*_):
        stop.set()
        if conn is not None:
            conn.reconnect()
    signal.signal(signal.SIGTERM, bye)
    signal.signal(signal.SIGINT, bye)

    ident = cfg["hub_identity"]
    if linked:
        log.info("droplet-agent %s for %s, hub %s%s", __version__, cfg["device"].get("name") or "?",
                 f"{ident['name'] or '?'} (id {ident['id']})" if ident.get("id") else cfg["hub"],
                 " (dry run)" if args.dry_run else "")
    else:
        log.info("droplet-agent %s, mesh only%s", __version__, " (dry run)" if args.dry_run else "")
    agent.start()
    agent.hello()   # what works now; the hub's hello (and the mesh's) say the same
    node = None
    if mesh_on:
        from .mesh_host import start_mesh
        try:
            node, host = start_mesh(agent, cfg, dry_run=args.dry_run)
            host.connection = conn
        except Exception as e:
            log.error("the mesh couldn't start, so other devices can't reach this one directly: %s", e)
    if conn is not None:
        t = threading.Thread(target=conn.run, args=(stop,), name="connection", daemon=True)
        t.start()
        while t.is_alive():
            t.join(0.5)
    else:
        while not stop.wait(0.5):
            pass
    if node is not None:
        node.close()
    agent.close()
    log.info("stopped")
    return 0


# --- the mesh: peers, pairing and sending -----------------------------------

def _ask_agent(request: dict, timeout: float = 30) -> dict | None:
    from .mesh import control
    try:
        out = control.call(request, timeout=timeout)
    except control.NotRunning:
        print("The agent isn't running, and these commands go through it. Start it with:\n"
              "  systemctl --user start droplet-agent     (or: droplet-agent run)", file=sys.stderr)
        return None
    except (OSError, ValueError) as e:
        print(f"Couldn't talk to the agent: {e}", file=sys.stderr)
        return None
    if out.get("error"):
        print(out["error"], file=sys.stderr)
        return None
    return out


def _short(fp: str) -> str:
    return fp[:16] if fp else "?"


def cmd_peers(args) -> int:
    st = _ask_agent({"cmd": "status"})
    if st is None:
        return 1
    print(f"this device: {st['name']} (id {st['id']}), mesh port {st['port']}")
    print(f"fingerprint: {st['fp']}")
    print()
    if st["peers"]:
        print("Trusted:")
        for p in st["peers"]:
            how = "paired directly" if p["source"] == "paired" else "from the hub's roster"
            where = p["link"] or ("on this network" if p["on_lan"] else "not seen")
            print(f"  {p['name']:<20} {p['id']:<16} {_short(p['fp'])}  {how}; {where}")
    else:
        print("No trusted peers yet. Pair with: droplet-agent pair <name or address>")
    if st["nearby"]:
        print("\nOn this network, not paired:")
        for p in st["nearby"]:
            print(f"  {p['name']:<20} {p['id']:<16} {_short(p['fp'])}  {p['os'] or '?'}, "
                  f"{', '.join(p['addresses'])} port {p['port']}")
    if st["incoming"]:
        print("\nAsking to pair (answer with droplet-agent pair):")
        for r in st["incoming"]:
            print(f"  {r['name']:<20} code {r['code']}  request {r['request'][:8]}")
    if st["outbox"]:
        print("\nWaiting to be sent:")
        for j in st["outbox"]:
            what = j["name"] if j["kind"] == "file" else "a message"
            print(f"  {what} to {j['peer']}: {j['state']}{' (' + j['error'] + ')' if j.get('error') else ''}")
    return 0


def _pick_request(incoming: list, which: str | None) -> dict | None:
    if which:
        found = [r for r in incoming if r["request"].startswith(which) or r["code"] == which
                 or r["name"].casefold() == which.casefold()]
        if len(found) != 1:
            print(f"No single pairing request matches {which!r}.", file=sys.stderr)
            return None
        return found[0]
    if len(incoming) == 1:
        return incoming[0]
    print("More than one device is asking; say which (its name, code or request):", file=sys.stderr)
    for r in incoming:
        print(f"  {r['name']}  code {r['code']}  request {r['request'][:8]}", file=sys.stderr)
    return None


def _answer(r: dict, accept: bool) -> int:
    out = _ask_agent({"cmd": "pair-answer", "request": r["request"], "accept": accept})
    if out is None:
        return 1
    print(f"Paired with {out['name']}." if accept else f"Refused {out['name']}.")
    return 0


def cmd_pair(args) -> int:
    import time as _time
    if args.accept is not None or args.deny is not None:
        st = _ask_agent({"cmd": "status"})
        if st is None:
            return 1
        if not st["incoming"]:
            print("No device is asking to pair right now.", file=sys.stderr)
            return 1
        accept = args.accept is not None
        r = _pick_request(st["incoming"], (args.accept if accept else args.deny) or None)
        return 1 if r is None else _answer(r, accept)
    if not args.peer:
        # the interactive way to answer: show each request and its code
        st = _ask_agent({"cmd": "status"})
        if st is None:
            return 1
        if not st["incoming"]:
            print("No device is asking to pair right now. To pair with one: droplet-agent pair <name or address>")
            return 0
        for r in st["incoming"]:
            print(f"{r['name']} ({r['id']}, {r['os'] or 'unknown system'}) wants to pair.")
            print(f"\n    {r['code']}\n")
            try:
                ans = input(f"Does {r['name']} show the same code? Pair with it? [y/N] ").strip().lower()
            except EOFError:
                ans = ""
            _answer(r, ans in ("y", "yes"))
        return 0
    out = _ask_agent({"cmd": "pair-start", "target": args.peer}, timeout=30)
    if out is None:
        return 1
    peer = out["peer"]
    print(f"Pairing with {peer['name']} (id {peer['id']}) at {out['address']}.")
    print(f"Its certificate: {peer['fp']}")
    print(f"\n    {out['code']}\n")
    print(f"On {peer['name']}, accept the request (droplet-agent pair, or its app) if it shows this code.")
    sys.stdout.flush()
    try:
        ans = input("Does it show the same code? [y/N] ").strip().lower()
    except EOFError:
        ans = ""
    yes = ans in ("y", "yes")
    if _ask_agent({"cmd": "pair-confirm", "request": out["request"], "yes": yes}) is None:
        return 1
    if not yes:
        print("Cancelled; nothing was paired.")
        return 1
    print("Waiting for it to accept… (Ctrl+C to stop)", flush=True)
    while True:
        st = _ask_agent({"cmd": "pair-status", "request": out["request"]})
        if st is None:
            return 1
        if st["state"] == "accepted":
            print(f"Paired with {peer['name']}.")
            return 0
        if st["state"] != "waiting":
            print(f"Not paired: the request was {st['state']}.", file=sys.stderr)
            return 1
        _time.sleep(1)


def cmd_unpair(args) -> int:
    out = _ask_agent({"cmd": "unpair", "peer": args.peer})
    if out is None:
        return 1
    print(f"Unpaired {out['name']}" + ("; it was told too." if out["told"] else
                                      "; it wasn't reachable, so it still lists this device until it's unpaired there."))
    return 0


ROUTE_TEXT = {"lan": "directly, over the LAN", "tailnet": "directly, over Tailscale",
              "hub": "through the hub", "hub-mailbox": "to the hub's mailbox (it's offline)"}


def _report_job(job: dict, what: str) -> int:
    if job["state"] == "done":
        print(f"{what} {ROUTE_TEXT.get(job['route'], job['route'])}.")
        return 0
    if job["state"] == "failed":
        print(f"{what} failed: {job.get('why')}", file=sys.stderr)
        return 1
    print(f"{job['peer']} can't be reached right now ({job.get('why') or 'no route'}). "
          "It's kept in the outbox and sent as soon as it or the hub can be reached.")
    return 0


def cmd_text(args) -> int:
    out = _ask_agent({"cmd": "text", "peer": args.peer, "body": args.message, "wait": 30}, timeout=40)
    return 1 if out is None else _report_job(out, "Sent")


def cmd_send_file(args) -> int:
    status = 0
    for path in args.paths:
        p = Path(path).expanduser()
        if not p.is_file():
            print(f"{path} isn't a file.", file=sys.stderr)
            status = 1
            continue
        out = _ask_agent({"cmd": "send-file", "peer": args.peer, "path": str(p.resolve()), "wait": 3600},
                         timeout=3700)
        if out is None:
            status = 1
            continue
        status |= _report_job(out, f"Sent {p.name}")
    return status


def cmd_ring(args) -> int:
    out = _ask_agent({"cmd": "ring", "peer": args.peer, "stop": args.stop})
    if out is None:
        return 1
    print(("Stopped ringing it" if args.stop else "Ringing it") + f", {ROUTE_TEXT.get(out['route'], out['route'])}.")
    return 0


def cmd_clip(args) -> int:
    text = args.text
    if text is None:
        mode, why = clip.detect()
        if mode is None:
            print(f"Can't read this computer's clipboard: {why}. Or give it: --text '…'", file=sys.stderr)
            return 1
        text = clip.ClipboardSync(mode, lambda _t: True).reader()
        if not text:
            print("The clipboard holds no text (or a password manager marked it secret).", file=sys.stderr)
            return 1
    out = _ask_agent({"cmd": "clip", "peer": args.peer, "text": text})
    if out is None:
        return 1
    print(f"Sent the clipboard, {ROUTE_TEXT.get(out['route'], out['route'])}.")
    return 0


def cmd_send(args) -> int:
    import json as _json
    try:
        msg = _json.loads(args.message)
    except ValueError as e:
        print(f"Not JSON: {e}", file=sys.stderr)
        return 2
    out = _ask_agent({"cmd": "send", "peer": args.peer, "msg": msg})
    if out is None:
        return 1
    print(f"Sent, {ROUTE_TEXT.get(out['route'], out['route'])}.")
    return 0


# --- status ------------------------------------------------------------------

def _probe_caps(cfg: dict) -> dict[str, tuple[bool, str]]:
    """What would work, without starting anything or showing any dialog."""
    out = {}
    tries = input_manager.probe(cfg.get("input_backend") or "auto")
    usable = [(n, why) for n, ok, why in tries if ok]
    if usable:
        name, why = usable[0]
        extra = ""
        if name == "portal":
            saved = config.read_secret(config.portal_token_path())
            extra = ("; permission remembered" if saved
                     else "; the desktop asks you to allow remote control the first time it runs")
        out["input"] = (True, f"{name}: {why}{extra}")
    else:
        out["input"] = (False, "; ".join(f"{n}: {why}" for n, _, why in tries))
    out["media"] = mediastate.available()
    cmd, why = lock.detect(cfg.get("lock_command"))
    out["lock"] = (cmd is not None, why if not cmd or cmd == [lock.LOGIND] else f"{why}: {' '.join(cmd)}")
    shots = screenshot.methods(cfg.get("screenshot_command"))
    out["screenshot"] = (bool(shots), "tries " + ", ".join(shots) if shots else "no screenshot tool found")
    mode, why = clip.detect()
    out["clipboard"] = (mode is not None, why)
    for cap in config.CAPS:
        if not config.enabled(cfg, cap):
            out[cap] = (False, "switched off in the config")
    return out


def _service_state() -> str:
    if not env.which("systemctl"):
        return "no systemd"
    r = env.run(["systemctl", "--user", "is-active", SERVICE])
    return r.stdout.decode().strip() if r is not None else "unknown"


def cmd_status(args) -> int:
    cfg = config.load()
    print(f"droplet-agent {__version__}")
    if cfg.get("pending"):
        print("not set up: waiting to be let in; run droplet-agent setup to see the code and finish")
    elif config.is_set_up(cfg):
        ident = cfg["hub_identity"]
        # persist=False: status only looks. A config from before local-first is
        # read from the hub here, and stored by the next `droplet-agent run`.
        router = routes.Router(cfg, persist=False)
        route = None
        try:
            route = router.select()
            route_text = route.describe()
        except hub.HubError as e:
            route_text = f"none: {e}"
        stored = "" if ident.get("id") else "  (read from the hub just now; `run` stores it)"
        ident = cfg["hub_identity"]
        print(f"hub:      {ident.get('name') or cfg['hub']}  (id {ident.get('id') or 'unknown'}){stored}")
        print(f"pin:      {ident.get('fingerprint') or 'none: the hub has no LAN HTTPS, so no LAN route'}")
        print(f"route:    {route_text}")
        if router.warning:
            print(f"warning:  {router.warning}")
        if router.on_hub_machine() or (route is not None and route.kind == "hub-local"):
            print("          this computer is the hub")
        print(f"tailnet:  {ident.get('tailnet') or 'none'}")
        print(f"lan:      {', '.join(ident.get('lan') or []) or 'no address known yet'}")
        print(f"device:   {cfg['device'].get('name')} ({cfg['device'].get('id')})")
        if route is not None:
            try:
                dev = hub.me(route, cfg["token"])
                print("token:    " + ("accepted by the hub" if dev and not dev.get("pending")
                                      else "NOT known to the hub; run setup again"))
            except hub.HubError as e:
                print(f"token:    can't check ({e})")
    else:
        print("not set up: run droplet-agent setup")
    print(f"service:  {_service_state()}")
    _mesh_status(cfg)
    print(f"desktop:  {', '.join(sorted(env.desktops())) or 'unknown'}"
          f" ({'Wayland' if env.is_wayland() else 'X11' if env.x11_display() else 'no display'})")
    print()
    for cap, (ok, why) in _probe_caps(cfg).items():
        print(f"  {'✓' if ok else '✗'} {cap:<10} {why}")
    return 0


def _mesh_status(cfg: dict):
    """The mesh, from its files: nothing is made or changed here."""
    if (cfg.get("mesh") or {}).get("enabled", True) is False:
        print("mesh:     off (mesh.enabled is false in the config)")
        return
    from .mesh import control
    from .mesh.identity import fingerprint, pem_to_der
    cert = config.mesh_config_dir() / "cert.pem"
    try:
        fp = fingerprint(pem_to_der(cert.read_text()))
    except (OSError, ValueError):
        print("mesh:     no identity yet (made the first time the agent runs)")
        return
    try:
        import json as _json
        peers = (_json.loads((config.mesh_config_dir() / "trust.json").read_text()).get("peers") or {}).values()
    except (OSError, ValueError, AttributeError):
        peers = []
    peers = list(peers)
    paired = sum(1 for p in peers if isinstance(p, dict) and p.get("source") == "paired")
    print(f"mesh:     fingerprint {fp}")
    print(f"          {len(peers) - paired} peer(s) from the hub's roster, {paired} paired directly; "
          f"files land in {config.downloads_dir(cfg)}")
    try:
        st = control.call({"cmd": "status"}, timeout=5)
        print(f"          listening on port {st.get('port')}; `droplet-agent peers` for more")
    except (control.NotRunning, OSError, ValueError):
        print("          the agent isn't running")


def _firewall_blocks_mesh() -> str | None:
    """The command that opens the mesh ports, if firewalld is running and they're closed."""
    if not env.which("firewall-cmd"):
        return None
    r = env.run(["firewall-cmd", "--state"], timeout=5)
    if r is None or r.returncode != 0:
        return None
    r = env.run(["firewall-cmd", "--list-ports"], timeout=5)
    listed = r.stdout.decode() if r is not None and r.returncode == 0 else ""
    if "1739-1749/tcp" in listed:
        return None
    return ("sudo firewall-cmd --permanent --add-port=1739-1749/tcp && sudo firewall-cmd --reload")


# --- doctor ------------------------------------------------------------------

UDEV_RULE_FILE = "/etc/udev/rules.d/60-droplet-uinput.rules"


def uinput_fix(user: str | None = None) -> str:
    """The one-time root commands that let this user write /dev/uinput."""
    user = user or getpass.getuser()
    import grp
    import pwd
    try:
        group = grp.getgrgid(pwd.getpwnam(user).pw_gid).gr_name
    except KeyError:
        group = user
    rule = f'KERNEL=="uinput", SUBSYSTEM=="misc", GROUP="{group}", MODE="0660", OPTIONS+="static_node=uinput"'
    return "\n".join([
        f"echo '{rule}' | sudo tee {UDEV_RULE_FILE}",
        "echo uinput | sudo tee /etc/modules-load.d/droplet-uinput.conf",
        "sudo modprobe uinput",
        "sudo udevadm control --reload-rules",
        "sudo udevadm trigger --action=add --sysname-match=uinput",
        "ls -l /dev/uinput    # should now show group " + group + " with rw",
        "systemctl --user restart droplet-agent",
    ])


PACKAGES = {  # tool → (Fedora, Debian/Ubuntu, Arch)
    "playerctl": ("playerctl", "playerctl", "playerctl"),
    "wpctl": ("wireplumber", "wireplumber", "wireplumber"),
    "wl-copy": ("wl-clipboard", "wl-clipboard", "wl-clipboard"),
    "xdotool": ("xdotool", "xdotool", "xdotool"),
    "xclip": ("xclip", "xclip", "xclip"),
    "grim": ("grim", "grim", "grim"),
    "gtklock": ("gtklock", "gtklock", "gtklock"),
    "swaylock": ("swaylock", "swaylock", "swaylock"),
    "wtype": ("wtype", "wtype", "wtype"),
}


def _install_hint(tools: list[str]) -> str:
    fed = " ".join(PACKAGES[t][0] for t in tools)
    deb = " ".join(PACKAGES[t][1] for t in tools)
    if Path("/run/ostree-booted").exists():
        return f"rpm-ostree install {fed}   (then reboot)"
    if env.which("dnf"):
        return f"sudo dnf install {fed}"
    if env.which("apt"):
        return f"sudo apt install {deb}"
    if env.which("pacman"):
        return f"sudo pacman -S {' '.join(PACKAGES[t][2] for t in tools)}"
    return f"install: {fed}"


def cmd_doctor(args) -> int:
    cfg = config.load()
    caps = _probe_caps(cfg)
    problems = 0
    print("droplet-agent doctor\n")
    if not config.is_set_up(cfg):
        problems += 1
        print("• Not linked to a hub. Run droplet-agent setup: it finds the hub on this network")
        print("  and asks one of your devices to let this computer in. Or, from droplet in this")
        print("  computer's browser (Devices → Link an app): droplet-agent setup --code <code>\n")

    tries = {n: (ok, why) for n, ok, why in input_manager.probe("auto")}
    if not config.enabled(cfg, "input"):
        print("• Input is switched off in the config (caps.input).\n")
    elif tries["portal"][0]:
        print("• Input goes through the desktop's RemoteDesktop portal. The first time the agent")
        print("  runs, the desktop asks you to allow remote control: say yes. If you said no,")
        print("  run: systemctl --user restart droplet-agent  to be asked again.\n")
    elif tries["uinput"][0]:
        print("• Input goes through /dev/uinput (virtual mouse and keyboard).")
        if env.is_wayland() and not env.which("wtype"):
            print("  It types what a US keyboard can. For any character, install wtype:")
            print(f"    {_install_hint(['wtype'])}")
        print()
    elif tries["x11"][0]:
        print("• Input goes through xdotool (X11).\n")
    else:
        problems += 1
        print("• Input: this desktop's portal can't inject input, so it needs /dev/uinput.")
        print(f"  Now: {tries['uinput'][1]}.")
        print("  One-time fix, as root. It lets only your own user's group create virtual input")
        print("  devices, and takes effect without logging out:\n")
        for line in uinput_fix().splitlines():
            print("    " + line)
        print()

    if not env.which("playerctl") or not env.which("wpctl"):
        missing = [t for t in ("playerctl", "wpctl") if not env.which(t)]
        problems += 1
        print(f"• Media control is missing {', '.join(missing)}: {_install_hint(missing)}\n")
    if not caps["lock"][0] and config.enabled(cfg, "lock"):
        problems += 1
        print(f"• Locking: {caps['lock'][1]}.")
        if env.is_wayland():
            print(f"  {_install_hint(['gtklock'])}")
        print()
    if not caps["screenshot"][0] and config.enabled(cfg, "screenshot"):
        problems += 1
        print(f"• Screenshots: no tool found. {_install_hint(['grim'])}\n")
    if not caps["clipboard"][0] and config.enabled(cfg, "clipboard"):
        problems += 1
        tool = ["wl-copy"] if env.is_wayland() else ["xclip"]
        print(f"• Clipboard sync: {caps['clipboard'][1]}. {_install_hint(tool)}\n")
    if "gnome" in env.desktops() and config.enabled(cfg, "clipboard"):
        print("• GNOME doesn't let other programs watch the clipboard (no data-control protocol),")
        print("  so clipboard sync from this computer may not work. Turn it off with")
        print('  "clipboard": false under "caps" in ' + str(config.config_path()) + "\n")

    if (cfg.get("mesh") or {}).get("enabled", True) is not False:
        fix = _firewall_blocks_mesh()
        if fix:
            problems += 1
            print("• The firewall (firewalld) may block other devices from reaching this one directly")
            print("  (the mesh listens on a port from 1739 to 1749). Open them:")
            print(f"    {fix}\n")

    if env.which("systemctl"):
        r = env.run(["systemctl", "--user", "is-active", "--quiet", "graphical-session.target"])
        if r is not None and r.returncode != 0:
            print("• graphical-session.target isn't active, so the service won't start with your")
            print("  desktop. Start the agent from your compositor's autostart instead:")
            print(f"    {data_dir() / 'bin' / 'droplet-agent'} run\n")
    print("Everything looks fine." if not problems else f"{problems} thing(s) to fix above.")
    return 0 if not problems else 1


# --- uninstall ---------------------------------------------------------------

def cmd_uninstall(args) -> int:
    targets = [unit_path(), desktop_file_path(), Path.home() / ".local/bin/droplet-agent",
               config.config_dir(), data_dir()]
    if not args.yes:
        print("This stops the agent and removes:")
        for t in targets:
            print(f"  {t}")
        if input("Go ahead? [y/N] ").strip().lower() not in ("y", "yes"):
            return 1
    if env.which("systemctl"):
        env.run(["systemctl", "--user", "disable", "--now", SERVICE], timeout=30)
    for t in targets:
        try:
            if t.is_symlink() or t.is_file():
                t.unlink()
            elif t.is_dir():
                shutil.rmtree(t)
        except OSError as e:
            print(f"couldn't remove {t}: {e}", file=sys.stderr)
    if env.which("systemctl"):
        env.run(["systemctl", "--user", "daemon-reload"], timeout=30)
    print("Removed. The hub still lists the device; remove its link there if you like.")
    if Path(UDEV_RULE_FILE).exists():
        print(f"The uinput rule stays; remove it with: sudo rm {UDEV_RULE_FILE} "
              "/etc/modules-load.d/droplet-uinput.conf")
    return 0


# --- main --------------------------------------------------------------------

def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="droplet-agent",
                                description="Lets your other devices control this computer through droplet.")
    p.add_argument("--version", action="version", version=f"droplet-agent {__version__}")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("setup", help="link this computer to a droplet hub",
                       description="Link this computer to a droplet hub. With no --hub, looks for hubs on "
                                   "this network. With no --code, joins as a new device that one of your "
                                   "devices allows in (or --pin). On a linked computer with no options, "
                                   "reads the hub's identity again over the tailnet.")
    s.add_argument("--hub", help="the hub: http://<LAN address>:8000 or its tailnet URL "
                                 "(default: look for it on this network)")
    g = s.add_mutually_exclusive_group()
    g.add_argument("--code", help="six-digit link code from droplet on this computer's browser")
    g.add_argument("--name", help="join as a new device with this name (default: this computer's name)")
    s.add_argument("--pin", nargs="?", const="", metavar="PIN",
                   help="get in with the hub's PIN instead of waiting to be allowed (asked for if left out)")
    s.set_defaults(func=cmd_setup)

    r = sub.add_parser("run", help="connect to the hub and act on what arrives")
    r.add_argument("--dry-run", action="store_true",
                   help="log input, media, lock and clipboard actions instead of doing them; fake screenshots")
    r.add_argument("--input", choices=config.INPUT_BACKENDS, help="force an input backend")
    r.add_argument("-v", "--verbose", action="store_true")
    r.set_defaults(func=cmd_run)

    sub.add_parser("status", help="show what works here and why the rest doesn't").set_defaults(func=cmd_status)

    sub.add_parser("peers", help="devices this one talks to directly, and who's nearby").set_defaults(func=cmd_peers)
    pr = sub.add_parser("pair", help="pair directly with another device, or answer one asking",
                        description="With a peer (its name as `peers` shows it, its id, or an address): ask it "
                                    "to pair; both screens show the same code. With nothing: answer the devices "
                                    "asking to pair with this one.")
    pr.add_argument("peer", nargs="?", help="name, id or address[:port] of the device to pair with")
    g = pr.add_mutually_exclusive_group()
    g.add_argument("--accept", nargs="?", const="", metavar="WHICH",
                   help="accept the device asking to pair (its name, code or request, if several ask)")
    g.add_argument("--deny", nargs="?", const="", metavar="WHICH", help="refuse it")
    pr.set_defaults(func=cmd_pair)
    up = sub.add_parser("unpair", help="stop trusting a directly paired device")
    up.add_argument("peer")
    up.set_defaults(func=cmd_unpair)
    tx = sub.add_parser("text", help="send a chat message to a device")
    tx.add_argument("peer")
    tx.add_argument("message")
    tx.set_defaults(func=cmd_text)
    sf = sub.add_parser("send-file", help="send files to a device")
    sf.add_argument("peer")
    sf.add_argument("paths", nargs="+", metavar="path")
    sf.set_defaults(func=cmd_send_file)
    rg = sub.add_parser("ring", help="ring a device to find it")
    rg.add_argument("peer")
    rg.add_argument("--stop", action="store_true", help="stop ringing it")
    rg.set_defaults(func=cmd_ring)
    cp = sub.add_parser("clip", help="send this computer's clipboard to a device")
    cp.add_argument("peer")
    cp.add_argument("--text", help="send this text instead of the clipboard")
    cp.set_defaults(func=cmd_clip)
    sn = sub.add_parser("send", help="send one input, media or cmd message (docs/remote.md), for scripting")
    sn.add_argument("peer")
    sn.add_argument("message", help='JSON, e.g. \'{"t":"input","ev":[{"k":"key","key":"ArrowRight"}]}\'')
    sn.set_defaults(func=cmd_send)
    sub.add_parser("doctor", help="explain how to fix what's missing").set_defaults(func=cmd_doctor)
    u = sub.add_parser("uninstall", help="stop the service and remove the agent")
    u.add_argument("-y", "--yes", action="store_true", help="don't ask")
    u.set_defaults(func=cmd_uninstall)

    args = p.parse_args(argv)
    # setup and status say what matters themselves; `run` sets up real logging
    logging.getLogger("droplet_agent").addHandler(logging.NullHandler())
    try:
        return args.func(args)
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
