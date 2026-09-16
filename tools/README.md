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
| `griddiff.ps1` | **Windows only** (GDI+) | `pwsh tools/griddiff.ps1 -A a.png -B b.png [-Cells 8] [-Threshold 10] [-Json]` |
| `paired-analyze.ps1` | PowerShell 5.1 / 7 | `pwsh tools/paired-analyze.ps1 -Spec spec.json -Session PN -Summary sum.json [-NoisePair "a,b"] [-Json out.json]` |
| `stylemetrics.ps1` | **Windows only** (GDI+) | `pwsh tools/stylemetrics.ps1 -Images a.png,b.png [-Json]` |
| `clusterprobe.ps1` | **Windows only** (GDI+) | `pwsh tools/clusterprobe.ps1 -Image img.png [-Color green] [-Json]` |
| `parse-check.ps1` | PowerShell 5.1 / 7 | `pwsh tools/parse-check.ps1 -Target script.ps1` → prints `ERRCOUNT=0` |
| `rcon.ps1` | PowerShell 5.1 / 7 | `pwsh tools/rcon.ps1 -ServerHost <host> -Command "list"` (password via prompt/env) |

## Conventions

* **Paths are always arguments.** Nothing in this directory hardcodes a machine path, a drive
  letter or a user profile directory. Defaults are relative (`./`) or environment variables
  (`MODTEST_RCON_HOST`, `MODTEST_RCON_PASSWORD`).
* **No default credentials.** `rcon.ps1` requires `-Password` (or `MODTEST_RCON_PASSWORD`) and has
  no fallback value; `-ServerHost` falls back only to `MODTEST_RCON_HOST`. Never point it at a
  server you do not own or administer.
* **Windows-only tools are labelled.** The three GDI+ scripts need `System.Drawing` (Windows
  PowerShell or PowerShell 7 on Windows). The Node tools run anywhere.
* **Recorder header signature.** `rec-analyze.js` expects the recording's first line to start with
  `#` and match `--header-pattern` (default: `/rec/i`). Pass your recorder's own signature if it
  differs; the default is intentionally generic.
* **Self-tests are the contract.** `imgdiff.test.js` and `rec-analyze.test.js` must pass before you
  change anything in this folder.

## Adding a tool

1. Take every input path as an argument (no defaults pointing outside the repo).
2. Print machine-readable JSON with `--json`/`-Json` when the result is meant for automation.
3. Add a row to the table above and, if the tool encodes a non-obvious data format, document that
   format in the header comment — the format is the interface.
