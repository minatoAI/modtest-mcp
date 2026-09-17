# SPDX-License-Identifier: GPL-3.0-or-later
# run-bounded.ps1 -- bounded real-machine run harness (time-boxed, watchdog-killed, self-audited).
#
# WHY: a real-client run must never be left running, must never outlive its budget, and must not
# keep the machine busy for long. This harness enforces that as hard caps and PROVES the cleanup
# afterwards. It is mod-agnostic: it launches whatever command you give it, nothing game-specific.
#
# HARD CAPS (fail closed; see "caps" comments below)
#   * one instance  <= 360 s  (6 min, startup+exit included)   -> -PerInstanceTimeoutSec  (default 330)
#   * whole round   <= 1500 s (25 min, accumulated)            -> -RoundBudgetSec         (default 1500)
#   * watchdog kills the instance itself (Stop-Process -Force) on deadline OR on stall
#   * before starting: any java/javaw/Minecraft window present -> REFUSE (never kills other people's
#     processes; it only declines to start)
#   * after every instance, mandatory post-run audit: this PID is gone, no orphan java/javaw remain
#     (a pre-launch baseline of matching PIDs is excluded: a round explicitly allowed to run next to
#     a pre-existing JVM, e.g. a LAN round with its own dedicated server, must not be blamed for it),
#     and system CPU / available memory have come back to the pre-round baseline
#   * any cap breach or failed audit => stop the whole round immediately with a non-zero exit code
#
# EXIT CODES
#   0 ok | 2 bad parameters (cap above the hard limit) | 3 refused (blocking process present)
#   4 instance hit the 6-min cap (killed) | 5 instance stalled (killed) | 6 round budget exceeded
#   7 post-run audit failed | 8 could not launch
#
# EVIDENCE (machine-readable; the run summary is the interface)
#   <EvidenceDir>/run-bounded-<yyyyMMdd-HHmmss>.json   full summary + per-instance metrics
#   <EvidenceDir>/run-bounded-<yyyyMMdd-HHmmss>.txt    same, human-readable
#   per instance: pid, wall seconds, peak process CPU%, peak working set MB, exit code, outcome,
#   kill reason, post-run audit readings (system CPU% and available MB before/after)
#   NOTE ON UNITS: peakCpuPct is the sum over cores (a busy 8-core process can read >100%), and
#   peakMemoryMB is Win32_Process.WorkingSetSize sampled every poll (not the .NET object's stale
#   WorkingSet64).
#   EvidenceDir default: $env:MODTEST_BOUNDED_EVIDENCE_DIR, else <temp>/modtest-bounded-runs
#
# DRY RUN (proves the logic WITHOUT ever starting a JVM)
#   -DryRun -DryRunScenario ok|timeout|stall|refuse|orphan
#     The harness substitutes a harmless non-JVM child (the current PowerShell host running a
#     sleep). It refuses to run at all if that child resolves to a java* executable.
#     ok      -> child exits by itself, audit passes, exit 0
#     timeout -> child is killed by the 6-min-equivalent deadline (use a small -PerInstanceTimeoutSec)
#     stall   -> child is killed by stall detection (flat CPU + no output for -StallSeconds)
#     refuse  -> -BlockingProcessNames picks up an existing process; nothing is launched, exit 3
#     orphan  -> FAULT INJECTION: the watchdog deliberately does not kill, so the post-run audit
#                must catch the still-live child (exit 7) and then clean up only that child
#
# USAGE
#   pwsh tools/run-bounded.ps1 -FilePath ./gradlew.bat -ArgumentList ':forge:runClient' `
#        -WorkingDirectory ../modtest-mcp -Windowed -OptionsFile ./run/options.txt -VerifyWindow
#   pwsh tools/run-bounded.ps1 -DryRun -DryRunScenario timeout -PerInstanceTimeoutSec 6 -Json
#
# ASCII only (PowerShell 5.1 + no BOM); no single-letter lowercase variables. Validate with
# `pwsh tools/parse-check.ps1 -Target tools/run-bounded.ps1` -> ERRCOUNT=0.

