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
| `capture-window.ps1` | **Windows only** (user32 + System.Drawing) | `pwsh tools/capture-window.ps1 -Out shot.png [-Title <regex>\|-ProcessId N\|-WindowHandle H] [-Method auto\|printwindow\|rect] [-Json]` — captures a window **without ever activating it** (PrintWindow first; unobstructed-rect screen grab as fallback, with that caveat printed). Reports `CAPTURE_METHOD=`, `CAPTURE_BLACKFRACTION=` and `foregroundUnchanged=True`. **Verified on a real GL window 2026-09-27** (Minecraft 1.20.1 + Forge, window not in the foreground: `printwindow`, 868×571, `blackFraction=0`, foreground unchanged) — so the "GL may come back black" worry is falsified for that setup; a black frame on anything else still exits 4 rather than "fixing" it by stealing focus. See "Never steal the user's window focus" below |
| `no-focus-steal.test.ps1` | PowerShell 5.1 / 7 | `pwsh tools/no-focus-steal.test.ps1 [-ExtraScanPath <file>]` → `NO-FOCUS-STEAL-TEST PASS (N checks)` — scans `tools/**` code for activation / input-synthesis primitives (comments and the README's marked block are exempt); `-ExtraScanPath` runs the negative control |
| `warm-build-env.ps1` | PowerShell 5.1 / 7 | `pwsh tools/warm-build-env.ps1 -TargetDir <fresh checkout> -SourceTree <warmed tree> [-Check] [-GradleUserHome <dir>] [-RunBuild] [-ExpectJarSha <sha256>]` — makes a fresh checkout buildable, **idempotently**: copies the un-versioned `libs/*.jar`, the three gitignored ForgeGradle mapping intermediates, and runs gradle with a warmed user home **from the target directory**. A missing prerequisite is **reported** (exit 2), never silently skipped; `-Check` changes nothing. See "Parallel builds" below |
| `warm-build-env.test.ps1` | PowerShell 5.1 / 7 | `pwsh tools/warm-build-env.test.ps1` → `WARM-BUILD-ENV-TEST PASS (21 checks)` — offline (synthetic trees, no gradle): fail-loud on each missing prerequisite, `-Check` writes nothing, warm copies exactly what is missing, second run is a no-op |
| `assert-no-round.ps1` | PowerShell 5.1 / 7 | `pwsh tools/assert-no-round.ps1 [-What edit-runner\|gradle-build] [-GradleLock <dir>]` → exit **0 = clear**, **1 = blocked**. Gate for exclusivity-sensitive work: BLOCKS while a real round is running (judged by command line — `BootstrapLauncher`/`forgeclient`/`net.minecraft` — never by "is there any java", since a teammate's Gradle daemon is java but is not a round), and for `-What gradle-build` also while the team gradle lock is held (path from `-GradleLock` or `MODTEST_GRADLE_LOCK`). Run it **as its own command** before the action: a check that does not block is not a check |
| `analyze-regions.js` | cross-platform (Node ≥ 18) | `node tools/analyze-regions.js A.png B.png [C.png …] [--regions <json\|@file>] [--label NAME] [--json]` — drift-controlled region analysis (per-image region mean luminance / lit≥128, plus adjacent-pair region diffs), because whole-image diffs are dominated by sky/HUD/particle noise. `--regions` overrides the rectangles (this is what the former per-pose **forked copies** were for); the frozen default set reproduces every archived `region-analysis.txt` **line for line** |
| `analyze-regions.test.js` | cross-platform | `node tools/analyze-regions.test.js [--tool PATH] [--archived-root DIR] [--skip-archived]` (self-test, **30 checks on a default run**: region maths, frozen default preset, `--regions` validation, relative `imgdiff.js` resolution proved with a stub dependency, archived equivalence) |
| `griddiff.ps1` | **Windows only** (GDI+) | `pwsh tools/griddiff.ps1 -A a.png -B b.png [-Cells 8] [-Threshold 10] [-Json]` |
| `paired-analyze.ps1` | PowerShell 5.1 / 7 | `pwsh tools/paired-analyze.ps1 -Spec spec.json -Session PN -Summary sum.json [-NoisePair "a,b"] [-Json out.json]` |
| `stylemetrics.ps1` | **Windows only** (GDI+) | `pwsh tools/stylemetrics.ps1 -Images a.png,b.png [-Json]` |
| `clusterprobe.ps1` | **Windows only** (GDI+) | `pwsh tools/clusterprobe.ps1 -Image img.png [-Color green] [-Json]` |
| `parse-check.ps1` | PowerShell 5.1 / 7 | `pwsh tools/parse-check.ps1 -Target <file>` → `ERRCOUNT=n` (historical first line, kept verbatim). **`-Scan -ScanRoot <dir,dir>`** (ONE comma-separated argument — `pwsh -File` cannot pass arrays) recursively parses every `*.ps1` under the roots → `PARSE-CHECK files=N failed=M` + a named file/line per failure. **Pass the evidence directory too**: a prose file named `*.ps1` looks like a tool and is not one — that is how a real gate got reported as broken on 2026-09-26 (see pitfall 9). **Both modes exit non-zero on a parse error** |
| `rcon.ps1` | PowerShell 5.1 / 7 | `pwsh tools/rcon.ps1 -ServerHost <host> -Command "list"` (password via prompt/env) |
| `run-bounded.ps1` | **Windows only** (process/watchdog/CPU audit) | `pwsh tools/run-bounded.ps1 -FilePath <exe> [-ArgumentList …] [-WorkingDirectory …] [-Windowed] [-OptionsFile run/options.txt] [-MaxInstances 1] [-Slot <name>] [-InstanceSignature <s>]` — hard caps **≤6 min/instance** (watchdog `Stop-Process`) and **≤25 min/round**, **refuses to start only if a process carrying THIS round's instance signature is already present** — and that match is **exact** (the candidate's parsed `--gameDir` must equal ours; a teammate's Gradle daemon or a sibling slot whose directory is a *prefix* of ours is reported `NOT BLOCKING:` and ignored). No signature ⇒ FAIL-CLOSED as before — see "Running several instances in parallel", always audits afterwards (target gone + no orphan `java.exe` + CPU/memory recovered) and writes per-run JSON/TXT metrics; `-DryRun -DryRunScenario ok\|timeout\|stall\|refuse\|orphan\|tree` self-tests every path **without starting a JVM**. **A REFUSAL (exit 3) now writes evidence too** — until 2026-09-27 it produced no file at all, which made refusals unattributable after the fact; the JSON now carries `verdict=REFUSED` plus a `blocking[]` array whose entries hold pid / name / title / reason / **scope** and a truncated **command line**, plus `blockingOutOfScope[]` for the matches that were ignored, because on a shared machine "a java process exists" cannot tell a teammate's build JVM from a real game client (all three refusals recorded 2026-09-25/26 were somebody else's Gradle daemon). It remains a refusal only: **nothing is launched, nothing is killed**, and no command-line exclusion was added. **Wrapper launches need `-KillProcessTree`**: if `-FilePath` is a wrapper (`renderdoccmd.exe`, a `*.bat` shim) a single-pid kill leaves the real client alive as an orphan — measured 2026-09-25, `pid=26308 java "Minecraft* Forge …"` survived a `stall-killed` round. `-KillProcessTree` (opt-in, plus `-KillProcessTreeExcludePattern`, default `GradleDaemon`) kills the instance's **descendants** only, deepest-first, and records each kill in `instances[].treeKill`. It is deliberately not the default: on a `gradlew.bat` launch the descendant chain runs through a **shared Gradle daemon**. When the audit sees any survivor the run prints one line and records `audit.leftoverHint` telling you to add `-KillProcessTree` next time |
| `run-bounded.test.ps1` | PowerShell 5.1 / 7 | `pwsh tools/run-bounded.test.ps1` → `RUN-BOUNDED-TEST PASS (93 checks)` (offline: drives the runner's 6 `-DryRun` scenarios, asserts exit code + audit verdict + the `caps` field semantics + the refusal's `blocking[]` attribution + the **guard scope** (unrelated signature must not block, same signature must block) + `slot` + pid-based window selection + the `-KillProcessTree` descendant kill **in both directions**; **never starts a JVM**) |
| `run-round.ps1` | PowerShell 5.1 / 7 | `pwsh tools/run-round.ps1 -Evidence <evidence dir> [-TimeoutSec 150] [-RestoreCursor] [-PreflightOnly] [-RunTag <tag>] [-Slot <name>] [-InstanceSignature <sig>]` — **the canonical round entry point**: snapshots/restores `options.txt` / `taclight-client.toml` / `oculus.properties`, clears per-round residue, then calls `run-bounded.ps1`. **Do not copy it into an evidence directory** — see the Conventions note below; it resolves the bounded runner beside itself (or `$env:MODTEST_HARNESS_PATH`, or `-HarnessPath`). `-PreflightOnly` resolves and reports everything and touches **nothing**. **It refuses to overwrite a previous round's outputs (exit 2, each conflict named)** — see "Per-round outputs (task-38)" below. `-Slot`/`-InstanceSignature` are forwarded to the bounded runner (default signature = the gameDir from `launch-args.json`), so a round is scoped to its own instance and parallel slots no longer refuse each other. **Restore is unconditional and reported per slot** (`[restore] slot=… file=… before=… after=… match=…`, `ROUND_RESTORE=OK`), and a restore that cannot be verified exits **6** — see "Round teardown" below |
| `run-round.test.ps1` | PowerShell 5.1 / 7 | `pwsh tools/run-round.test.ps1 [-ArchivedEvidenceRoot <dir>] [-SkipArchivedEquivalence] [-OldRunner <pre-change run-round.ps1>]` → `RUN-ROUND-TEST PASS (79 checks on a default run)` (offline: canonical-file shape, CLI **superset** of the archived copies, directory-independent harness resolution, read-only preflight, archived-copy provenance, **per-round outputs never clobber**, **the round restore is unconditional and fails loudly** — all driven through a stub harness whose exit code is configurable, so no JVM is ever started; `-OldRunner` enables the single-round comparison and is reported as SKIPPED, never as a pass, when absent) |
| `post-round-gates.ps1` | PowerShell 5.1 / 7 | `pwsh tools/post-round-gates.ps1 -ShadersDir <gameDir>\patched_shaders -Evidence <ev>\gates [-Require ...] [-RequireRestore -RoundEvidence <ev>]` → `GATES_VERDICT=PASS\|FAIL` — runs the offline shader gates (injection audit + glslang compile) as one step, **plus a restore gate**: `-RequireRestore -RoundEvidence <ev>` asserts the round's `round-setup\restore-report.txt` exists and says `ROUND_RESTORE=OK`, else FAIL. Without `-RoundEvidence` the restore gate is **skipped and says so** (shader-only callers keep working); with `-RequireRestore` but no evidence it FAILs, so "forgot the flag" cannot pass |
| `post-round-gates.test.ps1` | PowerShell 5.1 / 7 | `pwsh tools/post-round-gates.test.ps1` → `POST-ROUND-GATES-TEST PASS (15 checks)` — offline (~1 s, shader gates switched off): the restore gate's four verdicts (skipped/required-without-evidence/missing report/`ROUND_RESTORE=FAIL`) and the recorded per-file line count |
| `verify-parallel-restore.ps1` | PowerShell 5.1 / 7 | `pwsh tools/verify-parallel-restore.ps1 -RoundEvidence <evA>,<evB> [-ExpectSlots A,B] [-Evidence <dir>]` → `PARALLEL_RESTORE_VERDICT=PASS|FAIL` — one command for "did every slot of that parallel round give its game directory back?": per round it checks **provenance** (`round-setup\setup-manifest.txt` exists ⇒ the round went through `run-round.ps1` at all), the **recorded verdict** (`ROUND_RESTORE=OK` + one `[restore]` line per file), and then **re-verifies independently** by hashing every live game-directory file against its `*.orig` backup **now** (the report says what the round did; this says what the disk looks like). Two rounds claiming the same slot FAIL. `-RoundEvidence` accepts a comma-separated list because a second bare value would bind to the next positional parameter. |
| `verify-parallel-restore.test.ps1` | PowerShell 5.1 / 7 | `pwsh tools/verify-parallel-restore.test.ps1` → `VERIFY-PARALLEL-RESTORE-TEST PASS (21 checks)` — offline: the healthy two-slot case, the bypass case (no `round-setup\`), a report without `ROUND_RESTORE=OK`, a live file that no longer matches its backup, a duplicated slot, a missing `-ExpectSlots` entry, and the "no backup to compare against" case |
| `patched-shaders-audit.ps1` | PowerShell 5.1 / 7 | `pwsh tools/patched-shaders-audit.ps1 -ShadersDir <dump> -Evidence <dir> [-Require ...] [-Forbid ...] [-MinFiles N]` → `VERDICT=PASS|FAIL` — audits the GLSL Iris actually compiled. Emits **two** fingerprints: `fingerprintRaw` (any byte change; **round-sensitive**, never a package-identity judgment) and `fingerprintNormalized` (date headers of `*.properties` removed with the mod's own rule, via `pack-fingerprint.ps1`). `manifestSha256` remains as a deprecated **raw** alias |
| `pack-fingerprint.ps1` | PowerShell 5.1 / 7 | dot-source it: `. tools/pack-fingerprint.ps1` → `Get-PackTextFingerprint -RelPath <path> -Text <text>` gives `raw` + `normalized`. The **single** PowerShell implementation of the mod's digest rule, so the audit cannot drift from the mod again. Java details handled explicitly: ASCII `[A-Za-z0-9_]`/`[0-9]`, `\R` **including VT/FF**, and Java-style multiline `^` (lone CR / U+0085 / U+2028) instead of .NET's `(?m)` |
| `fingerprint-corpus.json` | data | The fixed corpus **shared by both implementations**. Every case carries an `expect` derived by reasoning from the rule text (never generated by an implementation), plus `why`/`trap`. Contains real samples (A), terminator/shape cases (B), near-misses (C), and the divergence killers (D: ASCII classes + Java line terminators; E: 5-digit year, VT, FF, NEL) |
| `fingerprint-parity.test.ps1` | PowerShell 5.1 / 7 + JDK 17 | `pwsh tools/fingerprint-parity.test.ps1 [-StrictRealFiles] [-RequireJava]` → `FINGERPRINT-PARITY PASS (93 checks + declared pending divergences)` — runs the **shipped Java class** (java -cp <mod classes> <temp driver>) and the PowerShell port over the same corpus. Two conditions per case: the sides agree **and** both match the declared expectation. Also asserts the real samples' **raw** digests differ while their **normalized** digests are equal, and red-controls the naive port (Unicode `\w`/`\d` + `(?m)`) into failing every killer case. Prints the corpus + driver + implementation sha256. Real-file checks SKIP **loudly** when the game dir is absent; `-StrictRealFiles` turns that into a FAIL |

## Conventions

* **Paths are always arguments.** Nothing in this directory hardcodes a machine path, a drive
  letter or a user profile directory. Defaults are relative (`./`) or environment variables
  (`MODTEST_RCON_HOST`, `MODTEST_RCON_PASSWORD`, `MODTEST_HARNESS_PATH`,
  `MODTEST_ARCHIVED_EVIDENCE_ROOT`).
* **The archived-evidence checks are FAIL-CLOSED** (2026-09-27). `run-round.test.ps1` and
  `analyze-regions.test.js` compare the live tools against the archived round artifacts, which live in
  a sibling workspace checkout and not in this repository. The root is resolved from
  `-ArchivedEvidenceRoot` / `--archived-root` → `MODTEST_ARCHIVED_EVIDENCE_ROOT` → a
  **content-validated** auto-discovery of sibling checkouts (a sibling whose `docs/evidence` contains
  `2026-09-26-default-day/r1-day/region-analysis.txt`; zero or several matches stay unresolved rather
  than being guessed). If the root cannot be resolved, the strongest assertions **FAIL** with a hint
  instead of being skipped — `19/19` and `30/30` above are **default-run** numbers, and a silent skip is
  exactly how they would quietly stop being true. Waiving them takes the explicit
  `-SkipArchivedEquivalence` / `--skip-archived`, and the skip is then printed, never hidden.
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
  The TXT caps line also shows the real stall cap (`stall<=45s`) now: `Write-Evidence` reads
  `$Summary.stallSeconds`, but the summary only ever set `caps.stallSeconds`, so every archived TXT
  printed an empty `stall<=s` until 2026-09-27.
* **Self-tests are the contract.** `imgdiff.test.js`, `rec-analyze.test.js`,
  `analyze-regions.test.js`, `run-bounded.test.ps1`, `run-round.test.ps1` and
  `no-focus-steal.test.ps1` must pass **on a default run** (no extra flags) before you change anything
  in this folder. A change to a tool whose behaviour is not covered should add a check to the matching
  self-test **and prove it is red on the old code first**.

## Never steal the user's window focus (policy — machine-checked)

**This really happened.** On 2026-09-26 the user reported that a real-machine round **stole their
window focus**: the round screenshotted by activating the game window and synthesising the vanilla F2
key. The technique had already been written off twice in this project, and it came back only because a
**release jar has no relay**, so the in-mod `!shot` was unavailable and somebody "just needed a
picture". Documentation did not stop it, so it is enforced by
`pwsh tools/no-focus-steal.test.ps1` — which scans **code lines** of every `*.ps1` / `*.js` / `*.md`
in this directory (comments are ignored, so a comment may explain the ban; the block marked below is
exempt so this list can live here).

Everything between the markers below — the ban list **and** the two-sided control commands, which have
to name the primitives — is exempt from the scan. Nothing outside them may contain these names.

<!-- BEGIN-BANNED-API-LIST -->
| Banned | Why |
|---|---|
| `SetForegroundWindow`, `SetActiveWindow`, `BringWindowToTop`, `SwitchToThisWindow`, `AttachThreadInput` | change the user's foreground window |
| `ShowWindow(..., SW_RESTORE / SW_SHOW / SW_MINIMIZE / SW_SHOWNORMAL / SW_SHOWDEFAULT / SW_MAXIMIZE, or the numeric 1,2,3,5,6,9,10,11)` | un-minimises / raises the window and hands it the foreground (the real incident used the numeric form `ShowWindow(h,9)`) |
| `keybd_event`, `SendInput`, `mouse_event` | synthesise input into whatever currently has focus — and can therefore type into the user's own applications |

**Do not trust the policy test — run both sides of it** when you touch this area. The first command
must stay GREEN, the second must go RED:

```
# GREEN: the real round script, which names the ban only in COMMENTS -> the scan must ignore them
pwsh tools/no-focus-steal.test.ps1 -ExtraScanPath <workspace>/docs/evidence/2026-09-27-release-smoke/drive-release-smoke.ps1
# RED: the historical synthetic-key screenshot route in a temp file
#       (that is: ShowWindow with flag 9, then SetForegroundWindow, then keybd_event with VK_F2)
pwsh tools/no-focus-steal.test.ps1 -ExtraScanPath <that temp file>
```
<!-- END-BANNED-API-LIST -->

Read-only helpers are fine and are what `capture-window.ps1` uses: `GetForegroundWindow`,
`IsWindowVisible`, `GetWindowRect`, `GetClientRect`, `PrintWindow`, `GetCursorPos`.

**Correct alternatives — never synthesise keys to take a screenshot**

| Situation | Use |
|---|---|
| dev / relay variant (the relay is present) | the mod's own `!shot` — in-process, no external input at all |
| release jar, or no relay | `pwsh tools/capture-window.ps1 -Out shot.png -Title '<window title>'` |
| you must show you did not steal focus | `capture-window.ps1` prints `CAPTURE_METHOD=`, `CAPTURE_BLACKFRACTION=` and `foregroundUnchanged=True` with the read-only foreground handle before/after |

## Running several instances in parallel (task-28)

**The machine can do it; the pre-launch guard used to prevent it.** Measured: 8 physical / 16 logical
cores, 16.8 GB RAM free, RTX 5070 Ti (16,303 MiB VRAM), 452 GB free on `E:`, one gameDir ≈ 0.5 GB.
`run-bounded.ps1` already had `-MaxInstances`, a gameDir-parameterised launch, and a **post-run audit
already scoped to our own launch signature** — only the **pre-launch** guard still refused on
"any `java` process", which is why a teammate's Gradle daemon blocked real rounds twice.

Since 2026-09-27 that guard is scoped the same way: a process blocks a round **only when its command
line contains this round's instance signature** (normally the `--gameDir` value; override with
`-InstanceSignature`). A Gradle daemon, or another slot's client in a different gameDir, is printed as
`NOT BLOCKING:` and **ignored**. With no signature available the guard is **FAIL-CLOSED** — any
name/title match blocks (the pre-2026-09-27 behaviour). `-BlockingProcessNames` is kept as the explicit
override. The run prints one line:
`scope: signature=<sig> inScope=<n> outOfScope=<n> slot=<name>`.

### Rules

1. **Functional / visual / logical verdicts may run in parallel.** One gameDir per slot, one `-Slot`
   per instance. With `-Slot` given, the slot is appended to the evidence filename, so several slots can
   even share one evidence directory.
2. **Performance-sensitive rounds MUST be exclusive.** Anything reading frame time / FPS / `!perf`
   windows / voxel phase shares the GPU with every other instance, and GPU sharing contaminates those
   numbers. Never run a timing round next to anything else.
3. **One gameDir per slot.** The signature defaults to the `--gameDir` value; two slots sharing a
   gameDir are indistinguishable to the guard and also share `saves/`, `options.txt` and the relay file.
4. **The relay file is per gameDir** (`<gameDir>/taclight-cmds.txt`), so different gameDirs can each be
   driven independently.
5. **Window handling goes by pid → handle, never by title**: two instances both report
   `Minecraft* ...`. `-VerifyWindow` already resolves the handle from the instance's own pid, and
   `capture-window.ps1` takes `-ProcessId`.

### Choosing a slot

```
pwsh tools/run-bounded.ps1 -FilePath <java> -ArgumentList @('--gameDir','E:\inst-a') \
     -Slot a -EvidenceDir <ev>
```

`slot=` is printed on the banner and recorded in the evidence (`slot`, `instanceSignature`). The slot
is appended to the **filename** only when `-Slot` is given explicitly, so existing evidence names and
every `run-bounded-*.json` glob are unchanged by default.

⚠️ **Caveat when testing the guard**: `-InstanceSignature` on the command line appears in the
**harness's own** command line. If `-BlockingProcessNames` also matches the harness's own process name
(e.g. `pwsh`), the guard matches **itself** and always refuses. `run-bounded.test.ps1` therefore drives
both scope controls through a **non-harness peer** process.

### How instance scope is decided — and why it must be EXACT (task-39)

A process belongs to this round **only when its parsed `--gameDir` equals this round's signature
exactly** (path-normalised, case-insensitive). The pre-launch guard and the post-run orphan audit call the
same function (`Test-InstanceScopeMatch`) with the same value, so they cannot disagree. For a
non-path signature (`-InstanceSignature <exe name>`) the fallback is equality with a **whole argument** or
with an argument's **file name** — never a prefix, never a substring.

**Why (measured on the real two-slot run, task-30):** slot A `…\versions\1.20.1-Forge` and slot B
`…\versions\1.20.1-Forge-B` are siblings where **B's path starts with A's**. The scope test used to be a
substring test, which produced:

| symptom | old behaviour | exact behaviour |
|---|---|---|
| guard over-blocks | A's signature ⇒ `inScope=2 outOfScope=0` (B counted as ours) ⇒ exit 3 | `inScope=1 outOfScope=1` |
| **false FAIL** | audit attributed B's live client to A ⇒ `orphans=1` ⇒ `VERDICT=FAIL:post-run-audit` (**exit 7**) although A's own instance had exited cleanly | `orphans=0`, `unattributedExcluded=1`, `VERDICT=PASS` |

⚠️ **Renaming the slot does not help**: `…-Forge-B` still starts with `…-Forge`, so a substring rule stays
wrong no matter what you call the directory. Exact matching is the fix. As *extra* defence (not a
substitute) you may lay slots out so no path is a prefix of another, e.g. `…\slots\A\…` and
`…\slots\B\…`.

**Temporary ban, now lifted:** while this defect was open, **"the post-run audit is ok" could not be used
as a criterion for a parallel round** — a sibling slot's client could turn it into a false FAIL — and
functional verdicts had to come from game-side evidence. That ban is **lifted by this change** (proven by
the prefix-sibling controls in `run-bounded.test.ps1`: 12 checks, 10 of which are red on the pre-fix
runner).

**Usage — through the documented entry point** (`run-round.ps1` now forwards both):

```
pwsh tools/run-round.ps1 -Evidence <dir>\B -Slot B -InstanceSignature '<gameDirB>'
```

`-InstanceSignature` defaults to the gameDir parsed out of `launch-args.json`, so **every** round is
scoped to its own instance even if you pass nothing. `-Slot` only names the slot (banner + evidence file
name when given). Before this passthrough existed the documented entry point could not run in parallel at
all: the bounded runner stayed FAIL-CLOSED and refused with `java/javaw already running` (exit 5).

### Parallel builds (task-29)

A fresh checkout does **not** build out of the box. `tools/warm-build-env.ps1` closes the gap, and the
closure is measured: **two independent worktrees at the same commit, warmed by the script, produce a
byte-identical jar.**

```
git -C <warmed tree> worktree add --detach <fresh> <commit>
pwsh tools/warm-build-env.ps1 -TargetDir <fresh> -SourceTree <warmed tree> -Check      # inspect only
pwsh tools/warm-build-env.ps1 -TargetDir <fresh> -SourceTree <warmed tree> -RunBuild   # warm + build
```

| Missing in a fresh checkout | Size / why |
|---|---|
| `libs/freecam-forge-1.2.1+1.20.jar`, `libs/player-animation-lib-forge-1.0.2-rc1+1.20.jar` | 75,389 B / 181,437 B — `libs/*.jar` is gitignored and only 3 of the 5 jars are tracked |
| `build/createSrgToMcp/output.srg`, `build/extractSrg/output.srg`, `build/createMcpToSrg/output.tsrg` | 20,871,086 / 4,558,114 / 9,129,252 B — all under the gitignored `build/`, and a fresh `createSrgToMcp` does not produce them |
| a warmed Gradle user home | without `-g` the deobf cache is absent → `Error getting artifact: blank:tacz:1.1.8-hotfix_mapped_official_1.20.1 … from DeobfuscatingRepo` |

⚠️ **The trap that looks like a missing prerequisite**: Gradle takes its **project directory from the
current working directory**, not from the location of the `gradlew.bat` you invoked (pitfall 8 below).
Run the fresh directory's wrapper while your shell sits in another project and Gradle builds THAT project:
`BUILD SUCCESSFUL`, exit 0, no jar where you asked. Always `cd` into the fresh directory first; the
script does.

**Measured closure — "isolated copies are reproducible"** (2026-09-27, commit `ed48564`; all three built
with `jar --offline --rerun-tasks --no-daemon -g <warmed tree>/.gradle-user-home`):

| directory | path | bytes | entries | sha256 |
|---|---|---|---|---|
| **main checkout** | `<taclight>\build\libs\taclight-0.11.0.jar` | 363,924 | 280 | `b9706833…dc749a34` |
| isolated #1 | `<iso>\harness-iso-build\build\libs\taclight-0.11.0.jar` | 363,924 | 280 | `b9706833…dc749a34` |
| isolated #2 | `<iso>\harness-iso-build-2\build\libs\taclight-0.11.0.jar` | 363,924 | 280 | `b9706833…dc749a34` |

Full sha256 (identical for all three, and #1 vs #2 verified **byte-for-byte** equal):
`b97068337c045cf0f4146a01d7a123f61701b7b1319f68c9243df567dc749a34`

Two **different** directories — the main checkout and an isolated worktree, each with its own eol state
and its own `build/` — producing the same bytes is the main evidence: isolated builds are reproducible,
which is what makes parallel builds legitimate. Build logs:
`docs/evidence/2026-09-27-harness-dev/iso-build-{1,2}.log`.

**Preconditions for that equality** (all four matter): the **same commit**, the **same JDK**
(`17.0.20.1` here), the **same set of un-versioned `libs/*.jar`** (that is exactly what
`warm-build-env.ps1` copies), and a **shared warmed Gradle user home**. Change any one and the jars may
legitimately differ.

**History (why an earlier comparison looked wrong)**: the jar that was in the main tree before this
measurement was built at 01:14:12 (`3AFB5F88…`, 360,403 B, 279 entries) while HEAD was already `ed48564`
(01:44:42) — i.e. it was **stale**, not a counter-example. Its difference from the fresh builds is fully
accounted for by the entries the intervening commits touched: `PackFingerprint.class` (the only class
`ed48564` changed), `PerfStats.class`, `ClientEvents*.class`, plus the newly added `KeyPersist.class`.
Lesson: compare jars **per commit**, and check the jar's mtime against HEAD before drawing a conclusion.

**eol independence**: verified earlier in `cf7b9d7` with an LF / CRLF / restored-LF three-state test
yielding the same sha; the code carrying that property has not been changed since, and the fresh jar's
`taclight.mixins.json` has **CR = 0**.

**Shared vs independent Gradle user home**

| | Shared (`-g <warmed tree>/.gradle-user-home`) | Independent (`GRADLE_USER_HOME` per checkout) |
|---|---|---|
| first build | offline immediately | needs its cache warmed once |
| disk | one copy | one copy per checkout |
| contention | Gradle locks inside the home; `--no-daemon` + distinct projects is the practical mitigation | none |
| use when | you want N checkouts building right now | you want full isolation, or the warm home is read-only |

**Rules**: one `build/` per checkout (never share it); `--no-daemon` for scripted builds; **take
`docs/.gradle-lock` before any gradle**; and **no gradle while a real round is running** — it is CPU / RAM /
disk noise the round did not ask for. `assert-no-round.ps1 -What gradle-build` enforces both conditions
(no real round AND no lock holder) and is meant to be run as its own command before every build.

### Round teardown: the restore must be unconditional and loud (task-40)

`run-round.ps1` changes the game directory for the duration of a round (`pauseOnLostFocus=false`,
`enableDebugOptions=true`, plus backups of `config\taclight-client.toml`) and must put it all back. What it
guarantees now:

1. **Restore is unconditional.** The setup, the launch and the restore are inside one `try/finally`, so a
   failure **inside the setup** still restores whatever the setup already changed (before task-40 the
   `try` began at the launch, so a mid-setup failure left `options.txt` modified). The restore does not
   depend on the harness exit code, on the audit verdict, or on the round being refused or timed out —
   evidence from before the change: `2026-09-27-a3-snow\ops` restored with `match=True` while that round
   exited **4** (instance timeout).
2. **One line per file per slot**, in stdout **and** in `round-setup\restore-report.txt`:
   ```
   [restore] slot=<slot> file=options.txt          before=<sha256> after=<sha256> match=True
   [restore] slot=<slot> file=taclight-client.toml before=<sha256> after=<sha256> match=True
   [restore] slot=<slot> file=oculus.properties    before=<sha256> after=<sha256> match=True
   ROUND_RESTORE=OK
   ```
3. **A failed restore is loud**: `ROUND_RESTORE=FAIL:<files>` in stdout and in the report, and the process
   exits **6 = RESTORE-FAILED**, which takes precedence over the harness's own code. Exit codes so far:
   `0` ok, `2` bad params/refused-to-overwrite, `3` refused (bounded runner), `4` instance timeout,
   `5` java already running, `6` **restore failed**, `7` audit failed, `8` launch failed.

**What "back to before the round" means for `taclight-client.toml`**: the baseline is the harness's
pre-round `.orig` backup, **not** "the value the mod considers default". The mod writes that file during a
round on purpose (`TacLightCommand` / `TuneService`: 调参写回, "重启保留"), so if a round retuned knobs, the
restore **is supposed to undo it** — that is exactly what `match=True` against the `.orig` means.

⚠️ **If you bypass the entry point there is no restore, by design.** A round driven straight through
`run-bounded.ps1` (or a bespoke driver) gets no transient setup and therefore no teardown; the driver owns
the game directory. That is what happened on the 2026-09-27 task-30 parallel run: its evidence contains no
`round-setup\` at all, and two slots were left dirty until they were restored by hand. Two consequences:

- a **parallel driver must call `run-round.ps1` per slot** —
  `run-round.ps1 -Evidence <ev>\B -Slot B -InstanceSignature <gameDirB>` (task-39 forwards both);
- the bypass is now a **machine-checkable** evidence failure rather than a convention:
  `post-round-gates.ps1 -RequireRestore -RoundEvidence <ev>` FAILs when that round has no
  `round-setup\restore-report.txt` or no `ROUND_RESTORE=OK` in it. For the two-slot hand-off use `tools/verify-parallel-restore.ps1 -RoundEvidence <evA>,<evB> -ExpectSlots A,B`: one command that checks both slots' `ROUND_RESTORE` **and** re-hashes their live files against the `*.orig` backups.
⚠️ **"A report says `ROUND_RESTORE=OK`" is NOT "the restore was verified"** (task-46). The gate originally
decided from the report alone — file present plus that one regex — which proves only that *some* report
claims success. An adversarial matrix showed it passing while nothing had been restored: a dirty live file,
a leftover report from an earlier round, a bypassed entry point (no `setup-manifest.txt` at all), and a
round that handled one file of three. Four ways, measured, not hypothesised.

The correct criterion has **three** parts, and `post-round-gates.ps1` no longer re-implements them (two
implementations of one rule drifting apart is exactly what happened to the package fingerprints): it
**calls** `verify-parallel-restore.ps1`, which checks —

1. **provenance** — `round-setup\setup-manifest.txt` exists, so the round really went through
   `run-round.ps1`; "no evidence" is never read as "fine";
2. **binding** — the report must name the same `gameDir` and carry `setupManifestSha256=` matching the
   manifest's bytes, so a stale report cannot speak for this round (reports written before task-46 fail
   this, by design);
3. **independent re-verification** — every file the manifest says the round changed must have a `.orig`
   backup **and** the live file must hash equal to it **now**. The expected set is derived from the
   manifest, so a round that restored one of three files fails.

`-RunTag` rounds: pass the same tag to the verifier/gate —
`post-round-gates.ps1 -RequireRestore -RoundEvidence <ev> -RunTag r1` (before task-46 both tools
hard-coded `round-setup\`, so a tagged round could not pass the gate at all).

### Per-round outputs (task-38)

`run-round.ps1` writes **fixed names** under `-Evidence`: `round-setup\residue-before.txt`,
`round-setup\setup-manifest.txt`, `round-setup\restore-report.txt`, plus the three `*.orig` backups.
Two rounds sharing one `-Evidence` root therefore used to overwrite each other, and **round 1's harness
verdict became permanently unreferenceable** (measured on `2026-09-27-a2-doubleimage`, task-31/A2).

**Rules**

1. **One round, one directory** — the historical practice: `-Evidence <dir>\r1`, `-Evidence <dir>\r2`.
2. **Sharing one root is allowed, but tag every round**: `-RunTag r1` writes those files into
   `round-setup\r1\` with the file names unchanged, so nothing has to re-learn a path.
3. **By default the script refuses to overwrite.** If any of those outputs already exists it exits **2**
   and prints one `conflict:` line per file, plus how to proceed. It writes nothing in that case, so a
   previous round's evidence stays byte-identical. A reused `-RunTag` is refused the same way, and a tag
   that is not a plain directory name (path separators, `.`, `..`) is rejected.
4. **An untagged single round keeps the same evidence LAYOUT and file names** — `round-setup\` with the
   same six names, and no per-tag subdirectory. That is the strongest honest claim: the **preflight stdout
   is NOT byte-identical**, because this change added `[round] instanceSignature=… slot=…` and one more key
   to the `harnessArgv` JSON (13,344 → 13,589 characters when R4 measured it on 2026-09-26).
   `run-round.test.ps1 -OldRunner <pre-change script>` compares the two versions field by field instead of
   pretending the bytes are unchanged.

   ⚠️ **Intentional behaviour change worth knowing**: re-running the SAME evidence root used to overwrite
   (the common "retry after a failure" habit) and now **exits 2**. Either give each attempt its own
   `-Evidence` subdirectory or pass `-RunTag`. Covered **at runtime without a JVM**: the clobber check runs
   *before* the java guard, and the F group asserts exactly this case —
   `F1 a SECOND round in the same evidence root REFUSES (exit 2) -- the retry-after-failure case`,
   `F2 …FAIL:outputs-exist`,
   `F3 …names EACH existing output`, `F4 …says how to proceed`, `F5 …byte-identical after the refusal`
   (the "previous round" is built as files, so nothing has to launch). Observed directly: on a run with a
   java process present the D group SKIPPED while F1–F10 all PASSED.

⚠️ **The archived `run-round.ps1` copies under `docs/evidence/**` are FROZEN HISTORY** — they are the
bytes that actually ran. Do not edit them, and do not back-fill this change into them. The live copy is
`tools/run-round.ps1` only.

### Package fingerprints: `raw` vs `normalized` (task-43)

Two implementations of one rule had drifted apart **in public**. The mod normalized date headers
(`PackFingerprint.normalizeForDigest`, task-23) while the harness fingerprint hashed a manifest of **raw**
per-file hashes, so the same jar produced `01555353…` vs `E72BEC56…` in harness reports while the mod side
reported equal digests — two contradictory "same package?" answers
(`docs/evidence/2026-09-27-fingerprint-verify/gates-audit-run1|run2`).

The audit now emits **two fields**, each with a different job — neither can do the other's:

| field | computed over | answers | CANNOT answer |
|---|---|---|---|
| `fingerprintRaw` | the manifest of **raw** per-file sha256 | "was this artifact modified?" — any byte change moves it (integrity / provenance of a dump) | "same package?" — `*.properties` are rewritten every round with a **new date header**, so this value is **round-sensitive by construction** |
| `fingerprintNormalized` | the same manifest, but each file hashed over the **normalized** text (date headers removed from `*.properties`) | "are these two artifacts the same package?" — stable across rounds that only re-stamped headers | "was anything touched?" — it deliberately ignores header changes, and it does **not** cover non-`.properties` files (a `.fsh` change still moves it) |

`manifestSha256` is kept as a **deprecated alias of the raw value** so existing readers keep working; its
semantics were always raw, which is exactly why it must not be cited for package identity.

The rule lives in **one** place: `tools/pack-fingerprint.ps1` (the audit dot-sources it).
`tools/fingerprint-parity.test.ps1` feeds `tools/fingerprint-corpus.json` to **both** that port and the
**shipped Java class** (`java -cp <mod classes> <driver>`), and every case must satisfy **two** conditions:
the implementations agree **and** both match the expectation declared in the corpus — agreeing on the same
mistake is not agreement. The corpus is read by both sides (never copied) and its own sha256 is printed so
a later weakening of it is visible. Its cases cover exactly what makes the port non-trivial: Java's ASCII
`\w`/`\d` vs .NET's Unicode classes (non-ASCII letters/digits inside a header), Java's `\R` **including VT
(`\u000B`) and FF (`\u000C`)**, and Java's multiline `^` matching after a lone CR / `\u0085` / `\u2028`
(which .NET's `(?m)^` does not). A red control rebuilds the naive port (.NET Unicode classes + `(?m)`) and
requires every killer case to go red while ordinary cases stay green.

**Defect found by this corpus and fixed on both sides (task-49 PS / task-50 Java)**:
`java.util.Properties.store()` writes single-digit days **right-aligned in a two-character field** — the
real shape is `#Thu Jan  1 …`, with `" 1"` as the day. The original rule demanded `\d{2}`, so such a header
was **not** stripped and the normalized digest still drifted **on days 1–9 of every month** — i.e.
"same package?" silently failed exactly when a month started. The day field is now
`(?:[0-9]{2}|[0-9]| [0-9])`, covering `" 1"` (B6), `"01"` (B10), `"1"` (B12) and `"10"` (B11).
**Only** the day field was widened: the hour stays `\d{2}`, so corpus **C12** (`#Thu Jan  1 2:03:04 …`) must
still **not** be stripped. The widening also flipped a former near-miss — **C1** (`#Sat Sep 6 …`) is now a
match, which is recorded in the corpus as the measure of the change's blast radius.

**"Both sides must change together" is a machine judgement, not a convention.** The corpus marks the cases
whose verdict depends on the still-pending Java side with `pendingJavaSide`, and the parity test verifies
the declaration **in both directions**: it fails if the PowerShell side is not already on the new rule, and
it fails if the declaration *outlives* the divergence (the marker must be removed once task-50 lands).
While the declaration stands the divergence is printed as `DIVERGE` with both digests and counted in the
summary — visible, never silent. **Measured — each number with the configuration that produces it** (quote the configuration, not just the
count; these are different runs, so the totals differ):

| configuration | command | result |
|---|---|---|
| PS new / Java old (**the real, committed state**) | `fingerprint-parity.test.ps1` | **PASS 93/93**, 3 declared divergences |
| PS **old** / Java old (repo copy of `pack-fingerprint.ps1` with the day field reverted) | `fingerprint-parity.test.ps1 -ImplPath <old-day variant>` | **FAIL 89/93** — B6/B12/C1 rejected by "powershell is already on the NEW rule"; B10/B11/B8/C10/C11/C12 stay green |
| PS new / Java **new** (peer review shadow-compiled a widened Java side) | `fingerprint-parity.test.ps1` + widened Java | **FAIL 90/93** — "the marker is STALE: remove pendingJavaSide" (3 failures) |
| markers **deleted** from a corpus COPY, Java still old | `fingerprint-parity.test.ps1 -CorpusPath <corpus with pendingJavaSide removed>` | **FAIL 90/96** — the undeclared divergence is caught by `[1] java and powershell agree` + `[2] java matches the declared expectation` |

The totals differ **by construction**, which is why `93` is the only number for the committed state:
a marked case contributes **2** checks (PS-must-be-new, Java-must-still-be-old) and skips `[1]`/`[2-java]`;
the same case with the marker removed contributes **3**. Three marked cases therefore give
93 - 6 + 9 = **96**, and the 6 failures give **90/96**. The variant corpus has
sha256 `C48DC09280ED0BF7B9F796F332566CF151231B863618E27992C7CF9C995F713C`, not the committed
`9E0F6A81…` — a peer review that could not reproduce `90/96` was right to ask: I had quoted the count
without its configuration.

**Raw-口径 catalog — inclusion criterion (so the count is reproducible): a `*.json` under any evidence directory whose PARSED json has a top-level `manifestSha256` property.** That scan (2026-09-26) yields **25 files**; R6 counted **17** with a narrower grep, i.e. a different criterion — if you recount, state your criterion. Those entries hold the RAW value (round-sensitive; state the口径 when citing them): (round-sensitive; state the
口径 whenever you cite them): `2026-09-25-numeric-probe\gates` `2BD5C22D…`;
`2026-09-25-patched-shaders-audit\*-report.json` (4) `21B39D7A…`; `2026-09-25-perf\gates` `08A0BE76…` and
`gates-v1-0948` `4177E93F…`; `2026-09-25-round-gates\gates` `7475D7FA…`; `2026-09-25-voxel-box\gates`
`6AADC86A…`; `2026-09-26-classify\gates` `C0BFC637…`; `2026-09-26-default-day\gates` `C169021C…`;
`2026-09-26-throttle-speeds\gates` `A6E2F56B…`; `2026-09-27-a2-doubleimage\gates` + `gates-corrected`
`84EA8E08…`; `2026-09-27-a3-snow\gates` `93F358C3…`; **`2026-09-27-fingerprint-verify\gates-audit-run1`
`01555353…` vs `gates-audit-run2` + `gates` `E72BEC56…`** (the pair that exposed the disagreement);
`2026-09-27-keychange\gates` `DD745A63…`; `2026-09-27-keyinject\gates` `1E5B342D…`;
`2026-09-27-keypersist\gates` `C23533ED…`; `2026-09-27-keypersist-verify\gates` `76992403…`;
**`2026-09-27-release-smoke\gates` `F8A5302A…` vs `gates-r4` `C8BEC688…`** (same reason);
`2026-09-27-release-smoke-b\gates` `018DD88B…`. (25 files; regenerate by scanning for `manifestSha256`.)

## 驱动脚本坑清单（PowerShell / .NET）—— 每条都来自一次真机事故

**9. 路径遮蔽：相对名按"当前目录"解析，同名文件会盖掉真工具。** 2026-09-26 审核目录里有个
**纯英文散文**文件叫 `assert-no-round.ps1`（原本只是个"工具已迁移"的路标）。从那个目录跑 `.\assert-no-round.ps1`
⇒ PowerShell 把**散文当代码解析** ⇒ `表达式中缺少右括号")"`，而真工具（4,400 B，解析 0 错）**根本没被调用**
⇒ 开发**合理地**报"门禁坏了"（其实门禁从未运行）。
**规矩**：① **调用工具一律写绝对路径**（脚本内部用 `$PSScriptRoot` 相对定位）；② **路标/墓碑绝不许顶着 `.ps1` 扩展名**
（用 `.md`/`.txt`）；③ 用 `parse-check.ps1 -Scan -ScanRoot <tools,evidence>` 把**证据目录也纳入解析检查**——
"看起来是工具、实际不执行"是本项目最隐蔽的一类失效。

驱动脚本（通过文件中继 `<gameDir>/taclight-cmds.txt` 发命令、截图、解析日志的那一层）由每一轮的人
现写，因此**同一批坑被反复踩**。下面 5 类是**已造成实际损失**的（空转整轮、作废整段数据、证据文件名
非法）。**事故出处是工作区仓 `ray-traced-spotlight-mod-dev` 内的路径（相对其根）**，不在本仓内 —— 引
用它们是为了让下一个人能去看原始记录，而不是当成理论风险。

| # | 症状（观察到什么） | 根因 | 正确写法 | 事故出处（真实） |
|---|---|---|---|---|
| 1 | 驱动**秒退**、游戏**空转到 150 s 上限被杀**，harness 记 `FAIL:instance-timeout` | `pwsh -File script.ps1 -Array 0,15,30` **传不了数组**：`-File` 模式把元素摊成独立 argv token，第一个元素被当成另一个开关 ⇒ 参数绑定失败 | 传**逗号分隔字符串**再在脚本内 `-split ','`（`-LagList "0,15,30"`）；或不用 `-File` 而用 `& ./script.ps1 @arrayList` / dot-source | `docs/evidence/2026-09-25-voxel-turn/README.md:74-76`（**两次空转、两次 FAIL**，自述"**同一坑犯第二次**"）；另见 `docs/evidence/2026-09-25-voxel-box/README.md:123` |
| 2 | 驱动在**采完第 1 臂之后**抛异常 ⇒ 游戏空转，**该轮 0 臂数据全部弃用** | `[math]::ToDegrees` **在 .NET / PowerShell 里不存在**（`System.Math` 只有三角函数本体，没有角度换算） | 自己算：`$deg = [math]::Acos($x) * 180 / [math]::PI`（`ToRadians` 同理不存在） | `docs/evidence/2026-09-26-throttle-speeds/README.md:105-109`；事后留注释 `drive-throttle-speeds.ps1:142` |
| 3 | TSV 的 `shot` 列**变成数组/多行** ⇒ 列数错乱，后续解析全歪 | PowerShell 函数**把"未捕获的输出"当返回值**：`Write-Output` 与裸表达式都进管道 ⇒ 调用方拿到多值 | 进度信息一律用 `Write-Host`（不进管道）；返回值只留**一个** `return`；调用方要么 `$row.shot = Save-Shot …`，要么 `[void](Save-Shot …)` | `docs/evidence/2026-09-26-default-day/README.md:138`（明记"修掉 TSV `shot` 列变数组的**老坑**"；6 个驱动各自写过一份 `Save-Shot`，3 种修法混用） |
| 4 | 证据文件名出现 `shot-…sphere:1.0-….png`；`Copy-Item` / `Get-FileHash` 行为**不可靠** | Windows 文件名里的 **`:` 是 NTFS 备用数据流（ADS）分隔符**，不是普通字符 | 拼文件名前净化：`$safe = ($Tag -replace '[^A-Za-z0-9._-]', '_')` | `docs/evidence/2026-09-26-default-day/README.md:135-139`（**真机轮已开跑**才发现，就地终止驱动、改脚本、重启驱动） |
| 5 | ① 报 `does not contain op_Subtraction`；② 正则**匹配成功但 `$Matches` 是空的** ⇒ 相机坐标解析成 `(0,0,0)`，**整轮探针扫在世界原点、结论无效** | ① `@([int]$cam[0] - 16, …)` 里**逗号优先级低于 `-`**，减法拿到 `Object[]` 操作数；② 对**数组**做 `-match` 返回**过滤后的数组**、**不设置 `$Matches`** | ① 每个表达式加括号：`@(([int]$cam[0]) - 16, ([int]$cam[1]) - 2, …)`；② 先取标量再匹配：`$one = @($lines | Select-Object -Last 1); if ($one -match '…') { $Matches[1] }` | `docs/evidence/2026-09-26-classify/README.md:148-154`（两坑叠加；臂数据未受影响但该轮没有 `!quit`，被 150 s 上限杀掉） |
| 6 | 报 `找不到"Add"的参数计数为"1"的重载` —— 明明是自己的 `List`，却像被换成了别的东西 | **自己的变量名撞上 PowerShell 自动变量 `$Matches`**：`$matches = New-Object ...List[object]` 之后，循环里任何一次成功的 `-match` 都会把 `$matches` **覆盖成哈希表**（哈希表的 `Add` 要 2 个参数） | **不要用 `$matches` / `$Matches` 当自己的变量名**（`$input` / `$args` / `$error` / `$host` / `$psitem` 同理）；叫 `$candidates`、`$found` 之类 | 本仓 `tools/capture-window.ps1` 首版（2026-09-27，笔者自己踩的；见 `docs/evidence/2026-09-27-harness-dev/README.md`） |
| 7 | `[System.IO.File]::ReadAllBytes('tools\x.ps1')` 报 `Could not find a part of the path '…\<别的目录>\tools\x.ps1'`，而同一行上 `Get-Content tools\x.ps1` 却正常 | **`cd` 只改 PowerShell 的 provider location，不改 .NET 的 `[Environment]::CurrentDirectory`** ⇒ `[System.IO.*]` 的相对路径按**进程启动目录**解析 | 给 `[System.IO.*]`（以及 `[Parser]::ParseFile` 之类）**一律传绝对路径**：`Join-Path (Get-Location).Path 'tools\x.ps1'` | 同上（2026-09-27；顺带核对出 `parse-check.ps1` **不会**因此假绿：读不到文件会报 `ERRCOUNT=1`） |
| 8 | **`gradle` 打印 `BUILD SUCCESSFUL`、exit 0，但你要的那个目录里`build/libs` 是空的**（或 `createSrgToMcp` exit=0 却不产出 `output.srg`）；日志里出现的还是**另一个项目**的源文件路径 | **Gradle 的"项目目录"取自当前工作目录，不是 `gradlew.bat` 所在目录** ⇒ 在新目录里调 `<新目录>\gradlew.bat`、而 shell 停在别的项目 ⇒ Gradle 构建的是**那个项目**：成功、exit 0、你要的目录里什么都没有 | **先进入目标目录再调 wrapper**：`Push-Location <目标目录>` → `& .\gradlew.bat …` → `Pop-Location`（人类就 `cd`）。**判据**：日志里出现的是**你要的那个目录**的路径（例如 `…\<新目录>\build\generated\refmap\…`） | 本仓 `tools/warm-build-env.ps1` 首版（2026-09-27，我自己踩的：它编译了工作区仓 `com.spotviz` 的源码，并把 `spotviz-0.1.0.jar` 写进**工作区仓**的 `build\libs`）。⚠️ **口径**：该机制**产生与** `dev-voxel`/R3 记录的"新目录里 jar 不产出、日志被写空"**完全相同的症状** ⇒ 下次遇到**先排查这一条**；但**不得**据此断言那就是他们那次的原因（未核过他们当时的 cwd） |

**通用纪律**（上表 5 条的教训）：
* 驱动里的**每一条 sink**（`Write-Output` / 裸表达式）都要当成"会进 TSV / 会进返回值"来看待；不确定就
  用 `Write-Host` + `[void]`。
* **先证明"改的那份真的被读/被加载"**，再报红绿 —— 上表 5 与 `run-round` 的"漏拷"事故都是同一族的
  "假绿/假红"。
* 驱动崩了要**先把实例收掉**（relay `!quit`），别让游戏空转到上限：`FAIL:instance-timeout` 会污染
  "这一轮到底跑没跑"的判断。

## Adding a tool

1. Take every input path as an argument (no defaults pointing outside the repo).
2. Print machine-readable JSON with `--json`/`-Json` when the result is meant for automation.
3. Add a row to the table above and, if the tool encodes a non-obvious data format, document that
   format in the header comment — the format is the interface.
