#!/bin/sh
# Installs (or upgrades) the droplet agent for the current user. No sudo.
#
#   curl -fsSL https://<hub>/agent/install.sh | sh -s -- --code 123456
#
# The hub fills in its own address below when it serves this script, and
# the agent itself is downloaded from that hub. Only the agent's two small
# dependencies (websockets, jeepney) come from PyPI.
#
# Options:
#   --code 123456   link to this computer's existing droplet device (from its
#                   browser: Devices → Link an app)
#   --name NAME     or register this computer as a new device called NAME
#   --hub URL       the hub, if this copy of the script didn't come from it
#   --no-service    don't install the systemd user service
#
# Re-running it upgrades the agent and keeps the settings.

set -eu

DROPLET_HUB=''  # filled in by the hub that serves this script
DROPLET_WHEEL=''  # likewise

say() { printf '%s\n' "$*"; }
usage() {
    say "usage: install.sh [--code 123456 | --name NAME] [--hub URL] [--no-service]"
    say "  --code     link to this computer's droplet device (browser: Devices, Link an app)"
    say "  --name     or register this computer as a new device"
    say "  --hub      the hub, when this script didn't come from it"
    say "  --no-service   don't install the systemd user service"
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

main() {
    hub="$DROPLET_HUB" code="" name="" service=1
    # a copy that wasn't served by a hub gets the hub's own copy and runs that
    prev=""
    for a in "$@"; do
        [ "$prev" = "--hub" ] && hub="$a"
        prev="$a"
    done
    hub="${hub%/}"
    [ -n "$hub" ] || die "which hub? Add --hub https://<your hub>"
    if [ -z "$DROPLET_WHEEL" ]; then
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
            --code) [ $# -ge 2 ] || die "--code needs the six-digit code"; code="$2"; shift 2 ;;
            --name) [ $# -ge 2 ] || die "--name needs a name"; name="$2"; shift 2 ;;
            --no-service) service=0; shift ;;
            -h|--help) usage; exit 0 ;;
            *) die "unknown option $1" ;;
        esac
    done
    [ -z "$code" ] || [ -z "$name" ] || die "use --code or --name, not both"

    # --- Python and a venv --------------------------------------------------
    py=""
    for c in python3 python; do
        if command -v "$c" >/dev/null 2>&1 &&
            "$c" -c 'import sys; sys.exit(sys.version_info < (3, 9))' 2>/dev/null; then
            py="$c"; break
        fi
    done
    [ -n "$py" ] || die "needs Python 3.9 or newer (python3)"
    if ! "$py" -c 'import venv, ensurepip' 2>/dev/null; then
        die "Python can't make a virtual environment here. On Debian/Ubuntu: sudo apt install python3-venv"
    fi

    data="${XDG_DATA_HOME:-$HOME/.local/share}/droplet-agent"
    config="${XDG_CONFIG_HOME:-$HOME/.config}"
    if ! "$data/bin/python" -c 'import sys' 2>/dev/null; then
        # missing, or broken by a Python upgrade: start fresh (settings live elsewhere)
        rm -rf "$data"
        say "Creating $data"
        "$py" -m venv "$data" </dev/null
    fi

    tmpdir="$(mktemp -d)"
    trap 'rm -rf "$tmpdir"' EXIT
    say "Downloading the agent from $hub"
    fetch "$hub/agent/$DROPLET_WHEEL" "$tmpdir/$DROPLET_WHEEL" || die "can't download $hub/agent/$DROPLET_WHEEL"
    say "Installing (its dependencies come from PyPI)"
    pip() { "$data/bin/python" -m pip --disable-pip-version-check --quiet "$@" </dev/null; }
    pip install --upgrade "$tmpdir/$DROPLET_WHEEL"
    # same version number, newer code: install it anyway
    pip install --force-reinstall --no-deps "$tmpdir/$DROPLET_WHEEL"
    agent="$data/bin/droplet-agent"

    mkdir -p "$HOME/.local/bin"
    ln -sf "$agent" "$HOME/.local/bin/droplet-agent"

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

    # --- link to the hub -----------------------------------------------------
    if [ -n "$code" ]; then
        "$agent" setup --hub "$hub" --code "$code" </dev/null
    elif [ -n "$name" ]; then
        "$agent" setup --hub "$hub" --name "$name" </dev/null
    elif [ -f "$config/droplet-agent/config.json" ]; then
        say "Keeping the existing link ($config/droplet-agent/config.json)"
    else
        die "not linked yet: re-run with --code 123456 (from droplet in this computer's browser: Devices → Link an app)"
    fi

    # --- the service ---------------------------------------------------------
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

    say ""
    "$agent" doctor </dev/null || true
    say ""
    say "Done. Check it any time with: droplet-agent status"
}

main "$@"
