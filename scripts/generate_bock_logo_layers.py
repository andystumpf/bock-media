#!/usr/bin/env python3
"""Split launcher art into static base + spinning bottle-cap teeth layer."""

from __future__ import annotations

import math
import os
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
ANDROID_OUT = ROOT / "android/app/src/main/res/drawable-nodpi"
IOS_BASE = ROOT / "ios/BockMedia/Resources/Assets.xcassets/bock_logo_base.imageset"
IOS_CAP = ROOT / "ios/BockMedia/Resources/Assets.xcassets/bock_logo_cap.imageset"
WEB_OUT = ROOT / "public/img"

# Emblem center in 192×192 launcher art (circle above BOCK text).
CX = 96.0
CY = 72.0
CAP_R_INNER = 57.5
CAP_R_OUTER = 69.5


def is_cap_tooth(r: int, g: int, b: int, a: int) -> bool:
    if a < 128:
        return False
    # Transparent gaps between teeth and outer white halo.
    if r > 248 and g > 248 and b > 245:
        return False
    if r < 35 and g < 35 and b < 35:
        return False
    return True


def in_cap_ring(x: int, y: int, pixel: tuple[int, int, int, int]) -> bool:
    d = math.hypot(x - CX, y - CY)
    if not (CAP_R_INNER <= d <= CAP_R_OUTER):
        return False
    r, g, b, _a = pixel
    # Keep cap teeth out of the BOCK text band.
    if y > 118:
        return False
    if y > 104 and d < 62:
        return False
    if y > 104 and r < 70 and g < 70 and b < 70:
        return False
    return True


def inner_sample(im: Image.Image, x: int, y: int) -> tuple[int, int, int, int]:
    angle = math.atan2(y - CY, x - CX)
    sample_r = CAP_R_INNER - 2.5
    sx = int(round(CX + math.cos(angle) * sample_r))
    sy = int(round(CY + math.sin(angle) * sample_r))
    return im.getpixel((sx, sy))


def generate() -> None:
    im = Image.open(SRC).convert("RGBA")
    w, h = im.size
    base = im.copy()
    cap = Image.new("RGBA", im.size, (0, 0, 0, 0))
    bp = base.load()
    cp = cap.load()
    ip = im.load()
    cap_count = 0

    for y in range(h):
        for x in range(w):
            pixel = ip[x, y]
            r, g, b, a = pixel
            if in_cap_ring(x, y, pixel) and is_cap_tooth(r, g, b, a):
                cp[x, y] = ip[x, y]
                bp[x, y] = inner_sample(im, x, y)
                cap_count += 1

    for path in (ANDROID_OUT, IOS_BASE, IOS_CAP):
        path.mkdir(parents=True, exist_ok=True)

    base.save(ANDROID_OUT / "bock_logo_base.png")
    cap.save(ANDROID_OUT / "bock_logo_cap.png")
    base.save(IOS_BASE / "bock_logo_base.png")
    cap.save(IOS_CAP / "bock_logo_cap.png")

    emblem_r = int(CY)
    left, top, right, bottom = int(CX - emblem_r), 0, int(CX + emblem_r), int(CY + emblem_r)

    def emblem_crop(layer: Image.Image) -> Image.Image:
        cropped = layer.crop((left, top, right, bottom))
        px = cropped.load()
        cw, ch = cropped.size
        for y in range(ch):
            for x in range(cw):
                r, g, b, a = px[x, y]
                if r > 248 and g > 248 and b > 245 and a > 200:
                    px[x, y] = (r, g, b, 0)
        return cropped

    WEB_OUT.mkdir(parents=True, exist_ok=True)
    mark = emblem_crop(base)
    mark.save(WEB_OUT / "bock-logo-mark.png")
    emblem_crop(base).save(WEB_OUT / "bock-logo-base.png")
    emblem_crop(cap).save(WEB_OUT / "bock-logo-cap.png")
    for size, name in ((32, "icon-32.png"), (180, "icon-180.png"), (192, "icon-192.png"), (512, "icon-512.png")):
        mark.resize((size, size), Image.LANCZOS).save(WEB_OUT / name)

    print(f"Wrote layers ({w}x{h}) cap_pixels={cap_count} pivot=({CX/w:.3f}, {CY/h:.3f})")


if __name__ == "__main__":
    generate()
