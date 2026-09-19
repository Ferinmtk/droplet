"""Drives the real Linux agent for the Android mesh interop tests (MeshInteropTest).

Run with the Python of a venv that has ./agent installed. Each instance is a
throwaway HOME/XDG tree under <root>; the agent runs with --dry-run, so
nothing on this computer is typed, played or copied.

    mesh_agent.py init <root> <name> <runtime dir>   write a config (no mDNS announcing)
    mesh_agent.py run <root>                         exec `droplet-agent run --dry-run -v`
    mesh_agent.py cli <root> <args…>                 a droplet-agent command
    mesh_agent.py ctl <root> <json>                  one control-socket request; prints the JSON answer
    mesh_agent.py fp <root>                          the agent's certificate fingerprint
    mesh_agent.py link <root> <host> <port> <fp> <json lines…>
        a raw link with the agent's own identity (the reference TLS and
        WebSocket code): hello, then each message; prints every message
        received, one JSON per line, until 4 s of quiet. A file offered on
        it is fetched with the reference's download code and acknowledged.
    mesh_agent.py request <root|-> <host> <port> <method> <path> [json]
        one HTTPS request as that identity (- for no certificate); prints
        the status, or "refused: <why>" if TLS refused it
    mesh_agent.py handshake <host> <port>            TLS only; prints the server's fingerprint
"""

import json
import os
import socket
import sys
import time
from pathlib import Path

from droplet_agent.mesh import identity, tlsctx, wslink


def env_for(root: Path) -> dict:
    cfg = json.loads((root / "instance.json").read_text())
    env = dict(os.environ, HOME=str(root / "h"), XDG_CONFIG_HOME=str(root / "h/.config"),
               XDG_DATA_HOME=str(root / "h/.local/share"), XDG_RUNTIME_DIR=cfg["runtime"])
    for k in ("DBUS_SESSION_BUS_ADDRESS", "WAYLAND_DISPLAY", "DISPLAY"):
        env.pop(k, None)   # nothing reaches the real desktop, even by mistake
    return env


def ident(root: Path):
    return identity.load_or_create(root / "h/.config/droplet-agent/mesh")


def main():
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "handshake":
        host, port = args[0], int(args[1])
        ctx = tlsctx.client_context(None, None)
        with ctx.wrap_socket(socket.create_connection((host, port), timeout=5)) as s:
            print(tlsctx.peer_fingerprint(s))
        return
    root = Path(args[0]) if args[0] != "-" else None
    if cmd == "init":
        name, runtime = args[1], args[2]
        Path(runtime).mkdir(parents=True, exist_ok=True)
        os.chmod(runtime, 0o700)
        (root / "h/.config/droplet-agent").mkdir(parents=True, exist_ok=True)
        (root / "instance.json").write_text(json.dumps({"runtime": runtime}))
        cfg = root / "h/.config/droplet-agent/config.json"
        if not cfg.exists():
            cfg.write_text(json.dumps({"device": {"id": "", "name": name}, "mesh": {"announce": False}}))
        return
    if cmd == "run":
        os.execvpe(sys.executable, [sys.executable, "-m", "droplet_agent", "run", "--dry-run", "-v"], env_for(root))
    if cmd == "cli":
        os.execvpe(sys.executable, [sys.executable, "-m", "droplet_agent", *args[1:]], env_for(root))
    if cmd == "ctl":
        from droplet_agent.mesh import control
        os.environ["XDG_RUNTIME_DIR"] = env_for(root)["XDG_RUNTIME_DIR"]
        print(json.dumps(control.call(json.loads(args[1]), timeout=60)))
        return
    if cmd == "fp":
        print(ident(root).fp)
        return
    if cmd == "request":
        host, port, method, path = args[1], int(args[2]), args[3], args[4]
        body = args[5].encode() if len(args) > 5 else b""
        me = ident(root) if root else None
        try:
            s = tlsctx.client_context(me, None).wrap_socket(socket.create_connection((host, port), timeout=5))
            s.sendall(f"{method} {path} HTTP/1.1\r\nHost: x\r\nContent-Length: {len(body)}\r\n"
                      f"Connection: close\r\n\r\n".encode() + body)
            out = b""
            while True:
                chunk = s.recv(65536)
                if not chunk:
                    break
                out += chunk
            print(int(out.split(b" ", 2)[1]) if out else "refused: closed with no answer")
        except (OSError, ValueError, IndexError) as e:
            print(f"refused: {e}")
        return
    if cmd == "link":
        host, port, fp = args[1], int(args[2]), args[3]
        me = ident(root)
        raw = socket.create_connection((host, port), timeout=5)
        tls = tlsctx.client_context(me, fp).wrap_socket(raw)
        proto, frames = wslink.client_handshake(tls, host, port, "mesh-interop-test")
        got = []

        def on_message(link, msg):
            got.append(msg)
            print(json.dumps(msg), flush=True)
            if msg.get("t") == "offer":
                # a file back (files.get): fetched with the reference's own download code, then acknowledged
                import threading
                from droplet_agent.mesh import files

                def fetch():
                    try:
                        oid, name, size, _ = files.check_offer(msg)
                        path = files.download(me, fp, [(host, port)], oid, name, size, root / "link-downloads")
                        link.send({"t": "ack", "id": oid})
                        print(json.dumps({"t": "saved", "path": str(path)}), flush=True)
                    except Exception as e:
                        print(json.dumps({"t": "download-failed", "error": str(e)}), flush=True)
                threading.Thread(target=fetch, daemon=True).start()

        link = wslink.Link(tls, proto, fp=fp, address=host, outbound=True, on_message=on_message,
                           on_close=lambda link: None, pending_frames=frames)
        link.start()
        # say who the running agent is, exactly: the phone records what a hello says about the peer
        from droplet_agent.mesh import control
        os.environ["XDG_RUNTIME_DIR"] = env_for(root)["XDG_RUNTIME_DIR"]
        st = control.call({"cmd": "status"}, timeout=10)
        link.send({"t": "hello", "id": st["id"], "name": st["name"], "caps": ["input", "media"], "os": "linux", "v": 1,
                   "port": st["port"]})
        for line in args[4:]:
            link.send(json.loads(line))
        last = len(got)
        quiet = time.monotonic()
        end = time.monotonic() + 60
        while time.monotonic() < end:
            time.sleep(0.2)
            if len(got) != last:
                last, quiet = len(got), time.monotonic()
            elif time.monotonic() - quiet > 4:
                break
        link.close()
        return
    raise SystemExit(f"unknown command {cmd}")


if __name__ == "__main__":
    main()
