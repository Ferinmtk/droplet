#!/usr/bin/env python3
"""Builds droplet for Windows' images from the web app's icon (static/icon-512.png) and the
tray mark (branding/droplet-tray-256.png); branding/make-icons.py draws both, then runs this.

    python3 windows-net/tools/make-assets.py      (needs Pillow)

It writes, under windows-net/:
  src/Droplet.Windows/Assets/droplet.ico    the exe and window icon (the full tile)
  src/Droplet.Windows/Assets/droplet-256.png  the notification and window image
  src/Droplet.Windows/Assets/tray*.ico      the tray: the drop and its signal alone, which
                                            read better at 16 px than the tile; aqua, grey
                                            (can't reach anything), amber (being controlled)
                                            and grey with an orange pause badge (paused)
  packaging/Assets/*.png                    the MSIX visual assets, every scale

The outputs are committed, so building doesn't need this step.
"""
import io
import os
import struct

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(ROOT)
SRC = os.path.join(REPO, "static", "icon-512.png")
MARK = os.path.join(REPO, "branding", "droplet-tray-256.png")
APP = os.path.join(ROOT, "src", "Droplet.Windows", "Assets")
PKG = os.path.join(ROOT, "packaging", "Assets")

AMBER = (251, 191, 36)   # "someone is controlling this PC"
ORANGE = (245, 158, 11)  # the pause badge


def ico(images):
    """An .ico file holding the given square images, each as PNG (Vista and later read them)."""
    blobs = []
    for im in images:
        b = io.BytesIO()
        im.save(b, "PNG", optimize=True)
        blobs.append((im.size[0], b.getvalue()))
    out = io.BytesIO()
    out.write(struct.pack("<HHH", 0, 1, len(blobs)))
    offset = 6 + 16 * len(blobs)
    for size, data in blobs:
        dim = 0 if size >= 256 else size
        out.write(struct.pack("<BBBBHHII", dim, dim, 0, 0, 1, 32, len(data), offset))
        offset += len(data)
    for _, data in blobs:
        out.write(data)
    return out.getvalue()


def resized(im, n):
    return im.resize((n, n), Image.LANCZOS)


def paused(mark):
    """Grey, with an orange pause badge in the corner (Pause everything is on)."""
    out = recolour(mark, dim)
    w = out.size[0]
    d = ImageDraw.Draw(out)
    r = w * 0.27
    cx = cy = w - r - w * 0.01
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=ORANGE + (255,), outline=(16, 20, 29, 255), width=round(w * 0.04))
    bw, bh, gap = r * 0.26, r * 0.95, r * 0.22
    for x in (cx - gap - bw, cx + gap):
        d.rounded_rectangle((x, cy - bh / 2, x + bw, cy + bh / 2), radius=bw / 3, fill=(255, 255, 255, 255))
    return out


def recolour(im, fn):
    out = im.copy()
    p = out.load()
    for y in range(out.size[1]):
        for x in range(out.size[0]):
            r, g, b, a = p[x, y]
            if a:
                p[x, y] = fn(r, g, b, a)
    return out


def dim(r, g, b, a):
    l = (299 * r + 587 * g + 114 * b) // 1000
    l = 110 + (l - 110) * 2 // 5
    return (l, l, l, a * 3 // 4)


def amber(r, g, b, a):
    # the drop's own light and shade, in amber: the highlight stays lighter than the drop
    l = (299 * r + 587 * g + 114 * b) / 1000 / 255
    t = min(1.0, max(0.0, (l - 0.55) / 0.4))
    c = [round(AMBER[i] * (0.8 + 0.2 * l) + (255 - AMBER[i]) * t) for i in range(3)]
    return (min(255, c[0]), min(255, c[1]), min(255, c[2]), a)


def save_png(im, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, "PNG", optimize=True)


def canvas(icon, w, h, scale):
    """The icon centred on a transparent w×h canvas, filling `scale` of the height."""
    c = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    n = round(h * scale)
    c.paste(resized(icon, n), ((w - n) // 2, (h - n) // 2))
    return c


def main():
    src = Image.open(SRC).convert("RGBA")
    os.makedirs(APP, exist_ok=True)
    with open(os.path.join(APP, "droplet.ico"), "wb") as f:
        f.write(ico([resized(src, n) for n in (16, 20, 24, 32, 40, 48, 64, 256)]))
    save_png(resized(src, 256), os.path.join(APP, "droplet-256.png"))

    drop = Image.open(MARK).convert("RGBA")
    tray = (16, 20, 24, 32, 40, 48, 64)
    for name, im in (("tray", drop), ("tray-dim", recolour(drop, dim)), ("tray-live", recolour(drop, amber)),
                     ("tray-paused", paused(drop))):
        with open(os.path.join(APP, name + ".ico"), "wb") as f:
            f.write(ico([resized(im, n) for n in tray]))

    # MSIX: each image at scale 100, 125, 150, 200 and 400, plus an unqualified copy
    # (the scale-200 one) so the manifest's plain names resolve even without resources.pri
    scales = (100, 125, 150, 200, 400)

    def square(name, base, pad=1.0):
        for s in scales:
            n = round(base * s / 100)
            save_png(canvas(src, n, n, pad), os.path.join(PKG, f"{name}.scale-{s}.png"))
        save_png(canvas(src, base * 2, base * 2, pad), os.path.join(PKG, f"{name}.png"))

    square("Square44x44Logo", 44)
    square("Square71x71Logo", 71, 0.8)
    square("Square150x150Logo", 150, 0.66)
    square("Square310x310Logo", 310, 0.6)
    square("StoreLogo", 50)
    for t in (16, 20, 24, 30, 32, 36, 40, 48, 60, 64, 72, 80, 96, 256):
        im = resized(src, t)
        save_png(im, os.path.join(PKG, f"Square44x44Logo.targetsize-{t}.png"))
        save_png(im, os.path.join(PKG, f"Square44x44Logo.targetsize-{t}_altform-unplated.png"))
        save_png(im, os.path.join(PKG, f"Square44x44Logo.targetsize-{t}_altform-lightunplated.png"))
    for s in scales:
        save_png(canvas(src, round(310 * s / 100), round(150 * s / 100), 0.66), os.path.join(PKG, f"Wide310x150Logo.scale-{s}.png"))
        save_png(canvas(src, round(620 * s / 100), round(300 * s / 100), 0.5), os.path.join(PKG, f"SplashScreen.scale-{s}.png"))
    save_png(canvas(src, 620, 300, 0.66), os.path.join(PKG, "Wide310x150Logo.png"))
    save_png(canvas(src, 1240, 600, 0.5), os.path.join(PKG, "SplashScreen.png"))


if __name__ == "__main__":
    main()
