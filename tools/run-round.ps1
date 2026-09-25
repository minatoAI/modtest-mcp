# SPDX-License-Identifier: GPL-3.0-or-later
# run-round.ps1 (v3, 2026-09-27) -- launch THIS instance's client under the bounded runner
# (caps + post-run audit) WITH a per-round transient setup and a verified restore.
#
# CANONICAL LOCATION -- DO NOT COPY THIS FILE INTO AN EVIDENCE DIRECTORY.
#   This is the one and only live copy: <modtest-mcp>/tools/run-round.ps1. Call it where it lives:
#     pwsh <modtest-mcp>\tools\run-round.ps1 -Evidence <evidence dir>
#   Old rounds solved this by copying the script into each evidence dir; that produced FIVE
#   byte-identical copies (sha256 0FB721FD74B3..., 10,933 B) and, on 2026-09-26, a round that failed
#   to start because somebody forgot the copy (docs/evidence/2026-09-26-classify/README.md 4.3). Those
#   archived copies are FROZEN HISTORY (they are the bytes that actually ran) and are deliberately
#   left untouched -- do not edit them, and do not add a sixth copy.
#   Because nothing is copied any more, no path below may assume where this file sits beyond
#   $PSScriptRoot; -Evidence is still resolved against the CALLER's working directory, exactly as
#   the archived copies did.
#
# PER-ROUND OUTPUTS -- ONE ROUND MUST NEVER OVERWRITE ANOTHER (2026-09-27, task-38)
#   This script's outputs under -Evidence have FIXED names: round-setup\residue-before.txt,
#   round-setup\setup-manifest.txt, round-setup\restore-report.txt (plus the three *.orig backups).
#   Two rounds sharing one -Evidence root therefore clobbered each other, and round 1's harness verdict
#   became permanently unreferenceable (measured on task-31/A2). Consequences, in order of preference:
#     1. give each round its own subdirectory:  -Evidence <dir>\r1   (the historical practice)
#     2. or share one root and tag the round:   -RunTag r1  ->  round-setup\r1\...
#   And by default, if any of those outputs already exists, this script REFUSES (exit 2) and names each
#   conflict instead of overwriting it. The untagged layout and file names are unchanged, so
#   single-round evidence stays byte-identical to earlier rounds.
#
# Reads launch-args.json (produced by make-launch-args.ps1) and splats the argument array from
# THIS session -- `pwsh -File runner.ps1 -ArgumentList <array>` cannot work, because -File mode
# flattens the array into separate argv tokens and the first element is read as another switch.
#
# WHAT v2 ADDS (BACKLOG 2.18 items 13/14) -- all of it is "as found, restored":
#   1. RESIDUE CLEANUP: a leftover <gameDir>/taclight-cmds.txt from a killed round would be
#      consumed as a real command the moment a world loads (and it made the last two rounds
#      impossible to diagnose), and a stale saves/<world>/session.lock can block the world.
#      Both are removed ONLY when no java/javaw is running; every removal is recorded with
#      length + sha256 into round-setup/residue-before.txt.
#   2. pauseOnLostFocus=false for the duration of the round (measurement hygiene, BACKLOG 2.18 #5):
#      the instance's options.txt says true, so an unfocused window pauses the integrated server and
#      the world stops ticking (frame-time comparability across ablation arms suffers). The original
#      bytes are backed up and restored afterwards, verified by sha256.
#   3. config/taclight-client.toml is snapshotted and restored too: the game REWRITES it during a
#      round (schema migration, 922 -> 1433 B, BACKLOG 2.14), so "as found" needs an explicit restore.
#   4. config/oculus.properties: enableDebugOptions=true is what makes Iris dump patched_shaders/
#      (the offline gates read that dump). It is applied for the round and restored afterwards --
#      the archived "as found" state has it false.
#
# WHAT v3 ADDS (2026-09-27, harness task-12 item P2)
#   * NO MACHINE-ABSOLUTE HARNESS PATH. v2 hardcoded an absolute path to run-bounded.ps1 on the
#     author's machine, i.e. it only worked on one machine at one location. v3 resolves in this order:
#     -HarnessPath, then $env:MODTEST_HARNESS_PATH, then the run-bounded.ps1 sitting BESIDE THIS SCRIPT
#     ($PSScriptRoot). The value used is printed.
#   * -PreflightOnly: do the read-only half (resolve the harness, build the argv, summarise
#     launch-args.json) and exit WITHOUT touching the game directory at all. This is how the path
#     resolution can be tested offline, and it lets an operator see the exact argv before spending a
#     round budget.
#   * A missing harness is now a clean exit 2 instead of a terminating error.
#   * It prints which script it is, so an operator can see at a glance whether they used the canonical
#     copy or a stale archived one.
#
# ASCII only. Usage:
#   pwsh run-round.ps1 -Evidence <evidence dir> [-TimeoutSec 150] [-RestoreCursor]
#   pwsh run-round.ps1 -Evidence <evidence dir> -PreflightOnly      # read-only, touches nothing
# Exit: 0 ok | 2 setup error (no launch-args / harness not found) | 5 java already running
#       (otherwise the bounded runner's own exit code is passed through)

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Evidence,
    [int]$TimeoutSec = 150,
    # '' -> $env:MODTEST_HARNESS_PATH -> the run-bounded.ps1 beside this script. See the v3 note above.
    [string]$HarnessPath = '',
    # Forwarded to the harness: after the round, put the OS cursor back where it was. In-world
    # Minecraft grabs the cursor (GLFW_CURSOR_DISABLED -> ClipCursor + recentre on Windows), so the
    # round otherwise leaves the pointer parked at the window centre. See
    # docs/evidence/2026-09-25-mouse-grab/README.md.
    [switch]$RestoreCursor,
    # 2026-09-25 perf 轮:默认 'Minecraft' 未锚定,浏览器里一个 Minecraft 视频标题
    # (window-title)误触发 REFUSING TO START。锚到开头后真游戏窗口("Minecraft* ...")
    # 照常匹配,java/javaw 进程名守卫不动(真双开照拒)。只收窄误拒,不放宽安全。
    [string]$BlockingWindowTitlePattern = '^Minecraft',
    # Escape hatch for a deliberate "leave it as found" diagnostic round.
    [switch]$SkipOptionsSetup,
    # Read-only: resolve + report, touch nothing (no residue cleanup, no options.txt edit, no launch).
    [switch]$PreflightOnly,
    # Per-round output subdirectory. Empty (the default) keeps the historical layout EXACTLY: the three
    # report files and the three *.orig backups go straight into round-setup\, so single-round evidence
    # stays byte-identical to what earlier rounds produced. When set, those files go into
    # round-setup\<tag>\ instead -- which is how two rounds share one -Evidence root without
    # overwriting each other. See the clobber check below for why this is not just tidiness.
    [string]$RunTag = '',
    # Forwarded to run-bounded.ps1 so the round is scoped to ITS OWN instance (task-39 defect 2). Without
    # this passthrough the DOCUMENTED entry point could not be used in parallel at all: the bounded
    # runner's guard stayed FAIL-CLOSED and refused with "java/javaw already running" (exit 5) as soon as
    # another slot's client was up. The instance signature defaults to the gameDir parsed out of
    # launch-args.json; -Slot only names the slot (banner + evidence filename when explicitly given).
    [string]$Slot = '',
    [string]$InstanceSignature = ''
)

