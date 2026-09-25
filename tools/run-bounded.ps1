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
#     OPT-IN EXTRA: -KillProcessTree also kills the instance's DESCENDANTS (nearest-first walk over
#     Win32_Process.ParentProcessId). This exists because a WRAPPER launch defeats a single-pid kill:
#     measured 2026-09-25, the watchdog killed `renderdoccmd.exe` while the game JVM -- its
#     GRANDCHILD -- survived as an orphan (docs/evidence/2026-09-25-renderdoc/README.md 3.2). It is
#     opt-in, NOT default, because on a `gradlew.bat` launch the descendant chain runs through a
#     Gradle DAEMON that is shared with other builds on this machine; killing it would break
#     somebody else's work. -KillProcessTreeExcludePattern (default 'GradleDaemon') is a second belt
#     for the same reason. Only descendants of OUR pid are ever touched -- never a process sweep.
#   * before starting: a process matching -BlockingProcessNames AND carrying THIS round's instance
#     signature (normally the --gameDir value; see -InstanceSignature) -> REFUSE (never kills other
#     people's processes; it only declines to start). An UNRELATED JVM -- a teammate's Gradle daemon, or
#     another slot's client in a different gameDir -- is reported as out-of-scope and does NOT block.
#     With no signature available the guard stays FAIL-CLOSED: any name/title match blocks (this is the
#     pre-2026-09-27 behaviour, kept for dry runs and for launches with nothing to attribute by).
#   * after every instance, mandatory post-run audit: this PID is gone, no orphan java/javaw remain
#     (a pre-launch baseline of matching PIDs is excluded: a round explicitly allowed to run next to
#     a pre-existing JVM, e.g. a LAN round with its own dedicated server, must not be blamed for it),
#     and system CPU / available memory have come back to the pre-round baseline
#   * any cap breach or failed audit => stop the whole round immediately with a non-zero exit code
#   * DISCOVERABILITY: when an audit fails with a surviving process, the run says so and records
#     audit.leftoverHint, pointing at -KillProcessTree (an opt-in nobody discovers is an opt-in
#     nobody uses)
#
# EXIT CODES
#   0 ok | 2 bad parameters (cap above the hard limit) | 3 refused (blocking process present; writes
#   evidence with blocking[]) | 4 instance hit the 6-min cap (killed) | 5 instance stalled (killed)
#   | 6 round budget exceeded | 7 post-run audit failed | 8 could not launch
#
# EVIDENCE (machine-readable; the run summary is the interface)
#   <EvidenceDir>/run-bounded-<yyyyMMdd-HHmmss>.json   full summary + per-instance metrics
#   <EvidenceDir>/run-bounded-<yyyyMMdd-HHmmss>.txt    same, human-readable
#   per instance: pid, wall seconds, peak process CPU%, peak working set MB, exit code, outcome,
#   kill reason, post-run audit readings (system CPU% and available MB before/after)
#   NOTE ON UNITS: peakCpuPct is the sum over cores (a busy 8-core process can read >100%), and
#   peakMemoryMB is Win32_Process.WorkingSetSize sampled every poll (not the .NET object's stale
#   WorkingSet64).
#   NOTE ON ROUND-BUDGET FIELD NAMES (corrected 2026-09-27): caps.roundBudgetCapSec is the configured
#   CAP and caps.roundBudgetElapsedSec is the seconds ACTUALLY used (= roundWallSeconds). The old key
#   caps.roundBudgetUsedSec was misnamed -- it always held the CAP, never the usage -- and is KEPT as
#   a deprecated alias with the same value, so that readers of pre-2026-09-27 evidence keep working.
#   New references must use roundBudgetCapSec / roundBudgetElapsedSec.
#   NOTE ON A REFUSAL (added 2026-09-27): a refusal (exit 3) NOW ALSO WRITES this evidence summary, with
#   verdict=REFUSED, exitCode=3 and a `blocking[]` array. Each entry carries pid / name / title / reason
#   AND a truncated COMMAND LINE, because "a java process exists" is not attributable on a shared
#   machine -- all three refusals recorded on 2026-09-25/26 were somebody else's Gradle daemon. An entry
#   never means the harness touched it: a refusal only declines to start, and says so explicitly.
#   This is an intentional observable change: previously a refusal produced no file at all.
#   EvidenceDir default: $env:MODTEST_BOUNDED_EVIDENCE_DIR, else <temp>/modtest-bounded-runs
#
# DRY RUN (proves the logic WITHOUT ever starting a JVM)
#   -DryRun -DryRunScenario ok|timeout|stall|refuse|orphan|tree
#     The harness substitutes a harmless non-JVM child (the current PowerShell host running a
#     sleep). It refuses to run at all if that child resolves to a java* executable.
#     ok      -> child exits by itself, audit passes, exit 0
#     timeout -> child is killed by the 6-min-equivalent deadline (use a small -PerInstanceTimeoutSec)
#     stall   -> child is killed by stall detection (flat CPU + no output for -StallSeconds)
#     refuse  -> -BlockingProcessNames picks up an existing process; nothing is launched, exit 3
#     orphan  -> FAULT INJECTION: the watchdog deliberately does not kill, so the post-run audit
#                must catch the still-live child (exit 7) and then clean up only that child
#     tree    -> the child spawns a GRANDCHILD and prints `GRANDCHILD_PID=<n>` on stdout, then sleeps
#                until the watchdog kills it. WITH -KillProcessTree the grandchild is dead afterwards;
#                WITHOUT it the grandchild survives -- which is exactly the old (wrapper-launch)
#                failure, and is what run-bounded.test.ps1 uses as the negative control.
#
# USAGE
#   pwsh tools/run-bounded.ps1 -FilePath ./gradlew.bat -ArgumentList ':forge:runClient' `
#        -WorkingDirectory ../modtest-mcp -Windowed -OptionsFile ./run/options.txt -VerifyWindow
#   pwsh tools/run-bounded.ps1 -DryRun -DryRunScenario timeout -PerInstanceTimeoutSec 6 -Json
#   pwsh tools/run-bounded.ps1 -FilePath <wrapper.exe> -ArgumentList ... -KillProcessTree
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
    # SCOPE FOR THE PRE-LAUNCH GUARD (added 2026-09-27). The guard used to refuse whenever ANY process
    # matched -BlockingProcessNames, so a teammate's Gradle daemon blocked real rounds (measured twice
    # on 2026-09-25/26) and two isolated instances could never coexist -- precisely the asymmetry the
    # POST-run audit had already fixed by scoping itself to our own launch signature (see
    # -LaunchSignature in Test-PostRunAudit). A matched process now blocks only when its command line
    # contains THIS signature (normally the --gameDir value); otherwise it is reported as out-of-scope
    # (unrelated JVM / another slot) and does NOT block. Empty means "no signature available" (e.g. a
    # dry run): the guard then stays FAIL-CLOSED and treats every match as blocking.
    [string]$InstanceSignature = '',
    # Human-readable name for this instance slot. Printed as slot=..., recorded in the evidence summary,
    # and -- only when given explicitly -- appended to the evidence FILENAME so parallel slots can share
    # one evidence directory. Defaults to the gameDir leaf when one can be derived, else 'default'.
    [string]$Slot = '',
    [string]$OrphanProcessNames = 'java,javaw',
    [switch]$Windowed,
    [string]$OptionsFile = '',
    [switch]$VerifyWindow,
    # Opt-in: after the instance ends, put the OS cursor back where it was before the round. In-world
    # Minecraft grabs the cursor (GLFW_CURSOR_DISABLED -> ClipCursor + recentre on Windows), so the
    # round otherwise leaves the pointer parked at the window centre. Guarded so a background round
    # never fights an operator who is using the mouse (see Restore-CursorPosition).
    [switch]$RestoreCursor,
    # Opt-in: when the watchdog has to kill the instance, also kill the instance's DESCENDANTS. Needed
    # when -FilePath is a WRAPPER (renderdoccmd.exe, *.bat shims) rather than the java executable
    # itself: a single-pid kill then leaves the real client alive as an orphan (measured 2026-09-25,
    # docs/evidence/2026-09-25-renderdoc/README.md 3.2). Default OFF on purpose -- on a gradlew.bat
    # launch the descendant chain passes through a Gradle DAEMON shared with other builds, and killing
    # that would break somebody else's work. Only descendants of our own pid are ever considered.
    [switch]$KillProcessTree,
    # ...and never kill a descendant whose command line matches this regex. Second belt for the
    # Gradle-daemon hazard above; applied only when -KillProcessTree is set.
    [string]$KillProcessTreeExcludePattern = 'GradleDaemon',
    [double]$CpuRecoveryTolerancePct = 20.0,
    [int]$MemoryRecoveryToleranceMB = 512,
    # If another process grew by >= this much private memory, or burned >= this many CPU seconds,
    # during our instance, a CPU/memory non-recovery is attributed to it (advisory, not a failure).
    [int]$ForeignMemoryGrowthToleranceMB = 256,
    [int]$ForeignCpuSecondsTolerance = 5,
    [int]$PollSeconds = 1,
    [switch]$Json,
    [switch]$DryRun,
    [ValidateSet('ok', 'timeout', 'stall', 'refuse', 'orphan', 'tree')]
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