[CmdletBinding()]
param(
    [string]$FilePath = '',
    [string[]]$ArgumentList = @(),
    [string]$WorkingDirectory = '',
    [int]$PerInstanceTimeoutSec = 330,
    [int]$RoundBudgetSec = 1500,
    [int]$StallSeconds = 45,
    [int]$MaxInstances = 1,
    [string]$EvidenceDir = '',
    [string]$BlockingProcessNames = 'java,javaw',
    [string]$BlockingWindowTitlePattern = 'Minecraft',
    [string]$OrphanProcessNames = 'java,javaw',
    [switch]$Windowed,
    [string]$OptionsFile = '',
    [switch]$VerifyWindow,
    [double]$CpuRecoveryTolerancePct = 20.0,
    [int]$MemoryRecoveryToleranceMB = 512,
    [int]$PollSeconds = 1,
    [switch]$Json,
    [switch]$DryRun,
    [ValidateSet('ok', 'timeout', 'stall', 'refuse', 'orphan')]
    [string]$DryRunScenario = 'ok'
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- constants (hard caps)
$HARD_CAP_PER_INSTANCE_SEC = 360      # 6 minutes -- cannot be raised by a parameter
$HARD_CAP_ROUND_SEC = 1500            # 25 minutes -- cannot be raised by a parameter
$WINDOW_WIDTH = 1280                  # hardcoded windowed geometry (never fullscreen)
$WINDOW_HEIGHT = 800

$EXIT_OK = 0
$EXIT_BAD_PARAMS = 2
$EXIT_REFUSED = 3
$EXIT_INSTANCE_TIMEOUT = 4
$EXIT_INSTANCE_STALL = 5
$EXIT_ROUND_BUDGET = 6
$EXIT_AUDIT_FAILED = 7
$EXIT_LAUNCH_FAILED = 8

$scriptFindings = New-Object System.Collections.Generic.List[string]
$instanceRecords = New-Object System.Collections.Generic.List[object]

function Add-Finding([string]$Message) {
    $scriptFindings.Add($Message) | Out-Null
}

function Get-SystemSnapshot {
    # system-wide CPU% (busy) and available memory in MB, best effort with fallbacks
    $cpuPct = -1.0
    try {
        $perf = Get-CimInstance -ClassName Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'" -ErrorAction Stop
        if ($null -ne $perf -and $null -ne $perf.PercentProcessorTime) { $cpuPct = [double]$perf.PercentProcessorTime }
    } catch {
        try {
            $processor = Get-CimInstance -ClassName Win32_Processor -ErrorAction Stop | Select-Object -First 1
            if ($null -ne $processor) { $cpuPct = [double]$processor.LoadPercentage }
        } catch { $cpuPct = -1.0 }
    }
    $availableMB = -1.0
    try {
        $osInfo = Get-CimInstance -ClassName Win32_OperatingSystem -ErrorAction Stop
        if ($null -ne $osInfo) { $availableMB = [math]::Round(([double]$osInfo.FreePhysicalMemory) / 1024.0, 1) }
    } catch { $availableMB = -1.0 }
    return [pscustomobject]@{ cpuPct = $cpuPct; availableMB = $availableMB; at = (Get-Date).ToString('s') }
}

function Get-ProcessNameList([string]$Csv) {
    $names = New-Object System.Collections.Generic.List[string]
    foreach ($piece in ($Csv -split ',')) {
        $trimmed = $piece.Trim()
        if ($trimmed.Length -gt 0) { $names.Add($trimmed) }
    }
    return $names
}

function Get-BlockingProcesses {
    param([System.Collections.Generic.List[string]]$Names, [string]$TitlePattern)
    $found = New-Object System.Collections.Generic.List[object]
    foreach ($processItem in @(Get-Process -ErrorAction SilentlyContinue)) {
        $isNameMatch = $false
        foreach ($nameItem in $Names) {
            $bare = $nameItem -replace '\.exe$', ''
            if ($processItem.ProcessName -ieq $bare) { $isNameMatch = $true }
        }
        $isTitleMatch = $false
        if ($TitlePattern.Length -gt 0 -and $processItem.MainWindowTitle -match $TitlePattern) { $isTitleMatch = $true }
        if ($isNameMatch -or $isTitleMatch) {
            $found.Add([pscustomobject]@{
                pid = $processItem.Id; name = $processItem.ProcessName
                title = $processItem.MainWindowTitle; reason = $(if ($isTitleMatch) { 'window-title' } else { 'process-name' })
            }) | Out-Null
        }
    }
    return $found
}

function Test-ProcessAlive([int]$ProcessId) {
    $live = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $live) { return $false }
    return $true
}

