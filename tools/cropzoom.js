// SPDX-License-Identifier: GPL-3.0-or-later
'use strict';
// cropzoom.js — 裁剪并最近邻放大 PNG 区域(零依赖,复用 imgdiff 解码/编码)
// 用法: node cropzoom.js IN.png OUT.png X0 Y0 X1 Y1 [SCALE]
const { decodePng, encodePng } = require('./imgdiff.js');
const fs = require('fs');
const [inp, outp, x0s, y0s, x1s, y1s, sS] = process.argv.slice(2);
const { data, width: W, height: H } = decodePng(fs.readFileSync(inp));
const x0 = +x0s, y0 = +y0s, x1 = +x1s, y1 = +y1s, S = +(sS || 4);
const cw = x1 - x0, ch = y1 - y0, ow = cw * S, oh = ch * S;
const out = Buffer.alloc(ow * oh * 4, 255);
for (let y = 0; y < oh; y++) for (let x = 0; x < ow; x++) {
  const sx = x0 + Math.min(cw - 1, (x / S) | 0), sy = y0 + Math.min(ch - 1, (y / S) | 0);
  const si = (sy * W + sx) * 4, di = (y * ow + x) * 4;
  out[di] = data[si]; out[di+1] = data[si+1]; out[di+2] = data[si+2]; out[di+3] = 255;
}
fs.writeFileSync(outp, encodePng(out, ow, oh));
console.log(`cropzoom ${inp} [${x0},${y0},${x1},${y1}] x${S} -> ${outp} (${ow}x${oh})`);
