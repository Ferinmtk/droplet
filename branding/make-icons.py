#!/usr/bin/env python3
"""Draws Droplet's logo and writes every icon the apps, the web app and the website use.

    python3 branding/make-icons.py      (needs ImageMagick with SVG support, and Pillow)

The logo: a drop leaning forward, its signal ahead of it and speed lines behind, glowing
aqua to blue on a deep navy tile. It is drawn here once, as geometry, and written as:

  branding/droplet.svg                  the tile (also the website's favicon, site/droplet.svg)
  branding/droplet-mark.svg             the drop, signal and speed lines without the tile
  branding/droplet-tray-256.png         the drop and signal alone, for trays (no speed lines:
                                        they turn to noise at 16 px)
  static/, site/app/icons/              the web app's icons: any, maskable, apple-touch
  site/favicon-32.png, site/apple-touch-icon.png
  agent/droplet_agent/app_icon.png      the Mac Dock and the Linux window
  android/.../mipmap-*/ic_launcher_{foreground,monochrome}.png, colors.xml's background
  android/.../drawable/ic_drop.xml      the notification icon: a white silhouette
  android/.../drawable/ic_drop_color.xml  the same in colour, on the app's screens

Then it runs windows-net/tools/make-assets.py and agent/tools/make_tray_icon.py, which build
the Windows and Linux icons from static/icon-512.png and the tray PNG. The outputs are
committed, so building the apps doesn't need this step.
"""
import math
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

NAVY = "#0E1A33"
AQUA = "#7EF0DC"
BLUE = "#3B7BFF"

# --- the geometry, on a 128-unit tile ----------------------------------------------------------

CX, CY, R, H, TILT = 68.0, 82.0, 22.0, 44.0, 18.0   # the drop: circle, tip height, lean (degrees)
ARCS = ((13.0, 1.0), (25.0, 0.5))                   # signal radii and opacity, round the tip
ARC_FROM, ARC_TO, ARC_W = -122.0, -22.0, 6.5
SPEED = (((20, 76), (34, 76)), ((14, 90), (32, 90)), ((22, 104), (36, 104)))
SPEED_W = 5.0


def rot(x, y, deg=TILT, cx=CX, cy=CY):
    a = math.radians(deg)
    dx, dy = x - cx, y - cy
    return cx + dx * math.cos(a) - dy * math.sin(a), cy + dx * math.sin(a) + dy * math.cos(a)


def tip():
    return rot(CX, CY - H)


def drop_path(k=1.0, ox=0.0, oy=0.0, sep=" "):
    """The drop's outline, leaning, scaled by k and moved by (ox, oy)."""
    c = R / H
    s = math.sqrt(1 - c * c)
    t = (CX, CY - H)
    pl, pr = (CX - R * s, CY - R * c), (CX + R * s, CY - R * c)

    def bow(p, side):
        mx, my = (t[0] + p[0]) / 2, (t[1] + p[1]) / 2
        dx, dy = p[0] - t[0], p[1] - t[1]
        n = math.hypot(dx, dy)
        ox_, oy_ = (dy / n, -dx / n) if side > 0 else (-dy / n, dx / n)
        return mx + ox_ * R * 0.12, my + oy_ * R * 0.12

    def f(p):
        x, y = rot(*p)
        return f"{x * k + ox:.2f}{sep}{y * k + oy:.2f}"
    return (f"M{f(t)} Q{f(bow(pr, 1))} {f(pr)} A{R * k:.2f} {R * k:.2f} 0 1 1 {f(pl)} "
            f"Q{f(bow(pl, -1))} {f(t)}Z")


def arc_path(r, k=1.0, ox=0.0, oy=0.0, sep=" "):
    tx, ty = tip()
    a0, a1 = math.radians(ARC_FROM), math.radians(ARC_TO)
    p0 = (tx + r * math.cos(a0), ty + r * math.sin(a0))
    p1 = (tx + r * math.cos(a1), ty + r * math.sin(a1))
    return (f"M{p0[0] * k + ox:.2f}{sep}{p0[1] * k + oy:.2f} A{r * k:.2f} {r * k:.2f} 0 0 1 "
            f"{p1[0] * k + ox:.2f}{sep}{p1[1] * k + oy:.2f}")