function Get-ProcessSample([System.Diagnostics.Process]$ProcessObject) {
    # Prefer CIM: a Start-Process .NET Process object can report a stale/early WorkingSet64 (a JVM
    # read 3.1 MB that way), which would make the required "peak memory" metric worthless.
    $cpuSeconds = -1.0
    $workingSetMB = -1.0
    try {
        $ownerPid = $ProcessObject.Id
        $row = Get-CimInstance -ClassName Win32_Process -Filter ("ProcessId={0}" -f $ownerPid) -ErrorAction Stop
        if ($null -ne $row) {
            $workingSetMB = [math]::Round(([double]$row.WorkingSetSize) / 1MB, 1)
            # Win32_Process times are 100-ns units; sum of user+kernel = CPU seconds used
            $cpuSeconds = ([double]$row.UserModeTime + [double]$row.KernelModeTime) / 10000000.0
        }
    } catch {
        $cpuSeconds = -1.0
        $workingSetMB = -1.0
    }
    if ($workingSetMB -lt 0) {
        try { $workingSetMB = [math]::Round(([double]$ProcessObject.WorkingSet64) / 1MB, 1) } catch { $workingSetMB = -1.0 }
    }
    if ($cpuSeconds -lt 0) {
        try { $cpuSeconds = $ProcessObject.TotalProcessorTime.TotalSeconds } catch { $cpuSeconds = -1.0 }
    }
    return [pscustomobject]@{ cpuSeconds = $cpuSeconds; workingSetMB = $workingSetMB }
}

function Get-WindowGeometry([int]$ProcessId) {
    # optional window audit; returns physical window/client rect and DPI, or $null
    $signature = @'
[DllImport("user32.dll")] public static extern bool GetWindowRect(System.IntPtr hWnd, out RECT lpRect);
[DllImport("user32.dll")] public static extern bool GetClientRect(System.IntPtr hWnd, out RECT lpRect);
[DllImport("user32.dll")] public static extern uint GetDpiForWindow(System.IntPtr hWnd);
public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
'@
    try { Add-Type -Namespace ModtestBounded -Name Win -MemberDefinition $signature -ErrorAction SilentlyContinue } catch { }
    $live = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $live -or $live.MainWindowHandle -eq 0) { return $null }
    try {
        $windowRect = New-Object ModtestBounded.Win+RECT
        $clientRect = New-Object ModtestBounded.Win+RECT
        [void][ModtestBounded.Win]::GetWindowRect($live.MainWindowHandle, [ref]$windowRect)
        [void][ModtestBounded.Win]::GetClientRect($live.MainWindowHandle, [ref]$clientRect)
        $dpi = [ModtestBounded.Win]::GetDpiForWindow($live.MainWindowHandle)
        return [pscustomobject]@{
            title = $live.MainWindowTitle
            windowPhysical = ('{0}x{1}' -f ($windowRect.Right - $windowRect.Left), ($windowRect.Bottom - $windowRect.Top))
            clientPhysical = ('{0}x{1}' -f ($clientRect.Right - $clientRect.Left), ($clientRect.Bottom - $clientRect.Top))
            dpi = $dpi
        }
    } catch { return $null }
}

function Assert-OptionsWindowed([string]$Path) {
    # hard rule: windowed 1280x800, fullscreen forbidden
    if ($Path.Length -eq 0) { return }
    if (-not (Test-Path -LiteralPath $Path)) { throw ("options file not found: {0}" -f $Path) }
    $fullscreenLine = Select-String -LiteralPath $Path -Pattern '^fullscreen:' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $fullscreenLine -and $fullscreenLine.Line -match 'fullscreen:true') {
        throw ("refusing to run: {0} has fullscreen:true (windowed 1280x800 is mandatory)" -f $Path)
    }
}

function Get-DryRunChild {
    # a harmless NON-JVM child used only by -DryRun: the current PowerShell host sleeping
    $hostExe = (Get-Process -Id $PID).Path
    if ($hostExe -match '(?i)javaw?\.exe$') { throw 'dry-run child resolved to a java executable; refusing (dry runs must not start a JVM)' }
    if ($DryRunScenario -eq 'timeout') { $sleepSeconds = 120 }
    elseif ($DryRunScenario -eq 'stall') { $sleepSeconds = 120 }
    elseif ($DryRunScenario -eq 'orphan') { $sleepSeconds = 30 }
    else { $sleepSeconds = 3 }
    $childArgs = @('-NoProfile', '-NonInteractive', '-Command', ('Start-Sleep -Seconds {0}' -f $sleepSeconds))
    return [pscustomobject]@{ exe = $hostExe; args = $childArgs; note = ('dry-run child (PowerShell sleep {0}s, NOT a JVM)' -f $sleepSeconds) }
}