function Get-ProcessCommandLineMap {
    # ONE Win32_Process snapshot -> pid => { cmdline, cmdlineTruncated, cmdlineFull }. Built once and
    # looked up by pid, instead of a Get-CimInstance call per match.
    #   cmdline      : truncated to $TruncateTo, for DISPLAY and for the evidence JSON
    #   cmdlineFull  : the whole command line, used ONLY for the instance-signature scope decision and
    #                  never written out -- so the display truncation can never hide the signature
    #                  (a missed match would be a safety regression, not a cosmetic one).
    param([int]$TruncateTo = 240)
    $map = @{}
    foreach ($row in @(Get-CimInstance -ClassName Win32_Process -ErrorAction SilentlyContinue)) {
        $full = ''
        if ($null -ne $row.CommandLine) { $full = [string]$row.CommandLine }
        $shown = $full
        $truncated = $false
        if ($TruncateTo -gt 0 -and $shown.Length -gt $TruncateTo) {
            $shown = $shown.Substring(0, $TruncateTo)
            $truncated = $true
        }
        $map[[int]$row.ProcessId] = [pscustomobject]@{ cmdline = $shown; cmdlineTruncated = $truncated; cmdlineFull = $full }
    }
    return $map
}

function ConvertTo-CommandLineTokens([string]$CommandLine) {
    # Minimal Windows argv splitter: whitespace separated, "double quoted" runs kept together, quotes
    # stripped. It does not have to be a perfect CRT parser -- it only has to be good enough to compare
    # WHOLE arguments (see Test-InstanceScopeMatch). What matters is that it is never used to justify a
    # substring match, because a prefix is exactly what made a sibling slot look like our own instance.
    $tokens = New-Object System.Collections.Generic.List[string]
    if ([string]::IsNullOrEmpty($CommandLine)) { return $tokens }
    $current = New-Object System.Text.StringBuilder
    $inQuotes = $false
    $hasContent = $false
    for ($i = 0; $i -lt $CommandLine.Length; $i++) {
        $ch = $CommandLine[$i]
        if ($ch -eq '"') { $inQuotes = -not $inQuotes; $hasContent = $true; continue }
        if ((-not $inQuotes) -and (($ch -eq ' ') -or ($ch -eq "`t"))) {
            if ($hasContent) { [void]$tokens.Add($current.ToString()); [void]$current.Clear(); $hasContent = $false }
            continue
        }
        [void]$current.Append($ch)
        $hasContent = $true
    }
    if ($hasContent) { [void]$tokens.Add($current.ToString()) }
    return $tokens
}

function Get-CommandLineArgumentValue([System.Collections.Generic.List[string]]$Tokens, [string]$Name) {
    # Supports both `--gameDir <value>` and `--gameDir=<value>`.
    for ($i = 0; $i -lt $Tokens.Count; $i++) {
        $token = $Tokens[$i]
        if ($token -ieq $Name) {
            if ($i + 1 -lt $Tokens.Count) { return [string]$Tokens[$i + 1] }
            return ''
        }
        if ($token -like ($Name + '=*')) { return $token.Substring($Name.Length + 1) }
    }
    return ''
}

