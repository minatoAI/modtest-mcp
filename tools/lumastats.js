// SPDX-License-Identifier: GPL-3.0-or-later
'use strict';
/*
  * lumastats.js - screenshot luminance statistics (no deps, node >= 18; reuses imgdiff.js PNG decode).
  * Use: over-exposure checks for overlapping light sources - luma distribution and near-saturation ratio in a region.
 *
 * 用法:
 *   node lumastats.js A.png [B.png ...] [--bbox x0,y0,x1,y1] [--exclude x0,y0,x1,y2 ...]
 * 输出(每图一行 JSON):mean/max luma、分位、近饱和(>=thr)像素数与占比。
 * luma = 0.2126R+0.7152G+0.0722B(Rec.709,8bit)。
 * 排除矩形用于扣除画面上的常量 UI(如教程提示框),避免污染绝对饱和计数。
 */
const fs = require('fs');
const { decodePng } = require('./imgdiff.js');

function parseRect(s) {
  const p = s.split(',').map(Number);
  if (p.length !== 4 || p.some(isNaN)) throw new Error('bad rect: ' + s);
  return p; // [x0,y0,x1,y1] 闭区间
}

const args = process.argv.slice(2);
const files = [];
let bbox = null;
const excludes = [];
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--bbox') bbox = parseRect(args[++i]);
  else if (args[i] === '--exclude') excludes.push(parseRect(args[++i]));
  else files.push(args[i]);
}
if (!files.length) { console.error('usage: node lumastats.js A.png [--bbox x0,y0,x1,y1] [--exclude ...]'); process.exit(1); }

const inRect = (x, y, r) => x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3];

for (const f of files) {
  const img = decodePng(fs.readFileSync(f));
  const { width: w, height: h, data } = img;
  const x0 = bbox ? Math.max(0, bbox[0]) : 0, y0 = bbox ? Math.max(0, bbox[1]) : 0;
  const x1 = bbox ? Math.min(w - 1, bbox[2]) : w - 1, y1 = bbox ? Math.min(h - 1, bbox[3]) : h - 1;
  let n = 0, sum = 0, max = 0, c250 = 0, c200 = 0, c128 = 0;
  const lumas = [];
  for (let y = y0; y <= y1; y++) {
    for (let x = x0; x <= x1; x++) {
      if (excludes.some((r) => inRect(x, y, r))) continue;
      const i = (y * w + x) * 4;
      const l = 0.2126 * data[i] + 0.7152 * data[i + 1] + 0.0722 * data[i + 2];
      n++; sum += l; lumas.push(l);
      if (l > max) max = l;
      if (l >= 250) c250++;
      if (l >= 200) c200++;
      if (l >= 128) c128++;
    }
  }
  lumas.sort((a, b) => a - b);
  const q = (p) => Math.round(lumas[Math.min(lumas.length - 1, Math.floor(p * lumas.length))] * 10) / 10;
  console.log(JSON.stringify({
    file: f.split(/[\\/]/).pop(), region: [x0, y0, x1, y1], px: n,
    mean: Math.round((sum / n) * 10) / 10, p50: q(0.5), p90: q(0.9), p99: q(0.99), max: Math.round(max * 10) / 10,
    ge128: c128, ge200: c200, ge250: c250,
    frac250: Math.round((c250 / n) * 10000) / 10000,
  }));
}