function Invoke-BoundedInstance {
    param(
        [int]$Index,
        [string]$Exe,
        [string[]]$ExeArgs,
        [string]$WorkDir,
        [int]$TimeoutSec,
        [int]$StallLimitSec,
        [string]$DryScenario,
        [string]$EvidenceDirectory,
        [string]$RunStamp,
        [switch]$IsDry
    )
    $startedAt = Get-Date
    $effectiveArgs = @($ExeArgs)
    if ($Windowed) {
        # hardcoded windowed geometry; never fullscreen
        $effectiveArgs += @('--width', "$WINDOW_WIDTH", '--height', "$WINDOW_HEIGHT")
    }
    # child output goes to files: (a) it is evidence, (b) the child must NOT inherit our own
    # stdout/stderr handles (that can keep an agent harness call blocked while the child lives)
    if ($EvidenceDirectory.Length -gt 0 -and -not (Test-Path -LiteralPath $EvidenceDirectory)) {
        New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
    }
    $outFile = Join-Path $EvidenceDirectory ('{0}-instance{1}-out.txt' -f $RunStamp, $Index)
    $errFile = Join-Path $EvidenceDirectory ('{0}-instance{1}-err.txt' -f $RunStamp, $Index)
    $launchParams = @{ FilePath = $Exe; ArgumentList = $effectiveArgs; PassThru = $true; RedirectStandardOutput = $outFile; RedirectStandardError = $errFile }
    if ($WorkDir.Length -gt 0) { $launchParams['WorkingDirectory'] = $WorkDir }
    $childProcess = $null
    try {
        $childProcess = Start-Process @launchParams
    } catch {
        Add-Finding ('launch failed: {0}' -f $_.Exception.Message)
        return [pscustomobject]@{ index = $Index; pid = -1; outcome = 'launch-failed'; error = $_.Exception.Message }
    }
    $childPid = $childProcess.Id
    $lastSample = Get-ProcessSample -ProcessObject $childProcess
    $peakCpuPct = 0.0
    $peakMemoryMB = 0.0
    $lastCpuSeconds = $lastSample.cpuSeconds
    $lastChangeAt = Get-Date
    $outcome = 'exited'
    $killReason = ''
    $exitCode = $null
    while ($true) {
        Start-Sleep -Seconds $PollSeconds
        $elapsedSec = ((Get-Date) - $startedAt).TotalSeconds
        if ($childProcess.HasExited) {
            try { $childProcess.Refresh(); $exitCode = $childProcess.ExitCode } catch { $exitCode = $null }
            if ($null -eq $exitCode) { $exitCode = -1; Add-Finding 'child exit code unavailable (recorded as -1)' }
            $outcome = 'exited'
            break
        }
        $sample = Get-ProcessSample -ProcessObject $childProcess
        if ($sample.workingSetMB -gt $peakMemoryMB) { $peakMemoryMB = $sample.workingSetMB }
        if ($sample.cpuSeconds -ge 0 -and $lastCpuSeconds -ge 0) {
            $deltaCpu = $sample.cpuSeconds - $lastCpuSeconds
            $deltaWall = [double]$PollSeconds
            if ($deltaWall -gt 0) {
                $cpuPctNow = ($deltaCpu / $deltaWall) * 100.0
                if ($cpuPctNow -gt $peakCpuPct) { $peakCpuPct = $cpuPctNow }
            }
            if ($deltaCpu -gt 0.001) { $lastChangeAt = Get-Date }
            $lastCpuSeconds = $sample.cpuSeconds
        }
        if ($elapsedSec -ge $TimeoutSec) {
            $outcome = 'timeout-killed'
            $killReason = ('instance exceeded the per-instance cap ({0}s)' -f $TimeoutSec)
            break
        }
        $idleSec = ((Get-Date) - $lastChangeAt).TotalSeconds
        if ($StallLimitSec -gt 0 -and $idleSec -ge $StallLimitSec -and $elapsedSec -ge ($PollSeconds * 2)) {
            $outcome = 'stall-killed'
            $killReason = ('no CPU progress for {0}s (stall detection)' -f [int]$idleSec)
            break
        }
        if ($IsDry -and $DryScenario -eq 'orphan' -and $elapsedSec -ge 3) {
            # FAULT INJECTION: pretend the instance is over while the child is still alive, so the
            # post-run audit must catch it. Cleanup happens below, for THIS pid only.
            $outcome = 'exited'
            $killReason = 'fault injection: watchdog intentionally skipped (orphan audit test)'
            break
        }
    }
    $wallSeconds = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 2)
    $killPerformed = $false
    if ($outcome -eq 'timeout-killed' -or $outcome -eq 'stall-killed') {
        try {
            Stop-Process -Id $childPid -Force -ErrorAction Stop
            $killPerformed = $true
        } catch {
            Add-Finding ('kill failed for pid {0}: {1}' -f $childPid, $_.Exception.Message)
        }
    }
    $geometry = $null
    if ($VerifyWindow) { $geometry = Get-WindowGeometry -ProcessId $childPid }
    return [pscustomobject]@{
        index = $Index; pid = $childPid; outcome = $outcome; killReason = $killReason
        killPerformed = $killPerformed; wallSeconds = $wallSeconds
        peakCpuPct = [math]::Round($peakCpuPct, 1); peakMemoryMB = [math]::Round($peakMemoryMB, 1)
        exitCode = $exitCode; window = $geometry; startedAt = $startedAt.ToString('s')
        stdoutFile = $outFile; stderrFile = $errFile
        effectiveArgs = $effectiveArgs
    }
}

