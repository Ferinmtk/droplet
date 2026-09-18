"""droplet-agent: setup, run, status, doctor, uninstall."""

from __future__ import annotations

import argparse
import getpass
import logging
import os
import shutil
import signal
import sys
import threading
from pathlib import Path

from . import APP_ID, __version__, clip, config, env, hub, lock, mediastate, screenshot
from .inject import manager as input_manager

SERVICE = "droplet-agent.service"


def data_dir() -> Path:
    return Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share") / "droplet-agent"


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

def cmd_setup(args) -> int:
    try:
        url = hub.normalize(args.hub)
        if args.code:
            code = "".join(ch for ch in args.code if ch.isdigit())
            if len(code) != 6:
                print("The link code is six digits, from droplet on this computer's browser "
                      "(Devices → Link an app).", file=sys.stderr)
                return 2
            got = hub.link(url, code)
        else:
            name = " ".join(args.name.split())[:40]
            if not name:
                print("Give the device a name with --name.", file=sys.stderr)
                return 2
            got = hub.register(url, name)
    except hub.HubError as e:
        print(f"Setup failed: {e}", file=sys.stderr)
        return 1
    cfg = config.load()
    cfg.update(hub=url, token=got["token"], device={"id": got["id"], "name": got["name"]})
    path = config.save(cfg)
    print(f"Linked to {url} as \"{got['name']}\". Settings saved in {path}")
    return 0


# --- run ---------------------------------------------------------------------

def cmd_run(args) -> int:
    _setup_logging(args.verbose)
    log = logging.getLogger("droplet_agent")
    cfg = config.load()
    if not config.is_set_up(cfg):
        print("Not set up yet. Run: droplet-agent setup --hub https://<hub> --code 123456", file=sys.stderr)
        return 2
    from .agent import Agent
    from .connection import Connection

    agent = Agent(cfg, dry_run=args.dry_run, input_backend=args.input)
    conn = Connection(agent, cfg["hub"], cfg["token"])
    stop = agent.stop

    def bye(*_):
        stop.set()
        conn.reconnect()
    signal.signal(signal.SIGTERM, bye)
    signal.signal(signal.SIGINT, bye)

    log.info("droplet-agent %s for %s, hub %s%s", __version__, cfg["device"].get("name") or "?",
             cfg["hub"], " (dry run)" if args.dry_run else "")
    agent.start()
    t = threading.Thread(target=conn.run, args=(stop,), name="connection", daemon=True)
    t.start()
    while t.is_alive():
        t.join(0.5)
    agent.close()
    log.info("stopped")
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
    if config.is_set_up(cfg):
        print(f"hub:      {cfg['hub']}")
        print(f"device:   {cfg['device'].get('name')} ({cfg['device'].get('id')})")
        try:
            dev = hub.me(cfg["hub"], cfg["token"])
            print("token:    " + ("accepted by the hub" if dev else "NOT known to the hub; run setup again"))
        except hub.HubError as e:
            print(f"token:    can't check ({e})")
    else:
        print("not set up: run droplet-agent setup --hub https://<hub> --code 123456")
    print(f"service:  {_service_state()}")
    print(f"desktop:  {', '.join(sorted(env.desktops())) or 'unknown'}"
          f" ({'Wayland' if env.is_wayland() else 'X11' if env.x11_display() else 'no display'})")
    print()
    for cap, (ok, why) in _probe_caps(cfg).items():
        print(f"  {'✓' if ok else '✗'} {cap:<10} {why}")
    return 0


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
        print("• Not linked to a hub. On this computer's browser, open droplet → Devices →")
        print("  Link an app, then run: droplet-agent setup --hub https://<hub> --code <code>\n")

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

    s = sub.add_parser("setup", help="link this computer to a droplet hub")
    s.add_argument("--hub", required=True, help="the hub's URL, e.g. https://t15.tail1234.ts.net")
    g = s.add_mutually_exclusive_group(required=True)
    g.add_argument("--code", help="six-digit link code from droplet on this computer's browser")
    g.add_argument("--name", help="register as a new device with this name instead")
    s.set_defaults(func=cmd_setup)

    r = sub.add_parser("run", help="connect to the hub and act on what arrives")
    r.add_argument("--dry-run", action="store_true",
                   help="log input, media, lock and clipboard actions instead of doing them; fake screenshots")
    r.add_argument("--input", choices=config.INPUT_BACKENDS, help="force an input backend")
    r.add_argument("-v", "--verbose", action="store_true")
    r.set_defaults(func=cmd_run)

    sub.add_parser("status", help="show what works here and why the rest doesn't").set_defaults(func=cmd_status)
    sub.add_parser("doctor", help="explain how to fix what's missing").set_defaults(func=cmd_doctor)
    u = sub.add_parser("uninstall", help="stop the service and remove the agent")
    u.add_argument("-y", "--yes", action="store_true", help="don't ask")
    u.set_defaults(func=cmd_uninstall)

    args = p.parse_args(argv)
    try:
        return args.func(args)
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
