#!/bin/sh
# Installs (or upgrades) the droplet agent for the current user. No sudo.
#
# No hub needed: the agent comes from droplet's latest GitHub release, and
# your devices pair with it directly.
#
#   curl -fsSL https://github.com/Ferinmtk/droplet/releases/latest/download/install-agent.sh | sh
#
# With a droplet hub (optional), the hub serves this script too, fills in its
# own address below, and the agent is downloaded from that hub:
#
#   curl -fsSL http://<hub's LAN address>:8000/agent/install.sh | sh -s -- --code 123456
#   curl -fsSL https://<hub's tailnet name>/agent/install.sh | sh -s -- --code 123456
#
# Either way only the agent's dependencies (websockets, jeepney, zeroconf,
# cryptography, the iPhone link's aiortc and the few small packages it needs, and
# for Droplet's window PySide6) come from PyPI. A release's wheel is checked
# against the release's SHA256SUMS.txt.
#
# On a Mac it's the same command, in Terminal. It uses the Mac's Python 3
# (Xcode's Command Line Tools, python.org or Homebrew), runs the agent as a
# LaunchAgent instead of a systemd service, puts droplet in the menu bar, and
# Droplet.app in ~/Applications (Launchpad and Spotlight).
#
# Fetched over the LAN's plain http, the agent reads the hub's certificate
# fingerprint from it, then switches to the hub's LAN HTTPS with that
# certificate pinned before it sends anything that matters.
#
# Options:
#   --code 123456   link to this computer's existing droplet device (from its
#                   browser: Devices → Link an app)
#   --name NAME     or join as a new device called NAME (default: this
#                   computer's name): one of your devices allows it in
#   --pin PIN       join with the hub's PIN instead of waiting to be allowed
#   --hub URL       the hub, if this copy of the script didn't come from it
#                   (--code, --name and --pin need a hub; without one,
#                   pair with your devices directly)
#   --release TAG   install the agent from this GitHub release (v1.2.0)
#                   instead of the latest one
#   --wheel FILE    install this agent wheel (a path or a URL)
#   --no-service    don't install the systemd user service (on a Mac: don't
#                   load the LaunchAgent)
#   --no-tray       don't put droplet in the system tray (on a Mac: the menu
#                   bar, and Droplet.app)
#   --no-app        don't install Droplet's window (PySide6, about 80 MB from
#                   PyPI; installed only from a desktop session, and always on a Mac)
#
# Re-running it upgrades the agent and keeps the settings.

set -eu

DROPLET_HUB=''  # filled in by the hub that serves this script
DROPLET_WHEEL=''  # likewise
DROPLET_RELEASE=''  # filled in by the GitHub release that ships this script
# where releases are published; the variable is for testing
RELEASES="${DROPLET_RELEASES_URL:-https://github.com/Ferinmtk/droplet/releases}"
RELEASE_WHEEL='droplet-agent.whl'  # each release's wheel, under a name that doesn't change
AIORTC='aiortc>=1.9'  # the iPhone link; installed with --no-deps (droplet_agent/webrtc/deps.py)

say() { printf '%s\n' "$*"; }
usage() {
    say "usage: install.sh [--hub URL [--code 123456 | --name NAME] [--pin PIN]]"
    say "                  [--release TAG | --wheel FILE] [--no-service] [--no-tray] [--no-app]"
    say "  Without a hub, the agent comes from droplet's latest GitHub release and"
    say "  pairs with your devices directly."
    say "  --hub      a droplet hub to link to (optional)"
    say "  --code     link to this computer's droplet device (browser: Devices, Link an app)"
    say "  --name     or join as a new device (default: this computer's name)"
    say "  --pin      join with the hub's PIN instead of waiting to be allowed"
    say "  --release  install from this GitHub release (a tag like v1.2.0), not the latest"
    say "  --wheel    install this agent wheel (a path or a URL)"
    say "  --no-service   don't install the systemd user service"
    say "  --no-tray      don't put droplet in the system tray"
    say "  --no-app       don't install Droplet's window (PySide6, about 80 MB)"
}
die() { printf 'droplet-agent install: %s\n' "$*" >&2; exit 1; }

fetch() {  # fetch URL FILE
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --retry 2 -o "$2" "$1"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$2" "$1"
    else
        die "needs curl or wget"
    fi
}

