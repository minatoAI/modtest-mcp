// analyze-regions.test.js -- self-test for analyze-regions.js (no real machine, no game required).
//
// WHAT IT PINS (each one is a negative control where possible):
//   A. the pure region maths on synthetic images (fixed expected values);
//   B. the DEFAULT region set is FROZEN -- every archived region-analysis.txt was produced with those
//      five rectangles, so a silent edit to them would invalidate comparisons with archived reports;
//   C. --regions validates its input instead of silently producing NaN;
//   D. imgdiff.js is resolved RELATIVE TO THIS SCRIPT: the canonical copy runs after being relocated
//      (with its dependency), and fails when relocated ALONE. The archived copies instead required
//      'E:/.../modtest-mcp/tools/imgdiff.js' by absolute path (asserted statically below), i.e. they
//      only worked on the machine that wrote them;
//   E. ARCHIVED EQUIVALENCE (the most valuable check here): replaying the canonical tool over the
//      archived PNGs reproduces the archived reports LINE FOR LINE, both for the default preset and for
//      the forked pose preset -- which is what makes replacing 7 copies + 1 fork safe. The archived root
//      is resolved from --archived-root, then MODTEST_ARCHIVED_EVIDENCE_ROOT, then a CONTENT-VALIDATED
//      auto-discovery of sibling checkouts.
//      FAIL-CLOSED (2026-09-27, R3 review): this section used to be SKIPPED -- silently -- when the root
//      was not configured, while tools/README quoted the full-configuration 30/30. Now an unresolved or
//      incomplete archive is a FAIL with an actionable hint, and waiving it takes the EXPLICIT
//      --skip-archived switch. Intentional observable change: a bare `node tools/analyze-regions.test.js`
//      with no archive available used to pass and now fails until you provide the archive or waive it.
//
// It is a DIFFERENTIAL test: --tool points it at any candidate (default: the sibling canonical), so the
// very same checks can be run against an archived copy to show them going red. The candidate is only
// require()d after a child-process probe confirms it is library-shaped; an archived copy executes its
// CLI at load time and would otherwise process.exit() inside this test.
//
// Usage: node tools/analyze-regions.test.js [--tool PATH] [--archived-root DIR] [--skip-archived]
//        [--keep-temp]
// Exit: 0 all executed checks passed | 1 at least one failed | 2 setup error.

'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');
const { encodePng } = require(path.join(__dirname, 'imgdiff.js'));

// The report that proves a candidate directory really is the archived evidence tree we mean.
const ARCHIVED_MARKER = path.join('2026-09-26-default-day', 'r1-day', 'region-analysis.txt');

let toolPath = path.join(__dirname, 'analyze-regions.js');
let archivedRoot = '';
let rootSource = 'none';
let skipArchived = false;
let keepTemp = false;
{
  const argv = process.argv.slice(2);
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--tool') toolPath = argv[++i];
    else if (argv[i] === '--archived-root') { archivedRoot = argv[++i]; rootSource = 'parameter'; }
    else if (argv[i] === '--skip-archived') skipArchived = true;
    else if (argv[i] === '--keep-temp') keepTemp = true;
    else { console.error('unknown option: ' + argv[i]); process.exit(2); }
  }
}
if (!archivedRoot && process.env.MODTEST_ARCHIVED_EVIDENCE_ROOT) {
  archivedRoot = process.env.MODTEST_ARCHIVED_EVIDENCE_ROOT;
  rootSource = 'MODTEST_ARCHIVED_EVIDENCE_ROOT';
}
if (!archivedRoot && !skipArchived) {
  // Sibling checkouts of this repository, accepted only if they contain the marker report. Zero or
  // several matches stay unresolved -- ambiguity must not be guessed away.
  const checkoutParent = path.dirname(path.dirname(__dirname));
  const matches = [];
  let entries = [];
  try { entries = fs.readdirSync(checkoutParent, { withFileTypes: true }); } catch (e) { entries = []; }
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const candidate = path.join(checkoutParent, entry.name, 'docs', 'evidence');
    if (fs.existsSync(path.join(candidate, ARCHIVED_MARKER))) matches.push(candidate);
  }
  if (matches.length === 1) { archivedRoot = matches[0]; rootSource = 'auto-discovery'; }
}
const TOOL = path.resolve(toolPath);
if (!fs.existsSync(TOOL)) { console.error('ANALYZE-REGIONS-TEST SETUP-ERROR: tool not found: ' + TOOL); process.exit(2); }

let total = 0, passed = 0, failed = 0, skipped = 0;

