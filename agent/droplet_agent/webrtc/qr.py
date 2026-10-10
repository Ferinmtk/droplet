"""The QR code an iPhone scans to pair: where this computer is, and what to pin.

It's a link to the web app with everything in the fragment (never sent to
the website):

    https://droplet.noxeratech.com/app/#pair=<base64url of the JSON below>

    {"v":1, "n":<name>, "i":<peer id>, "f":<fingerprint, base64url of the 32 bytes>,
     "a":[<LAN addresses, the main one first>], "p":<UDP port>, "t":<one-time token, base64url>}

The fingerprint is what the browser pins in DTLS, so a QR code shown on
this computer's own screen is the root of trust. The token lets the browser
ask to pair at all, for a few minutes after the code is shown; pairing then
still needs the 4-digit code on both screens.

Drawing needs `segno` (pure Python): pip install segno.
"""

from __future__ import annotations

import base64
import json


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def unb64url(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def payload(*, name: str, peer_id: str, fp: str, addresses: list[str], port: int, token: str) -> dict:
    return {"v": 1, "n": name[:40], "i": peer_id, "f": b64url(bytes.fromhex(fp)), "a": list(addresses)[:4],
            "p": port, "t": token}


def link(app_url: str, data: dict) -> str:
    raw = json.dumps(data, separators=(",", ":"), ensure_ascii=False).encode()
    return f"{app_url.rstrip('#')}#pair={b64url(raw)}"


def parse(text: str) -> dict:
    """The payload from a link (or bare base64url). Raises ValueError. The web app does the same."""
    s = text.strip()
    if "#pair=" in s:
        s = s.split("#pair=", 1)[1]
    data = json.loads(unb64url(s))
    if not isinstance(data, dict) or data.get("v") != 1:
        raise ValueError("not a droplet pairing code")
    data["fp"] = unb64url(data["f"]).hex()
    if len(data["fp"]) != 64:
        raise ValueError("bad fingerprint")
    return data


def matrix(text: str) -> list[list[bool]]:
    """The QR code's modules, row by row, with no quiet zone. Needs segno."""
    import segno
    qr = segno.make(text, error="m", micro=False)
    return [[bool(v) for v in row] for row in qr.matrix]


def terminal(text: str) -> str:
    """The QR code drawn with half blocks, two rows per line, dark on light, with a quiet zone."""
    m = matrix(text)
    n = len(m)
    quiet = 2
    size = n + 2 * quiet

    def dark(r, c):
        r, c = r - quiet, c - quiet
        return 0 <= r < n and 0 <= c < n and m[r][c]

    lines = []
    for r in range(0, size, 2):
        line = []
        for c in range(size):
            top, bottom = dark(r, c), dark(r + 1, c) if r + 1 < size else False
            # light is printed (a terminal's background may be dark), dark is left blank
            line.append({(False, False): "█", (True, False): "▄", (False, True): "▀", (True, True): " "}[(top, bottom)])
        lines.append("".join(line))
    return "\n".join(lines)
