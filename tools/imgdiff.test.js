// SPDX-License-Identifier: GPL-3.0-or-later
'use strict';
/*
 * imgdiff 单元测试(零依赖,node imgdiff.test.js)。
 * 已知答案断言:合成图 + 精确矩形差异 → bbox/changed/maxDiff/mean 全部可手算。
 * 退出码 0 = 全过;非 0 = 有断言失败(错误打到 stderr)。
 */
const { encodePng, decodePng, diffImages, renderHeatmap } = require('./imgdiff.js');

let passed = 0;
function check(cond, what) {
  passed++;
  if (!cond) { console.error('FAIL ' + what); process.exit(1); }
}

function fillImage(w, h, rgb) {
  const data = new Uint8Array(w * h * 3);
  for (let i = 0; i < w * h; i++) { data[i * 3] = rgb[0]; data[i * 3 + 1] = rgb[1]; data[i * 3 + 2] = rgb[2]; }
  return { width: w, height: h, data };
}

function setRect(img, x0, y0, x1, y1, rgb) {
  for (let y = y0; y <= y1; y++) {
    for (let x = x0; x <= x1; x++) {
      const i = (y * img.width + x) * 3;
      img.data[i] = rgb[0]; img.data[i + 1] = rgb[1]; img.data[i + 2] = rgb[2];
    }
  }
}

// ---- 1 PNG 编解码往返(RGB)----
{
  const w = 33, h = 21; // 非整除尺寸,逼出 stride 处理
  const img = { width: w, height: h, data: new Uint8Array(w * h * 3) };
  for (let i = 0; i < w * h; i++) {
    img.data[i * 3] = (i * 7) & 255; img.data[i * 3 + 1] = (i * 13) & 255; img.data[i * 3 + 2] = (i * 29) & 255;
  }
  const png = encodePng(img.data, w, h);
  check(png.slice(1, 4).toString('latin1') === 'PNG', 'PNG 签名');
  const back = decodePng(png);
  check(back.width === w && back.height === h, '往返尺寸');
  let same = true;
  for (let i = 0; i < w * h; i++) {
    if (back.data[i * 4] !== img.data[i * 3] || back.data[i * 4 + 1] !== img.data[i * 3 + 1]
      || back.data[i * 4 + 2] !== img.data[i * 3 + 2] || back.data[i * 4 + 3] !== 255) { same = false; break; }
  }
  check(same, '往返像素一致(RGBA 输出,A=255)');
}

// ---- 2 完全一致 → 零差异 ----
{
  const a = fillImage(64, 48, [128, 128, 128]);
  const d = diffImages(a, a, 8);
  check(d.changed === 0 && d.meanDiff === 0 && d.maxDiff === 0, '同图: changed/mean/max 全零');
  check(d.bbox === null, '同图: bbox=null');
}

// ---- 3 已知矩形差异 ----
{
  const a = fillImage(64, 48, [128, 128, 128]);
  const b = fillImage(64, 48, [128, 128, 128]);
  setRect(b, 20, 10, 39, 29, [255, 0, 0]); // 20x20=400 px; 每像素 d=max(127,128,128)=128
  const d = diffImages(a, b, 8);
  check(d.changed === 400, '矩形差异 changed=400, 实得 ' + d.changed);
  check(d.maxDiff === 128, 'maxDiff=128');
  check(d.total === 64 * 48, 'total=全图像素');
  const expectedMean = 400 * 128 / (64 * 48);
  check(Math.abs(d.meanDiff - expectedMean) < 1e-9, 'meanDiff=' + expectedMean);
  check(d.bbox && d.bbox[0] === 20 && d.bbox[1] === 10 && d.bbox[2] === 39 && d.bbox[3] === 29, 'bbox=[20,10,39,29]');
  // 阈值高于最大差 → 零变化
  const d2 = diffImages(a, b, 200);
  check(d2.changed === 0 && d2.bbox === null, 'threshold=200 → 无变化');
}

// ---- 4 热图渲染 ----
{
  const a = fillImage(64, 48, [128, 128, 128]);
  const b = fillImage(64, 48, [128, 128, 128]);
  setRect(b, 20, 10, 39, 29, [255, 0, 0]);
  const heat = renderHeatmap(a, b, 8); // RGB
  const idx = (x, y) => (y * 64 + x) * 3;
  check(heat[idx(25, 15)] === 255, '热图差异中心 R=255(d=128→x4 饱和)');
  check(heat[idx(25, 15) + 1] === 0 && heat[idx(25, 15) + 2] === 0, '热图差异中心 G/B=底图该处值半亮(红矩形内=0)');
  check(heat[idx(5, 5)] === 0 && heat[idx(5, 5) + 1] === 64, '热图未变处 R=0,G=底图半亮(64)');
}

// ---- 5 经 PNG 往返的端到端 diff ----
{
  const a = fillImage(32, 32, [10, 200, 30]);
  const b = fillImage(32, 32, [10, 200, 30]);
  setRect(b, 8, 8, 12, 12, [0, 0, 0]);
  const da = decodePng(encodePng(a.data, 32, 32));
  const db = decodePng(encodePng(b.data, 32, 32));
  const d = diffImages(da, db, 8);
  check(d.changed === 25 && d.bbox[0] === 8 && d.bbox[3] === 12, '端到端(经 PNG)changed=25');
}

console.log('imgdiff.test.js: ALL PASS (' + passed + ' checks)');
