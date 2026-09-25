// analyze-regions.js -- drift-controlled region analysis for screenshots.
//
// WHY: whole-image diffs are dominated by the aurora sky, the animated gun/HUD and snow particles
// (see a heat-control-1.0-vs-1.0.png). A localised effect can only be judged on the TERRAIN regions,
// and only against a same-state control pair measured at the same spacing.
//
// CANONICAL -- DO NOT COPY THIS FILE INTO AN EVIDENCE DIRECTORY.
//   This is the one live copy: <modtest-mcp>/tools/analyze-regions.js. Until 2026-09-27 it existed only
//   as copies inside evidence dirs (7 byte-identical, sha256 2A73E6D4..., 2,756 B) plus a fork that
//   changed nothing but the five rectangles (analyze-regions-poseYaw34.js, 5 lines of diff). The copies
//   also required imgdiff.js by a HARDCODED absolute path
//   (require('E:/.../modtest-mcp/tools/imgdiff.js')), so they broke on any other machine or worktree.
//   The rectangles are now a PARAMETER (--regions) instead of a forked file, and imgdiff.js is resolved
//   relative to this file. Call it where it lives.
//
// Usage:
//   node tools/analyze-regions.js A.png B.png [C.png ...] [--regions <json|@file>] [--json]
//     [--label NAME]
//   --regions : region rectangles as inline JSON, or @path to a JSON file, e.g.
//               '{"spot":[540,330,780,480],"ground":[100,700,900,780]}'
//               Each region is [x0,y0,x1,y1] in image pixels, inclusive.
//   --json    : print ONLY one machine-readable JSON object (the text report is suppressed).
//   --label   : label recorded in the JSON output (default: 'default' or 'custom').
// The DEFAULT region set is the archived baseline. With it the text output is identical, line for
// line, to what the archived copies printed -- asserted by tools/analyze-regions.test.js against
// archived PNGs and archived reports. That equivalence is the point: a tool that reproduces the old
// numbers is safe to switch to.
//
// Exit: 0 ok | 2 bad usage (fewer than 2 images, malformed --regions, unreadable file).

'use strict';

const fs = require('fs');
const path = require('path');
const { decodePng } = require(path.join(__dirname, 'imgdiff.js'));

// The archived baseline region set. Do not change these rectangles: every archived region-analysis.txt
// was produced with them, and tools/analyze-regions.test.js asserts they are unchanged.
const DEFAULT_REGIONS = {
  sky: [0, 0, 1279, 150],
  farTerrainRight: [620, 250, 1270, 460],
  leftCliff: [0, 150, 450, 520],
  nearWallPool: [430, 360, 700, 600],
  groundLeft: [0, 500, 500, 700],
};

function lum(px, i) { return 0.299 * px[i] + 0.587 * px[i + 1] + 0.114 * px[i + 2]; }

function regionStats(img, r) {
  const [x0, y0, x1, y1] = r;
  let sum = 0, n = 0, lit = 0;
  for (let y = y0; y <= y1; y++) {
    for (let x = x0; x <= x1; x++) {
      const i = (y * img.width + x) * 4;
      const l = lum(img.data, i);
      sum += l; n++;
      if (l >= 128) lit++;
    }
  }
  return { mean: sum / n, lit, n };
}

function regionDiff(a, b, r) {
  const [x0, y0, x1, y1] = r;
  let sum = 0, max = 0, changed = 0, n = 0;
  for (let y = y0; y <= y1; y++) {
    for (let x = x0; x <= x1; x++) {
      const i = (y * a.width + x) * 4;
      let d = 0;
      for (let c = 0; c < 3; c++) d = Math.max(d, Math.abs(a.data[i + c] - b.data[i + c]));
      sum += d; n++;
      if (d > max) max = d;
      if (d >= 8) changed++;
    }
  }
  return { meanDiff: sum / n, maxDiff: max, changed, n, frac: changed / n };
}

// A region must be four finite numbers with x0 <= x1 and y0 <= y1. Anything else used to be accepted
// silently and then produce meaningless (or NaN) numbers -- refuse it instead.
function validateRegions(candidate, where) {
  if (candidate === null || typeof candidate !== 'object' || Array.isArray(candidate)) {
    throw new Error(`${where}: expected an object of name -> [x0,y0,x1,y1], got ${JSON.stringify(candidate)}`);
  }
  const names = Object.keys(candidate);
  if (names.length === 0) throw new Error(`${where}: no regions given`);
  const out = {};
  for (const name of names) {
    const r = candidate[name];
    const ok = Array.isArray(r) && r.length === 4 && r.every(v => typeof v === 'number' && Number.isFinite(v))
      && r[0] <= r[2] && r[1] <= r[3];
    if (!ok) throw new Error(`${where}: region '${name}' must be [x0,y0,x1,y1] with x0<=x1, y0<=y1; got ${JSON.stringify(r)}`);
    out[name] = r.slice();
  }
  return out;
}