function check(name, condition, detail) {
  total++;
  if (condition) { passed++; console.log('  ok    ' + name); }
  else { failed++; console.log('  FAIL  ' + name + (detail ? ' -- ' + detail : '')); }
}
function skip(name, reason) { skipped++; console.log('  skip  ' + name + ' (' + reason + ')'); }
function lines(text) {
  const parts = text.split(/\r?\n/);
  if (parts.length && parts[parts.length - 1] === '') parts.pop();
  return parts;
}
function solid(width, height, [r, g, b]) {
  const data = Buffer.alloc(width * height * 4);
  for (let i = 0; i < width * height; i++) { data[i * 4] = r; data[i * 4 + 1] = g; data[i * 4 + 2] = b; data[i * 4 + 3] = 255; }
  return { width, height, data };
}

// Probe in a CHILD process: the archived copies are flat scripts that call process.exit() while being
// loaded, so requiring them here would kill the test instead of reporting a red.
function probeExports(candidate) {
  const script = 'const m=require(process.argv[1]);console.log(JSON.stringify(Object.keys(m||{})))';
  try {
    const out = execFileSync('node', ['-e', script, candidate], { encoding: 'utf8', stdio: 'pipe' });
    return { ok: true, names: JSON.parse(out.trim()) };
  } catch (e) { return { ok: false, names: [] }; }
}

const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'analyze-regions-test-'));
console.log('ANALYZE-REGIONS-TEST tool=' + TOOL);
console.log('ANALYZE-REGIONS-TEST archivedRoot=' + (archivedRoot || '<unresolved>') + ' (source=' + rootSource + ')');

const probe = probeExports(TOOL);
const isLibrary = probe.ok && probe.names.includes('regionStats') && probe.names.includes('regionDiff');
// Safe to require only once the probe has confirmed it is guarded (require.main === module).
const lib = isLibrary ? require(TOOL) : null;

// ---------------------------------------------------------------- A. region maths
console.log('--- A. region maths on synthetic images ---');
if (!isLibrary) {
  check('candidate is library-shaped (exports regionStats/regionDiff)', false,
    'exports probe ' + (probe.ok ? 'returned [' + probe.names.join(',') + ']' : 'failed: the file runs its CLI at load time instead of exporting'));
  check('regionStats: black mean=0, lit=0, n=16', false, 'not loadable as a library');
  check('regionStats: white mean=255, lit=16', false, 'not loadable as a library');
  check('regionDiff: identical images -> meanDiff=0 maxDiff=0 changed=0', false, 'not loadable as a library');
  check('regionDiff: black vs white -> meanDiff=255, maxDiff=255, changed=16 (frac=1)', false, 'not loadable as a library');
  check('regionDiff: a difference of 7 is below the >=8 changed threshold', false, 'not loadable as a library');
  check('regionDiff: a difference of 8 counts as changed', false, 'not loadable as a library');
  check('lum: 0.299/0.587/0.114 weights (255 grey -> 255)', false, 'not loadable as a library');
} else {
  const black = solid(4, 4, [0, 0, 0]);
  const white = solid(4, 4, [255, 255, 255]);
  const region = [0, 0, 3, 3];
  const sb = lib.regionStats(black, region);
  const sw = lib.regionStats(white, region);
  check('regionStats: black mean=0, lit=0, n=16', sb.mean === 0 && sb.lit === 0 && sb.n === 16, JSON.stringify(sb));
  check('regionStats: white mean=255, lit=16', sw.mean === 255 && sw.lit === 16, JSON.stringify(sw));
  const dSame = lib.regionDiff(black, black, region);
  check('regionDiff: identical images -> meanDiff=0 maxDiff=0 changed=0', dSame.meanDiff === 0 && dSame.maxDiff === 0 && dSame.changed === 0, JSON.stringify(dSame));
  const dOpposite = lib.regionDiff(black, white, region);
  check('regionDiff: black vs white -> meanDiff=255, maxDiff=255, changed=16 (frac=1)', dOpposite.meanDiff === 255 && dOpposite.maxDiff === 255 && dOpposite.changed === 16 && dOpposite.frac === 1, JSON.stringify(dOpposite));
  const near = solid(4, 4, [7, 7, 7]);
  const dNear = lib.regionDiff(black, near, region);
  check('regionDiff: a difference of 7 is below the >=8 changed threshold', dNear.changed === 0 && dNear.maxDiff === 7, JSON.stringify(dNear));
  const eight = solid(4, 4, [8, 8, 8]);
  const dEight = lib.regionDiff(black, eight, region);
  check('regionDiff: a difference of 8 counts as changed', dEight.changed === 16 && dEight.maxDiff === 8, JSON.stringify(dEight));
  check('lum: 0.299/0.587/0.114 weights (255 grey -> 255)', Math.abs(lib.lum([255, 255, 255, 255], 0) - 255) < 1e-9);
}