$ErrorActionPreference = 'Stop'

Write-Output '[round] canonical=tools/run-round.ps1 (do not copy; archived copies in docs/evidence/** are frozen history)'

$launchPath = Join-Path $Evidence 'launch-args.json'
if (-not (Test-Path -LiteralPath $launchPath)) {
    Write-Output ('ROUND_PREFLIGHT=FAIL:no-launch-args ' + $launchPath)
    exit 2
}
$launch = Get-Content -LiteralPath $launchPath -Raw | ConvertFrom-Json
$gameDir = [string]$launch.game_directory
$setupDir = Join-Path $Evidence 'round-setup'

# --- per-round setup outputs: NEVER clobber a previous round ------------------------------------
# Measured loss (2026-09-27, task-31/A2): two rounds sharing one -Evidence root overwrote each other's
# fixed-name outputs, so round 1's harness verdict became PERMANENTLY UNREFERENCEABLE. The fix is
# "refuse to overwrite by default", not "rename": the file names stay stable, and the caller either
# gives each round its own -Evidence subdirectory (the historical practice -- r1/, r2/, rA-narrow/, ...)
# or passes -RunTag <tag> so the files land in round-setup\<tag>\.
#
# The check covers every file this script writes in that directory, INCLUDING the three *.orig backups:
# those hold the "as found" bytes that the restore step restores FROM, so letting a later round
# overwrite them with its own pre-round state would silently destroy the pristine reference.
$setupOutputNames = @(
    'residue-before.txt',
    'setup-manifest.txt',
    'restore-report.txt',
    'options.txt.orig',
    'taclight-client.toml.orig',
    'oculus.properties.orig'
)
if ($RunTag.Length -gt 0) {
    # The tag becomes one directory name: reject anything that could escape it, or is empty.
    if ($RunTag.Trim().Length -eq 0 -or $RunTag -match '[\\/:*?"<>|]' -or $RunTag -match '^\.+$') {
        Write-Output ('ROUND_PREFLIGHT=FAIL:bad-run-tag [' + $RunTag + ']')
        Write-Output '[round] -RunTag must be a single directory name: no path separators, and not "." or ".."'
        exit 2
    }
    $setupDir = Join-Path $setupDir $RunTag
}
function Get-SetupOutputConflicts([string]$Directory, [string[]]$Names) {
    $hits = New-Object System.Collections.Generic.List[string]
    foreach ($name in $Names) {
        $candidate = Join-Path $Directory $name
        if (Test-Path -LiteralPath $candidate) { $hits.Add($candidate) }
    }
    return $hits
}
$runTagDirExists = ($RunTag.Length -gt 0) -and (Test-Path -LiteralPath $setupDir)

