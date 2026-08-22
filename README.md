# droplet 💧

LAN file drop. Run it on any device on your network — every other device
sends/fetches files through its web page. No cloud, no accounts, no installs
on the other devices.

## Run (venv)

```bash
cd ~/curiosity/projects/droplet
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
sudo firewall-cmd --add-port=8000/tcp   # Fedora: open the port (until reboot)
python app.py
```

Scan the QR code in the terminal with your phone, or open the printed URL.
mDNS-capable devices (iPhone, Mac, Linux, Windows 10+) can also use
`http://droplet.local:8000`. Older Android browsers can't resolve `.local` —
use the IP/QR there.

To open the firewall port permanently:

```bash
sudo firewall-cmd --permanent --add-port=8000/tcp && sudo firewall-cmd --reload
```

## Run (Docker)

```bash
docker compose up -d --build
```

Files land in `./data/received` and `./data/shared`. Uses host networking so
mDNS works (Linux only).

## Config (env vars)

| Var | Default | Meaning |
|---|---|---|
| `DROPLET_PORT` | `8000` | listen port |
| `DROPLET_HOST` | `0.0.0.0` | bind address |
| `DROPLET_PIN` | *(off)* | require this PIN before access |
| `DROPLET_HTTPS` | *(off)* | `1` = HTTPS with a persistent self-signed cert (browser will warn once — accept it) |
| `DROPLET_NAME` | `droplet` | mDNS hostname (`<name>.local`) |
| `DROPLET_HOME` | app dir | where `received/`, `shared/`, `certs/` live |
| `DROPLET_MAX_MB` | `1024` | max upload size |

## How files flow

- **Send to the hub:** open the page on any device, drop/pick files → they land in `received/` on the hub.
- **Fetch from the hub:** anything in `shared/` (or `received/`) is listed on the page with a download link.
- **Phone → phone:** phone A uploads, phone B downloads from the list. The hub relays.

## Phone as the hub (Termux, Android)

```bash
pkg install python
pip install -r requirements.txt
python app.py
```

## Notes

- Flask's built-in server — fine for home LAN, not for the internet.
- Repeated filenames don't overwrite: `shot.png`, `shot-1.png`, …