// ---------------------------------------------------------------- B. frozen default preset
console.log('--- B. default region set is frozen (archived baseline) ---');
const EXPECTED_DEFAULT = {
  sky: [0, 0, 1279, 150],
  farTerrainRight: [620, 250, 1270, 460],
  leftCliff: [0, 150, 450, 520],
  nearWallPool: [430, 360, 700, 600],
  groundLeft: [0, 500, 500, 700],
};
if (!isLibrary) {
  check('default preset has exactly the 5 archived region names', false, 'not loadable as a library');
  check('every default rectangle is byte-identical to the archived baseline', false, 'not loadable as a library');
} else {
  const actual = lib.DEFAULT_REGIONS;
  check('default preset has exactly the 5 archived region names',
    JSON.stringify(Object.keys(actual)) === JSON.stringify(Object.keys(EXPECTED_DEFAULT)), Object.keys(actual).join(','));
  let same = true, detail = '';
  for (const name of Object.keys(EXPECTED_DEFAULT)) {
    if (JSON.stringify(actual[name]) !== JSON.stringify(EXPECTED_DEFAULT[name])) {
      same = false;
      detail += ` [${name}: ${JSON.stringify(actual[name])} != ${JSON.stringify(EXPECTED_DEFAULT[name])}]`;
    }
  }
  check('every default rectangle is byte-identical to the archived baseline', same, detail);
}

// ---------------------------------------------------------------- C. --regions validation
console.log('--- C. --regions validation ---');
{
  if (isLibrary) {
    const ok = lib.validateRegions({ spot: [1, 2, 3, 4] }, 'test');
    check('validateRegions accepts a well-formed region', JSON.stringify(ok) === '{"spot":[1,2,3,4]}');
    const rejects = [
      ['wrong length', { a: [1, 2, 3] }],
      ['non-numeric', { a: [1, 2, 3, 'x'] }],
      ['x0 > x1', { a: [5, 0, 1, 4] }],
      ['NaN member', { a: [0, 0, Number.NaN, 4] }],
      ['empty object', {}],
      ['an array instead of an object', [1, 2, 3, 4]],
    ];
    for (const [label, bad] of rejects) {
      let threw = false;
      try { lib.validateRegions(bad, 'test'); } catch (e) { threw = true; }
      check(`validateRegions rejects ${label}`, threw);
    }
  } else {
    check('validateRegions accepts a well-formed region', false, 'not loadable as a library');
    for (const label of ['wrong length', 'non-numeric', 'x0 > x1', 'NaN member', 'empty object', 'an array instead of an object']) {
      check(`validateRegions rejects ${label}`, false, 'not loadable as a library');
    }
  }
  let cliBad = 0;
  try { execFileSync('node', [TOOL, 'a.png', 'b.png', '--regions', '{"a":[1,2,3]}'], { stdio: 'pipe' }); } catch (e) { cliBad = e.status; }
  check('CLI exits 2 on a malformed --regions (rather than producing NaN)', cliBad === 2, 'exit=' + cliBad);
  let cliFew = 0;
  try { execFileSync('node', [TOOL, 'only-one.png'], { stdio: 'pipe' }); } catch (e) { cliFew = e.status; }
  check('CLI exits 2 with fewer than 2 images', cliFew === 2, 'exit=' + cliFew);
}

