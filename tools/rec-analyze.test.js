// SPDX-License-Identifier: GPL-3.0-or-later
'use strict';
/* Strict rec-analyze end-to-end regression tests. */
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');
const { encodePng } = require('./imgdiff.js');

const analyzer = path.join(__dirname, 'rec-analyze.js');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'modtest-rec-analyze-'));
let checks = 0;

function check(condition, message) {
  checks++;
  assert.ok(condition, message);
}

function writeSolidPng(file, value) {
  const data = new Uint8Array(2 * 2 * 3);
  data.fill(Math.max(0, Math.min(255, Math.round(value))));
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, encodePng(data, 2, 2));
}

function makeRows(options = {}) {
  const n = options.n ?? 24;
  const stepMs = options.stepMs ?? 20;
  const startT = options.startT ?? 1000;
  const signalLagMs = options.signalLagMs ?? 0;
  const shotOrder = options.shotOrder || Array.from({ length: n }, (_, i) => i + 1);
  const droppedCounts = options.droppedCounts || [];
  const lines = ['# rec s0001 run=fixture targetFps=50 rows=C/L/R/G/P/S/F/D'];
  for (let i = 0; i < n; i++) {
    const t = startT + i * stepMs;
    // One complete cycle per 400ms. Visual at t follows driver at t + signalLagMs.
    const walkDist = (t - startT) / 400 * 2;
    lines.push(`C,${t},${100 + i},0,64,0,${(options.baseYaw ?? 0) + (options.yawPerFrame ?? 0) * i},${(options.basePitch ?? 0) + (options.pitchPerFrame ?? 0) * i},0,63,0,0,64,0,${walkDist.toFixed(6)},${walkDist.toFixed(6)},1,1,1`);
    lines.push(`L,${t},${100 + i},0,1,2,3,0,0,-1,56,6,0.8480,0.9511`);
    lines.push(`R,${t},${100 + i},7,0.5,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22`);
    if (options.gunFor) lines.push(options.gunFor(i, t));
  }
  for (const seq of shotOrder) {
    const index = seq - 1;
    const t = startT + index * stepMs;
    const frame = 100 + index;
    const filename = `shot-${String(seq).padStart(6, '0')}.png`;
    lines.push(`P,${t},${frame},${seq},${filename}`);
    lines.push(`S,${t},${frame},${seq},${filename}`);
  }
  droppedCounts.forEach((count, index) => {
    lines.push(`D,${startT + n * stepMs + index},${100 + n + index},${count},deadline`);
  });
  const dataRows = n * (3 + (options.gunFor ? 1 : 0)) + n * 2 + droppedCounts.length;
  const dropped = droppedCounts.reduce((sum, count) => sum + count, 0);
  lines.push(`# END rows=${dataRows} requested=${n} succeeded=${n} failed=0 dropped=${dropped} closeReason=still`);
  return { lines, n, startT, stepMs, signalLagMs };
}

function createSession(name, options = {}) {
  const dir = path.join(root, name);
  fs.mkdirSync(path.join(dir, 'screenshots'), { recursive: true });
  const fixture = makeRows(options);
  const lineEnding = options.lineEnding ?? '\n';
  fs.writeFileSync(path.join(dir, 'frames.csv'), fixture.lines.join(lineEnding) + (options.noFinalNewline ? '' : lineEnding), 'utf8');
  const sequence = options.pngSeqs || Array.from({ length: fixture.n }, (_, i) => i + 1);
  for (const seq of sequence) {
    const t = fixture.startT + (seq - 1) * fixture.stepMs;
    const shiftedT = t + fixture.signalLagMs;
    const walkDist = (shiftedT - fixture.startT) / 400 * 2;
    const driver = Math.sin(Math.PI * walkDist);
    const luma = options.lumaForSeq ? options.lumaForSeq(seq) : 128 + 90 * driver;
    writeSolidPng(path.join(dir, 'screenshots', `shot-${String(seq).padStart(6, '0')}.png`), luma);
  }
  return dir;
}

function run(dir, extra = []) {
  return spawnSync(process.execPath, [analyzer, dir, ...extra], { encoding: 'utf8', windowsHide: true });
}

function summary(dir) {
  return JSON.parse(fs.readFileSync(path.join(dir, 'summary.json'), 'utf8'));
}