# --- resolve the harness WITHOUT any machine-absolute path (v3) -------------
if ($HarnessPath.Length -eq 0) {
    if ($env:MODTEST_HARNESS_PATH) {
        $HarnessPath = $env:MODTEST_HARNESS_PATH
        Write-Output ('[round] harnessPath from MODTEST_HARNESS_PATH')
    } else {
        $HarnessPath = Join-Path $PSScriptRoot 'run-bounded.ps1'
        Write-Output ('[round] harnessPath from $PSScriptRoot (beside this script)')
    }
}
$harnessExists = Test-Path -LiteralPath $HarnessPath -PathType Leaf

$argumentList = New-Object System.Collections.Generic.List[string]
foreach ($item in @($launch.jvm_arguments)) { $argumentList.Add([string]$item) }
$argumentList.Add([string]$launch.main_class)
foreach ($item in @($launch.game_arguments)) { $argumentList.Add([string]$item) }

$harnessParameters = @{
    FilePath                   = [string]$launch.java
    ArgumentList               = $argumentList.ToArray()
    WorkingDirectory           = $gameDir
    OptionsFile                = (Join-Path $gameDir 'options.txt')
    PerInstanceTimeoutSec      = $TimeoutSec
    MaxInstances               = 1
    EvidenceDir                = (Join-Path $Evidence 'bounded')
    Windowed                   = $true
    VerifyWindow               = $true
    BlockingWindowTitlePattern = $BlockingWindowTitlePattern
}
# Scope this round to ITS OWN instance so parallel slots never refuse each other (task-39 defect 2). The
# default is the gameDir from launch-args.json -- the value run-bounded.ps1 would derive from --gameDir
# anyway, but passed EXPLICITLY so the documented entry point and the built-in guard cannot drift apart.
# -Slot is forwarded only when given, so default evidence file names stay byte-identical.
$effectiveInstanceSignature = $InstanceSignature
if ($effectiveInstanceSignature.Length -eq 0) { $effectiveInstanceSignature = $gameDir }
$harnessParameters['InstanceSignature'] = $effectiveInstanceSignature
if ($Slot.Length -gt 0) { $harnessParameters['Slot'] = $Slot }
if ($RestoreCursor) { $harnessParameters['RestoreCursor'] = $true }

