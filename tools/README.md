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
| `analyze-regions.test.js` | cross-platform | `node tools/analyze-regions.test.js [--tool PATH] [--archived-root DIR] [--skip-archived]` (self-test, **30 checks on a default run**: region maths, frozen default preset, `--regions` validation, relative `imgdiff.js` resolution proved with a stub dependency, archived equivalence) |
| `griddiff.ps1` | **Windows only** (GDI+) | `pwsh tools/griddiff.ps1 -A a.png -B b.png [-Cells 8] [-Threshold 10] [-Json]` |
| `paired-analyze.ps1` | PowerShell 5.1 / 7 | `pwsh tools/paired-analyze.ps1 -Spec spec.json -Session PN -Summary sum.json [-NoisePair "a,b"] [-Json out.json]` |
| `stylemetrics.ps1` | **Windows only** (GDI+) | `pwsh tools/stylemetrics.ps1 -Images a.png,b.png [-Json]` |
| `clusterprobe.ps1` | **Windows only** (GDI+) | `pwsh tools/clusterprobe.ps1 -Image img.png [-Color green] [-Json]` |
| `parse-check.ps1` | PowerShell 5.1 / 7 | `pwsh tools/parse-check.ps1 -Target script.ps1` → prints `ERRCOUNT=0` |
| `rcon.ps1` | PowerShell 5.1 / 7 | `pwsh tools/rcon.ps1 -ServerHost <host> -Command "list"` (password via prompt/env) |
| `run-bounded.ps1` | **Windows only** (process/watchdog/CPU audit) | `pwsh tools/run-bounded.ps1 -FilePath <exe> [-ArgumentList …] [-WorkingDirectory …] [-Windowed] [-OptionsFile run/options.txt] [-MaxInstances 1]` — hard caps **≤6 min/instance** (watchdog `Stop-Process`) and **≤25 min/round**, refuses to start if a `java`/`javaw`/Minecraft window already exists, always audits afterwards (target gone + no orphan `java.exe` + CPU/memory recovered) and writes per-run JSON/TXT metrics; `-DryRun -DryRunScenario ok\|timeout\|stall\|refuse\|orphan\|tree` self-tests every path **without starting a JVM**. **Wrapper launches need `-KillProcessTree`**: if `-FilePath` is a wrapper (`renderdoccmd.exe`, a `*.bat` shim) a single-pid kill leaves the real client alive as an orphan — measured 2026-09-25, `pid=26308 java "Minecraft* Forge …"` survived a `stall-killed` round. `-KillProcessTree` (opt-in, plus `-KillProcessTreeExcludePattern`, default `GradleDaemon`) kills the instance's **descendants** only, deepest-first, and records each kill in `instances[].treeKill`. It is deliberately not the default: on a `gradlew.bat` launch the descendant chain runs through a **shared Gradle daemon**. When the audit sees any survivor the run prints one line and records `audit.leftoverHint` telling you to add `-KillProcessTree` next time |
| `run-bounded.test.ps1` | PowerShell 5.1 / 7 | `pwsh tools/run-bounded.test.ps1` → `RUN-BOUNDED-TEST PASS (N checks)` (offline: drives the runner's 6 `-DryRun` scenarios, asserts exit code + audit verdict + the `caps` field semantics + the `-KillProcessTree` descendant kill **in both directions**; **never starts a JVM**) |
| `run-round.ps1` | PowerShell 5.1 / 7 | `pwsh tools/run-round.ps1 -Evidence <evidence dir> [-TimeoutSec 150] [-RestoreCursor] [-PreflightOnly]` — **the canonical round entry point**: snapshots/restores `options.txt` / `taclight-client.toml` / `oculus.properties`, clears per-round residue, then calls `run-bounded.ps1`. **Do not copy it into an evidence directory** — see the Conventions note below; it resolves the bounded runner beside itself (or `$env:MODTEST_HARNESS_PATH`, or `-HarnessPath`). `-PreflightOnly` resolves and reports everything and touches **nothing** |
| `run-round.test.ps1` | PowerShell 5.1 / 7 | `pwsh tools/run-round.test.ps1 [-ArchivedEvidenceRoot <dir>] [-SkipArchivedEquivalence]` → `RUN-ROUND-TEST PASS (19 checks on a default run)` (offline: canonical-file shape, CLI **superset** of the archived copies, directory-independent harness resolution, read-only preflight, archived-copy provenance; never starts a JVM) |

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
* **Self-tests are the contract.** `imgdiff.test.js`, `rec-analyze.test.js`,
  `analyze-regions.test.js`, `run-bounded.test.ps1` and `run-round.test.ps1` must pass **on a default
  run** (no extra flags) before you change anything in this folder. A change to a tool whose behaviour
  is not covered should add a check to the matching self-test **and prove it is red on the old code
  first**.

## 驱动脚本坑清单（PowerShell / .NET）—— 每条都来自一次真机事故

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