function Get-NormalizedPath([string]$Value) {
    if ([string]::IsNullOrEmpty($Value)) { return '' }
    return ($Value.Trim().TrimEnd('\', '/') -replace '/', '\')
}

function Test-InstanceScopeMatch {
    # THE single scope decision, shared by the pre-launch guard and the post-run orphan audit, so the two
    # can never disagree about who is "ours".
    #
    # 2026-09-27 (task-39): this used to be a case-SENSITIVE SUBSTRING test. With sibling slots whose
    # directories are prefixes of one another -- ...\versions\1.20.1-Forge (A) and
    # ...\versions\1.20.1-Forge-B (B) -- that made B look like A: the guard printed inScope=2, and the
    # audit attributed B's LIVE client to A, producing a FALSE FAIL:post-run-audit (exit 7) for a round
    # whose own instance had exited cleanly. Renaming the slot does not help, because B's path still
    # starts with A's; the match has to be EXACT:
    #   1. the authoritative rule: compare our signature with the candidate's PARSED --gameDir value
    #      (exact, path-normalised, case-insensitive as the filesystem is);
    #   2. a bounded fallback for non-path signatures (e.g. -InstanceSignature <exe name>): equality with
    #      a whole argument, or with an argument's file name. Never a prefix, never a substring.
    param([string]$CommandLine, [string]$Signature)
    if ([string]::IsNullOrEmpty($Signature)) { return [pscustomobject]@{ inScope = $false; reason = 'no instance signature' } }
    $tokens = ConvertTo-CommandLineTokens -CommandLine $CommandLine
    if ($tokens.Count -eq 0) { return [pscustomobject]@{ inScope = $false; reason = 'no readable command line to compare with' } }
    $wantPath = Get-NormalizedPath $Signature
    $candidateGameDir = Get-CommandLineArgumentValue -Tokens $tokens -Name '--gameDir'
    if ($candidateGameDir.Length -gt 0) {
        $candidatePath = Get-NormalizedPath $candidateGameDir
        if ($candidatePath -ieq $wantPath) {
            return [pscustomobject]@{ inScope = $true; reason = 'same --gameDir as this round (exact match)' }
        }
        return [pscustomobject]@{ inScope = $false; reason = ('different --gameDir (candidate=' + $candidatePath + ')') }
    }
    foreach ($token in $tokens) {
        if ((Get-NormalizedPath $token) -ieq $wantPath) {
            return [pscustomobject]@{ inScope = $true; reason = 'a whole argument equals the instance signature (exact match)' }
        }
        $leaf = ''
        try { $leaf = [System.IO.Path]::GetFileName($token.TrimEnd('\', '/')) } catch { $leaf = '' }
        if ($leaf.Length -gt 0 -and $leaf -ieq $Signature.Trim()) {
            return [pscustomobject]@{ inScope = $true; reason = 'an argument file name equals the instance signature (exact match)' }
        }
    }
    return [pscustomobject]@{ inScope = $false; reason = 'no --gameDir and no whole argument equal to the instance signature' }
}

function Get-BlockingProcesses {
    # -CommandLineMap is optional (an empty map is built on demand), so every existing caller keeps working.
    # -ScopeSignature (added 2026-09-27, exact since task-39) tags each match with scope = 'in-scope'
    # when the process is OUR instance by the exact rule in Test-InstanceScopeMatch (its parsed --gameDir
    # equals our signature), else 'out-of-scope'. Without a signature every match is 'unscoped'
    # (fail-closed callers treat those as blocking). The FULL command line is used for the decision and
    # never leaves this function, so the 240-char display truncation can never cause a missed match
    # (that would be a safety regression, not a cosmetic one).
    param(
        [System.Collections.Generic.List[string]]$Names,
        [string]$TitlePattern,
        [hashtable]$CommandLineMap = @{},
        [string]$ScopeSignature = ''
    )
    if ($CommandLineMap.Count -eq 0) { $CommandLineMap = Get-ProcessCommandLineMap }
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
            # The COMMAND LINE is what lets an operator tell a teammate's build JVM from a real game
            # client. Without it a refusal is unattributable: on 2026-09-25/26 three rounds were blocked
            # and the witness had to look the culprit up by hand to find out it was a Gradle daemon.
            $cmdline = ''
            $cmdlineTruncated = $false
            $ownerPid = [int]$processItem.Id
            if ($CommandLineMap.ContainsKey($ownerPid)) {
                $cmdline = [string]$CommandLineMap[$ownerPid].cmdline
                $cmdlineTruncated = [bool]$CommandLineMap[$ownerPid].cmdlineTruncated
            }
            $scope = 'unscoped'
            $scopeReason = 'no instance signature available: FAIL-CLOSED, every match is treated as blocking'
            $fullCommandLine = ''
            if ($CommandLineMap.ContainsKey($ownerPid)) {
                $fullCommandLine = [string]$CommandLineMap[$ownerPid].cmdlineFull
            }
            if ($ScopeSignature.Length -gt 0) {
                # ONE exact-match rule, shared with the post-run audit (Test-InstanceScopeMatch). It used
                # to be a substring test, which made a sibling slot whose directory is a PREFIX of ours
                # look like our own instance (task-39: inScope=2 for two slots A and A-B).
                $scopeVerdict = Test-InstanceScopeMatch -CommandLine $fullCommandLine -Signature $ScopeSignature
                if ($scopeVerdict.inScope) {
                    $scope = 'in-scope'
                } else {
                    $scope = 'out-of-scope'
                }
                $scopeReason = $scopeVerdict.reason
            }
            $found.Add([pscustomobject]@{
                pid = $processItem.Id; name = $processItem.ProcessName
                title = $processItem.MainWindowTitle; reason = $(if ($isTitleMatch) { 'window-title' } else { 'process-name' })
                cmdline = $cmdline; cmdlineTruncated = $cmdlineTruncated
                scope = $scope; scopeReason = $scopeReason
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

function Get-DescendantProcesses {
    # Every process whose ancestor chain reaches $RootProcessId, NEAREST generation first.
    # Built from ONE Win32_Process snapshot via ParentProcessId; read-only, touches nothing.
    # NOTE: ParentProcessId is the creator recorded at process creation and is NOT rewritten when the
    # creator exits, which is why this still works for attributing a survivor to the wrapper we killed.
    param([int]$RootProcessId)
    $ordered = New-Object System.Collections.Generic.List[object]
    $rows = @(Get-CimInstance -ClassName Win32_Process -ErrorAction SilentlyContinue)
    if ($rows.Count -eq 0) { return $ordered }
    $byParent = @{}
    foreach ($row in $rows) {
        $parentId = [int]$row.ParentProcessId
        if (-not $byParent.ContainsKey($parentId)) { $byParent[$parentId] = New-Object System.Collections.Generic.List[object] }
        $byParent[$parentId].Add($row) | Out-Null
    }
    $seen = New-Object System.Collections.Generic.HashSet[int]
    $queue = New-Object System.Collections.Generic.Queue[int]
    $queue.Enqueue($RootProcessId)
    while ($queue.Count -gt 0) {
        $currentId = $queue.Dequeue()
        if (-not $byParent.ContainsKey($currentId)) { continue }
        foreach ($child in $byParent[$currentId]) {
            $childId = [int]$child.ProcessId
            if ($seen.Add($childId)) {
                $ordered.Add($child) | Out-Null
                $queue.Enqueue($childId)
            }
        }
    }
    return $ordered
}

function Stop-ProcessTree {
    # Kill the DESCENDANTS of $RootProcessId (the root itself is left to the caller). Deepest-first, so
    # a parent's own shutdown cannot reparent a child into an orphan before we reach it.
    # SAFETY: only descendants of our pid are ever considered (never a machine-wide sweep), the current
    # harness process is explicitly excluded, and any descendant whose command line matches
    # $ExcludePattern is skipped and REPORTED rather than killed.
    param(
        [int]$RootProcessId,
        [string]$ExcludePattern = '',
        [int[]]$ExcludeProcessIds = @()
    )
    $killed = New-Object System.Collections.Generic.List[string]
    $skipped = New-Object System.Collections.Generic.List[string]
    $descendants = @(Get-DescendantProcesses -RootProcessId $RootProcessId)
    [array]::Reverse($descendants)
    foreach ($proc in $descendants) {
        $procId = [int]$proc.ProcessId
        $procName = [string]$proc.Name
        $cmdLine = ''
        if ($null -ne $proc.CommandLine) { $cmdLine = [string]$proc.CommandLine }
        if ($ExcludeProcessIds -contains $procId) {
            $skipped.Add(('pid={0} name={1} (excluded: protected pid)' -f $procId, $procName)) | Out-Null
            continue
        }
        if ($ExcludePattern.Length -gt 0 -and $cmdLine -match $ExcludePattern) {
            $skipped.Add(('pid={0} name={1} (excluded: command line matches {2})' -f $procId, $procName, $ExcludePattern)) | Out-Null
            continue
        }
        try {
            Stop-Process -Id $procId -Force -ErrorAction Stop
            $killed.Add(('pid={0} name={1}' -f $procId, $procName)) | Out-Null
        } catch {
            $skipped.Add(('pid={0} name={1} (kill failed: {2})' -f $procId, $procName, $_.Exception.Message)) | Out-Null
        }
    }
    return [pscustomobject]@{
        descendantCount = $descendants.Count
        killed = $killed.ToArray()
        skipped = $skipped.ToArray()
    }
}

function Get-WindowGeometry([int]$ProcessId) {
    # Optional window audit; returns the handle, physical window/client rect and DPI, or $null.
    # WINDOW SELECTION IS BY PID, NEVER BY TITLE: with parallel slots two instances both report
    # "Minecraft* ...", so a title match is ambiguous -- the handle is taken from THIS instance's own
    # pid. `title` is recorded for a human reader only and must not be used to pick a window.
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
            pid = $ProcessId
            handle = [long]$live.MainWindowHandle
            title = $live.MainWindowTitle
            windowPhysical = ('{0}x{1}' -f ($windowRect.Right - $windowRect.Left), ($windowRect.Bottom - $windowRect.Top))
            clientPhysical = ('{0}x{1}' -f ($clientRect.Right - $clientRect.Left), ($clientRect.Bottom - $clientRect.Top))
            dpi = $dpi
        }
    } catch { return $null }
}

function Get-CursorPosition {
    # Reads the OS cursor position. NOTE: this is NOT an input channel -- nothing here ever moves the
    # cursor before or during a round. It exists only so an opt-in (-RestoreCursor) post-run step can
    # undo a side effect the GAME itself causes: in-world Minecraft grabs the cursor
    # (GLFW_CURSOR_DISABLED), and on Windows GLFW implements that with ClipCursor() on the window's
    # client rect plus recentring, so after the round the cursor is left at the window centre.
    # Measured 2026-09-25 (docs/evidence/2026-09-25-mouse-grab): clip rect 853,453,1706,986 == the
    # 1280x800 game window's client area on a 2560x1440 screen, and it is released when the game exits.
    $signature = @'
[DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
[DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
[DllImport("user32.dll")] public static extern int GetSystemMetrics(int nIndex);
public struct POINT { public int X; public int Y; }
'@
    try { Add-Type -Namespace ModtestBounded -Name Cursor -MemberDefinition $signature -ErrorAction SilentlyContinue } catch { }
    try {
        $p = New-Object ModtestBounded.Cursor+POINT
        if ([ModtestBounded.Cursor]::GetCursorPos([ref]$p)) { return [pscustomobject]@{ x = $p.X; y = $p.Y } }
    } catch { }
    return $null
}

function Restore-CursorPosition([object]$Saved) {
    # Opt-in. Guard: only restore when the cursor still sits near the screen centre, i.e. where the
    # game left it. If the operator has been using the mouse since the game exited, we do NOT fight
    # them -- a background round must never yank the pointer out from under the user.
    if ($null -eq $Saved) { return 'skipped (no saved position)' }
    try {
        $now = Get-CursorPosition
        if ($null -eq $now) { return 'skipped (cannot read cursor)' }
        $cx = [int]([ModtestBounded.Cursor]::GetSystemMetrics(0) / 2)
        $cy = [int]([ModtestBounded.Cursor]::GetSystemMetrics(1) / 2)
        $dx = [math]::Abs($now.x - $cx)
        $dy = [math]::Abs($now.y - $cy)
        if ($dx -gt 100 -or $dy -gt 100) {
            return ('skipped (cursor at {0},{1}, {2}px from screen centre -- looks user-moved)' -f $now.x, $now.y, [int][math]::Max($dx, $dy))
        }
        [void][ModtestBounded.Cursor]::SetCursorPos($Saved.x, $Saved.y)
        return ('restored {0},{1} -> {2},{3}' -f $now.x, $now.y, $Saved.x, $Saved.y)
    } catch { return ('restore failed: ' + $_.Exception.Message) }
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
    if ($DryRunScenario -eq 'tree') {
        # The child spawns a GRANDCHILD (a detached sleeper) and prints its pid on stdout as
        # GRANDCHILD_PID=<n>, then sleeps long enough for the watchdog to kill it. With
        # -KillProcessTree the grandchild dies with the watchdog kill; without the switch it survives
        # -- that IS the old wrapper-launch failure (renderdoc README 3.2), and it is what
        # run-bounded.test.ps1 asserts in both directions.
        $grandchildSpec = "-NoProfile','-NonInteractive','-Command','Start-Sleep -Seconds 90"
        $childCommand = "`$grandchild = Start-Process -FilePath '{0}' -ArgumentList @('{1}') -PassThru; Write-Output ('GRANDCHILD_PID=' + `$grandchild.Id); Start-Sleep -Seconds 120" -f $hostExe, $grandchildSpec
        $treeArgs = @('-NoProfile', '-NonInteractive', '-Command', $childCommand)
        return [pscustomobject]@{ exe = $hostExe; args = $treeArgs; note = 'dry-run tree child (PowerShell sleeping + one grandchild sleeper, NOT a JVM)' }
    }
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
    $cursorBefore = if ($RestoreCursor) { Get-CursorPosition } else { $null }
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
    $treeKill = $null
    if ($outcome -eq 'timeout-killed' -or $outcome -eq 'stall-killed') {
        # OPT-IN (-KillProcessTree): take the descendants FIRST, while our own pid is still alive, so the
        # ancestry is unambiguous; then the root itself. A wrapper launch (renderdoccmd.exe, *.bat shims)
        # otherwise leaves the real client alive -- see the HARD CAPS note in the header.
        if ($KillProcessTree) {
            $treeKill = Stop-ProcessTree -RootProcessId $childPid -ExcludePattern $KillProcessTreeExcludePattern -ExcludeProcessIds @($PID)
            foreach ($killedItem in @($treeKill.killed)) { Add-Finding ('kill tree: killed {0}' -f $killedItem) }
            foreach ($skippedItem in @($treeKill.skipped)) { Add-Finding ('kill tree: skipped {0}' -f $skippedItem) }
        }
        try {
            Stop-Process -Id $childPid -Force -ErrorAction Stop
            $killPerformed = $true
        } catch {
            Add-Finding ('kill failed for pid {0}: {1}' -f $childPid, $_.Exception.Message)
        }
    }
    $geometry = $null
    if ($VerifyWindow) { $geometry = Get-WindowGeometry -ProcessId $childPid }
    $cursorRestore = if ($RestoreCursor) { Restore-CursorPosition -Saved $cursorBefore } else { $null }
    return [pscustomobject]@{
        index = $Index; pid = $childPid; outcome = $outcome; killReason = $killReason
        killPerformed = $killPerformed; wallSeconds = $wallSeconds
        peakCpuPct = [math]::Round($peakCpuPct, 1); peakMemoryMB = [math]::Round($peakMemoryMB, 1)
        exitCode = $exitCode; window = $geometry; cursorRestore = $cursorRestore; startedAt = $startedAt.ToString('s')
        treeKill = $treeKill
        stdoutFile = $outFile; stderrFile = $errFile
        effectiveArgs = $effectiveArgs
    }
}

# Per-process activity snapshot: private bytes (MB) and CPU seconds keyed by PID. Used to decide
# whether a CPU/memory non-recovery is explained by somebody else's work (a teammate's build or the
# user's own game) instead of a real leak of ours. Not a security boundary - only an attribution aid.
function Get-ProcessActivitySnapshot {
    $map = @{}
    foreach ($p in (Get-Process -ErrorAction SilentlyContinue)) {
        $priv = -1.0
        $cpu = -1.0
        try { $priv = [double]$p.PrivateMemorySize64 / 1MB } catch { }
        try { $cpu = [double]$p.TotalProcessorTime.TotalSeconds } catch { }
        $map[[int]$p.Id] = [pscustomobject]@{ name = $p.ProcessName; privMB = $priv; cpuSec = $cpu }
    }
    return $map
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
        [int[]]$BaselineProcessIds = @(),
        # Our own instance signature (normally the parsed --gameDir value). On a shared machine another
        # teammate's build JVM, or ANOTHER SLOT whose directory is a prefix of ours, can appear *during*
        # our round and survive it; without attribution it would be reported as our orphan and the audit
        # would be a false red. Attribution is EXACT -- see Test-InstanceScopeMatch for why a substring
        # (or prefix) test is wrong here. When empty (e.g. dry runs) no attribution filtering happens.
        [string]$LaunchSignature = '',
        # Per-process activity before/after the instance (see Get-ProcessActivitySnapshot). A CPU or
        # memory non-recovery is only an advisory note when somebody else's process clearly worked or
        # grew during our round - which is the normal case on a shared machine (teammate's build, or
        # the user's own game). orphans stays a hard gate regardless.
        [hashtable]$ProcBaseline = @{},
        [hashtable]$ProcAfter = @{}
    )
    $problems = New-Object System.Collections.Generic.List[string]
    $targetAlive = $false
    if ($Record.pid -gt 0) { $targetAlive = Test-ProcessAlive -ProcessId $Record.pid }
    if ($targetAlive) { $problems.Add(('target pid {0} is still alive after the instance' -f $Record.pid)) | Out-Null }
    $matching = Get-BlockingProcesses -Names $OrphanNames -TitlePattern $TitlePattern
    $newSinceLaunch = @($matching | Where-Object { $BaselineProcessIds -notcontains [int]$_.pid })
    $preexisting = @($matching | Where-Object { $BaselineProcessIds -contains [int]$_.pid })
    $unattributed = @()
    $ours = @()
    if ($LaunchSignature.Length -gt 0) {
        $oursList = New-Object System.Collections.Generic.List[object]
        foreach ($candidate in $newSinceLaunch) {
            $cmdLine = ''
            $ci = Get-CimInstance Win32_Process -Filter ('ProcessId={0}' -f [int]$candidate.pid) -ErrorAction SilentlyContinue
            if ($null -ne $ci -and $null -ne $ci.CommandLine) { $cmdLine = [string]$ci.CommandLine }
            # SAME exact rule as the pre-launch guard (Test-InstanceScopeMatch). With the old substring
            # test, a sibling slot whose directory is a prefix of ours was attributed to us, and its live
            # client was reported as OUR orphan -- a false FAIL:post-run-audit (task-39).
            $auditVerdict = Test-InstanceScopeMatch -CommandLine $cmdLine -Signature $LaunchSignature
            if ($auditVerdict.inScope) { $oursList.Add($candidate) | Out-Null } else { $unattributed += $candidate }
        }
        # NOTE: @(<List[object]>) throws System.ArgumentException 'Argument types do not match' in
        # PowerShell; always go through .ToArray(). (The fuller PowerShell 5.1 notes this used to point
        # at -- docs/agent-harness -- live in the WORKSPACE repository, not in this one; the pitfall
        # itself is stated right here so this file needs no external document.)
        $ours = $oursList.ToArray()
    } else {
        $ours = @($newSinceLaunch)
    }
    $orphans = @($ours)
    if (@($orphans).Count -gt 0) {
        foreach ($orphan in $orphans) { $problems.Add(('orphan process: pid={0} name={1} title={2}' -f $orphan.pid, $orphan.name, $orphan.title)) | Out-Null }
    }
    $cpuRecovered = $true
    $memoryRecovered = $true
    # Lead ruling: on a shared machine a teammate's concurrent process can hold CPU/memory down. When
    # such a foreign process was actually observed (unattributedExcluded > 0), non-recovery is only an
    # advisory note and must not fail the round on its own; with no foreign process it stays a hard
    # gate. The orphan gate itself is NEVER relaxed.
    $advisories = New-Object System.Collections.Generic.List[string]
    # How much other processes grew / burned during our instance (our own pid is already gone).
    $foreignMemGrowthMB = 0.0
    $foreignCpuSec = 0.0
    if ($null -ne $ProcBaseline -and $null -ne $ProcAfter) {
        foreach ($key in @($ProcAfter.Keys)) {
            if ([int]$key -eq [int]$Record.pid) { continue }
            $nowP = $ProcAfter[$key]
            if ($null -eq $nowP) { continue }
            if ($ProcBaseline.ContainsKey($key)) {
                $wasP = $ProcBaseline[$key]
                if ($wasP.privMB -ge 0 -and $nowP.privMB -ge 0) {
                    $growth = $nowP.privMB - $wasP.privMB
                    if ($growth -gt $foreignMemGrowthMB) { $foreignMemGrowthMB = $growth }
                }
                if ($wasP.cpuSec -ge 0 -and $nowP.cpuSec -ge 0) {
                    $burn = $nowP.cpuSec - $wasP.cpuSec
                    if ($burn -gt $foreignCpuSec) { $foreignCpuSec = $burn }
                }
            } elseif ($nowP.privMB -gt $foreignMemGrowthMB) {
                # a process that appeared during the round: count its whole footprint as growth
                $foreignMemGrowthMB = $nowP.privMB
            }
        }
    }
    $foreignPresent = (@($unattributed).Count -gt 0) -or
        ($foreignMemGrowthMB -ge $ForeignMemoryGrowthToleranceMB) -or
        ($foreignCpuSec -ge $ForeignCpuSecondsTolerance)
    if ($null -ne $BaselineSnapshot -and $null -ne $AfterSnapshot) {
        if ($BaselineSnapshot.cpuPct -ge 0 -and $AfterSnapshot.cpuPct -ge 0) {
            if ($AfterSnapshot.cpuPct -gt ($BaselineSnapshot.cpuPct + $CpuRecoveryTolerancePct)) {
                if ($foreignPresent) {
                    $advisories.Add(('advisory: system CPU did not recover: baseline {0}% -> after {1}% (tolerance {2}%) -- not judged red: foreign activity observed (memGrowth={3} MB, cpu={4} s, unattributed={5})' -f $BaselineSnapshot.cpuPct, $AfterSnapshot.cpuPct, $CpuRecoveryTolerancePct, [math]::Round($foreignMemGrowthMB,1), [math]::Round($foreignCpuSec,1), @($unattributed).Count)) | Out-Null
                } else {
                    $cpuRecovered = $false
                    $problems.Add(('system CPU did not recover: baseline {0}% -> after {1}% (tolerance {2}%)' -f $BaselineSnapshot.cpuPct, $AfterSnapshot.cpuPct, $CpuRecoveryTolerancePct)) | Out-Null
                }
            }
        }
        if ($BaselineSnapshot.availableMB -ge 0 -and $AfterSnapshot.availableMB -ge 0) {
            if ($AfterSnapshot.availableMB -lt ($BaselineSnapshot.availableMB - $MemoryRecoveryToleranceMB)) {
                if ($foreignPresent) {
                    $advisories.Add(('advisory: available memory did not recover: baseline {0} MB -> after {1} MB (tolerance {2} MB) -- not judged red: foreign activity observed (memGrowth={3} MB, cpu={4} s, unattributed={5})' -f $BaselineSnapshot.availableMB, $AfterSnapshot.availableMB, $MemoryRecoveryToleranceMB, [math]::Round($foreignMemGrowthMB,1), [math]::Round($foreignCpuSec,1), @($unattributed).Count)) | Out-Null
                } else {
                    $memoryRecovered = $false
                    $problems.Add(('available memory did not recover: baseline {0} MB -> after {1} MB (tolerance {2} MB)' -f $BaselineSnapshot.availableMB, $AfterSnapshot.availableMB, $MemoryRecoveryToleranceMB)) | Out-Null
                }
            }
        }
    }
    return [pscustomobject]@{
        ok = ($problems.Count -eq 0); problems = $problems.ToArray(); advisories = $advisories.ToArray()
        targetGone = (-not $targetAlive); orphanCount = @($orphans).Count
        preexistingExcluded = @($preexisting).Count
        unattributedExcluded = @($unattributed).Count
        foreignMemGrowthMB = [math]::Round($foreignMemGrowthMB, 1); foreignCpuSec = [math]::Round($foreignCpuSec, 1)
        foreignPresent = $foreignPresent
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
    $lines.Add(('roundBudget: cap={0}s elapsed={1}s (roundBudgetUsedSec=<deprecated alias of cap> is kept for old readers)' -f $Summary.caps.roundBudgetCapSec, $Summary.caps.roundBudgetElapsedSec)) | Out-Null
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

function Show-BlockingReport {
    # Human-readable attribution for a refusal. Shared by BOTH refusal branches (the live guard and the
    # dry-run 'refuse' scenario) so they can never drift apart.
    # The per-process line keeps its pre-2026-09-27 shape ("REFUSING TO START: pid=.. name=.. title=..
    # (reason)") so existing greps still match; the command line is added as an indented continuation.
    param([object[]]$Blocking)
    $count = @($Blocking).Count
    if ($count -eq 0) {
        Write-Output 'REFUSING TO START: (dry-run scenario refuse) no process matched -BlockingProcessNames on this machine; the refusal path itself is what is being exercised.'
    }
    foreach ($blocked in @($Blocking)) {
        Write-Output ('REFUSING TO START: pid={0} name={1} title={2} ({3})' -f $blocked.pid, $blocked.name, $blocked.title, $blocked.reason)
        $shownCmdline = [string]$blocked.cmdline
        if ($shownCmdline.Length -eq 0) { $shownCmdline = '<command line unavailable>' }
        elseif ($blocked.cmdlineTruncated) { $shownCmdline = $shownCmdline + ' ...(truncated)' }
        Write-Output ('    cmdline: ' + $shownCmdline)
    }
    Write-Output ('REFUSING TO START: an existing client process is present ({0} pre-existing process(es), all of them from BEFORE this round); this harness never kills other processes.' -f $count)
    Write-Output '  the command line above is what tells a teammate''s build JVM apart from a real game client'
    Write-Output '  NOTHING was launched and NOTHING was killed; rerun once the listed process(es) are gone.'
}

function Write-RefusalEvidence {
    # A refusal used to produce NO evidence at all, which made it unattributable after the fact. It now
    # writes the same JSON/TXT pair as a normal run, with verdict=REFUSED and the blocking[] array.
    param(
        [object[]]$Blocking,
        [object[]]$BlockingOutOfScope = @(),
        [string]$EvidenceDirectory
    )
    if ($EvidenceDirectory.Length -eq 0) { return $null }
    $summary = [pscustomobject]@{
        mode = $mode
        dryRunScenario = $(if ($DryRun) { $DryRunScenario } else { '' })
        verdict = 'REFUSED'
        exitCode = $EXIT_REFUSED
        slot = $slotName
        instanceSignature = $effectiveSignature
        caps = [pscustomobject]@{
            perInstanceMaxSec = $HARD_CAP_PER_INSTANCE_SEC
            roundMaxSec = $HARD_CAP_ROUND_SEC
            perInstanceUsedSec = $PerInstanceTimeoutSec
            roundBudgetCapSec = $RoundBudgetSec
            roundBudgetElapsedSec = 0.0
            roundBudgetUsedSec = $RoundBudgetSec
            stallSeconds = $StallSeconds
        }
        windowed = [bool]$Windowed
        window = ('{0}x{1}' -f $WINDOW_WIDTH, $WINDOW_HEIGHT)
        command = $launchExe
        commandArgs = @($launchArgs)
        startedAt = $roundStartedAt.ToString('s')
        roundWallSeconds = [math]::Round(((Get-Date) - $roundStartedAt).TotalSeconds, 2)
        # Write-Evidence reads $Summary.stallSeconds (the TXT caps line); without this the line printed
        # "stall<=s" with an empty value -- see the same field in the normal summary below.
        stallSeconds = $StallSeconds
        baseline = $baselineSnapshot
        blocking = @($Blocking)
        blockingCount = @($Blocking).Count
        blockingNote = 'these processes carry THIS round''s instance signature and existed BEFORE it; nothing was launched and nothing was killed'
        # Not silently dropped: these matched -BlockingProcessNames but carry a different signature (a
        # teammate's Gradle daemon, or another slot's client), so they did NOT block this round.
        blockingOutOfScope = @($BlockingOutOfScope)
        blockingOutOfScopeCount = @($BlockingOutOfScope).Count
        instances = @()
        findings = $scriptFindings.ToArray()
    }
    $paths = Write-Evidence -Summary $summary -Directory $EvidenceDirectory
    if ($Json) { Write-Output ($summary | ConvertTo-Json -Depth 8) }
    return $paths
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

# Attribution signature for the post-run orphan audit: a substring that appears in OUR launch command
# line but not in an unrelated JVM. Prefer the --gameDir value, else a dotted main class, else the
# executable path. Left empty for dry runs, where the "our child" shape is different.
$launchSignature = ''
if (-not $DryRun) {
    for ($i = 0; $i -lt ($launchArgs.Count - 1); $i++) {
        if ($launchArgs[$i] -eq '--gameDir') { $launchSignature = [string]$launchArgs[$i + 1]; break }
    }
    if ($launchSignature.Length -eq 0) {
        foreach ($a in $launchArgs) { if (($a -notlike '-*') -and ($a -like '*.*')) { $launchSignature = [string]$a; break } }
    }
    if ($launchSignature.Length -eq 0) {
        for ($i = 0; $i -lt ($launchArgs.Count - 1); $i++) {
            if ($launchArgs[$i] -eq '--username') { $launchSignature = [string]$launchArgs[$i + 1]; break }
        }
    }
    # Deliberately NO exe-path fallback: the interpreter path is shared by every JVM on the machine,
    # so it would misattribute a teammate's process as ours. With no signature we fall back to
    # counting every new survivor (conservative: possible false red, never a silent miss).
}

# ---------------------------------------------------------------- instance scope + slot (2026-09-27)
# The PRE-LAUNCH guard and the POST-RUN audit both use this ONE value and ONE exact rule
# (Test-InstanceScopeMatch): a process belongs to this round only when its parsed --gameDir equals the
# signature exactly. Passing the same value to the audit matters -- before task-39 the guard honoured
# -InstanceSignature while the audit silently used the derived signature, so the two could disagree about
# who was "ours" (and with a substring rule they could also both be wrong in the same prefix case).
$effectiveSignature = $launchSignature
if ($InstanceSignature.Length -gt 0) { $effectiveSignature = $InstanceSignature }

# Slot: explicit -Slot wins; else the gameDir leaf name; else 'default'. Sanitised for filenames.
$slotName = $Slot
if ($slotName.Length -eq 0) {
    $derivedGameDir = ''
    for ($i = 0; $i -lt ($launchArgs.Count - 1); $i++) {
        if ($launchArgs[$i] -eq '--gameDir') { $derivedGameDir = [string]$launchArgs[$i + 1]; break }
    }
    if ($derivedGameDir.Length -gt 0) {
        $slotName = Split-Path -Leaf ($derivedGameDir.TrimEnd('\', '/'))
    }
}
if ($slotName.Length -eq 0) { $slotName = 'default' }
$slotName = ($slotName -replace '[^A-Za-z0-9._-]', '_')
$slotExplicit = ($Slot.Length -gt 0)
# Only an EXPLICIT -Slot changes the evidence filename, so the default naming stays byte-identical.
$runStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if ($slotExplicit) { $runStamp = $runStamp + '-' + $slotName }

$roundStartedAt = Get-Date
# Resolved HERE (not just before the loop) because a REFUSAL now writes evidence too -- it used to
# produce no file at all, which is exactly what made a refusal unattributable after the fact.
$evidenceDirectory = Get-EvidenceDirectory -Requested $EvidenceDir
$baselineSnapshot = Get-SystemSnapshot
$baselineSnapshotForAudit = $baselineSnapshot
# Snapshot which orphan-name-matching processes already exist BEFORE we launch. A round may be
# allowed to run alongside a pre-existing JVM (LAN round + its dedicated server); those must not be
# reported as our orphans afterwards.
$baselineOrphanPids = @(Get-BlockingProcesses -Names $orphanNameList -TitlePattern $BlockingWindowTitlePattern | ForEach-Object { [int]$_.pid })
$procBaselineForAudit = Get-ProcessActivitySnapshot

# 3) refuse to start when a client process is already there -- refuse only, never kill.
#    BOTH refusal branches (the live guard and the dry-run 'refuse' scenario) go through the same report
#    + evidence helper, so the two paths cannot drift apart. The guard is NOT relaxed in any way: no
#    command-line exclusion is applied, and exit 3 keeps meaning "declined to start, killed nothing".
$blockingCandidates = Get-BlockingProcesses -Names $blockingNameList -TitlePattern $BlockingWindowTitlePattern -ScopeSignature $effectiveSignature
$blockingInScope = @($blockingCandidates | Where-Object { $_.scope -ne 'out-of-scope' })
$blockingOutOfScope = @($blockingCandidates | Where-Object { $_.scope -eq 'out-of-scope' })
foreach ($unrelated in $blockingOutOfScope) {
    # Report them, never silently drop them: an unrelated JVM is precisely why this round may proceed,
    # and the reason is what tells a Gradle daemon apart from another slot's client.
    Write-Output ('NOT BLOCKING: pid={0} name={1} title={2} -- {3}' -f $unrelated.pid, $unrelated.name, $unrelated.title, $unrelated.scopeReason)
    $unrelatedShown = [string]$unrelated.cmdline
    if ($unrelatedShown.Length -eq 0) { $unrelatedShown = '<command line unavailable>' }
    Write-Output ('    cmdline: ' + $unrelatedShown)
}
Write-Output ('scope: signature={0} inScope={1} outOfScope={2} slot={3}' -f $(if ($effectiveSignature.Length -gt 0) { $effectiveSignature } else { '<none, FAIL-CLOSED>' }), $blockingInScope.Count, $blockingOutOfScope.Count, $slotName)
if (($blockingInScope.Count -gt 0) -or ($DryRun -and $DryRunScenario -eq 'refuse')) {
    Show-BlockingReport -Blocking $blockingInScope
    $refusalPaths = Write-RefusalEvidence -Blocking $blockingInScope -BlockingOutOfScope $blockingOutOfScope -EvidenceDirectory $evidenceDirectory
    if ($null -ne $refusalPaths) {
        Write-Output ('evidence: {0}' -f $refusalPaths.json)
        Write-Output ('evidence: {0}' -f $refusalPaths.text)
    }
    Write-Output ('VERDICT=REFUSED EXIT={0} slot={1}' -f $EXIT_REFUSED, $slotName)
    exit $EXIT_REFUSED
}

if ($OptionsFile.Length -gt 0) {
    try { Assert-OptionsWindowed -Path $OptionsFile } catch { Write-Output ('ERROR: {0}' -f $_.Exception.Message); exit $EXIT_BAD_PARAMS }
}

$finalExit = $EXIT_OK
$verdict = 'PASS'
for ($index = 1; $index -le $MaxInstances; $index++) {
    $roundElapsedSec = ((Get-Date) - $roundStartedAt).TotalSeconds
    # 2) whole-round wall clock budget: refuse to start an instance that cannot fit
    if (($roundElapsedSec + $PerInstanceTimeoutSec) -gt $RoundBudgetSec) {
        Add-Finding ('round budget would be exceeded: elapsed {0}s + instance cap {1}s > {2}s' -f [int]$roundElapsedSec, $PerInstanceTimeoutSec, $RoundBudgetSec)
        $verdict = 'FAIL:round-budget'
        $finalExit = $EXIT_ROUND_BUDGET
        break
    }
    Write-Output ('--- instance {0}/{1}: launching {2} (cap {3}s, stall {4}s, slot {5}) ---' -f $index, $MaxInstances, $launchExe, $PerInstanceTimeoutSec, $StallSeconds, $slotName)
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
        -BaselineSnapshot $baselineSnapshotForAudit -AfterSnapshot $afterSnapshot -BaselineProcessIds $baselineOrphanPids -LaunchSignature $effectiveSignature -ProcBaseline $procBaselineForAudit -ProcAfter (Get-ProcessActivitySnapshot)
    $record | Add-Member -NotePropertyName audit -NotePropertyValue $audit -Force
    $instanceRecords.Add($record) | Out-Null
    Write-Output ('instance #{0} pid={1} outcome={2} wall={3}s peakCpu={4}% peakMem={5}MB kill={6}' -f $record.index, $record.pid, $record.outcome, $record.wallSeconds, $record.peakCpuPct, $record.peakMemoryMB, $record.killPerformed)
    Write-Output ('  audit: target[{0}] orphans={1} preexistingExcluded={2} unattributedExcluded={3} foreignMemGrowthMB={4} foreignCpuSec={5} cpuRecovered={6} memRecovered={7} ok={8}' -f $audit.targetCheck, $audit.orphanCount, $audit.preexistingExcluded, $audit.unattributedExcluded, $audit.foreignMemGrowthMB, $audit.foreignCpuSec, $audit.cpuRecovered, $audit.memoryRecovered, $audit.ok)
    foreach ($problem in @($audit.problems)) { Write-Output ('  problem: {0}' -f $problem) }
    foreach ($advisory in @($audit.advisories)) { Write-Output ('  note: {0}' -f $advisory) }
    # DISCOVERABILITY: an opt-in nobody knows about is an opt-in nobody uses. Whenever the audit saw a
    # process survive (the target itself, or an orphan-name match), say what to do about it -- and put
    # the same sentence in the evidence JSON, which is the interface.
    $leftoversDetected = ((-not $audit.targetGone) -or ($audit.orphanCount -gt 0))
    $leftoverHint = ''
    if ($leftoversDetected -and -not $KillProcessTree) {
        $leftoverHint = 'this round left process(es) behind; next round add -KillProcessTree so the instance''s whole process tree is killed (a wrapper launch leaves the real client alive otherwise)'
    } elseif ($leftoversDetected -and $KillProcessTree) {
        $leftoverHint = 'this round left process(es) behind even though -KillProcessTree was set; a descendant is probably outside our pid''s ancestry (a detached Gradle daemon, or a re-parented child) -- check it by hand'
    }
    if ($leftoverHint.Length -gt 0) {
        Write-Output ('  hint: {0}' -f $leftoverHint)
        Add-Finding ('leftover hint: {0}' -f $leftoverHint)
    }
    $audit | Add-Member -NotePropertyName leftoversDetected -NotePropertyValue $leftoversDetected -Force
    $audit | Add-Member -NotePropertyName leftoverHint -NotePropertyValue $leftoverHint -Force
    if ($record.outcome -eq 'timeout-killed') { $verdict = 'FAIL:instance-timeout'; $finalExit = $EXIT_INSTANCE_TIMEOUT }
    elseif ($record.outcome -eq 'stall-killed') { $verdict = 'FAIL:instance-stall'; $finalExit = $EXIT_INSTANCE_STALL }
    if (-not $audit.ok) {
        # clean up only OUR pid (never a general process sweep), then report the failure
        if ($record.pid -gt 0 -and (Test-ProcessAlive -ProcessId $record.pid)) {
            if ($KillProcessTree) {
                # the root is still alive, so the ancestry is still unambiguous: take the descendants too
                $cleanupTree = Stop-ProcessTree -RootProcessId $record.pid -ExcludePattern $KillProcessTreeExcludePattern -ExcludeProcessIds @($PID)
                foreach ($killedItem in @($cleanupTree.killed)) { Write-Output ('  cleanup: killed descendant {0}' -f $killedItem) }
                foreach ($skippedItem in @($cleanupTree.skipped)) { Write-Output ('  cleanup: skipped descendant {0}' -f $skippedItem) }
            }
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
    slot = $slotName
    instanceSignature = $effectiveSignature
    # What this round ran NEXT TO, not just what it refused on: processes that matched
    # -BlockingProcessNames but carried a different signature (a teammate's Gradle daemon, or another
    # slot's client) were reported as NOT BLOCKING at guard time. Recording them here is what lets a
    # reader of this evidence tell "unrelated JVM" from "my own instance".
    blockingOutOfScope = @($blockingOutOfScope)
    blockingOutOfScopeCount = @($blockingOutOfScope).Count
    caps = [pscustomobject]@{
        perInstanceMaxSec = $HARD_CAP_PER_INSTANCE_SEC
        roundMaxSec = $HARD_CAP_ROUND_SEC
        perInstanceUsedSec = $PerInstanceTimeoutSec
        # Round-budget field semantics -- see the "NOTE ON ROUND-BUDGET FIELD NAMES" in the header.
        # roundBudgetCapSec / roundBudgetElapsedSec are the correct names (cap vs actual usage);
        # roundBudgetUsedSec is a DEPRECATED alias of the CAP, kept only so pre-2026-09-27 evidence
        # readers do not break. Do not use it in new code.
        roundBudgetCapSec = $RoundBudgetSec
        roundBudgetElapsedSec = $roundWallSeconds
        roundBudgetUsedSec = $RoundBudgetSec
        stallSeconds = $StallSeconds
    }
    windowed = [bool]$Windowed
    window = ('{0}x{1}' -f $WINDOW_WIDTH, $WINDOW_HEIGHT)
    command = $launchExe
    commandArgs = $(if ($instanceRecords.Count -gt 0) { $instanceRecords[0].effectiveArgs } else { $launchArgs })
    startedAt = $roundStartedAt.ToString('s')
    roundWallSeconds = $roundWallSeconds
    # Read by Write-Evidence for the TXT caps line. It was missing until 2026-09-27, so every archived
    # TXT printed "stall<=s" with an empty value (see e.g. run-bounded-20260917-060518.txt).
    stallSeconds = $StallSeconds
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
