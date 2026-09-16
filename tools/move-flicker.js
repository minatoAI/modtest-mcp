// SPDX-License-Identifier: GPL-3.0-or-later
// move-flicker.js - flicker metrics for a moving light source.
// 输入:连拍 PNG 目录;输出:
//  1) 每帧光斑亮度 L_t(luma>100 区均值)/ 饱和面积 sat_t / 全窗 p99
//  2) 相邻帧 ΔL 与符号翻转计数(忽亮忽暗 = 高频符号翻转)
//  3) 去趋势(3 帧滑动均值)残差 RMS → 闪烁指数
//  4) 逐像素时域标准差:光斑内部(luma>100)均值 vs 光斑外 → 区分"亮度闪烁"与"边缘游动"
// 用法:node tools/move-flicker.js <dir> <tag> [count]
const {decodePng} = require('./imgdiff.js');
const fs = require('fs');
const path = require('path');
const dir = process.argv[2], tag = process.argv[3];
const count = parseInt(process.argv[4] || '14', 10);

// BMP(32bpp BI_RGB bottom-up)解码:GDI+ MemoryBmp 落盘为 54B 头 + 像素
function decodeBmp(buf) {
  if (buf.readUInt16LE(0) !== 0x4d42) throw new Error('not bmp');
  const off = buf.readUInt32LE(10);
  const w = buf.readInt32LE(18), h = buf.readInt32LE(22);
  const bpp = buf.readUInt16LE(28);
  const data = Buffer.alloc(w * h * 4);
  const rowSize = Math.floor((bpp * w + 31) / 32) * 4;
  for (let y = 0; y < h; y++) {
    const src = off + (h - 1 - y) * rowSize;           // bottom-up → 翻正
    for (let x = 0; x < w; x++) {
      const s = src + x * (bpp / 8), d = (y * w + x) * 4;
      data[d] = buf[s + 2]; data[d + 1] = buf[s + 1]; data[d + 2] = buf[s]; data[d + 3] = 255;
    }
  }
  return {width: w, height: h, data};
}
function loadAny(p) {
  const buf = fs.readFileSync(p);
  return (buf.readUInt16LE(0) === 0x4d42) ? decodeBmp(buf) : decodePng(buf);
}

const frames = [];
for (let i = 0; i < count; i++) {
  const pb = path.join(dir, `${tag}_${String(i).padStart(3, '0')}.bmp`);
  const pp = path.join(dir, `${tag}_${String(i).padStart(3, '0')}.png`);
  if (fs.existsSync(pb)) frames.push(loadAny(pb));
  else if (fs.existsSync(pp)) frames.push(loadAny(pp));
  else break;
}
if (frames.length < 3) { console.log('NEED >=3 frames'); process.exit(1); }
const W = frames[0].width, H = frames[0].height;
const y0 = Math.floor(H * 0.08), y1 = Math.floor(H * 0.84);
const stats = frames.map(img => {
  let sum = 0, n = 0, sat = 0, tot = 0, hi = 0;
  for (let y = y0; y < y1; y++) for (let x = 0; x < W; x++) {
    const i = (y * W + x) * 4;
    const luma = 0.2126 * img.data[i] + 0.7152 * img.data[i + 1] + 0.0722 * img.data[i + 2];
    tot++;
    if (luma >= 220) sat++;
    if (luma > 100) { sum += luma; n++; }
    if (luma > 240) hi++;
  }
  return { L: n ? sum / n : 0, n, sat: sat / tot, hi: hi / tot };
});
console.log('== 时序(每帧) ==');
stats.forEach((s, i) => console.log(
  `#${String(i).padStart(2, '0')} L=${s.L.toFixed(1)} n=${s.n} sat=${(s.sat * 100).toFixed(1)}% hi240=${(s.hi * 100).toFixed(2)}%`));
console.log('== 相邻帧 ΔL ==');
let flips = 0, prevD = 0, dCount = 0, dSum = 0;
for (let i = 1; i < stats.length; i++) {
  const d = stats[i].L - stats[i - 1].L;
  dSum += Math.abs(d); dCount++;
  if (i >= 2 && prevD * d < 0 && Math.abs(prevD) > 1.5 && Math.abs(d) > 1.5) flips++;
  prevD = d;
  console.log(`#${i - 1}->#${i} ΔL=${d >= 0 ? '+' : ''}${d.toFixed(2)}`);
}
// 去趋势残差:3 帧滑动均值
let resSum = 0, resN = 0, resMax = 0;
for (let i = 1; i < stats.length - 1; i++) {
  const trend = (stats[i - 1].L + stats[i].L + stats[i + 1].L) / 3;
  const r = stats[i].L - trend;
  resSum += r * r; resN++;
  if (Math.abs(r) > resMax) resMax = Math.abs(r);
}
const resRms = Math.sqrt(resSum / resN);
console.log('== 汇总 ==');
console.log(`闪烁指数(去趋势残差 RMS / 均值 L)= ${(resRms / stats.reduce((a, s) => a + s.L, 0) / stats.length * 100).toFixed(2)}%  (残差RMS=${resRms.toFixed(2)}, 峰值=${resMax.toFixed(2)})`);
console.log(`|ΔL| 均值 = ${(dSum / dCount).toFixed(2)}  显著符号翻转(|Δ|>1.5) = ${flips}`);
// 逐像素时域 σ:光斑内 vs 光斑外
const mean = new Float64Array(W * H);
for (const img of frames)
  for (let y = y0; y < y1; y++) for (let x = 0; x < W; x++) {
    const p = y * W + x, i = p * 4;
    mean[p] += 0.2126 * img.data[i] + 0.7152 * img.data[i + 1] + 0.0722 * img.data[i + 2];
  }
for (let p = 0; p < mean.length; p++) mean[p] /= frames.length;
let inSum = 0, inN = 0, outSum = 0, outN = 0, inMax = 0;
const varMap = new Float64Array(W * H);
for (const img of frames)
  for (let y = y0; y < y1; y++) for (let x = 0; x < W; x++) {
    const p = y * W + x, i = p * 4;
    const luma = 0.2126 * img.data[i] + 0.7152 * img.data[i + 1] + 0.0722 * img.data[i + 2];
    const d = luma - mean[p];
    varMap[p] += d * d;
  }
for (let y = y0; y < y1; y++) for (let x = 0; x < W; x++) {
  const p = y * W + x;
  const sd = Math.sqrt(varMap[p] / frames.length);
  if (mean[p] > 100) { inSum += sd; inN++; if (sd > inMax) inMax = sd; }
  else { outSum += sd; outN++; }
}
console.log(`光斑内时域σ: 均值=${(inSum / inN).toFixed(2)} 峰值=${inMax.toFixed(2)} | 光斑外σ均值=${(outSum / outN).toFixed(2)}`);
console.log(`注:移动本身带来边缘位移(光斑外σ升高属预期);判据=光斑内部σ(亮度闪烁)`);
