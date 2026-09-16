// SPDX-License-Identifier: GPL-3.0-or-later
'use strict';
/*
 * imgdiff.js — 两张同机位截图 → 差异热图 + 量化摘要(零依赖,node >= 18)。
  * Use: turn a before/after screenshot pair into a quantitative verdict (heat map + numbers).
 *
 * 用法:
 *   node imgdiff.js A.png B.png [-o heat.png] [--threshold N] [--json]
 * 输出(stdout,单行 JSON):
 *   {"w":..,"h":..,"meanDiff":..,"maxDiff":..,"changed":N,"total":N,"bbox":[x0,y0,x1,y1]|null}
 *   bbox 为差异像素的最小包围盒(闭区间);-o 同时落盘热图 PNG(红=差异强度,底图半亮衬底)。
 * 库用法(module.exports):decodePng / encodePng / diffImages / renderHeatmap(测试 imgdiff.test.js)。
 * 约束:8bit 非交错 PNG;colorType 0/2/6(灰/RGB/RGBA)。真机截图均为 2/6。
 */
const zlib = require('zlib');

// ---------- PNG 解码 ----------
function decodePng(buf) {
  if (!(buf[0] === 0x89 && buf.slice(1, 4).toString('latin1') === 'PNG')) throw new Error('not a PNG');
  let pos = 8;
  let width = 0, height = 0, bitDepth = 0, colorType = 0, interlace = 0;
  const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.slice(pos + 4, pos + 8).toString('latin1');
    const data = buf.slice(pos + 8, pos + 8 + len);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0); height = data.readUInt32BE(4);
      bitDepth = data[8]; colorType = data[9]; interlace = data[12];
    } else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    pos += 12 + len;
  }
  if (bitDepth !== 8) throw new Error('unsupported bitDepth ' + bitDepth);
  if (interlace !== 0) throw new Error('interlaced PNG unsupported');
  const channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[colorType];
  if (!channels) throw new Error('unsupported colorType ' + colorType);
  const raw = zlib.inflateSync(Buffer.concat(idat));
  // 反滤波(逐行:0 None / 1 Sub / 2 Up / 3 Average / 4 Paeth)
  const bpp = channels;
  const stride = width * bpp;
  const out = Buffer.alloc(stride * height);
  let rp = 0;
  for (let y = 0; y < height; y++) {
    const filter = raw[rp++];
    const row = raw.slice(rp, rp + stride); rp += stride;
    const prev = y > 0 ? out.slice((y - 1) * stride, y * stride) : null;
    const cur = out.slice(y * stride, (y + 1) * stride);
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? cur[x - bpp] : 0;
      const b = prev ? prev[x] : 0;
      const c = prev && x >= bpp ? prev[x - bpp] : 0;
      let v = row[x];
      if (filter === 1) v += a;
      else if (filter === 2) v += b;
      else if (filter === 3) v += (a + b) >> 1;
      else if (filter === 4) {
        const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
      }
      cur[x] = v & 255;
    }
  }
  // 归一为 RGBA
  const rgba = Buffer.alloc(width * height * 4);
  for (let i = 0; i < width * height; i++) {
    let r, g, b2, a2;
    if (colorType === 6) { r = out[i * 4]; g = out[i * 4 + 1]; b2 = out[i * 4 + 2]; a2 = out[i * 4 + 3]; }
    else if (colorType === 2) { r = out[i * 3]; g = out[i * 3 + 1]; b2 = out[i * 3 + 2]; a2 = 255; }
    else if (colorType === 0) { r = g = b2 = out[i]; a2 = 255; }
    else { const px = out[i * 2]; r = g = b2 = px; a2 = out[i * 2 + 1]; } // gray+alpha
    rgba[i * 4] = r; rgba[i * 4 + 1] = g; rgba[i * 4 + 2] = b2; rgba[i * 4 + 3] = a2;
  }
  return { width, height, data: rgba };
}

// ---------- PNG 编码(RGB8,filter 0)----------
const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 255] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function chunk(type, data) {
  const out = Buffer.alloc(12 + data.length);
  out.writeUInt32BE(data.length, 0);
  out.write(type, 4, 'latin1');
  data.copy(out, 8);
  out.writeUInt32BE(crc32(out.slice(4, 8 + data.length)), 8 + data.length);
  return out;
}

