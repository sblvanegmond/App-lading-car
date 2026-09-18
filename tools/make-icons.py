#!/usr/bin/env python3
"""Generate the app icons as PNG files without any image library.

The icon is drawn procedurally: a dark rounded square, a sun in the top
corner and a charging bolt across it. Run with `npm run icons`.
"""

import math
import struct
import zlib
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "icons"

BG_OUTER = (15, 17, 21)
BG_INNER = (26, 32, 44)
SUN = (201, 133, 0)
BOLT = (25, 158, 112)


def lerp(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def rounded_square_alpha(x, y, size, radius):
    """Signed coverage of a rounded square covering the whole canvas."""
    cx = min(max(x, radius), size - radius)
    cy = min(max(y, radius), size - radius)
    dx, dy = x - cx, y - cy
    dist = math.hypot(dx, dy)
    return 1.0 if dist <= radius else max(0.0, 1.0 - (dist - radius))


def inside_polygon(x, y, points):
    inside = False
    n = len(points)
    for i in range(n):
        x1, y1 = points[i]
        x2, y2 = points[(i + 1) % n]
        if (y1 > y) != (y2 > y):
            xin = (x2 - x1) * (y - y1) / (y2 - y1) + x1
            if x < xin:
                inside = not inside
    return inside


def draw(size, maskable=False):
    """Return an RGBA pixel buffer for one icon."""
    pad = 0.0 if not maskable else size * 0.12
    inner = size - 2 * pad
    radius = size * (0.22 if not maskable else 0.5)

    sun_cx, sun_cy = pad + inner * 0.34, pad + inner * 0.34
    sun_r = inner * 0.155
    ray_inner, ray_outer = sun_r * 1.45, sun_r * 2.15

    bolt = [
        (pad + inner * 0.60, pad + inner * 0.14),
        (pad + inner * 0.34, pad + inner * 0.585),
        (pad + inner * 0.505, pad + inner * 0.585),
        (pad + inner * 0.415, pad + inner * 0.93),
        (pad + inner * 0.72, pad + inner * 0.45),
        (pad + inner * 0.545, pad + inner * 0.45),
        (pad + inner * 0.655, pad + inner * 0.14),
    ]

    rows = []
    for py in range(size):
        row = bytearray()
        for px in range(size):
            x, y = px + 0.5, py + 0.5
            cover = rounded_square_alpha(x, y, size, radius)
            if cover <= 0:
                row += bytes((0, 0, 0, 0))
                continue

            base = lerp(BG_OUTER, BG_INNER, min(1.0, (x + y) / (2.0 * size)))
            colour = base

            d_sun = math.hypot(x - sun_cx, y - sun_cy)
            if d_sun <= sun_r:
                colour = SUN
            elif ray_inner <= d_sun <= ray_outer:
                angle = math.atan2(y - sun_cy, x - sun_cx)
                # Eight rays, each covering a slice of the circle.
                phase = (angle % (math.pi / 4)) / (math.pi / 4)
                if abs(phase - 0.5) < 0.17:
                    colour = SUN

            if inside_polygon(x, y, bolt):
                colour = BOLT

            row += bytes((*colour, round(255 * cover)))
        rows.append(bytes(row))
    return rows


def write_png(path, rows, size):
    raw = b"".join(b"\x00" + row for row in rows)

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    path.write_bytes(png)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    targets = [
        ("icon-192.png", 192, False),
        ("icon-512.png", 512, False),
        ("apple-touch-icon.png", 180, False),
        ("maskable-512.png", 512, True),
    ]
    for name, size, maskable in targets:
        write_png(OUT / name, draw(size, maskable), size)
        print(f"icons/{name} ({size}x{size})")


if __name__ == "__main__":
    main()