def bbox(speed=True):
    """The mark's extent (drop, arcs with their stroke, speed lines)."""
    pts = []
    for i in range(360):
        a = math.radians(i)
        pts.append(rot(CX + R * math.cos(a), CY + R * math.sin(a)))
    pts.append(tip())
    tx, ty = tip()
    for r, _ in ARCS:
        for i in range(int(ARC_FROM), int(ARC_TO) + 1):
            a = math.radians(i)
            for d in (-ARC_W / 2, ARC_W / 2):
                pts.append((tx + (r + d) * math.cos(a), ty + (r + d) * math.sin(a)))
    if speed:
        for (x0, y0), (x1, y1) in SPEED:
            pts += [(x0 - SPEED_W / 2, y0 - SPEED_W / 2), (x1 + SPEED_W / 2, y1 + SPEED_W / 2)]
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    return min(xs), min(ys), max(xs), max(ys)


# --- SVGs ---------------------------------------------------------------------------------------

def defs(glow=True):
    g = (f'<linearGradient id="drop" x1="0" y1="0" x2="0" y2="1">'
         f'<stop offset="0" stop-color="{AQUA}"/><stop offset="1" stop-color="{BLUE}"/></linearGradient>')
    if glow:
        g += (f'<radialGradient id="glow" cx=".55" cy=".62" r=".5"><stop offset="0" stop-color="{BLUE}" '
              f'stop-opacity=".45"/><stop offset="1" stop-color="{BLUE}" stop-opacity="0"/></radialGradient>')
    return f"<defs>{g}</defs>"


def mark(k=1.0, ox=0.0, oy=0.0, speed=True, color=None, arc_color=None):
    """The drop, its signal and (optionally) the speed lines; one flat colour if `color`."""
    fill = color or "url(#drop)"
    ac = arc_color or color or AQUA
    w = 3.0 * k  # a stroke the drop's own colour softens its tip
    out = [f'<path d="{drop_path(k, ox, oy)}" fill="{fill}" stroke="{fill}" stroke-width="{w:.2f}" stroke-linejoin="round"/>']
    for r, op in ARCS:
        o = "" if color else f' opacity="{op}"'
        out.append(f'<path d="{arc_path(r, k, ox, oy)}" fill="none" stroke="{ac}" stroke-width="{ARC_W * k:.2f}" '
                   f'stroke-linecap="round"{o}/>')
    if speed:
        o = "" if color else ' opacity=".5"'
        out.append(f'<g stroke="{ac}" stroke-width="{SPEED_W * k:.2f}" stroke-linecap="round"{o}>')
        for (x0, y0), (x1, y1) in SPEED:
            out.append(f'<line x1="{x0 * k + ox:.2f}" y1="{y0 * k + oy:.2f}" x2="{x1 * k + ox:.2f}" y2="{y1 * k + oy:.2f}"/>')
        out.append("</g>")
    return "".join(out)


def svg(body, size=128, view=128):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 {view} {view}">'
            f"{body}</svg>\n")


def tile_svg(rounded=True, k=1.0):
    """The full icon. `rounded`: the tile's own corners (else full bleed, for maskable and iOS).
    k < 1 shrinks the mark about the centre (a maskable icon's safe zone)."""
    rx = ' rx="30"' if rounded else ""
    o = 64 * (1 - k)
    return svg(defs() + f'<rect width="128" height="128"{rx} fill="{NAVY}"/>'
               f'<rect width="128" height="128"{rx} fill="url(#glow)"/>' + mark(k, o, o))


def fitted_svg(speed=True, color=None, pad=0.06, glow=False):
    """The mark alone, centred and filling the square (less `pad` each side)."""
    x0, y0, x1, y1 = bbox(speed)
    side = max(x1 - x0, y1 - y0)
    k = 128 * (1 - 2 * pad) / side
    ox = 64 - (x0 + x1) / 2 * k
    oy = 64 - (y0 + y1) / 2 * k
    return svg(defs(glow) + mark(k, ox, oy, speed, color))


def foreground_svg(color=None):
    """Android's adaptive-icon foreground: the mark inside the 66/108 safe zone."""
    x0, y0, x1, y1 = bbox(True)
    side = max(x1 - x0, y1 - y0)
    k = 128 * 0.56 / side
    ox = 64 - (x0 + x1) / 2 * k
    oy = 64 - (y0 + y1) / 2 * k
    glow = "" if color else '<rect width="128" height="128" fill="url(#glow)"/>'
    return svg(defs() + glow + mark(k, ox, oy, True, color))