function encodePng(rgb, width, height) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  const stride = width * 3;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0; // filter none
    rgb.copy ? rgb.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride)
             : raw.set(rgb.subarray(y * stride, (y + 1) * stride), y * (stride + 1) + 1);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 6 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---------- 差异与热图 ----------
function diffImages(imgA, imgB, threshold = 8) {
  if (imgA.width !== imgB.width || imgA.height !== imgB.height) {
    throw new Error('size mismatch: ' + imgA.width + 'x' + imgA.height + ' vs ' + imgB.width + 'x' + imgB.height);
  }
  const w = imgA.width, h = imgA.height;
  const ch = channelsOf(imgA, imgB);
  let sum = 0, maxDiff = 0, changed = 0;
  let x0 = -1, y0 = -1, x1 = -1, y1 = -1;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * ch;
      const dr = Math.abs(imgA.data[i] - imgB.data[i]);
      const dg = Math.abs(imgA.data[i + 1] - imgB.data[i + 1]);
      const db = Math.abs(imgA.data[i + 2] - imgB.data[i + 2]);
      const d = Math.max(dr, dg, db);
      sum += d;
      if (d > maxDiff) maxDiff = d;
      if (d > threshold) {
        changed++;
        if (x0 < 0 || x < x0) x0 = x;
        if (y0 < 0 || y < y0) y0 = y;
        if (x > x1) x1 = x;
        if (y > y1) y1 = y;
      }
    }
  }
  return {
    w, h, meanDiff: sum / (w * h), maxDiff, changed, total: w * h,
    bbox: changed > 0 ? [x0, y0, x1, y1] : null,
  };
}

/** 通道数自适应:两图必须同为 RGB(3) 或 RGBA(4)。 */
function channelsOf(a, b) {
  const ca = a.data.length / (a.width * a.height);
  const cb = b.data.length / (b.width * b.height);
  if (ca !== cb || (ca !== 3 && ca !== 4)) throw new Error('channels mismatch: ' + ca + ' vs ' + cb);
  return ca;
}

/** 热图(RGB):R=差异强度 x4 饱和;未变处 = B 底图半亮衬底。 */
function renderHeatmap(imgA, imgB, threshold = 8) {
  const w = imgA.width, h = imgA.height;
  const ch = channelsOf(imgA, imgB);
  const out = Buffer.alloc(w * h * 3);
  for (let i = 0; i < w * h; i++) {
    const j = i * ch, k = i * 3;
    const dr = Math.abs(imgA.data[j] - imgB.data[j]);
    const dg = Math.abs(imgA.data[j + 1] - imgB.data[j + 1]);
    const db = Math.abs(imgA.data[j + 2] - imgB.data[j + 2]);
    const d = Math.max(dr, dg, db);
    if (d > threshold) {
      out[k] = Math.min(255, d * 4);
      out[k + 1] = imgB.data[j + 1] >> 1;
      out[k + 2] = imgB.data[j + 2] >> 1;
    } else {
      out[k] = 0;
      out[k + 1] = imgB.data[j + 1] >> 1;
      out[k + 2] = imgB.data[j + 2] >> 1;
    }
  }
  return out;
}

// ---------- CLI ----------
function main(argv) {
  const args = { files: [], out: null, threshold: 8, json: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '-o') args.out = argv[++i];
    else if (a === '--threshold') args.threshold = parseInt(argv[++i], 10);
    else if (a === '--json') args.json = true;
    else args.files.push(a);
  }
  if (args.files.length !== 2) {
    console.error('usage: node imgdiff.js A.png B.png [-o heat.png] [--threshold N] [--json]');
    process.exit(2);
  }
  const fs = require('fs');
  const a = decodePng(fs.readFileSync(args.files[0]));
  const b = decodePng(fs.readFileSync(args.files[1]));
  const d = diffImages(a, b, args.threshold);
  if (args.out) {
    fs.writeFileSync(args.out, encodePng(renderHeatmap(a, b, args.threshold), a.width, a.height));
  }
  console.log(JSON.stringify(d));
}

if (require.main === module) main(process.argv.slice(2));

module.exports = { decodePng, encodePng, diffImages, renderHeatmap };