function Test-PostRunAudit {
    param(
        [object]$Record,
        [System.Collections.Generic.List[string]]$OrphanNames,
        [string]$TitlePattern,
        [object]$BaselineSnapshot,
        [object]$AfterSnapshot,
        # PIDs of orphan-name-matching processes that already existed BEFORE this round started.
        # They are not "our" leftovers: a round may intentionally run alongside a pre-existing JVM
        # (e.g. a LAN round with its own dedicated server). Only NEW survivors are orphans.
        [int[]]$BaselineProcessIds = @()
    )
    $problems = New-Object System.Collections.Generic.List[string]
    $targetAlive = $false
    if ($Record.pid -gt 0) { $targetAlive = Test-ProcessAlive -ProcessId $Record.pid }
    if ($targetAlive) { $problems.Add(('target pid {0} is still alive after the instance' -f $Record.pid)) | Out-Null }
    $matching = Get-BlockingProcesses -Names $OrphanNames -TitlePattern $TitlePattern
    $orphans = @($matching | Where-Object { $BaselineProcessIds -notcontains [int]$_.pid })
    $preexisting = @($matching | Where-Object { $BaselineProcessIds -contains [int]$_.pid })
    if (@($orphans).Count -gt 0) {
        foreach ($orphan in $orphans) { $problems.Add(('orphan process: pid={0} name={1} title={2}' -f $orphan.pid, $orphan.name, $orphan.title)) | Out-Null }
    }
    $cpuRecovered = $true
    $memoryRecovered = $true
    if ($null -ne $BaselineSnapshot -and $null -ne $AfterSnapshot) {
        if ($BaselineSnapshot.cpuPct -ge 0 -and $AfterSnapshot.cpuPct -ge 0) {
            if ($AfterSnapshot.cpuPct -gt ($BaselineSnapshot.cpuPct + $CpuRecoveryTolerancePct)) {
                $cpuRecovered = $false
                $problems.Add(('system CPU did not recover: baseline {0}% -> after {1}% (tolerance {2}%)' -f $BaselineSnapshot.cpuPct, $AfterSnapshot.cpuPct, $CpuRecoveryTolerancePct)) | Out-Null
            }
        }
        if ($BaselineSnapshot.availableMB -ge 0 -and $AfterSnapshot.availableMB -ge 0) {
            if ($AfterSnapshot.availableMB -lt ($BaselineSnapshot.availableMB - $MemoryRecoveryToleranceMB)) {
                $memoryRecovered = $false
                $problems.Add(('available memory did not recover: baseline {0} MB -> after {1} MB (tolerance {2} MB)' -f $BaselineSnapshot.availableMB, $AfterSnapshot.availableMB, $MemoryRecoveryToleranceMB)) | Out-Null
            }
        }
    }
    return [pscustomobject]@{
        ok = ($problems.Count -eq 0); problems = $problems.ToArray()
        targetGone = (-not $targetAlive); orphanCount = @($orphans).Count
        preexistingExcluded = @($preexisting).Count
        cpuRecovered = $cpuRecovered; memoryRecovered = $memoryRecovered
        targetCheck = ('Get-Process -Id {0} => {1}' -f $Record.pid, $(if ($targetAlive) { 'ALIVE' } else { 'not found (gone)' }))
        baseline = $BaselineSnapshot; after = $AfterSnapshot
    }
}