# Python 3.9 or newer on a Mac. python.org's first, then Apple's (the Command Line Tools'), then
# any other, newest first: the first two are signed by their makers, so the permissions macOS
# gives the agent (Accessibility, for remote control) last across their updates. Homebrew's
# Python is signed afresh by each upgrade, and macOS then asks again.
py_ok() {
    "$1" -c 'import sys; sys.exit(sys.version_info < (3, 9))' 2>/dev/null </dev/null
}
mac_python() {
    c=/Library/Frameworks/Python.framework/Versions/Current/bin/python3
    if [ -x "$c" ] && py_ok "$c"; then printf '%s\n' "$c"; return; fi
    # only with the tools there: without them, /usr/bin/python3 offers to install them instead
    if xcode-select -p >/dev/null 2>&1 && py_ok /usr/bin/python3; then
        printf '%s\n' /usr/bin/python3; return
    fi
    best="" best_v=0
    for c in python3.14 python3.13 python3.12 python3.11 python3.10 \
        /opt/homebrew/bin/python3 /usr/local/bin/python3 python3; do
        command -v "$c" >/dev/null 2>&1 || continue
        [ "$(command -v "$c")" != /usr/bin/python3 ] || continue
        v="$("$c" -c 'import sys; print(sys.version_info[0] * 100 + sys.version_info[1])' 2>/dev/null </dev/null)" ||
            continue
        case "$v" in ''|*[!0-9]*) continue ;; esac
        if [ "$v" -ge 309 ] && [ "$v" -gt "$best_v" ]; then
            best="$c" best_v="$v"
        fi
    done
    printf '%s\n' "$best"
}

# the desktop entries (Linux): the permission dialog's name, and the tray at login
linux_entries() {
    # the desktop's permission dialog names the app from this file
    apps="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
    mkdir -p "$apps"
    cat >"$apps/io.github.ferinmtk.DropletAgent.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=droplet agent
Comment=Lets your other devices control this computer through droplet
Exec="$agent" run
Icon=input-mouse
NoDisplay=true
EOF

    # the tray icon starts with the desktop session (the service can't show one)
    autostart="$config/autostart/io.github.ferinmtk.DropletAgent.Tray.desktop"
    if [ "$tray" = 1 ]; then
        mkdir -p "$config/autostart"
        cat >"$autostart" <<EOF
[Desktop Entry]
Type=Application
Name=droplet
Comment=droplet in the system tray: send to your devices, answer pairing requests
Exec="$agent" tray
Icon=input-mouse
Terminal=false
X-GNOME-Autostart-enabled=true
EOF
    else
        rm -f "$autostart"
    fi
}

# the systemd user service and the tray (Linux)
linux_service() {
    unit_dir="$config/systemd/user"
    mkdir -p "$unit_dir"
    cat >"$unit_dir/droplet-agent.service" <<EOF
[Unit]
Description=droplet agent: lets your other devices control this computer
Documentation=https://github.com/Ferinmtk/droplet
# started with the desktop, so it has WAYLAND_DISPLAY and the session bus
After=graphical-session.target
PartOf=graphical-session.target

[Service]
Type=simple
ExecStart="$agent" run
Restart=on-failure
RestartSec=5

[Install]
WantedBy=graphical-session.target
EOF

    if [ "$service" = 1 ] && command -v systemctl >/dev/null 2>&1 &&
        systemctl --user show-environment >/dev/null 2>&1; then
        systemctl --user daemon-reload
        systemctl --user enable droplet-agent.service >/dev/null 2>&1 ||
            say "Couldn't enable the service; start it with: systemctl --user enable --now droplet-agent"
        if systemctl --user is-active --quiet graphical-session.target; then
            systemctl --user restart droplet-agent.service
            say "The agent is running (systemctl --user status droplet-agent)."
        else
            say "The service starts with your next desktop session."
        fi
    else
        say "Service not installed. Start the agent with: $agent run"
    fi

    # Droplet in the app menu: opens its window (or, without one, says where the tray is)
    if [ "$tray" = 1 ]; then
        "$agent" open --install >/dev/null 2>&1 </dev/null || true
    fi

    # start (or restart, after an upgrade) the tray in this desktop session
    if [ "$tray" = 1 ] && [ -n "${WAYLAND_DISPLAY:-}${DISPLAY:-}" ]; then
        nohup "$agent" tray >/dev/null 2>&1 </dev/null &
        say "droplet is in the system tray, and starts there with your desktop."
    fi
}