function expectSuccess(dir, extra = []) {
  const result = run(dir, extra);
  check(result.status === 0, `expected success, got ${result.status}: ${result.stderr}`);
  check(result.stdout.includes('REC-ANALYZE PASS'), 'success authority string');
  check(fs.existsSync(path.join(dir, 'summary.json')), 'success writes summary');
  return summary(dir);
}

function expectFailure(dir, pattern) {
  const summaryPath = path.join(dir, 'summary.json');
  if (fs.existsSync(summaryPath)) fs.unlinkSync(summaryPath);
  const result = run(dir);
  check(result.status !== 0, 'failure exits non-zero');
  check(result.stderr.includes('REC-ANALYZE FAIL'), 'failure authority string');
  if (pattern) check(pattern.test(result.stderr), `failure matches ${pattern}: ${result.stderr}`);
  check(!fs.existsSync(summaryPath), 'hard failure does not write summary');
}

try {
  // 1) Valid known signal, bbox passthrough, correct FPS and complete summaries.
  {
    const dir = createSession('valid', { n: 24, droppedCounts: [3, 2] });
    const s = expectSuccess(dir, ['--bbox', '0,0,1,1']);
    check(s.integrity.ok === true, 'valid integrity=true');
    check(Math.abs(s.actualFps - 50) < 1e-9, 'FPS=(n-1)/duration');
    check(s.targetFps === 50, 'target FPS parsed from header');
    check(s.input.counts.P === 24 && s.input.counts.S === 24, 'input counts recorded');
    check(s.input.counts.D === 2 && s.dropped === 5, 'footer dropped equals sum(D.count), not D row count');
    check(s.visual.summary.mean && s.visual.summary.p90 && s.visual.summary.p99 && s.visual.summary.ge200Fraction, 'all visual metrics summarized');
    check(s.invariance.lights[0].anchor.x.range === 0 && s.invariance.remote[0].disp.x.range === 0, 'L/R invariance emitted');
    check(!JSON.stringify(s).includes('view-bob'), 'camera-eye delta is not named view-bob');
  }

  // 2) Lexically troublesome _2/_10 names are paired by seq token, not directory order.
  {
    const order = [1, 10, 2, 11, 3, 12, 4, 13, 5, 14, 6, 15, 7, 16, 8, 17, 9, 18, 19, 20, 21, 22, 23, 24];
    const dir = createSession('token-order', { n: 24, shotOrder: order, lumaForSeq: seq => seq });
    const s = expectSuccess(dir);
    const bySeq = new Map(s.visual.series.map(row => [row.seq, row.mean]));
    check(bySeq.get(2) === 2 && bySeq.get(10) === 10, '_2/_10 retain exact token pairing');
  }

  // 3) Missing successful PNG.
  {
    const dir = createSession('missing-png', { n: 12, pngSeqs: Array.from({ length: 11 }, (_, i) => i + 1) });
    expectFailure(dir, /successful PNG missing/);
  }

  // 4) Extra PNG.
  {
    const dir = createSession('extra-png', { n: 12 });
    writeSolidPng(path.join(dir, 'screenshots', 'shot-999999.png'), 1);
    expectFailure(dir, /extra PNG/);
  }

  // 5) Missing footer.
  {
    const dir = createSession('missing-footer', { n: 12 });
    const file = path.join(dir, 'frames.csv');
    const lines = fs.readFileSync(file, 'utf8').trimEnd().split('\n');
    lines.pop();
    fs.writeFileSync(file, lines.join('\n') + '\n');
    expectFailure(dir, /footer/);
  }

  // 6) NUL byte.
  {
    const dir = createSession('nul', { n: 12 });
    fs.appendFileSync(path.join(dir, 'frames.csv'), Buffer.from([0]));
    expectFailure(dir, /NUL/);
  }

  // 7) S/P mismatch.
  {
    const dir = createSession('sp-mismatch', { n: 12 });
    const file = path.join(dir, 'frames.csv');
    const lines = fs.readFileSync(file, 'utf8').trimEnd().split('\n');
    const sIndex = lines.findIndex(line => line.startsWith('S,') && line.split(',')[3] === '12');
    lines.splice(sIndex, 1);
    const footer = lines.length - 1;
    lines[footer] = lines[footer].replace(/rows=60/, 'rows=59').replace(/succeeded=12/, 'succeeded=11');
    fs.writeFileSync(file, lines.join('\n') + '\n');
    expectFailure(dir, /P\/S\/F mismatch|no S\/F outcome/);
  }

  // 8) 120 images are split into three lumastats batches of <=50.
  {
    const dir = createSession('batch-120', { n: 120, stepMs: 20 });
    const s = expectSuccess(dir);
    check(s.visual.lumastatsBatchSize === 50 && s.visual.lumastatsBatches === 3, '120 images use 50/50/20 batches');
  }

  // 9) Known lag recovery. visual(t)=driver(t+73ms), expected +73ms within quantization tolerance.
  {
    const dir = createSession('lag', { n: 100, stepMs: 20, signalLagMs: 73 });
    const s = expectSuccess(dir);
    const best = s.timeLagCorrelations.metrics.mean;
    check(best && Math.abs(best.lagMs - 73) <= 3, `known lag recovered: ${JSON.stringify(best)}`);
    check(best && Math.abs(best.r) > 0.99, 'known lag correlation is strong');
  }

  // Additional hard gates requested by the protocol.
  {
    const dir = createSession('duplicate-sampling', { n: 12, stepMs: 5 });
    expectFailure(dir, /duplicate sampling/);
  }
  {
    const dir = createSession('truncated-line', { n: 12, noFinalNewline: true });
    expectFailure(dir, /truncated final line/);
  }
  {
    const dir = createSession('footer-count', { n: 12 });
    const file = path.join(dir, 'frames.csv');
    fs.writeFileSync(file, fs.readFileSync(file, 'utf8').replace('requested=12', 'requested=11'));
    expectFailure(dir, /footer requested/);
  }

  // ---- light-chain invariants: G rows (34 columns); invariance/continuity checks ----
  // G 列序: G,t,frame,id,state,weight,tpfb,tproe, rawF(3), capYaw,capPitch, quat(4),
  //          eye(3), pos(3), dir(3), refPos(3), refYaw,refPitch, aim,item,hold
  function gunLine(t, frame, fields = {}) {
    const f = Object.assign({
      id: 7, state: 'fresh', weight: '1.000', tpfb: 'blend', tproe: 'col',
      raw: [0.05, 0.02, 0.9], cap: ['0', '0'], quat: ['0', '0', '0', '1'],
      eye: ['10', '64', '10'], pos: ['1', '64', '9'], dir: [0.05, 0.02, 0.9],
      refPos: ['1', '64', '1'], refAng: ['90', '0'], aim: '0', item: '7', hold: '1',
    }, fields);
    const d = typeof f.dir === 'function' ? null : f.dir;
    const parts = ['G', t, frame, f.id, f.state, f.weight, f.tpfb, f.tproe,
      ...f.raw, ...f.cap, ...f.quat, ...f.eye, ...f.pos,
      ...(d ? d : f.dir(frame)).map(v => Number(v).toFixed(5)),
      ...f.refPos, ...f.refAng, f.aim, f.item, f.hold];
    return parts.join(',');
  }

  // 10) Green: frozen target, constant world dir, camera sweeping 1.2°/frame -> both PASS.
  {
    const dir = [0.05, 0.02, 0.9];
    const gunFor = (i, t) => gunLine(t, 100 + i, { dir });
    const s = expectSuccess(createSession('tp-green', { n: 120, yawPerFrame: 1.2, gunFor }));
    check(s.tp && s.tp.entities.length === 1, 'tp block emitted');
    const e = s.tp.entities[0];
    check(e.invariance.verdict === 'PASS', `invariance PASS: ${JSON.stringify(e.invariance)}`);
    check(e.continuity.verdict === 'PASS', `continuity PASS: ${JSON.stringify(e.continuity)}`);
    check(e.invariance.cameraTravelDeg > 140 && e.invariance.maxDirDevDeg < 1e-6, 'travel counted from C rows; dir frozen');
  }

  // 11) Red mutation (坑68 signature): world dir rotates with camera -> TP-INVARIANCE FAIL.
  {
    const gunFor = (i, t) => gunLine(t, 100 + i, {
      dir: frame => {
        const yawRad = (-60 + frame * 1.2) * Math.PI / 180;
        return [Math.cos(yawRad) * 0.9, 0.02, Math.sin(yawRad) * 0.9];
      },
    });
    expectFailure(createSession('tp-rot', { n: 120, yawPerFrame: 1.2, gunFor }), /TP-INVARIANCE FAIL/);
  }

  // 12) Red continuity: 0.5-block step at fresh->fallback transition -> TP-CONTINUITY FAIL.
  {
    const gunFor = (i, t) => gunLine(t, 100 + i, i < 60
      ? { state: 'fresh', pos: ['1', '64', '9'] }
      : { state: 'fallback', weight: '0.000', hold: '0', pos: ['1', '64', '9.5'] });
    expectFailure(createSession('tp-step', { n: 120, gunFor }), /TP-CONTINUITY FAIL/);
  }

  // 13) Hard mode: same big step is REPORT-ONLY (legacy baseline), no continuity failure.
  {
    const gunFor = (i, t) => gunLine(t, 100 + i, i < 60
      ? { state: 'fresh', tpfb: 'hard', pos: ['1', '64', '9'] }
      : { state: 'fallback', tpfb: 'hard', weight: '0.000', hold: '0', pos: ['1', '64', '9.5'] });
    const s = expectSuccess(createSession('tp-hard', { n: 120, gunFor }));
    check(s.tp.entities[0].continuity.verdict === 'REPORT-ONLY', 'hard mode continuity report-only');
  }

  // 14) G validation: unknown state string is a hard failure.
  {
    const gunFor = (i, t) => gunLine(t, 100 + i, { state: 'frozen' });
    expectFailure(createSession('tp-bad-state', { n: 12, gunFor }), /G\.state unknown/);
  }

  // 15) Green follow: referent walks AND turns; local-frame (muzzle−eye) offset constant
  //     (yaw rotation cancels in local frame) -> TP-FOLLOW PASS. 修复核心不变式。
  {
    const OFF = [1.038, -0.326, 0.142];
    const gunFor = (i, t) => {
      const yaw = -90 + i * 1.2;
      const rad = yaw * Math.PI / 180;
      const wx = OFF[0] * -Math.sin(rad) + OFF[2] * -Math.cos(rad);
      const wz = OFF[0] * Math.cos(rad) + OFF[2] * -Math.sin(rad);
      const rz = (1 + i * 0.05).toFixed(4);
      return gunLine(t, 100 + i, {
        state: i < 60 ? 'hold' : 'fresh',
        pos: [(1 + wx).toFixed(4), '63.674', (Number(rz) + wz).toFixed(4)],
        refPos: ['1', '64', rz],
        refAng: [yaw.toFixed(3), '0'],
      });
    };
    const s = expectSuccess(createSession('tp-follow-green', { n: 120, gunFor }));
    const f = s.tp.entities[0].follow;
    check(f.verdict === 'PASS', `follow PASS: ${JSON.stringify(f)}`);
    check(f.groups[0].travel > 5.5 && f.groups[0].drift <= 0.3, 'walk 6 blocks with turning; local offset drift ~0');
  }

  // 16) Red follow: walking referent but fwd offset jumps 0.45->1.04 mid-hold
  //     (灯不随人/偏移漂移签名;全程 hold 避开状态过渡由 TP-CONTINUITY 先截) -> TP-FOLLOW FAIL.
  {
    const gunFor = (i, t) => {
      const fwd = i < 60 ? 0.45 : 1.04;
      return gunLine(t, 100 + i, {
        state: 'hold',
        pos: [(1 + fwd).toFixed(4), '63.674', String(1 + i * 0.05)],
        refPos: ['1', '64', (1 + i * 0.05).toFixed(4)],
      });
    };
    expectFailure(createSession('tp-follow-red', { n: 120, gunFor }), /TP-FOLLOW FAIL/);
  }

  // 17) Follow REPORT-ONLY: no referent travel (static scene) never hard-fails.
  {
    const gunFor = (i, t) => gunLine(t, 100 + i, {});
    const s = expectSuccess(createSession('tp-follow-static', { n: 120, gunFor }));
    check(s.tp.entities[0].follow.verdict === 'REPORT-ONLY', 'static scene follow report-only');
  }

  console.log(`REC-ANALYZE-TEST PASS (${checks} checks)`);
} finally {
  fs.rmSync(root, { recursive: true, force: true });
}