function Get-EvidenceDirectory([string]$Requested) {
    if ($Requested.Length -gt 0) { return $Requested }
    if ($env:MODTEST_BOUNDED_EVIDENCE_DIR) { return $env:MODTEST_BOUNDED_EVIDENCE_DIR }
    return (Join-Path ([System.IO.Path]::GetTempPath()) 'modtest-bounded-runs')
}

function Write-Evidence {
    param([object]$Summary, [string]$Directory)
    if (-not (Test-Path -LiteralPath $Directory)) { New-Item -ItemType Directory -Force -Path $Directory | Out-Null }
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $jsonPath = Join-Path $Directory ('run-bounded-{0}.json' -f $stamp)
    $textPath = Join-Path $Directory ('run-bounded-{0}.txt' -f $stamp)
    [System.IO.File]::WriteAllText($jsonPath, (($Summary | ConvertTo-Json -Depth 8) + "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add(('RUN-BOUNDED mode={0} scenario={1} verdcit={2}' -f $Summary.mode, $Summary.dryRunScenario, $Summary.verdict)) | Out-Null
    $lines.Add(('caps: perInstance<={0}s round<={1}s stall<={2}s windowed={3} ({4}x{5})' -f $HARD_CAP_PER_INSTANCE_SEC, $HARD_CAP_ROUND_SEC, $Summary.stallSeconds, $Summary.windowed, $WINDOW_WIDTH, $WINDOW_HEIGHT)) | Out-Null
    $lines.Add(('roundWallSeconds={0} baselineCpu={1}% baselineAvailMB={2}' -f $Summary.roundWallSeconds, $Summary.baseline.cpuPct, $Summary.baseline.availableMB)) | Out-Null
    foreach ($record in @($Summary.instances)) {
        $lines.Add(('instance#{0} pid={1} outcome={2} wall={3}s peakCpu={4}% peakMem={5}MB exit={6} kill={7}' -f $record.index, $record.pid, $record.outcome, $record.wallSeconds, $record.peakCpuPct, $record.peakMemoryMB, $record.exitCode, $record.killPerformed)) | Out-Null
        if ($null -ne $record.audit) {
            $lines.Add(('  audit ok={0} target={1} orphans={2} cpuRecovered={3} memRecovered={4}' -f $record.audit.ok, $record.audit.targetCheck, $record.audit.orphanCount, $record.audit.cpuRecovered, $record.audit.memoryRecovered)) | Out-Null
            foreach ($problem in @($record.audit.problems)) { $lines.Add(('  problem: {0}' -f $problem)) | Out-Null }
        }
    }
    foreach ($finding in $Summary.findings) { $lines.Add(('finding: {0}' -f $finding)) | Out-Null }
    [System.IO.File]::WriteAllText($textPath, (($lines -join "`r`n") + "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
    return [pscustomobject]@{ json = $jsonPath; text = $textPath }
}

# ---------------------------------------------------------------------------- main
if ($PerInstanceTimeoutSec -gt $HARD_CAP_PER_INSTANCE_SEC) {
    Write-Output ('ERROR: -PerInstanceTimeoutSec {0} exceeds the 6-minute hard cap ({1}s)' -f $PerInstanceTimeoutSec, $HARD_CAP_PER_INSTANCE_SEC)
    exit $EXIT_BAD_PARAMS
}
if ($RoundBudgetSec -gt $HARD_CAP_ROUND_SEC) {
    Write-Output ('ERROR: -RoundBudgetSec {0} exceeds the 25-minute hard cap ({1}s)' -f $RoundBudgetSec, $HARD_CAP_ROUND_SEC)
    exit $EXIT_BAD_PARAMS
}
if ($MaxInstances -lt 1) { Write-Output 'ERROR: -MaxInstances must be >= 1'; exit $EXIT_BAD_PARAMS }

$orphanNameList = Get-ProcessNameList -Csv $OrphanProcessNames
$blockingNameList = Get-ProcessNameList -Csv $BlockingProcessNames
$mode = 'live'
$launchExe = $FilePath
$launchArgs = @($ArgumentList)
if ($DryRun) {
    $mode = 'dry-run'
    $dryChild = Get-DryRunChild
    $launchExe = $dryChild.exe
    $launchArgs = @($dryChild.args)
    Add-Finding ('dry-run: using {0}' -f $dryChild.note)
}
if ($launchExe.Length -eq 0) { Write-Output 'ERROR: -FilePath is required unless -DryRun is used'; exit $EXIT_BAD_PARAMS }

$roundStartedAt = Get-Date
$baselineSnapshot = Get-SystemSnapshot
$baselineSnapshotForAudit = $baselineSnapshot
# Snapshot which orphan-name-matching processes already exist BEFORE we launch. A round may be
# allowed to run alongside a pre-existing JVM (LAN round + its dedicated server); those must not be
# reported as our orphans afterwards.
$baselineOrphanPids = @(Get-BlockingProcesses -Names $orphanNameList -TitlePattern $BlockingWindowTitlePattern | ForEach-Object { [int]$_.pid })

# 3) refuse to start when a client process is already there -- refuse only, never kill
$blocking = Get-BlockingProcesses -Names $blockingNameList -TitlePattern $BlockingWindowTitlePattern
if (@($blocking).Count -gt 0 -and -not ($DryRun -and $DryRunScenario -eq 'refuse')) {
    foreach ($blocked in $blocking) {
        Write-Output ('REFUSING TO START: pid={0} name={1} title={2} ({3})' -f $blocked.pid, $blocked.name, $blocked.title, $blocked.reason)
    }
    Write-Output 'REFUSING TO START: an existing client process is present; this harness never kills other processes.'
    exit $EXIT_REFUSED
}
if ($DryRun -and $DryRunScenario -eq 'refuse') {
    $blocking = Get-BlockingProcesses -Names $blockingNameList -TitlePattern $BlockingWindowTitlePattern
    foreach ($blocked in $blocking) { Write-Output ('REFUSING TO START: pid={0} name={1} title={2} ({3})' -f $blocked.pid, $blocked.name, $blocked.title, $blocked.reason) }
    Write-Output 'REFUSING TO START: pre-existing process matched -BlockingProcessNames; nothing was launched and nothing was killed.'
    exit $EXIT_REFUSED
}

if ($OptionsFile.Length -gt 0) {
    try { Assert-OptionsWindowed -Path $OptionsFile } catch { Write-Output ('ERROR: {0}' -f $_.Exception.Message); exit $EXIT_BAD_PARAMS }
}

$finalExit = $EXIT_OK
$verdict = 'PASS'
$evidenceDirectory = Get-EvidenceDirectory -Requested $EvidenceDir
$runStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
for ($index = 1; $index -le $MaxInstances; $index++) {
    $roundElapsedSec = ((Get-Date) - $roundStartedAt).TotalSeconds
    # 2) whole-round wall clock budget: refuse to start an instance that cannot fit
    if (($roundElapsedSec + $PerInstanceTimeoutSec) -gt $RoundBudgetSec) {
        Add-Finding ('round budget would be exceeded: elapsed {0}s + instance cap {1}s > {2}s' -f [int]$roundElapsedSec, $PerInstanceTimeoutSec, $RoundBudgetSec)
        $verdict = 'FAIL:round-budget'
        $finalExit = $EXIT_ROUND_BUDGET
        break
    }
    Write-Output ('--- instance {0}/{1}: launching {2} (cap {3}s, stall {4}s) ---' -f $index, $MaxInstances, $launchExe, $PerInstanceTimeoutSec, $StallSeconds)
    $record = Invoke-BoundedInstance -Index $index -Exe $launchExe -ExeArgs $launchArgs -WorkDir $WorkingDirectory `
        -TimeoutSec $PerInstanceTimeoutSec -StallLimitSec $StallSeconds -DryScenario $DryRunScenario `
        -EvidenceDirectory $evidenceDirectory -RunStamp $runStamp -IsDry:$DryRun
    if ($record.outcome -eq 'launch-failed') {
        $instanceRecords.Add($record) | Out-Null
        $verdict = 'FAIL:launch'
        $finalExit = $EXIT_LAUNCH_FAILED
        break
    }
    # 5) mandatory post-run audit
    Start-Sleep -Seconds $PollSeconds
    $afterSnapshot = Get-SystemSnapshot
    $audit = Test-PostRunAudit -Record $record -OrphanNames $orphanNameList -TitlePattern $BlockingWindowTitlePattern `
        -BaselineSnapshot $baselineSnapshotForAudit -AfterSnapshot $afterSnapshot -BaselineProcessIds $baselineOrphanPids
    $record | Add-Member -NotePropertyName audit -NotePropertyValue $audit -Force
    $instanceRecords.Add($record) | Out-Null
    Write-Output ('instance #{0} pid={1} outcome={2} wall={3}s peakCpu={4}% peakMem={5}MB kill={6}' -f $record.index, $record.pid, $record.outcome, $record.wallSeconds, $record.peakCpuPct, $record.peakMemoryMB, $record.killPerformed)
    Write-Output ('  audit: target[{0}] orphans={1} preexistingExcluded={2} cpuRecovered={3} memRecovered={4} ok={5}' -f $audit.targetCheck, $audit.orphanCount, $audit.preexistingExcluded, $audit.cpuRecovered, $audit.memoryRecovered, $audit.ok)
    foreach ($problem in @($audit.problems)) { Write-Output ('  problem: {0}' -f $problem) }
    if ($record.outcome -eq 'timeout-killed') { $verdict = 'FAIL:instance-timeout'; $finalExit = $EXIT_INSTANCE_TIMEOUT }
    elseif ($record.outcome -eq 'stall-killed') { $verdict = 'FAIL:instance-stall'; $finalExit = $EXIT_INSTANCE_STALL }
    if (-not $audit.ok) {
        # clean up only OUR pid (never a general process sweep), then report the failure
        if ($record.pid -gt 0 -and (Test-ProcessAlive -ProcessId $record.pid)) {
            try { Stop-Process -Id $record.pid -Force -ErrorAction Stop; Write-Output ('  cleanup: killed our own leftover pid {0}' -f $record.pid) } catch { Write-Output ('  cleanup FAILED for pid {0}: {1}' -f $record.pid, $_.Exception.Message) }
        }
        if ($verdict -eq 'PASS') { $verdict = 'FAIL:post-run-audit' }
        $finalExit = $EXIT_AUDIT_FAILED
    }
    # 7) fail fast
    if ($finalExit -ne $EXIT_OK) { break }
}

$roundWallSeconds = [math]::Round(((Get-Date) - $roundStartedAt).TotalSeconds, 2)
if ($roundWallSeconds -gt $RoundBudgetSec) {
    Add-Finding ('round wall clock {0}s exceeded the budget {1}s' -f $roundWallSeconds, $RoundBudgetSec)
    $verdict = 'FAIL:round-budget'
    $finalExit = $EXIT_ROUND_BUDGET
}
$summary = [pscustomobject]@{
    mode = $mode
    dryRunScenario = $(if ($DryRun) { $DryRunScenario } else { '' })
    verdict = $verdict
    exitCode = $finalExit
    caps = [pscustomobject]@{ perInstanceMaxSec = $HARD_CAP_PER_INSTANCE_SEC; roundMaxSec = $HARD_CAP_ROUND_SEC; perInstanceUsedSec = $PerInstanceTimeoutSec; roundBudgetUsedSec = $RoundBudgetSec; stallSeconds = $StallSeconds }
    windowed = [bool]$Windowed
    window = ('{0}x{1}' -f $WINDOW_WIDTH, $WINDOW_HEIGHT)
    command = $launchExe
    commandArgs = $(if ($instanceRecords.Count -gt 0) { $instanceRecords[0].effectiveArgs } else { $launchArgs })
    startedAt = $roundStartedAt.ToString('s')
    roundWallSeconds = $roundWallSeconds
    baseline = $baselineSnapshot
    instances = $instanceRecords.ToArray()
    findings = $scriptFindings.ToArray()
}
$evidencePaths = Write-Evidence -Summary $summary -Directory $evidenceDirectory
Write-Output ('evidence: {0}' -f $evidencePaths.json)
Write-Output ('evidence: {0}' -f $evidencePaths.text)
Write-Output ('VERDICT={0} EXIT={1} roundWall={2}s' -f $verdict, $finalExit, $roundWallSeconds)
if ($Json) { Write-Output ($summary | ConvertTo-Json -Depth 8) }
exit $finalExit