main() {
    hub="$DROPLET_HUB" code="" name="" pin="" service=1 tray=1 app=1
    mac=0
    [ "$(uname -s 2>/dev/null)" = Darwin ] && mac=1
    release="$DROPLET_RELEASE" wheel="" source=""
    prev=""
    for a in "$@"; do
        case "$prev" in
            --hub) hub="$a" ;;
            --release|--wheel) source="$a" ;;
        esac
        prev="$a"
    done
    hub="${hub%/}"
    # a copy that wasn't served by a hub gets the hub's own copy and runs that,
    # so the agent matches the hub (unless --release or --wheel says otherwise)
    if [ -n "$hub" ] && [ -z "$DROPLET_WHEEL" ] && [ -z "$source" ]; then
        tmp="$(mktemp)"
        fetch "$hub/agent/install.sh" "$tmp" || die "can't download the installer from $hub"
        grep -q "^DROPLET_WHEEL='droplet_agent-" "$tmp" || die "$hub didn't serve a usable installer"
        status=0
        sh "$tmp" "$@" </dev/null || status=$?
        rm -f "$tmp"
        exit "$status"
    fi

    while [ $# -gt 0 ]; do
        case "$1" in
            --hub) [ $# -ge 2 ] || die "--hub needs a URL"; shift 2 ;;
            --release) [ $# -ge 2 ] || die "--release needs a tag, like v1.2.0"; release="$2"; shift 2 ;;
            --wheel) [ $# -ge 2 ] || die "--wheel needs a file or URL"; wheel="$2"; shift 2 ;;
            --code) [ $# -ge 2 ] || die "--code needs the six-digit code"; code="$2"; shift 2 ;;
            --name) [ $# -ge 2 ] || die "--name needs a name"; name="$2"; shift 2 ;;
            --pin) [ $# -ge 2 ] || die "--pin needs the PIN"; pin="$2"; shift 2 ;;
            --no-service) service=0; shift ;;
            --no-tray) tray=0; shift ;;
            --no-app) app=0; shift ;;
            -h|--help) usage; exit 0 ;;
            *) die "unknown option $1" ;;
        esac
    done
    [ -z "$code" ] || [ -z "$name" ] || die "use --code or --name, not both"
    [ -z "$code" ] || [ -z "$pin" ] || die "use --code or --pin, not both"
    if [ -z "$hub" ] && [ -n "$code$name$pin" ]; then
        die "--code, --name and --pin link to a hub: add --hub https://<your hub>, or leave them out and pair with your devices directly"
    fi

    # --- Python and a venv --------------------------------------------------
    py=""
    if [ "$mac" = 1 ]; then
        # DROPLET_PYTHON: use this Python instead of the one picked
        py="${DROPLET_PYTHON:-$(mac_python)}"
        if [ -z "$py" ]; then
            # this asks macOS to offer the Command Line Tools (a window opens), which bring Python 3
            /usr/bin/python3 -c '' >/dev/null 2>&1 </dev/null || true
            die "needs Python 3, which this Mac doesn't have yet. Install Apple's Command Line Tools (a window may have opened offering them; or run: xcode-select --install), or Python from https://www.python.org/downloads/macos/. Then run this command again."
        fi
        say "Using $py (Python $("$py" -c 'import sys; print(sys.version.split()[0])' 2>/dev/null </dev/null))"
    else
        for c in python3 python; do
            if command -v "$c" >/dev/null 2>&1 &&
                "$c" -c 'import sys; sys.exit(sys.version_info < (3, 9))' 2>/dev/null; then
                py="$c"; break
            fi
        done
        [ -n "$py" ] || die "needs Python 3.9 or newer (python3)"
    fi
    if ! "$py" -c 'import venv, ensurepip' 2>/dev/null; then
        [ "$mac" = 0 ] || die "$py can't make a virtual environment. Install Python from https://www.python.org/downloads/macos/ and run this again."
        die "Python can't make a virtual environment here. On Debian/Ubuntu: sudo apt install python3-venv"
    fi

    # --- the agent itself ---------------------------------------------------
    tmpdir="$(mktemp -d)"
    trap 'rm -rf "$tmpdir"' EXIT
    if [ -n "$wheel" ]; then
        case "$wheel" in
            http://*|https://*|file://*)
                say "Downloading the agent from $wheel"
                fetch "$wheel" "$tmpdir/download.whl" || die "can't download $wheel" ;;
            *)
                [ -f "$wheel" ] || die "no such file: $wheel"
                cp "$wheel" "$tmpdir/download.whl" ;;
        esac
    elif [ -n "$hub" ] && [ -n "$DROPLET_WHEEL" ] && [ -z "$release" ]; then
        say "Downloading the agent from $hub"
        fetch "$hub/agent/$DROPLET_WHEEL" "$tmpdir/download.whl" || die "can't download $hub/agent/$DROPLET_WHEEL"
    else
        if [ -n "$release" ]; then
            from="$RELEASES/download/$release"
        else
            from="$RELEASES/latest/download"
        fi
        say "Downloading the agent from $from"
        fetch "$from/$RELEASE_WHEEL" "$tmpdir/download.whl" ||
            die "can't download $from/$RELEASE_WHEEL (does that release have the Linux agent?)"
        # the release's checksums, when it has them: a wheel that doesn't match is refused
        if fetch "$from/SHA256SUMS.txt" "$tmpdir/SHA256SUMS.txt" 2>/dev/null; then
            want="$(awk -v f="$RELEASE_WHEEL" '$2 == f || $2 == "*" f { print $1; exit }' "$tmpdir/SHA256SUMS.txt")"
            if [ -n "$want" ]; then
                got="$("$py" -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' \
                    "$tmpdir/download.whl" </dev/null)"
                [ "$got" = "$want" ] ||
                    die "the download doesn't match the release's SHA256SUMS.txt (got $got, expected $want). Nothing was installed."
                say "Checked its SHA-256 against the release's SHA256SUMS.txt"
            else
                say "The release's SHA256SUMS.txt doesn't list $RELEASE_WHEEL, so the download wasn't checked"
            fi
        else
            say "The release has no SHA256SUMS.txt, so the download wasn't checked"
        fi
    fi
    # pip wants a wheel's real file name (name-version-tags.whl): read it from inside
    whl="$("$py" -c '
