"""Regenerate droplet_agent/tray_icon.bin from static/icon-192.png.

The tray hands the desktop its icon as pixels (StatusNotifierItem's
IconPixmap: ARGB32, big-endian), so nothing has to be installed in an icon
theme. Scaling a PNG needs Pillow, which the agent doesn't depend on, so it
happens here, once, and the result is committed:

    python3 agent/tools/make_tray_icon.py

The file is zlib-compressed: for each size, a big-endian u32 width and
height, then width*height*4 bytes of ARGB.
"""

import struct
import sys
import zlib
from pathlib import Path

from PIL import Image

SIZES = (22, 32, 48, 64)
ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "static" / "icon-192.png"
TARGET = ROOT / "agent" / "droplet_agent" / "tray_icon.bin"


def main() -> int:
    src = Image.open(SOURCE).convert("RGBA")
    out = bytearray()
    for size in SIZES:
        img = src.resize((size, size), Image.LANCZOS)
        out += struct.pack(">II", size, size)
        rgba = img.tobytes()
        for i in range(0, len(rgba), 4):
            r, g, b, a = rgba[i:i + 4]
            out += bytes((a, r, g, b))
    TARGET.write_bytes(zlib.compress(bytes(out), 9))
    print(f"wrote {TARGET} ({TARGET.stat().st_size} bytes, sizes {', '.join(map(str, SIZES))})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
