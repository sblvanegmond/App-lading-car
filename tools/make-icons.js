#!/usr/bin/env node
/**
 * Generate the app icons as PNG files without any image library.
 *
 * The icon is drawn procedurally - a dark rounded square, a sun in the top
 * corner and a charging bolt across it - and written with a hand-rolled PNG
 * encoder, so the project stays dependency-free and the icons never have to
 * live in git as binaries.
 *
 *   npm run icons
 */

import { deflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const OUT = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'icons');

const BG_OUTER = [15, 17, 21];
const BG_INNER = [26, 32, 44];
const SUN = [201, 133, 0];
const BOLT = [25, 158, 112];

function lerp(a, b, t) {
  return a.map((x, i) => Math.round(x + (b[i] - x) * t));
}

/** Coverage of a rounded square filling the whole canvas, with soft edges. */
function roundedSquareAlpha(x, y, size, radius) {
  const cx = Math.min(Math.max(x, radius), size - radius);
  const cy = Math.min(Math.max(y, radius), size - radius);
  const dist = Math.hypot(x - cx, y - cy);
  return dist <= radius ? 1 : Math.max(0, 1 - (dist - radius));
}

function insidePolygon(x, y, points) {
  let inside = false;
  for (let i = 0; i < points.length; i += 1) {
    const [x1, y1] = points[i];
    const [x2, y2] = points[(i + 1) % points.length];
    if (y1 > y !== y2 > y) {
      const xin = ((x2 - x1) * (y - y1)) / (y2 - y1) + x1;
      if (x < xin) inside = !inside;
    }
  }
  return inside;
}

function draw(size, maskable = false) {
  const pad = maskable ? size * 0.12 : 0;
  const inner = size - 2 * pad;
  const radius = size * (maskable ? 0.5 : 0.22);

  const sunCx = pad + inner * 0.34;
  const sunCy = pad + inner * 0.34;
  const sunR = inner * 0.155;
  const rayInner = sunR * 1.45;
  const rayOuter = sunR * 2.15;

  const bolt = [
    [pad + inner * 0.6, pad + inner * 0.14],
    [pad + inner * 0.34, pad + inner * 0.585],
    [pad + inner * 0.505, pad + inner * 0.585],
    [pad + inner * 0.415, pad + inner * 0.93],
    [pad + inner * 0.72, pad + inner * 0.45],
    [pad + inner * 0.545, pad + inner * 0.45],
    [pad + inner * 0.655, pad + inner * 0.14],
  ];

  const raw = Buffer.alloc(size * (size * 4 + 1));
  let offset = 0;
  for (let py = 0; py < size; py += 1) {
    raw[offset] = 0; // no per-row filter
    offset += 1;
    for (let px = 0; px < size; px += 1) {
      const x = px + 0.5;
      const y = py + 0.5;
      const cover = roundedSquareAlpha(x, y, size, radius);
      if (cover <= 0) {
        offset += 4;
        continue;
      }

      let colour = lerp(BG_OUTER, BG_INNER, Math.min(1, (x + y) / (2 * size)));

      const dSun = Math.hypot(x - sunCx, y - sunCy);
      if (dSun <= sunR) {
        colour = SUN;
      } else if (dSun >= rayInner && dSun <= rayOuter) {
        const angle = Math.atan2(y - sunCy, x - sunCx);
        const slice = Math.PI / 4;
        // Eight rays, each covering the middle third of its slice.
        const phase = (((angle % slice) + slice) % slice) / slice;
        if (Math.abs(phase - 0.5) < 0.17) colour = SUN;
      }

      if (insidePolygon(x, y, bolt)) colour = BOLT;

      raw[offset] = colour[0];
      raw[offset + 1] = colour[1];
      raw[offset + 2] = colour[2];
      raw[offset + 3] = Math.round(255 * cover);
      offset += 4;
    }
  }
  return raw;
}

function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc ^= byte;
    for (let i = 0; i < 8; i += 1) {
      crc = crc & 1 ? (crc >>> 1) ^ 0xedb88320 : crc >>> 1;
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(tag, data) {
  const body = Buffer.concat([Buffer.from(tag, 'ascii'), data]);
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const checksum = Buffer.alloc(4);
  checksum.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, checksum]);
}

function writePng(path, raw, size) {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size, 0);
  header.writeUInt32BE(size, 4);
  header[8] = 8; // bit depth
  header[9] = 6; // colour type: RGBA
  const png = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
  writeFileSync(path, png);
}

mkdirSync(OUT, { recursive: true });
for (const [name, size, maskable] of [
  ['icon-192.png', 192, false],
  ['icon-512.png', 512, false],
  ['apple-touch-icon.png', 180, false],
  ['maskable-512.png', 512, true],
]) {
  writePng(join(OUT, name), draw(size, maskable), size);
  console.log(`icons/${name} (${size}x${size})`);
}
