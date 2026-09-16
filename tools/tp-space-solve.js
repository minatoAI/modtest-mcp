// SPDX-License-Identifier: GPL-3.0-or-later
// View-space capture -> world mapping solver (from recorded sample pairs).
// Input: measured samples plus ground-truth world positions; the procedure is reproducible.
// Method: enumerate candidate rotations R, map the view-space vector into world space and
'use strict';
const rad = (d) => (d * Math.PI) / 180;

// ---- joml 语义最小实现(四元数 xyzw;rotate = v.rotate(q),右侧先作用)----
function qmul(a, b) { // this=a, arg=b(joml mul 语义)
  const [ax, ay, az, aw] = a, [bx, by, bz, bw] = b;
  return [
    aw * bx + ax * bw + ay * bz - az * by,
    aw * by + ay * bw + az * bx - ax * bz,
    aw * bz + az * bw + ax * by - ay * bx,
    aw * bw - ax * bx - ay * by - az * bz,
  ];
}
function qrot(q, v) { // joml Vector3f.rotate(q) 语义:归一化真旋转 q·v·q⁻¹
  const ql = Math.hypot(q[0], q[1], q[2], q[3]);
  const n = q.map((c) => c / ql);
  const [vx, vy, vz] = v, [qx, qy, qz, qw] = n;
  const tx = 2 * (qy * vz - qz * vy), ty = 2 * (qz * vx - qx * vz), tz = 2 * (qx * vy - qy * vx);
  return [vx + qw * tx + (qy * tz - qz * ty), vy + qw * ty + (qz * tx - qx * tz), vz + qw * tz + (qx * ty - qy * tx)];
}
function qconj(q) { return [-q[0], -q[1], -q[2], q[3]]; }
// vanilla Camera.rotation() = rotationYXZ(-yaw, pitch, 0)(契约注释实测约定)
function rotYXZ(yawDeg, pitchDeg) {
  const y = rotY(rad(-yawDeg)), x = rotX(rad(pitchDeg));
  return qmul(y, x); // rotationYXZ = Y 后 X(Y·X,先作用 X)
}
function rotY(a) { const s = Math.sin(a / 2), c = Math.cos(a / 2); return [0, s, 0, c]; }
function rotX(a) { const s = Math.sin(a / 2), c = Math.cos(a / 2); return [s, 0, 0, c]; }
const norm = (v) => { const l = Math.hypot(...v); return v.map((c) => c / l); };
const add = (a, b) => a.map((c, i) => c + b[i]);
const sub = (a, b) => a.map((c, i) => c - b[i]);
const scale = (a, s) => a.map((c) => c * s);
const len = (v) => Math.hypot(...v);
const dot = (a, b) => a.reduce((s, c, i) => s + c * b[i], 0);
const angDeg = (a, b) => (Math.acos(Math.max(-1, Math.min(1, dot(norm(a), norm(b))))) * 180) / Math.PI;

// ---- 实测样本(09-02 10:39 DIAG-TP,B 漂移机位 yRot=0 xRot=45)----
const camQ = [0.383, 0.0, 0.0, 0.924]; // 实机 camQ 字段
const camQchk = rotYXZ(0, 45);         // rotationYXZ(-0,45,0) 应与上者一致
const cp = [2000.74, 125.62, 0.76];    // B 相机位
const offRaw = [-7.41, -1.03, -3.95];  // HEAD 矩阵平移(视图空间)
const raw = [-0.745, 0.475, -0.469];   // 配对差值方向(归一)
const deepNegZ = [0.475, 0.264, 0.146];// 深矩阵 -Z 列(未归一,含骨缩放)
const A = [2007.7, 121.0, 4.2];        // DIAG-REMOTE Dev 脚底
const eye = add(A, [0, 1.62, 0]);
const look = [0.845, -0.171, -0.507];  // DIAG-TP look 字段

console.log('camQ 实机 vs rotationYXZ(-yaw,pitch,0) 构造一致:',
  camQ.every((c, i) => Math.abs(c - camQchk[i]) < 5e-4));
console.log('|offRaw| =', len(offRaw).toFixed(2), ' |cp→A眼| =', len(sub(eye, cp)).toFixed(2));

const Ry180 = rotY(Math.PI);
const candidates = {
  'Q·Ry180': qmul(camQ, Ry180),
  'Q': camQ,
  'Q⁻¹·Ry180': qmul(qconj(camQ), Ry180),
  'Q⁻¹': qconj(camQ),
  'I': [0, 0, 0, 1],
};

// ---- 位置:cp + R·offRaw 应落在target region (~0.2..1.6 blocks in front of the eye)----
console.log('\n== 位置候选(cp + R·offRaw,判据:距 眼+look·k(k∈0.2..1.2) 最近值) ==');
for (const [name, R] of Object.entries(candidates)) {
  const w = add(cp, qrot(R, offRaw));
  let best = 1e9, bk = 0;
  for (let k = 0.2; k <= 1.201; k += 0.05) {
    const d = len(sub(w, add(eye, scale(look, k))));
    if (d < best) { best = d; bk = k; }
  }
  console.log(`${name.padEnd(10)} -> (${w.map((c) => c.toFixed(2)).join(', ')})  distance to reference axis ${best.toFixed(2)} 格 @k=${bk.toFixed(2)}`);
}

// ---- 方向:R·normalize(v) 与 look 的夹角(plausible direction window 10..50 deg)----
console.log('\n== 方向候选(与 look 夹角) ==');
const dirs = {
  'raw': raw, '-raw': scale(raw, -1),
  'col2(=−deepNegZ)': norm(deepNegZ).map((c) => -c), '-col2(=deepNegZ)': norm(deepNegZ),
};
for (const [name, R] of Object.entries(candidates)) {
  for (const [dn, v] of Object.entries(dirs)) {
    const w = qrot(R, v);
    const a = angDeg(w, look);
    const flag = a >= 10 && a <= 50 ? ' ✓合理' : '';
    console.log(`${name.padEnd(10)} · ${dn.padEnd(18)} -> ${a.toFixed(1)}°${flag}`);
  }
}