import sys, zipfile
try:
    names = zipfile.ZipFile(sys.argv[1]).namelist()
except Exception:
    sys.exit(1)
for n in names:
    d = n.split("/")[0]
    if d.startswith("droplet_agent-") and d.endswith(".dist-info") and n == d + "/METADATA":
        print(d[: -len(".dist-info")] + "-py3-none-any.whl")
        sys.exit(0)
sys.exit(1)
' "$tmpdir/download.whl" </dev/null)" || die "that download isn't the droplet agent"
    mv "$tmpdir/download.whl" "$tmpdir/$whl"

    data="${XDG_DATA_HOME:-$HOME/.local/share}/droplet-agent"
    config="${XDG_CONFIG_HOME:-$HOME/.config}"
    if ! "$data/bin/python" -c 'import sys' 2>/dev/null; then
        # missing, or broken by a Python upgrade: start fresh (settings live elsewhere)
        rm -rf "$data"
        say "Creating $data"
        "$py" -m venv "$data" </dev/null
    fi

    say "Installing (its dependencies come from PyPI)"
    pip() { "$data/bin/python" -m pip --disable-pip-version-check --quiet "$@" </dev/null; }
    # --prefer-binary: an older release with a wheel over a newer one that would need a compiler
    pip install --upgrade --prefer-binary "$tmpdir/$whl"
    # same version number, newer code: install it anyway
    pip install --force-reinstall --no-deps "$tmpdir/$whl"
    agent="$data/bin/droplet-agent"

    # The iPhone link (docs/iphone.md). The wheel brought what it needs but aiortc itself, whose
    # own requirements would add PyAV (FFmpeg, about 100 MB) for video it never uses: --no-deps.
    # Not fatal: everything else works without it, and droplet-agent doctor offers it again.
    say "Adding the iPhone link (aiortc, without the video codecs it would bring)"
    if pip install --prefer-binary --no-deps "$AIORTC" &&
        "$data/bin/python" -c 'from droplet_agent.webrtc import deps; deps.load()' 2>/dev/null </dev/null; then
        say "iPhones can pair with this computer: Droplet → Pair an iPhone."
    else
        say "Couldn't add the iPhone link; everything else works without it. Later, droplet-agent doctor installs it."
    fi

    # Droplet's window (Qt, through PySide6): only where there's a desktop to show it on.
    # Without it the agent, the tray and the commands work the same.
    if [ "$mac" = 1 ] && [ "$app" = 1 ]; then
        # a Mac always has a desktop, and its menu bar icon is Qt too
        say "Installing Droplet's window and menu bar icon (PySide6, about 80 MB from PyPI)"
        if pip install "${tmpdir}/${whl}[app]" &&
            "$data/bin/python" -c 'import PySide6.QtWidgets' 2>/dev/null </dev/null; then
            say "Droplet's window is installed: open Droplet from Launchpad or Spotlight."
        else
            say "Couldn't install Droplet's window ($py may be too old for PySide6); the agent and the"
            say "commands work without it, but there's no menu bar icon. Python 3.10 or newer from"
            say "https://www.python.org/downloads/macos/ fixes it: install it, then run this again."
        fi
    elif [ "$app" = 1 ] && [ -n "${WAYLAND_DISPLAY:-}${DISPLAY:-}" ]; then
        say "Installing Droplet's window (PySide6, about 80 MB from PyPI)"
        if pip install "${tmpdir}/${whl}[app]" &&
            "$data/bin/python" -c 'import PySide6.QtWidgets' 2>/dev/null </dev/null; then
            say "Droplet's window is installed: open Droplet from your app menu."
        else
            say "Couldn't install Droplet's window; the tray and the commands work without it."
            say "To try again: $data/bin/python -m pip install 'droplet-agent[app]'"
        fi
    fi

    mkdir -p "$HOME/.local/bin"
    ln -sf "$agent" "$HOME/.local/bin/droplet-agent"
    if [ "$mac" = 1 ]; then
        # a Mac's Terminal doesn't look in ~/.local/bin: add it for zsh, the Mac's shell
        case ":${PATH:-}:" in
            *":$HOME/.local/bin:"*) ;;
            *)
                if ! grep -qs '.local/bin' "$HOME/.zprofile"; then
                    # shellcheck disable=SC2016  # written as is, for zsh to expand
                    printf '\n# droplet-agent\nexport PATH="$HOME/.local/bin:$PATH"\n' >>"$HOME/.zprofile"
                    say "Added ~/.local/bin to your PATH in ~/.zprofile (new Terminal windows find droplet-agent)."
                fi ;;
        esac
    fi

    if [ "$mac" = 0 ]; then
        linux_entries
    fi

    # --- link to the hub (only with one) ---------------------------------------
    # a plain http:// LAN hub is fine here: setup reads the hub's certificate
    # fingerprint from it and carries on over the pinned LAN HTTPS
    linked=0
    if "$data/bin/python" -c \
        'import sys; from droplet_agent import config; sys.exit(not config.is_set_up(config.load()))' \
        2>/dev/null </dev/null; then
        linked=1
    fi
    if [ -z "$hub" ]; then
        if [ "$linked" = 1 ]; then
            say "Keeping the existing link ($config/droplet-agent/config.json)"
        else
            say "No hub: this computer pairs with your devices directly."
        fi
    elif [ -n "$code" ]; then
        "$agent" setup --hub "$hub" --code "$code" </dev/null
    elif [ -n "$name" ] || [ -n "$pin" ] || [ "$linked" = 0 ]; then
        # not linked yet (or still waiting to be let in): join as a new
        # device, and wait for one of yours to allow it (or use the PIN)
        set -- --hub "$hub"
        [ -z "$name" ] || set -- "$@" --name "$name"
        [ -z "$pin" ] || set -- "$@" --pin "$pin"
        "$agent" setup "$@" </dev/null
    else
        say "Keeping the existing link ($config/droplet-agent/config.json)"
    fi

    # --- the service ---------------------------------------------------------
    if [ "$mac" = 1 ]; then
        # LaunchAgents for the agent and the menu bar icon, and Droplet.app
        set --
        [ "$service" = 1 ] || set -- "$@" --no-service
        [ "$tray" = 1 ] || set -- "$@" --no-menu
        "$data/bin/python" -m droplet_agent.macos install "$@" </dev/null ||
            say "Couldn't set up the agent's LaunchAgent. Start it with: $agent run"
    else
        linux_service
    fi

    say ""
    "$agent" doctor </dev/null || true
    say ""
    say "Done. Check it any time with: droplet-agent status"
    if [ -z "$hub" ] && [ "$linked" = 0 ]; then
        say ""
        say "Pair with your phone: open droplet on the phone, tap Pair a device and pick"
        if [ "$mac" = 1 ]; then
            say "this Mac, then accept here (the menu bar icon asks), or run: droplet-agent pair"
            say ""
            say "To control this Mac from your phone, allow it once: System Settings, Privacy &"
            say "Security, Accessibility: switch on Python. (droplet-agent doctor says what else.)"
        else
            say "this computer, then accept here (the tray asks), or run: droplet-agent pair"
        fi
        say "Your devices: droplet-agent peers"
    fi
}

main "$@"