# --- rendering ----------------------------------------------------------------------------------

def render(svg_text, path, w, h=None, opaque=None):
    h = h or w
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as f:
        f.write(svg_text)
        src = f.name
    try:
        density = 96 * 4 * max(w, h) / 128
        cmd = ["magick", "-background", opaque or "none", "-density", f"{density:.1f}", src,
               "-resize", f"{w}x{h}!"]
        if opaque:
            cmd += ["-flatten", "-alpha", "off"]
        subprocess.run(cmd + ["-strip", path], check=True)
    finally:
        os.unlink(src)


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def vector_drawable():
    """The notification icon: drop and signal in white, on Android's 24 dp grid."""
    x0, y0, x1, y1 = bbox(False)
    side = max(x1 - x0, y1 - y0)
    k = 24 * 0.86 / side
    ox = 12 - (x0 + x1) / 2 * k
    oy = 12 - (y0 + y1) / 2 * k
    d = drop_path(k, ox, oy, sep=",").replace(" ", " ")
    arcs = "".join(
        f'\n    <path\n        android:strokeColor="#FFFFFFFF"\n        android:strokeWidth="{ARC_W * k:.2f}"\n'
        f'        android:strokeLineCap="round"\n        android:pathData="{arc_path(r, k, ox, oy, sep=",")}" />'
        for r, _ in ARCS)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- the Droplet mark as a silhouette (notification icon, headers): the leaning drop and its\n'
            '     signal; made by branding/make-icons.py -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp"\n    android:height="24dp"\n'
            '    android:viewportWidth="24"\n    android:viewportHeight="24">\n'
            '    <path\n        android:fillColor="#FFFFFFFF"\n        android:strokeColor="#FFFFFFFF"\n'
            f'        android:strokeWidth="{3.0 * k:.2f}"\n        android:strokeLineJoin="round"\n'
            f'        android:pathData="{d}" />{arcs}\n</vector>\n')


def main():
    p = lambda *a: os.path.join(ROOT, *a)
    tile = tile_svg()
    write(p("branding", "droplet.svg"), tile)
    write(p("site", "droplet.svg"), tile)
    write(p("branding", "droplet-mark.svg"), fitted_svg(True))
    render(fitted_svg(False, pad=0.03), p("branding", "droplet-tray-256.png"), 256)

    for d in ("static", os.path.join("site", "app", "icons")):
        render(tile, p(d, "icon-192.png"), 192)
        render(tile, p(d, "icon-512.png"), 512)
        render(tile_svg(False, 0.8), p(d, "icon-maskable-512.png"), 512)
        render(tile_svg(False), p(d, "apple-touch-icon.png"), 180, opaque=NAVY)
    render(tile_svg(False), p("site", "apple-touch-icon.png"), 180, opaque=NAVY)
    render(tile, p("site", "favicon-32.png"), 32)
    render(tile, p("agent", "droplet_agent", "app_icon.png"), 512)

    res = p("android", "app", "src", "main", "res")
    for dpi, n in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
        render(foreground_svg(), os.path.join(res, f"mipmap-{dpi}", "ic_launcher_foreground.png"), n)
        render(foreground_svg("#FFFFFF"), os.path.join(res, f"mipmap-{dpi}", "ic_launcher_monochrome.png"), n)
    colors = os.path.join(res, "values", "colors.xml")
    with open(colors) as f:
        c = f.read()
    c2 = re.sub(r'(<color name="ic_launcher_background">)#[0-9A-Fa-f]{6}(</color>)', rf"\g<1>{NAVY}\g<2>", c)
    write(colors, c2)
    write(os.path.join(res, "drawable", "ic_drop.xml"), vector_drawable())
    write(os.path.join(res, "drawable", "ic_drop_color.xml"), vector_drawable().replace("#FFFFFFFF", "@color/drop")
          .replace("as a silhouette (notification icon, headers)", "in colour (screen headers)"))

    py = sys.executable
    subprocess.run([py, p("windows-net", "tools", "make-assets.py")], check=True)
    subprocess.run([py, p("agent", "tools", "make_tray_icon.py")], check=True)
    print("done")


if __name__ == "__main__":
    main()
