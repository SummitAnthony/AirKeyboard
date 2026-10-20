"""Generates the AirKeyboard icon files from one geometry (Python 3.11 + Pillow + NumPy).

Run from the repo root:  python -I branding/make_icons.py

Writes branding/icon.svg, branding/icon-512.png, windows-server/AirKeyboard.ico
(16, 24, 32, 48, 64, 256 px) and, under android-app/app/src/main/res:
  drawable/ic_launcher.xml            full icon as a vector (splash screen)
  drawable/ic_launcher_background.xml adaptive icon background (wallpaper)
  drawable/ic_launcher_foreground.xml adaptive icon foreground (glass keycap + pointer)
  mipmap-anydpi-v26/ic_launcher.xml   adaptive icon (Android 8+)
  mipmap-*dpi/ic_launcher.png         launcher icon for Android 7

The mark (Glass design): a frosted-glass keycap carrying a white mouse pointer, on the app's
dark wallpaper with violet, orange and cyan glows. All coordinates are in a 512 x 512 box.
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "android-app" / "app" / "src" / "main" / "res"
BOX = 512

BASE = "#1C1652"  # deep indigo
# Glows: (color, center x, center y, radius, peak alpha), back to front, in box units
GLOWS = [
    ("#120D38", 80, 470, 300, 0.9),    # dark bottom-left
    ("#2A6EA8", 380, 300, 260, 0.55),  # teal through the middle right
    ("#6F68F2", 100, 90, 300, 0.95),   # violet top-left
    ("#FF7A40", 420, 50, 190, 0.85),   # orange top-right
    ("#2FCBE3", 440, 450, 230, 0.95),  # cyan bottom-right
]

BACKGROUND = (0, 0, 512, 512, 112)        # (x, y, w, h, r)
KEYCAP = (120, 104, 278, 278, 66)
HIGHLIGHT = (138, 118, 242, 64, 32)       # glossy band across the top of the keycap
SHADOW = (259, 404, 150, 34)              # soft shadow under the keycap: (cx, cy, rx, ry)

# Mouse pointer, tip first; drawn with round joins of POINTER_ROUND width
POINTER = [(214, 176), (214, 316), (245, 287), (266, 334), (288, 324), (267, 278), (309, 278)]
POINTER_ROUND = 14


def rounded_path(x, y, w, h, r):
    """SVG / Android path data for a rounded rectangle"""
    return (f"M{x + r},{y}h{w - 2 * r}a{r},{r} 0 0 1 {r},{r}v{h - 2 * r}a{r},{r} 0 0 1 {-r},{r}"
            f"h{-(w - 2 * r)}a{r},{r} 0 0 1 {-r},{-r}v{-(h - 2 * r)}a{r},{r} 0 0 1 {r},{-r}z")


def polygon_path(points, dx=0, dy=0):
    head, *rest = points
    return f"M{head[0] + dx},{head[1] + dy}" + "".join(f"L{x + dx},{y + dy}" for x, y in rest) + "z"


def ellipse_path(cx, cy, rx, ry):
    return f"M{cx - rx},{cy}a{rx},{ry} 0 1 0 {2 * rx},0a{rx},{ry} 0 1 0 {-2 * rx},0z"


def argb(color, alpha):
    return f"#{round(alpha * 255):02X}{color[1:]}"


# ---------------------------------------------------------------- SVG

def write_svg(path):
    defs, body = [], []
    clip = rounded_path(*BACKGROUND)
    body.append(f'  <path fill="{BASE}" d="{clip}"/>')
    for i, (color, cx, cy, r, a) in enumerate(GLOWS):
        defs.append(f'    <radialGradient id="g{i}" gradientUnits="userSpaceOnUse" cx="{cx}" cy="{cy}" r="{r}">'
                    f'<stop offset="0" stop-color="{color}" stop-opacity="{a}"/>'
                    f'<stop offset="1" stop-color="{color}" stop-opacity="0"/></radialGradient>')
        body.append(f'  <path fill="url(#g{i})" d="{clip}"/>')
    defs.append('    <filter id="blur" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="14"/></filter>')
    defs.append('    <filter id="pblur" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="5"/></filter>')
    defs.append('    <linearGradient id="cap" x1="0" y1="0" x2="0" y2="1">'
                '<stop offset="0" stop-color="#FFFFFF" stop-opacity="0.30"/>'
                '<stop offset="1" stop-color="#FFFFFF" stop-opacity="0.10"/></linearGradient>')
    defs.append('    <linearGradient id="hl" x1="0" y1="0" x2="0" y2="1">'
                '<stop offset="0" stop-color="#FFFFFF" stop-opacity="0.55"/>'
                '<stop offset="1" stop-color="#FFFFFF" stop-opacity="0.06"/></linearGradient>')
    body.append(f'  <path fill="#000000" fill-opacity="0.45" filter="url(#blur)" d="{ellipse_path(*SHADOW)}"/>')
    body.append(f'  <path fill="url(#cap)" stroke="#FFFFFF" stroke-opacity="0.6" stroke-width="3" d="{rounded_path(*KEYCAP)}"/>')
    body.append(f'  <path fill="url(#hl)" d="{rounded_path(*HIGHLIGHT)}"/>')
    pointer = polygon_path(POINTER)
    body.append(f'  <path fill="#000000" fill-opacity="0.28" stroke="#000000" stroke-opacity="0.28" '
                f'stroke-width="{POINTER_ROUND}" stroke-linejoin="round" filter="url(#pblur)" d="{polygon_path(POINTER, 4, 8)}"/>')
    body.append(f'  <path fill="#FFFFFF" stroke="#FFFFFF" stroke-width="{POINTER_ROUND}" stroke-linejoin="round" d="{pointer}"/>')
    path.write_text(
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{BOX}" height="{BOX}" viewBox="0 0 {BOX} {BOX}">\n'
        f"  <title>AirKeyboard</title>\n  <defs>\n" + "\n".join(defs) + "\n  </defs>\n"
        + "\n".join(body) + "\n</svg>\n", encoding="utf-8", newline="\n")


# ---------------------------------------------------------------- Android vectors

HEADER = '<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by branding/make_icons.py; edit the geometry there -->\n'
VECTOR = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
          '    xmlns:aapt="http://schemas.android.com/aapt"\n'
          '    android:width="{size}dp"\n    android:height="{size}dp"\n'
          '    android:viewportWidth="{vp}"\n    android:viewportHeight="{vp}">\n{body}</vector>\n')


def radial_path(d, color, cx, cy, r, a, ind="    "):
    return (f'{ind}<path android:pathData="{d}">\n'
            f'{ind}    <aapt:attr name="android:fillColor">\n'
            f'{ind}        <gradient android:type="radial" android:centerX="{cx}" android:centerY="{cy}" '
            f'android:gradientRadius="{r}" android:startColor="{argb(color, a)}" android:endColor="{argb(color, 0)}" />\n'
            f'{ind}    </aapt:attr>\n{ind}</path>\n')


def linear_path(d, y0, y1, a0, a1, extra="", ind="    "):
    return (f'{ind}<path android:pathData="{d}"{extra}>\n'
            f'{ind}    <aapt:attr name="android:fillColor">\n'
            f'{ind}        <gradient android:type="linear" android:startX="0" android:startY="{y0}" '
            f'android:endX="0" android:endY="{y1}" android:startColor="{argb("#FFFFFF", a0)}" '
            f'android:endColor="{argb("#FFFFFF", a1)}" />\n'
            f'{ind}    </aapt:attr>\n{ind}</path>\n')


def background_paths(d, ind="    "):
    out = f'{ind}<path android:fillColor="{BASE}" android:pathData="{d}" />\n'
    for color, cx, cy, r, a in GLOWS:
        out += radial_path(d, color, cx, cy, r, a, ind)
    return out


def foreground_paths(ind="    "):
    cx, cy, rx, ry = SHADOW
    # The shadow: a circle with a radial fade, squashed vertically (vectors have no blur)
    out = (f'{ind}<group android:pivotX="{cx}" android:pivotY="{cy}" android:scaleY="{ry * 2.2 / (rx * 1.3):.3f}">\n'
           + radial_path(ellipse_path(cx, cy, rx * 1.3, rx * 1.3), "#000000", cx, cy, rx * 1.3, 0.5, ind + "    ")
           + f'{ind}</group>\n')
    x, y, w, h, _ = KEYCAP
    out += linear_path(rounded_path(*KEYCAP), y, y + h, 0.30, 0.10,
                       '\n' + ind + '    android:strokeColor="#99FFFFFF"\n' + ind + '    android:strokeWidth="3"', ind)
    x, y, w, h, _ = HIGHLIGHT
    out += linear_path(rounded_path(*HIGHLIGHT), y, y + h, 0.55, 0.06, "", ind)
    for d, color in ((polygon_path(POINTER, 4, 8), "#33000000"), (polygon_path(POINTER), "#FFFFFFFF")):
        out += (f'{ind}<path\n{ind}    android:fillColor="{color}"\n{ind}    android:strokeColor="{color}"\n'
                f'{ind}    android:strokeWidth="{POINTER_ROUND}"\n{ind}    android:strokeLineJoin="round"\n'
                f'{ind}    android:pathData="{d}" />\n')
    return out


def write_android():
    (RES / "drawable" / "ic_launcher.xml").write_text(
        HEADER + VECTOR.format(size=48, vp=BOX, body=background_paths(rounded_path(*BACKGROUND)) + foreground_paths()),
        encoding="utf-8", newline="\n")
    # Adaptive icon: 108dp layers, of which the launcher shows the middle 72dp (masked). The 512 box
    # maps onto those 72dp, so the viewport is 768 with the box moved in by 128.
    full = "M-128,-128h768v768h-768z"
    group = '    <group android:translateX="128" android:translateY="128">\n'
    (RES / "drawable" / "ic_launcher_background.xml").write_text(
        HEADER + VECTOR.format(size=108, vp=768, body=group + background_paths(full, "        ") + "    </group>\n"),
        encoding="utf-8", newline="\n")
    (RES / "drawable" / "ic_launcher_foreground.xml").write_text(
        HEADER + VECTOR.format(size=108, vp=768, body=group + foreground_paths("        ") + "    </group>\n"),
        encoding="utf-8", newline="\n")
    adaptive = RES / "mipmap-anydpi-v26"
    adaptive.mkdir(exist_ok=True)
    (adaptive / "ic_launcher.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by branding/make_icons.py -->\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@drawable/ic_launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n', encoding="utf-8", newline="\n")


# ---------------------------------------------------------------- raster (PNG, ICO)

def rgb(color):
    return np.array([int(color[i:i + 2], 16) / 255 for i in (1, 3, 5)])


def over(canvas, color, alpha):
    """Composite a color with a per-pixel alpha map over an RGB canvas (both float)"""
    a = alpha[..., None]
    canvas[:] = canvas * (1 - a) + color * a


def mask(n, draw_fn):
    im = Image.new("L", (n, n), 0)
    draw_fn(ImageDraw.Draw(im))
    return np.asarray(im, dtype=np.float64) / 255


def render(size):
    """The icon at size x size px, drawn 4x larger (at least 1024 px) and downsampled"""
    n = max(1024, 4 * size)
    s = n / BOX
    yy, xx = np.mgrid[0:n, 0:n] / s + 0.5 / s

    rr = lambda x, y, w, h, r: (lambda d: d.rounded_rectangle([x * s, y * s, (x + w) * s - 1, (y + h) * s - 1],
                                                              radius=r * s, fill=255))
    canvas = np.zeros((n, n, 3))
    canvas[:] = rgb(BASE)
    for color, cx, cy, r, a in GLOWS:
        over(canvas, rgb(color), np.clip(1 - np.hypot(xx - cx, yy - cy) / r, 0, 1) * a)

    cx, cy, rx, ry = SHADOW
    shadow = Image.fromarray((mask(n, lambda d: d.ellipse([(cx - rx) * s, (cy - ry) * s, (cx + rx) * s, (cy + ry) * s],
                                                         fill=255)) * 255).astype(np.uint8))
    shadow = np.asarray(shadow.filter(ImageFilter.GaussianBlur(14 * s)), dtype=np.float64) / 255
    over(canvas, np.zeros(3), shadow * 0.45)

    x, y, w, h, r = KEYCAP
    cap = mask(n, rr(*KEYCAP))
    over(canvas, np.ones(3), cap * np.clip(0.30 - 0.20 * (yy - y) / h, 0.10, 0.30))
    ring = cap - mask(n, rr(x + 3, y + 3, w - 6, h - 6, r - 3))
    over(canvas, np.ones(3), np.clip(ring, 0, 1) * 0.6)
    x, y, w, h, r = HIGHLIGHT
    over(canvas, np.ones(3), mask(n, rr(*HIGHLIGHT)) * np.clip(0.55 - 0.49 * (yy - y) / h, 0.06, 0.55))

    def pointer(d, dx=0, dy=0):
        pts = [((px + dx) * s, (py + dy) * s) for px, py in POINTER]
        d.polygon(pts, fill=255)
        d.line(pts + [pts[0]], fill=255, width=round(POINTER_ROUND * s), joint="curve")
        rad = POINTER_ROUND * s / 2
        for px, py in pts:
            d.ellipse([px - rad, py - rad, px + rad, py + rad], fill=255)

    pshadow = Image.fromarray((mask(n, lambda d: pointer(d, 4, 8)) * 255).astype(np.uint8))
    pshadow = np.asarray(pshadow.filter(ImageFilter.GaussianBlur(5 * s)), dtype=np.float64) / 255
    over(canvas, np.zeros(3), pshadow * 0.28)
    over(canvas, np.ones(3), mask(n, pointer))

    alpha = mask(n, rr(*BACKGROUND))
    rgba = np.dstack([canvas, alpha])
    img = Image.fromarray((np.clip(rgba, 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA")
    return img.resize((size, size), Image.Resampling.LANCZOS)


def main():
    write_svg(ROOT / "branding" / "icon.svg")
    render(512).save(ROOT / "branding" / "icon-512.png", optimize=True)

    sizes = [256, 64, 48, 32, 24, 16]
    images = [render(s) for s in sizes]
    images[0].save(ROOT / "windows-server" / "AirKeyboard.ico", format="ICO",
                   sizes=[(s, s) for s in sizes], append_images=images[1:])

    write_android()
    for density, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        folder = RES / f"mipmap-{density}"
        folder.mkdir(exist_ok=True)
        render(px).save(folder / "ic_launcher.png", optimize=True)


if __name__ == "__main__":
    main()