Write-Output ('[round] java={0}' -f $launch.java)
Write-Output ('[round] gameDir={0} args={1} timeoutSec={2}' -f $gameDir, $argumentList.Count, $TimeoutSec)
Write-Output ('[round] harnessPath=' + $HarnessPath)
Write-Output ('[round] harnessExists=' + $harnessExists)
Write-Output ('[round] instanceSignature=' + $effectiveInstanceSignature + ' slot=' + $(if ($Slot.Length -gt 0) { $Slot } else { '<default: gameDir leaf>' }))

if ($PreflightOnly) {
    # READ-ONLY: no residue cleanup, no options.txt / toml / oculus edits, no process launched.
    Write-Output ('[round] evidence=' + (Resolve-Path -LiteralPath $Evidence).Path)
    Write-Output ('[round] setupDir=' + $setupDir)
    # Reporting the clobber condition here is a courtesy, not a behaviour change: preflight keeps its
    # exit codes and still writes nothing (a test asserts that). The refusing happens below, in the
    # real-round path, so that "preflight says OK" keeps meaning "the invocation itself is well-formed".
    if ($runTagDirExists) { Write-Output ('[round] WARNING: that -RunTag directory already exists: ' + $setupDir) }
    $preflightConflicts = @(Get-SetupOutputConflicts -Directory $setupDir -Names $setupOutputNames)
    if ($preflightConflicts.Count -gt 0) {
        Write-Output ('[round] WARNING: a real round would REFUSE -- ' + $preflightConflicts.Count + ' round-setup output(s) already exist:')
        foreach ($conflict in $preflightConflicts) { Write-Output ('  conflict: ' + $conflict) }
        Write-Output '[round] WARNING: use a per-round -Evidence subdirectory, or -RunTag <tag>.'
    }
    Write-Output ('[round] harnessArgv=' + ($harnessParameters | ConvertTo-Json -Compress -Depth 4))
    if (-not $harnessExists) {
        Write-Output 'ROUND_PREFLIGHT=FAIL:no-harness'
        exit 2
    }
    Write-Output 'ROUND_PREFLIGHT=OK'
    exit 0
}
if (-not $harnessExists) {
    Write-Output ('[round] REFUSING: harness not found: ' + $HarnessPath)
    Write-Output 'ROUND_PREFLIGHT=FAIL:no-harness'
    exit 2
}

# --- refuse to clobber: this is the property that protects a previous round's evidence -----------
if ($runTagDirExists) {
    Write-Output ('ROUND_PREFLIGHT=FAIL:run-tag-exists ' + $setupDir)
    Write-Output '[round] REFUSING: that -RunTag directory already exists. Pick another tag, or another -Evidence subdirectory. Nothing was written.'
    exit 2
}
$setupConflicts = @(Get-SetupOutputConflicts -Directory $setupDir -Names $setupOutputNames)
if ($setupConflicts.Count -gt 0) {
    Write-Output 'ROUND_PREFLIGHT=FAIL:outputs-exist'
    Write-Output ('[round] REFUSING to overwrite ' + $setupConflicts.Count + ' existing round-setup output(s) -- a previous round''s evidence must stay intact:')
    foreach ($conflict in $setupConflicts) { Write-Output ('  conflict: ' + $conflict) }
    Write-Output '[round] Use a per-round -Evidence subdirectory (historical practice: -Evidence <dir>\r1), or pass -RunTag <tag> to write into round-setup\<tag>\. Nothing was written.'
    exit 2
}

New-Item -ItemType Directory -Force -Path $setupDir | Out-Null

