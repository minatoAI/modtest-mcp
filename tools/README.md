# tools/ — mod-agnostic analysis helpers

These tools analyse the **artifacts a test run produces** (screenshots, frame recordings,
position/telemetry CSVs). They contain no game code and no knowledge of any particular mod.

## Usage

| Tool | Platform | Command |
|---|---|---|
| `imgdiff.js` | cross-platform (Node ≥ 18) | `node tools/imgdiff.js A.png B.png [-o heat.png] [--threshold N] [--json]` |
| `imgdiff.test.js` | cross-platform | `node tools/imgdiff.test.js` (self-test, 15 checks) |
| `lumastats.js` | cross-platform | `node tools/lumastats.js A.png [B.png …] [--bbox x0,y0,x1,y1] [--exclude …]` |
| `rec-analyze.js` | cross-platform | `node tools/rec-analyze.js <sessionDir> [--bbox …] [--header-pattern REGEX]` |
| `rec-analyze.test.js` | cross-platform | `node tools/rec-analyze.test.js` (self-test, 92 checks) |
| `move-flicker.js` | cross-platform | `node tools/move-flicker.js <dir> <tag> [count]` |
| `tp-space-solve.js` | cross-platform | `node tools/tp-space-solve.js <samples…>` (see file header) |
| `cropzoom.js` | cross-platform | `node tools/cropzoom.js IN.png OUT.png X0 Y0 X1 Y1 [SCALE]` |
| `analyze-regions.js` | cross-platform (Node ≥ 18) | `node tools/analyze-regions.js A.png B.png [C.png …] [--regions <json\|@file>] [--label NAME] [--json]` — drift-controlled region analysis (per-image region mean luminance / lit≥128, plus adjacent-pair region diffs), because whole-image diffs are dominated by sky/HUD/particle noise. `--regions` overrides the rectangles (this is what the former per-pose **forked copies** were for); the frozen default set reproduces every archived `region-analysis.txt` **line for line** |
| `analyze-regions.test.js` | cross-platform | `node tools/analyze-regions.test.js [--tool PATH] [--archived-root DIR]` (self-test, 30 checks: region maths, frozen default preset, `--regions` validation, relative `imgdiff.js` resolution proved with a stub dependency, archived equivalence) |
| `griddiff.ps1` | **Windows only** (GDI+) | `pwsh tools/griddiff.ps1 -A a.png -B b.png [-Cells 8] [-Threshold 10] [-Json]` |
| `paired-analyze.ps1` | PowerShell 5.1 / 7 | `pwsh tools/paired-analyze.ps1 -Spec spec.json -Session PN -Summary sum.json [-NoisePair "a,b"] [-Json out.json]` |
| `stylemetrics.ps1` | **Windows only** (GDI+) | `pwsh tools/stylemetrics.ps1 -Images a.png,b.png [-Json]` |
| `clusterprobe.ps1` | **Windows only** (GDI+) | `pwsh tools/clusterprobe.ps1 -Image img.png [-Color green] [-Json]` |
| `parse-check.ps1` | PowerShell 5.1 / 7 | `pwsh tools/parse-check.ps1 -Target script.ps1` → prints `ERRCOUNT=0` |
| `rcon.ps1` | PowerShell 5.1 / 7 | `pwsh tools/rcon.ps1 -ServerHost <host> -Command "list"` (password via prompt/env) |
| `run-bounded.ps1` | **Windows only** (process/watchdog/CPU audit) | `pwsh tools/run-bounded.ps1 -FilePath <exe> [-ArgumentList …] [-WorkingDirectory …] [-Windowed] [-OptionsFile run/options.txt] [-MaxInstances 1]` — hard caps **≤6 min/instance** (watchdog `Stop-Process`) and **≤25 min/round**, refuses to start if a `java`/`javaw`/Minecraft window already exists, always audits afterwards (target gone + no orphan `java.exe` + CPU/memory recovered) and writes per-run JSON/TXT metrics; `-DryRun -DryRunScenario ok\|timeout\|stall\|refuse\|orphan\|tree` self-tests every path **without starting a JVM**. **Wrapper launches need `-KillProcessTree`**: if `-FilePath` is a wrapper (`renderdoccmd.exe`, a `*.bat` shim) a single-pid kill leaves the real client alive as an orphan — measured 2026-09-25, `pid=26308 java "Minecraft* Forge …"` survived a `stall-killed` round. `-KillProcessTree` (opt-in, plus `-KillProcessTreeExcludePattern`, default `GradleDaemon`) kills the instance's **descendants** only, deepest-first, and records each kill in `instances[].treeKill`. It is deliberately not the default: on a `gradlew.bat` launch the descendant chain runs through a **shared Gradle daemon**. When the audit sees any survivor the run prints one line and records `audit.leftoverHint` telling you to add `-KillProcessTree` next time |
| `run-bounded.test.ps1` | PowerShell 5.1 / 7 | `pwsh tools/run-bounded.test.ps1` → `RUN-BOUNDED-TEST PASS (N checks)` (offline: drives the runner's 6 `-DryRun` scenarios, asserts exit code + audit verdict + the `caps` field semantics + the `-KillProcessTree` descendant kill **in both directions**; **never starts a JVM**) |
| `run-round.ps1` | PowerShell 5.1 / 7 | `pwsh tools/run-round.ps1 -Evidence <evidence dir> [-TimeoutSec 150] [-RestoreCursor] [-PreflightOnly]` — **the canonical round entry point**: snapshots/restores `options.txt` / `taclight-client.toml` / `oculus.properties`, clears per-round residue, then calls `run-bounded.ps1`. **Do not copy it into an evidence directory** — see the Conventions note below; it resolves the bounded runner beside itself (or `$env:MODTEST_HARNESS_PATH`, or `-HarnessPath`). `-PreflightOnly` resolves and reports everything and touches **nothing** |
| `run-round.test.ps1` | PowerShell 5.1 / 7 | `pwsh tools/run-round.test.ps1 [-ArchivedEvidenceRoot <dir>]` → `RUN-ROUND-TEST PASS (N checks)` (offline: canonical-file shape, CLI **superset** of the archived copies, directory-independent harness resolution, read-only preflight; never starts a JVM) |

## Conventions

* **Paths are always arguments.** Nothing in this directory hardcodes a machine path, a drive
  letter or a user profile directory. Defaults are relative (`./`) or environment variables
  (`MODTEST_RCON_HOST`, `MODTEST_RCON_PASSWORD`, `MODTEST_HARNESS_PATH`,
  `MODTEST_ARCHIVED_EVIDENCE_ROOT`).
* **Never copy a round script or an analyser into an evidence directory.** Until 2026-09-27 the round
  entry point existed *only* as five byte-identical copies inside evidence dirs
  (`sha256 0FB721FD74B3…`, 10,933 B) with a hardcoded absolute `$HarnessPath`, and `analyze-regions.js`
  as seven identical copies (`sha256 2A73E6D4…`, 2,756 B) plus a five-line fork that changed nothing but
  the rectangles — all of them requiring `imgdiff.js` by an absolute machine path. Forgetting the round
  copy cost a real round (`docs/evidence/2026-09-26-classify/README.md` §4.3: "harness 拒绝启动、驱动在
  白等"). The live copies are `tools/run-round.ps1` and `tools/analyze-regions.js` — call them where
  they live. The archived copies are **frozen history** (the bytes that actually ran): do not edit them,
  do not convert them into forwarders, and do not add a copy. `run-round.ps1` prints
  `[round] canonical=tools/run-round.ps1 (do not copy; …)` on every run so an operator can see at a
  glance which copy they used.
* **No default credentials.** `rcon.ps1` requires `-Password` (or `MODTEST_RCON_PASSWORD`) and has
  no fallback value; `-ServerHost` falls back only to `MODTEST_RCON_HOST`. Never point it at a
  server you do not own or administer.
* **Windows-only tools are labelled.** The three GDI+ scripts need `System.Drawing` (Windows
  PowerShell or PowerShell 7 on Windows). The Node tools run anywhere.
* **Recorder header signature.** `rec-analyze.js` expects the recording's first line to start with
  `#` and match `--header-pattern` (default: `/rec/i`). Pass your recorder's own signature if it
  differs; the default is intentionally generic.
* **`caps` field names in `run-bounded` evidence.** `caps.roundBudgetCapSec` is the configured **cap**
  and `caps.roundBudgetElapsedSec` is the seconds **actually used** (= `roundWallSeconds`). The key
  `caps.roundBudgetUsedSec` is a **deprecated alias of the cap** (it was misnamed: it never held the
  usage); it is kept so readers of pre-2026-09-27 evidence keep working. New code must use
  `roundBudgetCapSec` / `roundBudgetElapsedSec`.
* **Self-tests are the contract.** `imgdiff.test.js`, `rec-analyze.test.js`,
  `analyze-regions.test.js`, `run-bounded.test.ps1` and `run-round.test.ps1` must pass before you
  change anything in this folder. A change to a tool whose behaviour is not covered should add a check
  to the matching self-test **and prove it is red on the old code first**.

## Adding a tool

1. Take every input path as an argument (no defaults pointing outside the repo).
2. Print machine-readable JSON with `--json`/`-Json` when the result is meant for automation.
3. Add a row to the table above and, if the tool encodes a non-obvious data format, document that
   format in the header comment — the format is the interface.