function path0(p) { return p.split(/[\\/]/).pop(); }

function parseArgs(argv) {
  const opts = { regions: null, json: false, label: '', files: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--json') { opts.json = true; continue; }
    if (a === '--regions') {
      const raw = argv[++i];
      if (raw === undefined) throw new Error('--regions needs a value (inline JSON or @file)');
      if (raw.startsWith('@')) {
        const p = raw.slice(1);
        if (!fs.existsSync(p)) throw new Error(`--regions file not found: ${p}`);
        opts.regions = JSON.parse(fs.readFileSync(p, 'utf8'));
      } else {
        opts.regions = JSON.parse(raw);
      }
      continue;
    }
    if (a === '--label') {
      const raw = argv[++i];
      if (raw === undefined) throw new Error('--label needs a value');
      opts.label = raw;
      continue;
    }
    if (a === '--help' || a === '-h') { opts.help = true; continue; }
    if (a.startsWith('--')) throw new Error(`unknown option: ${a}`);
    opts.files.push(a);
  }
  return opts;
}

const USAGE = 'usage: analyze-regions.js A.png B.png [C.png ...] [--regions <json|@file>] [--label NAME] [--json]';

function main(argv) {
  let opts;
  try {
    opts = parseArgs(argv);
  } catch (err) {
    console.error(String(err.message));
    console.error(USAGE);
    process.exit(2);
  }
  if (opts.help) { console.log(USAGE); process.exit(0); }
  if (opts.files.length < 2) { console.error('need >=2 pngs'); console.error(USAGE); process.exit(2); }

  let regions;
  let isDefault = false;
  try {
    if (opts.regions === null) { regions = validateRegions(DEFAULT_REGIONS, 'default regions'); isDefault = true; }
    else { regions = validateRegions(opts.regions, '--regions'); }
  } catch (err) {
    console.error(String(err.message));
    process.exit(2);
  }
  const label = opts.label || (isDefault ? 'default' : 'custom');

  let imgs;
  try {
    imgs = opts.files.map(f => ({ name: f, img: decodePng(fs.readFileSync(f)) }));
  } catch (err) {
    console.error(`cannot read/decode an image: ${err.message}`);
    process.exit(2);
  }

  const perImage = [];
  for (const { name, img } of imgs) {
    const stats = {};
    for (const [rn, r] of Object.entries(regions)) stats[rn] = regionStats(img, r);
    perImage.push({ file: path0(name), path: name, width: img.width, height: img.height, stats });
  }
  const pairs = [];
  for (let i = 0; i + 1 < imgs.length; i++) {
    const a = imgs[i], b = imgs[i + 1];
    const diffs = {};
    for (const [rn, r] of Object.entries(regions)) diffs[rn] = regionDiff(a.img, b.img, r);
    pairs.push({ from: path0(a.name), to: path0(b.name), diffs });
  }

  if (opts.json) {
    console.log(JSON.stringify({ label, regions, images: perImage, pairs }, null, 2));
    return;
  }

  // --- text report: kept byte-for-byte compatible with the archived copies -----
  console.log('== per-image region stats (mean luminance / lit>=128 pixels) ==');
  for (const item of perImage) {
    const parts = [];
    for (const rn of Object.keys(regions)) {
      const s = item.stats[rn];
      parts.push(`${rn}: mean=${s.mean.toFixed(1)} lit=${s.lit}/${s.n}`);
    }
    console.log(item.file + '  ' + parts.join(' | '));
  }
  console.log('\n== pairwise region diffs (adjacent pairs; control pair is marked) ==');
  for (const item of pairs) {
    const parts = [];
    for (const rn of Object.keys(regions)) {
      const d = item.diffs[rn];
      parts.push(`${rn}: mean=${d.meanDiff.toFixed(2)} changed=${d.changed}(${(d.frac * 100).toFixed(1)}%)`);
    }
    console.log(item.from + ' -> ' + item.to + '\n   ' + parts.join('\n   '));
  }
}

module.exports = { DEFAULT_REGIONS, lum, regionStats, regionDiff, validateRegions };

if (require.main === module) main(process.argv.slice(2));