function Get-Sha256([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }

$manifest = New-Object System.Collections.Generic.List[string]
$manifest.Add('round=' + (Get-Date).ToString('o'))
$manifest.Add('gameDir=' + $gameDir)
$manifest.Add('timeoutSec=' + $TimeoutSec)
$manifest.Add('harnessPath=' + $HarnessPath)
$manifest.Add('canonicalScript=' + $PSCommandPath)
if ($RunTag.Length -gt 0) {
    # Additive, and only when tagged: an untagged manifest must stay byte-identical to before, which is
    # the compatibility property this change is required to preserve.
    $manifest.Add('runTag=' + $RunTag)
}

# --- 1. residue cleanup ---------------------------------------------------
$javaNow = @(Get-Process java, javaw -ErrorAction SilentlyContinue)
if ($javaNow.Count -gt 0) {
    Write-Output ('[preflight] REFUSING: java/javaw already running (pids=' + (($javaNow | ForEach-Object { $_.Id }) -join ',') + ')')
    exit 5
}
$residue = New-Object System.Collections.Generic.List[string]
$cmdFile = Join-Path $gameDir 'taclight-cmds.txt'
if (Test-Path -LiteralPath $cmdFile) {
    $fi = Get-Item -LiteralPath $cmdFile
    $content = ''
    try { $content = ([System.IO.File]::ReadAllText($cmdFile) -replace "`r?`n", ' / ') } catch { $content = '<unreadable>' }
    $residue.Add(('cmdfile len={0} sha256={1} content=[{2}]' -f $fi.Length, (Get-Sha256 $cmdFile), $content))
    Remove-Item -LiteralPath $cmdFile -Force
    $residue.Add('cmdfile REMOVED')
    Write-Output ('[preflight] removed stale taclight-cmds.txt (' + $fi.Length + 'B)')
}
$locks = @(Get-ChildItem -Path (Join-Path $gameDir 'saves') -Recurse -Filter 'session.lock' -ErrorAction SilentlyContinue)
foreach ($lk in $locks) {
    $residue.Add(('session.lock {0} len={1} sha256={2}' -f $lk.FullName, $lk.Length, (Get-Sha256 $lk.FullName)))
    Remove-Item -LiteralPath $lk.FullName -Force
    $residue.Add('session.lock REMOVED ' + $lk.FullName)
    Write-Output ('[preflight] removed stale session.lock: ' + $lk.FullName)
}
if ($residue.Count -eq 0) { $residue.Add('none') }
$residue | Set-Content -Encoding UTF8 (Join-Path $setupDir 'residue-before.txt')
$manifest.Add('residueRemoved=' + (($residue | Where-Object { $_ -match 'REMOVED' }).Count))

# --- 2. transient options setup + snapshot of files the game rewrites -----
$optionsPath = Join-Path $gameDir 'options.txt'
$optionsBackup = Join-Path $setupDir 'options.txt.orig'
$tomlPath = Join-Path $gameDir 'config\taclight-client.toml'
$tomlBackup = Join-Path $setupDir 'taclight-client.toml.orig'
$oculusPath = Join-Path $gameDir 'config\oculus.properties'
$oculusBackup = Join-Path $setupDir 'oculus.properties.orig'
$restoreOptions = $false
$restoreToml = $false
$restoreOculus = $false
$optionsShaBefore = ''
$oculusShaBefore = ''

if (-not $SkipOptionsSetup) {
    if (-not (Test-Path -LiteralPath $optionsPath)) { throw ('options.txt not found: ' + $optionsPath) }
    Copy-Item -LiteralPath $optionsPath -Destination $optionsBackup -Force
    $optionsShaBefore = Get-Sha256 $optionsBackup
    $restoreOptions = $true
    $text = [System.IO.File]::ReadAllText($optionsPath)
    $newText = [regex]::Replace($text, '(?m)^pauseOnLostFocus:.*$', 'pauseOnLostFocus:false')
    if ($newText -eq $text -and $text -notmatch '(?m)^pauseOnLostFocus:') {
        $newText = $text.TrimEnd() + "`npauseOnLostFocus:false`n"
    }
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($optionsPath, $newText, $enc)
    $optionsShaAfter = Get-Sha256 $optionsPath
    $manifest.Add('optionsBeforeSha256=' + $optionsShaBefore)
    $manifest.Add('optionsDuringRoundSha256=' + $optionsShaAfter)
    Write-Output ('[preflight] options.txt: pauseOnLostFocus=false applied (before sha ' + $optionsShaBefore.Substring(0, 12) + '...)')

    if (Test-Path -LiteralPath $tomlPath) {
        Copy-Item -LiteralPath $tomlPath -Destination $tomlBackup -Force
        $restoreToml = $true
        $manifest.Add('taclightClientTomlBeforeSha256=' + (Get-Sha256 $tomlBackup))
    }

    if (Test-Path -LiteralPath $oculusPath) {
        Copy-Item -LiteralPath $oculusPath -Destination $oculusBackup -Force
        $oculusShaBefore = Get-Sha256 $oculusBackup
        $restoreOculus = $true
        $otext = [System.IO.File]::ReadAllText($oculusPath)
        $onew = [regex]::Replace($otext, '(?m)^enableDebugOptions\s*[:=].*$', 'enableDebugOptions=true')
        if ($onew -eq $otext -and $otext -notmatch 'enableDebugOptions') {
            $onew = $otext.TrimEnd() + "`nenableDebugOptions=true`n"
        }
        [System.IO.File]::WriteAllText($oculusPath, $onew, $enc)
        $manifest.Add('oculusBeforeSha256=' + $oculusShaBefore)
        $manifest.Add('oculusDuringRoundSha256=' + (Get-Sha256 $oculusPath))
        Write-Output ('[preflight] oculus.properties: enableDebugOptions=true applied (before sha ' + $oculusShaBefore.Substring(0, 12) + '...)')
    }
} else {
    $manifest.Add('optionsSetup=SKIPPED')
    Write-Output '[preflight] options setup SKIPPED (-SkipOptionsSetup)'
}

$manifest | Set-Content -Encoding UTF8 (Join-Path $setupDir 'setup-manifest.txt')

# --- 3. run the bounded instance, then restore ----------------------------
$harnessExit = 99
try {
    & $HarnessPath @harnessParameters
    $harnessExit = $LASTEXITCODE
} finally {
    $restore = New-Object System.Collections.Generic.List[string]
    $restore.Add('harnessExit=' + $harnessExit)
    if ($restoreOptions) {
        Copy-Item -LiteralPath $optionsBackup -Destination $optionsPath -Force
        $shaNow = Get-Sha256 $optionsPath
        $ok = ($shaNow -eq $optionsShaBefore)
        $restore.Add(('options.txt restored sha256={0} matches-before={1}' -f $shaNow, $ok))
        Write-Output ('[restore] options.txt sha ' + $shaNow.Substring(0, 12) + '... matches-before=' + $ok)
        if (-not $ok) { Write-Output '[restore] ERROR: options.txt restore sha mismatch' }
    }
    if ($restoreToml) {
        $tomlShaBefore = (Get-Sha256 $tomlBackup)
        Copy-Item -LiteralPath $tomlBackup -Destination $tomlPath -Force
        $tomlShaNow = Get-Sha256 $tomlPath
        $restore.Add(('taclight-client.toml restored sha256={0} matches-before={1}' -f $tomlShaNow, ($tomlShaNow -eq $tomlShaBefore)))
        Write-Output ('[restore] taclight-client.toml matches-before=' + ($tomlShaNow -eq $tomlShaBefore))
    }
    if ($restoreOculus) {
        Copy-Item -LiteralPath $oculusBackup -Destination $oculusPath -Force
        $oculusShaNow = Get-Sha256 $oculusPath
        $restore.Add(('oculus.properties restored sha256={0} matches-before={1}' -f $oculusShaNow, ($oculusShaNow -eq $oculusShaBefore)))
        Write-Output ('[restore] oculus.properties matches-before=' + ($oculusShaNow -eq $oculusShaBefore))
    }
    $javaAfter = @(Get-Process java, javaw -ErrorAction SilentlyContinue)
    $restore.Add('javaAfter=' + $javaAfter.Count)
    Write-Output ('[restore] java processes after round = ' + $javaAfter.Count)
    $restore | Set-Content -Encoding UTF8 (Join-Path $setupDir 'restore-report.txt')
}

exit $harnessExit
