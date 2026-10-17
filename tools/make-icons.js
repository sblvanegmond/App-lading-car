#!/usr/bin/env node
/**
 * Generate the app icons as PNG files without any image library.
 *
 * Both icons are drawn procedurally and written with a hand-rolled PNG
 * encoder, so the project stays dependency-free and the icons never have to
 * live in git as binaries:
 *  - Laadmoment: a dark rounded square, a sun in the top corner and a
 *    charging bolt across it (icons/);
 *  - Coach: the same square with a heartbeat line and a recovery dot
 *    (coach/icons/).
 *
 *   npm run icons
 */

import { deflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

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

const PULSE = [255, 138, 76];
const DOT = [60, 197, 164];

/** Distance from a point to the segment a-b. */
function segmentDistance(x, y, [ax, ay], [bx, by]) {
  const dx = bx - ax;
  const dy = by - ay;
  const t = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)));
  return Math.hypot(x - (ax + t * dx), y - (ay + t * dy));
}

function drawCoach(size, maskable = false) {
  const pad = maskable ? size * 0.12 : 0;
  const inner = size - 2 * pad;
  const radius = size * (maskable ? 0.5 : 0.22);
  const at = (fx, fy) => [pad + inner * fx, pad + inner * fy];
  const pulse = [
    at(0.12, 0.58),
    at(0.34, 0.58),
    at(0.42, 0.38),
    at(0.53, 0.78),
    at(0.62, 0.5),
    at(0.68, 0.58),
    at(0.88, 0.58),
  ];
  const half = inner * 0.042;
  const [dotX, dotY] = at(0.72, 0.27);
  const dotR = inner * 0.075;

  const raw = Buffer.alloc(size * (size * 4 + 1));
  let offset = 0;
  for (let py = 0; py < size; py += 1) {
    raw[offset] = 0;
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

      const dDot = Math.hypot(x - dotX, y - dotY);
      colour = lerp(colour, DOT, Math.max(0, Math.min(1, dotR - dDot + 0.5)));

      let d = Infinity;
      for (let i = 1; i < pulse.length; i += 1) d = Math.min(d, segmentDistance(x, y, pulse[i - 1], pulse[i]));
      colour = lerp(colour, PULSE, Math.max(0, Math.min(1, half - d + 0.5)));

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

const SIZES = [
  ['icon-192.png', 192, false],
  ['icon-512.png', 512, false],
  ['apple-touch-icon.png', 180, false],
  ['maskable-512.png', 512, true],
];

for (const [dir, paint] of [
  ['icons', draw],
  ['coach/icons', drawCoach],
]) {
  mkdirSync(join(ROOT, dir), { recursive: true });
  for (const [name, size, maskable] of SIZES) {
    writePng(join(ROOT, dir, name), paint(size, maskable), size);
    console.log(`${dir}/${name} (${size}x${size})`);
  }
}