// ---------------------------------------------------------------- D. relocation / relative require
console.log('--- D. imgdiff.js is resolved relative to this script ---');
{
  const source = fs.readFileSync(TOOL, 'utf8');
  const codeBody = source.split(/\r?\n/).filter(l => !l.trim().startsWith('//')).join('\n');
  const driveHits = codeBody.match(/[A-Za-z]:\\[^\s"']*/g) || [];
  check('CODE has no machine-absolute path', driveHits.length === 0, 'hits=' + driveHits.join(','));
  check('requires imgdiff.js via __dirname', /require\(path\.join\(__dirname, 'imgdiff\.js'\)\)/.test(codeBody));

  const tinyA = path.join(tempRoot, 'tiny-a.png');
  const tinyB = path.join(tempRoot, 'tiny-b.png');
  fs.writeFileSync(tinyA, encodePng(Buffer.from([10, 10, 10, 20, 20, 20, 30, 30, 30, 40, 40, 40]), 2, 2));
  fs.writeFileSync(tinyB, encodePng(Buffer.from([12, 12, 12, 22, 22, 22, 32, 32, 32, 42, 42, 42]), 2, 2));

  // Relocated ALONE with the DEFAULT preset (which every candidate understands): if the require is
  // relative to __dirname this must fail BECAUSE imgdiff.js is not beside it. Asserting the reason
  // matters -- a tool that merely rejected an unknown option would "pass" this for the wrong reason.
  const aloneDir = path.join(tempRoot, 'alone');
  fs.mkdirSync(aloneDir);
  fs.copyFileSync(TOOL, path.join(aloneDir, 'analyze-regions.js'));
  let aloneFailed = false, aloneErr = '';
  try {
    execFileSync('node', [path.join(aloneDir, 'analyze-regions.js'), tinyA, tinyB], { stdio: 'pipe' });
  } catch (e) { aloneFailed = true; aloneErr = String((e.stderr && e.stderr.toString()) || e.message); }
  check('relocated ALONE it fails BECAUSE imgdiff.js is not beside it',
    aloneFailed && /imgdiff/i.test(aloneErr), 'failed=' + aloneFailed + ' err=' + aloneErr.replace(/\s+/g, ' ').slice(0, 140));

  // Relocated WITH a STUB imgdiff.js beside it. The stub decodes any file to a solid 200-grey 2x2
  // image, so the output tells us WHICH imgdiff.js was loaded: a tool that resolves relative to
  // __dirname reports mean=200.0 / lit=4/4, while one bound to a machine-global path decodes the real
  // PNGs and reports other numbers. This is what makes the check discriminating instead of vacuous.
  const withDepDir = path.join(tempRoot, 'withdep');
  fs.mkdirSync(withDepDir);
  fs.copyFileSync(TOOL, path.join(withDepDir, 'analyze-regions.js'));
  fs.writeFileSync(path.join(withDepDir, 'imgdiff.js'),
    "'use strict';\n// test stub: any file decodes to one solid 200-grey 2x2 image\nexports.decodePng = () => ({ width: 2, height: 2, data: Buffer.alloc(16, 200) });\n");
  let relocatedOut = '', relocatedErr = '';
  try {
    relocatedOut = execFileSync('node', [path.join(withDepDir, 'analyze-regions.js'), tinyA, tinyB, '--regions', '{"all":[0,0,1,1]}'], { encoding: 'utf8', stdio: 'pipe' });
  } catch (e) { relocatedErr = String(e.message); }
  check('relocated WITH a stub imgdiff.js beside it, the STUB is the one loaded (mean=200.0, lit=4/4)',
    relocatedOut.includes('all: mean=200.0 lit=4/4'), relocatedErr || relocatedOut.trim().split('\n').slice(0, 3).join(' / '));
  check('and with the stub both images are identical (adjacent pair meanDiff=0.00)',
    relocatedOut.includes('all: mean=0.00 changed=0'), relocatedOut.trim().split('\n').pop());

  // --json is machine-readable and does not disturb the default text path
  let parsed = null;
  try { parsed = JSON.parse(execFileSync('node', [TOOL, tinyA, tinyB, '--regions', '{"all":[0,0,1,1]}', '--json'], { encoding: 'utf8' })); } catch (e) { parsed = null; }
  check('--json prints one parseable JSON object', parsed !== null && parsed.label === 'custom' && Array.isArray(parsed.images) && Array.isArray(parsed.pairs));
  check('--json carries both images and the one adjacent pair', parsed !== null && parsed.images.length === 2 && parsed.pairs.length === 1);
  let defaultJson = null;
  try { defaultJson = JSON.parse(execFileSync('node', [TOOL, tinyA, tinyB, '--json'], { encoding: 'utf8' })); } catch (e) { defaultJson = null; }
  check('--json reports the default label (and the frozen preset) when no --regions is given',
    defaultJson !== null && defaultJson.label === 'default' && JSON.stringify(Object.keys(defaultJson.regions)) === JSON.stringify(Object.keys(EXPECTED_DEFAULT)));
}

// ---------------------------------------------------------------- E. archived equivalence
console.log('--- E. archived equivalence ---');
const POSE_REGIONS = {
  skyFog: [0, 0, 1279, 330],
  lightSpotBox: [540, 330, 780, 480],
  terrainBelowSpot: [300, 480, 1000, 700],
  snowLeft: [0, 480, 300, 760],
  groundBottom: [100, 700, 900, 780],
};

function equivalenceCase(label, dir, reportName, regionsArg) {
  const reportPath = path.join(archivedRoot, dir, reportName);
  // FAIL, not skip: this function only runs once a root has been resolved, and an incomplete archive
  // must not quietly reduce the evidence (that is the whole point of the fail-closed change).
  if (!fs.existsSync(reportPath)) { check(label, false, 'archived report not found: ' + reportPath); return; }
  const expected = lines(fs.readFileSync(reportPath, 'utf8'));
  // The report itself is the ordering authority: those runs fed the PNGs in capture-time order, which
  // is NOT always name order (rC-fluid lists nocc_1 before cc_2; rB-lag lists lag45-3 before lag0-4).
  const ordered = [];
  for (const line of expected) {
    const m = /^(\S+\.png) {2}/.exec(line);
    if (m && !ordered.includes(m[1])) ordered.push(m[1]);
  }
  if (ordered.length < 2) { check(label, false, 'could not read an image order from ' + reportPath); return; }
  const missing = ordered.filter(f => !fs.existsSync(path.join(archivedRoot, dir, f)));
  if (missing.length > 0) { check(label, false, 'archived PNGs missing: ' + missing.join(',')); return; }
  const args = [TOOL].concat(ordered.map(f => path.join(archivedRoot, dir, f)));
  if (regionsArg) args.push('--regions', JSON.stringify(regionsArg));
  let out = '';
  try { out = execFileSync('node', args, { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 }); }
  catch (e) { check(label, false, 'run failed: ' + e.message); return; }
  const actual = lines(out);
  if (expected.length !== actual.length) { check(label, false, `lines ${actual.length} != archived ${expected.length}`); return; }
  let firstDiff = -1;
  for (let i = 0; i < expected.length; i++) { if (expected[i] !== actual[i]) { firstDiff = i; break; } }
  check(label, firstDiff === -1, firstDiff === -1 ? '' : `line ${firstDiff + 1}: got '${actual[firstDiff]}' want '${expected[firstDiff]}'`);
}

if (skipArchived) {
  skip('archived equivalence (default + pose preset)', 'waived on purpose with --skip-archived');
} else if (!archivedRoot) {
  // FAIL-CLOSED (see the header): an unresolved root must not silently drop the strongest evidence.
  check('archived evidence root resolved (the equivalence replay must run)', false,
    'not resolved via --archived-root/env/auto-discovery; pass --archived-root DIR, set '
    + 'MODTEST_ARCHIVED_EVIDENCE_ROOT, or waive on purpose with --skip-archived');
} else if (!fs.existsSync(archivedRoot)) {
  check('configured archived evidence root exists', false, archivedRoot);
} else if (!isLibrary) {
  // A flat script has no --regions/--json and would treat them as image filenames; replaying the
  // archived reports against it proves nothing (and feeds it nonsense arguments). The reds for such a
  // candidate already come from A-D.
  skip('archived equivalence (default + pose preset)', 'candidate is not library-shaped and takes no --regions');
} else {
  equivalenceCase('archived equivalence: default preset reproduces region-analysis.txt line for line',
    path.join('2026-09-26-default-day', 'r1-day'), 'region-analysis.txt', null);
  equivalenceCase('archived equivalence: the pose FORK is now just --regions (reproduces region-analysis-poseYaw34.txt)',
    path.join('2026-09-26-throttle-speeds', 'rB-lag'), 'region-analysis-poseYaw34.txt', POSE_REGIONS);
  equivalenceCase('archived equivalence: 4-region run reproduces region-analysis.txt line for line',
    path.join('2026-09-26-classify', 'rC-fluid'), 'region-analysis.txt', null);
  const archivedSample = path.join(archivedRoot, '2026-09-27-keyinject', 'analyze-regions.js');
  if (fs.existsSync(archivedSample)) {
    const archivedCode = fs.readFileSync(archivedSample, 'utf8').split(/\r?\n/).filter(l => !l.trim().startsWith('//')).join('\n');
    check('archived copy required imgdiff.js by MACHINE-ABSOLUTE path (what this change removes)',
      /require\('[A-Za-z]:\//.test(archivedCode));
  } else {
    check('archived copy absolute-require evidence available', false, 'not found: ' + archivedSample);
  }
}

if (!keepTemp) {
  try { fs.rmSync(tempRoot, { recursive: true, force: true }); } catch (e) { /* best effort */ }
} else {
  console.log('ANALYZE-REGIONS-TEST temp kept at ' + tempRoot);
}
const verdict = failed === 0 ? 'PASS' : 'FAIL';
console.log(`ANALYZE-REGIONS-TEST ${verdict} (${passed}/${total} checks passed${skipped ? ', ' + skipped + ' skipped' : ''})`);
process.exit(failed === 0 ? 0 : 1);
