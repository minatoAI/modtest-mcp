# SPDX-License-Identifier: GPL-3.0-or-later
# run-bounded.test.ps1 -- offline self-test for run-bounded.ps1 (NEVER starts a JVM).
#
# WHY: run-bounded.ps1 is the tool every real round depends on, yet its only automated check used to
# be parse-check.ps1 (that the file PARSES -- nothing about behaviour). This test drives the harness
# through its -DryRun paths (which substitute a harmless PowerShell sleep as the child) and asserts
# the machine-readable contract: the exit code per scenario, the post-run audit verdict, and the
# semantics of the `caps` fields.
#
# It is deliberately the VEHICLE FOR NEGATIVE CONTROLS. Every field/behaviour added to the runner
# should make a check here go RED on the old code first. The `caps roundBudget*` checks were written
# exactly that way: they were red against the pre-renaming runner (which had no roundBudgetCapSec /
# roundBudgetElapsedSec keys) and green only after the correction. See
# docs/evidence/2026-09-27-harness-dev/README.md for the recorded red/green transcripts.
#
# MACHINE INDEPENDENCE: a real round may be running on this machine at the same time. The scenarios
# therefore pass a -BlockingProcessNames that matches nothing and an empty -BlockingWindowTitlePattern
# (so the pre-launch guard cannot REFUSE because a teammate's java.exe / Gradle daemon is up), plus
# very generous CPU/memory recovery tolerances (the recovery judgement is a shared-machine judgement
# and is not what this test is about). The `refuse` scenario deliberately points the guard at a
# process that does exist (this PowerShell host) to exercise the refusal path.
#
# The dry child is the current PowerShell host sleeping; run-bounded.ps1 itself refuses to run a dry
# run whose child resolves to java*. Nothing here ever starts a JVM or touches a game directory.
#
# ASCII only (PowerShell 5.1 + no BOM). Usage:
#   pwsh tools/run-bounded.test.ps1 [-Runner tools/run-bounded.ps1] [-KeepEvidence]
# Prints: RUN-BOUNDED-TEST PASS (N checks) | RUN-BOUNDED-TEST FAIL (K/N checks)  + a FAIL line each.
# Exit: 0 all checks passed | 1 at least one check failed | 2 setup error.

[CmdletBinding()]
param(
    [string]$Runner = '',
    [switch]$KeepEvidence
)

$ErrorActionPreference = 'Stop'

if ($Runner.Length -eq 0) { $Runner = Join-Path $PSScriptRoot 'run-bounded.ps1' }
if (-not (Test-Path -LiteralPath $Runner -PathType Leaf)) {
    Write-Output ('RUN-BOUNDED-TEST SETUP-ERROR: runner not found: ' + $Runner)
    exit 2
}
$Runner = (Resolve-Path -LiteralPath $Runner).Path
$RunnerHost = (Get-Process -Id $PID).Path
if ($RunnerHost -match '(?i)javaw?\.exe$') {
    Write-Output 'RUN-BOUNDED-TEST SETUP-ERROR: the test host is a java executable; refusing to run'
    exit 2
}

$NO_SUCH_PROCESS = 'modtest-no-such-process-9f31'
$script:Total = 0
$script:Passed = 0
$script:Failed = 0

# A fresh per-scenario evidence directory, so "did a run write evidence?" is answerable.
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('run-bounded-test-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null

function Add-Check {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    $script:Total++
    if ($Condition) {
        $script:Passed++
        Write-Output ('  ok    ' + $Name)
    } else {
        $script:Failed++
        $suffix = ''
        if ($Detail.Length -gt 0) { $suffix = ' -- ' + $Detail }
        Write-Output ('  FAIL  ' + $Name + $suffix)
    }
}

function Invoke-DryScenario {
    # Runs one -DryRun scenario in its own evidence directory and returns exit code + parsed summary.
    param([string]$Scenario, [string[]]$ExtraArgs = @())
    $directory = Join-Path $testRoot $Scenario
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $argv = @('-NoProfile', '-File', $Runner, '-DryRun', '-DryRunScenario', $Scenario,
        '-EvidenceDir', $directory, '-PollSeconds', '1',
        # machine independence: never REFUSE because somebody else's JVM is up, and never judge
        # CPU/memory recovery on a shared machine.
        '-BlockingProcessNames', $NO_SUCH_PROCESS, '-BlockingWindowTitlePattern', '',
        '-OrphanProcessNames', $NO_SUCH_PROCESS,
        '-CpuRecoveryTolerancePct', '1000', '-MemoryRecoveryToleranceMB', '1000000')
    $argv += $ExtraArgs
    $stdout = @(& pwsh @argv 2>&1 | ForEach-Object { [string]$_ })
    $exitCode = $LASTEXITCODE
    $summary = $null
    $jsonPath = ''
    $candidates = @(Get-ChildItem -LiteralPath $directory -File -Filter 'run-bounded-*.json' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime)
    if ($candidates.Count -gt 0) {
        $jsonPath = $candidates[$candidates.Count - 1].FullName
        $summary = (Get-Content -LiteralPath $jsonPath -Raw | ConvertFrom-Json)
    }
    return [pscustomobject]@{
        scenario = $Scenario; directory = $directory; exit = $exitCode
        stdout = ($stdout -join "`n"); jsonPath = $jsonPath; json = $summary
    }
}

function Test-HasProperty {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return $false }
    return ($null -ne $Object.PSObject.Properties[$Name])
}

function Get-GrandchildPid {
    # The `tree` dry-run child prints GRANDCHILD_PID=<n> on ITS stdout, which the runner redirects to
    # the per-instance out file (recorded as instances[0].stdoutFile).
    param([object]$Run)
    if ($null -eq $Run.json) { return -1 }
    $outPath = [string]$Run.json.instances[0].stdoutFile
    if ($outPath.Length -eq 0 -or -not (Test-Path -LiteralPath $outPath)) { return -1 }
    $body = Get-Content -LiteralPath $outPath -Raw
    if ($body -match 'GRANDCHILD_PID=(\d+)') { return [int]$Matches[1] }
    return -1
}

function Test-ProcessAlive([int]$ProcessId) {
    if ($ProcessId -le 0) { return $false }
    return ($null -ne (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue))
}

Write-Output ('RUN-BOUNDED-TEST runner=' + $Runner)
Write-Output ('RUN-BOUNDED-TEST evidenceRoot=' + $testRoot)

# ---------------------------------------------------------------- 1. ok (happy path)
Write-Output '--- scenario ok ---'
$runOk = Invoke-DryScenario -Scenario 'ok'
Add-Check 'ok/exit=0' ($runOk.exit -eq 0) ('exit=' + $runOk.exit)
Add-Check 'ok/evidence json written' ($runOk.jsonPath.Length -gt 0)
if ($null -ne $runOk.json) {
    Add-Check 'ok/verdict=PASS' ($runOk.json.verdict -eq 'PASS') ('verdict=' + $runOk.json.verdict)
    Add-Check 'ok/instance outcome=exited' ($runOk.json.instances[0].outcome -eq 'exited') ('outcome=' + $runOk.json.instances[0].outcome)
    $auditOk = $false
    if (Test-HasProperty $runOk.json.instances[0] 'audit') { $auditOk = [bool]$runOk.json.instances[0].audit.ok }
    Add-Check 'ok/post-run audit ok' $auditOk

    # --- the caps contract -------------------------------------------------
    $caps = $runOk.json.caps
    Add-Check 'caps/perInstanceMaxSec=360 (hard cap)' ($caps.perInstanceMaxSec -eq 360) ('value=' + $caps.perInstanceMaxSec)
    Add-Check 'caps/roundMaxSec=1500 (hard cap)' ($caps.roundMaxSec -eq 1500) ('value=' + $caps.roundMaxSec)
    Add-Check 'caps/perInstanceUsedSec=330 (default, distinct from the cap)' ($caps.perInstanceUsedSec -eq 330) ('value=' + $caps.perInstanceUsedSec)
    Add-Check 'caps/roundBudgetCapSec exists and =1500' ((Test-HasProperty $caps 'roundBudgetCapSec') -and ($caps.roundBudgetCapSec -eq 1500)) ('value=' + $caps.roundBudgetCapSec)
    Add-Check 'caps/roundBudgetElapsedSec exists (actual usage, not the cap)' (Test-HasProperty $caps 'roundBudgetElapsedSec') ('value=' + $caps.roundBudgetElapsedSec)
    if (Test-HasProperty $caps 'roundBudgetElapsedSec') {
        Add-Check 'caps/roundBudgetElapsedSec = roundWallSeconds' ([math]::Abs([double]$caps.roundBudgetElapsedSec - [double]$runOk.json.roundWallSeconds) -lt 0.01) `
            ('elapsed=' + $caps.roundBudgetElapsedSec + ' roundWallSeconds=' + $runOk.json.roundWallSeconds)
        Add-Check 'caps/roundBudgetElapsedSec is NOT the cap' (([double]$caps.roundBudgetElapsedSec -ne 1500.0) -or ([double]$runOk.json.roundWallSeconds -eq 1500.0)) `
            ('elapsed=' + $caps.roundBudgetElapsedSec)
    }
    # BACKWARD COMPATIBILITY, asserted so nobody can silently drop it.
    Add-Check 'caps/roundBudgetUsedSec kept (deprecated alias)' (Test-HasProperty $caps 'roundBudgetUsedSec') 
    if (Test-HasProperty $caps 'roundBudgetUsedSec') {
        Add-Check 'caps/roundBudgetUsedSec == roundBudgetCapSec (same value as before)' ($caps.roundBudgetUsedSec -eq $caps.roundBudgetCapSec) `
            ('used=' + $caps.roundBudgetUsedSec + ' cap=' + $caps.roundBudgetCapSec)
    }
    $textFile = [System.IO.Path]::ChangeExtension($runOk.jsonPath, '.txt')
    $textBody = ''
    if (Test-Path -LiteralPath $textFile) { $textBody = Get-Content -LiteralPath $textFile -Raw }
    Add-Check 'ok/txt summary states roundBudget cap+elapsed' ($textBody -match 'roundBudget: cap=' -and $textBody -match 'elapsed=')
    Add-Check 'ok/txt summary still states the hard caps' ($textBody -match 'caps: perInstance<=')
    # Write-Evidence reads $Summary.stallSeconds; the summary only ever set caps.stallSeconds, so every
    # archived TXT printed "stall<=s" with an empty value until 2026-09-27.
    Add-Check 'ok/txt summary states the real stall cap (was an empty value before)' ($textBody -match 'stall<=45s') ('text=' + (($textBody -split "`r?`n") | Where-Object { $_ -match '^caps:' }))
} else {
    Add-Check 'ok/summary parsed' $false 'no run-bounded-*.json in the evidence dir'
}

# ---------------------------------------------------------------- 2. timeout (per-instance cap)
Write-Output '--- scenario timeout ---'
$runTimeout = Invoke-DryScenario -Scenario 'timeout' -ExtraArgs @('-PerInstanceTimeoutSec', '6')
Add-Check 'timeout/exit=4 (instance hit the per-instance cap)' ($runTimeout.exit -eq 4) ('exit=' + $runTimeout.exit)
if ($null -ne $runTimeout.json) {
    Add-Check 'timeout/instance outcome=timeout-killed' ($runTimeout.json.instances[0].outcome -eq 'timeout-killed') ('outcome=' + $runTimeout.json.instances[0].outcome)
    Add-Check 'timeout/kill performed' ([bool]$runTimeout.json.instances[0].killPerformed)
    Add-Check 'timeout/verdict=FAIL:instance-timeout' ($runTimeout.json.verdict -eq 'FAIL:instance-timeout') ('verdict=' + $runTimeout.json.verdict)
    Add-Check 'timeout/caps.perInstanceUsedSec reflects -PerInstanceTimeoutSec 6' ($runTimeout.json.caps.perInstanceUsedSec -eq 6) ('value=' + $runTimeout.json.caps.perInstanceUsedSec)
} else {
    Add-Check 'timeout/summary parsed' $false 'no run-bounded-*.json in the evidence dir'
}

# ---------------------------------------------------------------- 3. stall (flat CPU)
Write-Output '--- scenario stall ---'
$runStall = Invoke-DryScenario -Scenario 'stall' -ExtraArgs @('-StallSeconds', '4')
Add-Check 'stall/exit=5 (instance stalled)' ($runStall.exit -eq 5) ('exit=' + $runStall.exit)
if ($null -ne $runStall.json) {
    Add-Check 'stall/instance outcome=stall-killed' ($runStall.json.instances[0].outcome -eq 'stall-killed') ('outcome=' + $runStall.json.instances[0].outcome)
    Add-Check 'stall/verdict=FAIL:instance-stall' ($runStall.json.verdict -eq 'FAIL:instance-stall') ('verdict=' + $runStall.json.verdict)
} else {
    Add-Check 'stall/summary parsed' $false 'no run-bounded-*.json in the evidence dir'
}

# ---------------------------------------------------------------- 4. refuse (pre-existing process)
# The refusal is the ONE place where the harness tells a human "I will not run", and until 2026-09-27 it
# printed only pid/name/title and wrote NO evidence at all -- so three real refusals (all caused by
# somebody else's Gradle daemon) were unattributable from the archive. These checks pin the fix, and
# they were RED on the previous runner (no evidence file, no blocking[], no command line).
Write-Output '--- scenario refuse ---'
$directoryRefuse = Join-Path $testRoot 'refuse'
New-Item -ItemType Directory -Force -Path $directoryRefuse | Out-Null
# -BlockingWindowTitlePattern '' keeps this scenario deterministic: only the process-name channel may
# match, so a stray browser tab whose title contains "Minecraft" cannot change the outcome.
$argvRefuse = @('-NoProfile', '-File', $Runner, '-DryRun', '-DryRunScenario', 'refuse',
    '-EvidenceDir', $directoryRefuse, '-PollSeconds', '1', '-BlockingProcessNames', 'pwsh',
    '-BlockingWindowTitlePattern', '')
$stdoutRefuse = @(& pwsh @argvRefuse 2>&1 | ForEach-Object { [string]$_ })
$exitRefuse = $LASTEXITCODE
$outRefuse = ($stdoutRefuse -join "`n")
Add-Check 'refuse/exit=3 (still a refusal)' ($exitRefuse -eq 3) ('exit=' + $exitRefuse)
Add-Check 'refuse/says REFUSING TO START' ($outRefuse -match 'REFUSING TO START')
$refuseInstances = @(Get-ChildItem -LiteralPath $directoryRefuse -File -Filter '*-instance*-out.txt' -ErrorAction SilentlyContinue)
Add-Check 'refuse/nothing was launched (no instance out/err files)' ($refuseInstances.Count -eq 0) ('count=' + $refuseInstances.Count)
Add-Check 'refuse/stdout shows a COMMAND LINE, not just name/title' ($outRefuse -match '(?i)cmdline:')
Add-Check 'refuse/stdout states nothing was launched and nothing was killed' ($outRefuse -match '(?i)NOTHING was launched and NOTHING was killed')
Add-Check 'refuse/stdout states the blockers pre-date this round' ($outRefuse -match '(?i)from BEFORE this round')

$refuseJsonFiles = @(Get-ChildItem -LiteralPath $directoryRefuse -File -Filter 'run-bounded-*.json' -ErrorAction SilentlyContinue)
# INTENTIONAL OBSERVABLE CHANGE (approved 2026-09-27): this assertion used to read
# "refuse/no evidence written" and pass because a refusal produced no file at all.
Add-Check 'refuse/WRITES an evidence JSON (used to write nothing)' ($refuseJsonFiles.Count -gt 0) ('count=' + $refuseJsonFiles.Count)
if ($refuseJsonFiles.Count -gt 0) {
    $refusal = Get-Content -LiteralPath $refuseJsonFiles[0].FullName -Raw | ConvertFrom-Json
    Add-Check 'refuse/evidence records verdict=REFUSED and exitCode=3' (([string]$refusal.verdict -eq 'REFUSED') -and ([int]$refusal.exitCode -eq 3)) ('verdict=' + $refusal.verdict + ' exitCode=' + $refusal.exitCode)
    $blocking = @($refusal.blocking)
    Add-Check 'refuse/evidence carries a non-empty blocking[]' ($blocking.Count -gt 0) ('count=' + $blocking.Count)
    if ($blocking.Count -gt 0) {
        $noCmdline = @($blocking | Where-Object { -not ($_.PSObject.Properties['cmdline']) -or ([string]$_.cmdline).Length -eq 0 })
        Add-Check 'refuse/every blocking[] entry carries a command-line summary' ($noCmdline.Count -eq 0) ('entries without cmdline=' + $noCmdline.Count)
        $noPid = @($blocking | Where-Object { [int]$_.pid -le 0 })
        Add-Check 'refuse/every blocking[] entry carries a pid' ($noPid.Count -eq 0) ('without pid=' + $noPid.Count)
        Add-Check 'refuse/evidence notes the blockers pre-date this round' ([string]$refusal.blockingNote -match '(?i)existed BEFORE') ('note=' + $refusal.blockingNote)
    } else {
        Add-Check 'refuse/every blocking[] entry carries a command-line summary' $false 'blocking[] is empty'
        Add-Check 'refuse/every blocking[] entry carries a pid' $false 'blocking[] is empty'
        Add-Check 'refuse/evidence notes the blockers pre-date this round' $false 'blocking[] is empty'
    }
} else {
    Add-Check 'refuse/evidence records verdict=REFUSED and exitCode=3' $false 'no evidence JSON written'
    Add-Check 'refuse/evidence carries a non-empty blocking[]' $false 'no evidence JSON written'
    Add-Check 'refuse/every blocking[] entry carries a command-line summary' $false 'no evidence JSON written'
    Add-Check 'refuse/every blocking[] entry carries a pid' $false 'no evidence JSON written'
    Add-Check 'refuse/evidence notes the blockers pre-date this round' $false 'no evidence JSON written'
}

# ---------------------------------------------------------------- 5. orphan (fault injection)
Write-Output '--- scenario orphan ---'
$runOrphan = Invoke-DryScenario -Scenario 'orphan'
Add-Check 'orphan/exit=7 (post-run audit failed)' ($runOrphan.exit -eq 7) ('exit=' + $runOrphan.exit)
if ($null -ne $runOrphan.json) {
    Add-Check 'orphan/verdict=FAIL:post-run-audit' ($runOrphan.json.verdict -eq 'FAIL:post-run-audit') ('verdict=' + $runOrphan.json.verdict)
    $audit = $runOrphan.json.instances[0].audit
    Add-Check 'orphan/audit.ok=false' ([bool]$audit.ok -eq $false)
    Add-Check 'orphan/audit.targetGone=false (the alive-but-reported-exited child was caught)' ([bool]$audit.targetGone -eq $false)
    $problemsText = (@($audit.problems) -join ' | ')
    Add-Check 'orphan/audit names the surviving target pid' ($problemsText -match 'still alive') ('problems=' + $problemsText)
    Add-Check 'orphan/audit.leftoversDetected=true' ([bool]$audit.leftoversDetected)
    Add-Check 'orphan/audit.leftoverHint points at -KillProcessTree' ((Test-HasProperty $audit 'leftoverHint') -and ([string]$audit.leftoverHint -match '-KillProcessTree')) ('hint=' + $audit.leftoverHint)
    Add-Check 'orphan/stdout prints the hint (discoverability)' ($runOrphan.stdout -match 'hint:')
} else {
    Add-Check 'orphan/summary parsed' $false 'no run-bounded-*.json in the evidence dir'
}

# ---------------------------------------------------------------- 6. tree (descendant kill)
# The negative control for -KillProcessTree, in both directions and WITHOUT a game client:
#   (a) no switch  -> the grandchild the child spawned SURVIVES  == the old wrapper-launch failure
#                     (measured for real on 2026-09-25 with renderdoccmd.exe; renderdoc README 3.2)
#   (b) -KillProcessTree -> the same grandchild is DEAD and the kill is recorded in the evidence
Write-Output '--- scenario tree, WITHOUT -KillProcessTree (expect the grandchild to survive) ---'
$runTreeOld = Invoke-DryScenario -Scenario 'tree' -ExtraArgs @('-PerInstanceTimeoutSec', '6')
$grandchildOld = Get-GrandchildPid -Run $runTreeOld
Add-Check 'tree/child printed GRANDCHILD_PID' ($grandchildOld -gt 0)
Add-Check 'tree/WITHOUT the switch the grandchild survives (the old wrapper-launch failure)' (Test-ProcessAlive -ProcessId $grandchildOld) ('pid=' + $grandchildOld)

# clean up the survivor we deliberately created (only the pid we spawned, then prove it is gone)
if (Test-ProcessAlive -ProcessId $grandchildOld) {
    try { Stop-Process -Id $grandchildOld -Force -ErrorAction Stop } catch { }
    Start-Sleep -Milliseconds 500
}
Add-Check 'tree/survivor cleaned up by the test itself' (-not (Test-ProcessAlive -ProcessId $grandchildOld)) ('pid=' + $grandchildOld)

Write-Output '--- scenario tree, WITH -KillProcessTree (expect the grandchild to die) ---'
$runTreeNew = Invoke-DryScenario -Scenario 'tree' -ExtraArgs @('-PerInstanceTimeoutSec', '6', '-KillProcessTree')
$grandchildNew = Get-GrandchildPid -Run $runTreeNew
Add-Check 'tree/child printed GRANDCHILD_PID (2nd run)' ($grandchildNew -gt 0)
# NOT vacuous: require that a grandchild really existed AND is gone. Without the "> 0" conjunct this
# check would pass on any runner that never created a grandchild at all (a false green).
Add-Check 'tree/WITH the switch the grandchild is dead' (($grandchildNew -gt 0) -and (-not (Test-ProcessAlive -ProcessId $grandchildNew))) ('pid=' + $grandchildNew)
Add-Check 'tree/exit=4 (the instance still hit the per-instance cap)' ($runTreeNew.exit -eq 4) ('exit=' + $runTreeNew.exit)
if ($null -ne $runTreeNew.json) {
    $treeKill = $runTreeNew.json.instances[0].treeKill
    Add-Check 'tree/treeKill recorded in the evidence' ((Test-HasProperty $runTreeNew.json.instances[0] 'treeKill') -and ($null -ne $treeKill))
    if ($null -ne $treeKill) {
        Add-Check 'tree/treeKill.descendantCount>=1' ([int]$treeKill.descendantCount -ge 1) ('count=' + $treeKill.descendantCount)
        Add-Check 'tree/treeKill.killed names the grandchild pid' ((@($treeKill.killed) -join ' | ') -match ('pid=' + $grandchildNew)) ('killed=' + (@($treeKill.killed) -join ' | '))
    }
    Add-Check 'tree/finding records the tree kill' ((@($runTreeNew.json.findings) -join ' | ') -match 'kill tree: killed')
} else {
    Add-Check 'tree/summary parsed' $false 'no run-bounded-*.json in the evidence dir'
}
# never leave a stray sleeper behind even if an assertion above failed
if (Test-ProcessAlive -ProcessId $grandchildNew) { try { Stop-Process -Id $grandchildNew -Force -ErrorAction Stop } catch { } }

# ---------------------------------------------------------------- 7. guard SCOPE (task-28)
# The pre-launch guard used to refuse whenever ANY process matched -BlockingProcessNames, so a
# teammate's Gradle daemon blocked real rounds (measured twice) and two isolated instances could never
# coexist. It is now scoped exactly like the post-run audit: a match blocks only if its command line
# carries THIS round's instance signature. Three controls:
#   1. an UNRELATED signature present  -> must NOT block (it is reported as NOT BLOCKING and ignored)
#   2. a match carrying OUR signature  -> MUST block, and be named
#   3. -DryRunScenario refuse          -> unchanged (asserted in section 4 above)
# The signature used here is the scenario's own evidence directory: it is unique, and the harness's own
# process command line contains it, so control 2 has a real in-scope match without pretending that a
# shared interpreter path is an instance signature.
# The signature passed on the command line ends up in THE HARNESS'S OWN command line. If
# -BlockingProcessNames also matched the harness's own process name (pwsh), the guard would match
# ITSELF and always refuse -- a self-match artefact that would silently make both controls meaningless.
# So pick a PEER process whose name is not pwsh/powershell, and drive both controls through it.
$peer = $null
foreach ($peerCandidate in @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)) {
    if ($peerCandidate.Name -in @('pwsh.exe', 'powershell.exe')) { continue }
    $peerCommandLine = [string]$peerCandidate.CommandLine
    if ($peerCommandLine.Length -lt 12) { continue }
    $peer = [pscustomobject]@{ pid = $peerCandidate.ProcessId; exe = $peerCandidate.Name; bare = [System.IO.Path]::GetFileNameWithoutExtension($peerCandidate.Name) }
    break
}
Add-Check 'scope/a non-harness peer process exists for the scope controls' ($null -ne $peer) 'none found: every candidate was pwsh/powershell (the harness itself)'

Write-Output '--- guard scope: an UNRELATED instance signature must NOT block ---'
$dirScopeUnrelated = Join-Path $testRoot 'scope-unrelated'
New-Item -ItemType Directory -Force -Path $dirScopeUnrelated | Out-Null
$foreignSignature = 'modtest-signature-absent-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$peerName = if ($null -ne $peer) { $peer.exe } else { 'modtest-no-such-process' }
$argvUnrelated = @('-NoProfile', '-File', $Runner, '-DryRun', '-DryRunScenario', 'ok',
    '-EvidenceDir', $dirScopeUnrelated, '-PollSeconds', '1',
    '-BlockingProcessNames', $peerName, '-BlockingWindowTitlePattern', '', '-InstanceSignature', $foreignSignature,
    # Same machine-independence guards Invoke-DryScenario uses: a teammate's Gradle JVM appearing during
    # this 6-second dry run must not be mistaken for our orphan, and CPU/memory recovery is not what
    # this control is about (that flakiness cost one run: exit 7 with peer=DefenderSessionHelper.exe).
    '-OrphanProcessNames', $NO_SUCH_PROCESS,
    '-CpuRecoveryTolerancePct', '1000', '-MemoryRecoveryToleranceMB', '1000000')
$outUnrelated = @(& pwsh @argvUnrelated 2>&1 | ForEach-Object { [string]$_ })
$exitUnrelated = $LASTEXITCODE
$textUnrelated = ($outUnrelated -join "`n")
Add-Check 'scope/unrelated process does NOT block (exit 0)' ($exitUnrelated -eq 0) ('exit=' + $exitUnrelated + ' peer=' + $peerName)
Add-Check 'scope/unrelated process is reported as NOT BLOCKING' ($textUnrelated -match 'NOT BLOCKING:')
Add-Check 'scope/scope line records inScope=0 with outOfScope>=1' ($textUnrelated -match 'inScope=0 outOfScope=[1-9]')
Add-Check 'scope/no refusal was printed for an unrelated process' (-not ($textUnrelated -match 'REFUSING TO START'))
Add-Check 'scope/the round then ran normally (verdict PASS)' ($textUnrelated -match 'VERDICT=PASS')

Write-Output '--- guard scope: a process carrying OUR signature MUST block ---'
$dirScopeSame = Join-Path $testRoot 'scope-same'
New-Item -ItemType Directory -Force -Path $dirScopeSame | Out-Null
# The peer's own image path is always in its command line, so its executable name is a real in-scope
# signature for it -- and it is NOT in the harness's command line, so the harness is not a candidate.
$ourSignature = if ($null -ne $peer) { $peer.exe } else { $dirScopeSame }
$argvSame = @('-NoProfile', '-File', $Runner, '-DryRun', '-DryRunScenario', 'ok',
    '-EvidenceDir', $dirScopeSame, '-PollSeconds', '1',
    '-BlockingProcessNames', $peerName, '-BlockingWindowTitlePattern', '', '-InstanceSignature', $ourSignature,
    '-OrphanProcessNames', $NO_SUCH_PROCESS,
    '-CpuRecoveryTolerancePct', '1000', '-MemoryRecoveryToleranceMB', '1000000')
$outSame = @(& pwsh @argvSame 2>&1 | ForEach-Object { [string]$_ })
$exitSame = $LASTEXITCODE
$textSame = ($outSame -join "`n")
Add-Check 'scope/same-signature process DOES block (exit 3)' ($exitSame -eq 3) ('exit=' + $exitSame + ' signature=' + $ourSignature)
Add-Check 'scope/scope line records inScope>=1' ($textSame -match 'inScope=[1-9]')
Add-Check 'scope/the in-scope process is named with its command line' (($textSame -match 'REFUSING TO START: pid=') -and ($textSame -match '(?i)cmdline:'))
Add-Check 'scope/nothing was launched on this refusal' (@(Get-ChildItem -LiteralPath $dirScopeSame -File -Filter '*-instance*-out.txt' -ErrorAction SilentlyContinue).Count -eq 0)
$sameJsonFiles = @(Get-ChildItem -LiteralPath $dirScopeSame -File -Filter 'run-bounded-*.json' -ErrorAction SilentlyContinue)
Add-Check 'scope/refusal evidence records verdict=REFUSED' ($sameJsonFiles.Count -gt 0)
if ($sameJsonFiles.Count -gt 0) {
    $sameSummary = Get-Content -LiteralPath $sameJsonFiles[0].FullName -Raw | ConvertFrom-Json
    Add-Check 'scope/evidence records the instance signature it matched on' (([string]$sameSummary.instanceSignature) -eq $ourSignature) ('sig=' + $sameSummary.instanceSignature)
    Add-Check 'scope/evidence blocking[] holds only the in-scope match(es)' ([int]$sameSummary.blockingCount -ge 1) ('count=' + $sameSummary.blockingCount)
    Add-Check 'scope/every in-scope entry is tagged in-scope' (@($sameSummary.blocking | Where-Object { [string]$_.scope -ne 'in-scope' }).Count -eq 0)
    Add-Check 'scope/evidence lists the out-of-scope matches separately' ($null -ne $sameSummary.PSObject.Properties['blockingOutOfScope'])
    Add-Check 'scope/out-of-scope entries explain why they did not block' (@($sameSummary.blockingOutOfScope | Where-Object { [string]$_.scopeReason -notmatch 'does not carry' }).Count -eq 0)
} else {
    Add-Check 'scope/evidence records the instance signature it matched on' $false 'no evidence JSON'
    Add-Check 'scope/evidence blocking[] holds only the in-scope match(es)' $false 'no evidence JSON'
    Add-Check 'scope/every in-scope entry is tagged in-scope' $false 'no evidence JSON'
    Add-Check 'scope/evidence lists the out-of-scope matches separately' $false 'no evidence JSON'
    Add-Check 'scope/out-of-scope entries explain why they did not block' $false 'no evidence JSON'
}
Write-Output '--- guard scope: -Slot is printed and recorded ---'
Add-Check 'scope/slot is printed on the run banner' ($textUnrelated -match 'slot=') 
$slotJsonFiles = @(Get-ChildItem -LiteralPath $dirScopeUnrelated -File -Filter 'run-bounded-*.json' -ErrorAction SilentlyContinue)
if ($slotJsonFiles.Count -gt 0) {
    $slotSummary = Get-Content -LiteralPath $slotJsonFiles[0].FullName -Raw | ConvertFrom-Json
    Add-Check 'scope/evidence records a non-empty slot' (([string]$slotSummary.slot).Length -gt 0) ('slot=' + $slotSummary.slot)
    # Default (no explicit -Slot) must NOT change the evidence filename: run-bounded-<stamp>.json
    Add-Check 'scope/default slot leaves the evidence filename unchanged' ($slotJsonFiles[0].Name -match '^run-bounded-\d{8}-\d{6}\.json$') ($slotJsonFiles[0].Name)
    # A NON-refusal round must also record what it ran NEXT TO (otherwise the scoping is invisible in a
    # passing round's evidence, and a reader cannot tell "unrelated JVM" from "my own instance").
    Add-Check 'scope/NON-refusal evidence records the out-of-scope matches it ran next to' ([int]$slotSummary.blockingOutOfScopeCount -ge 1) ('count=' + $slotSummary.blockingOutOfScopeCount)
    Add-Check 'scope/those out-of-scope entries carry a command line and a scope tag' (@($slotSummary.blockingOutOfScope | Where-Object { ([string]$_.cmdline).Length -eq 0 -or [string]$_.scope -ne 'out-of-scope' }).Count -eq 0)
    Add-Check 'scope/NON-refusal evidence records the signature that was enforced' (([string]$slotSummary.instanceSignature) -eq $foreignSignature) ('sig=' + $slotSummary.instanceSignature)
} else {
    Add-Check 'scope/evidence records a non-empty slot' $false 'no evidence JSON'
    Add-Check 'scope/default slot leaves the evidence filename unchanged' $false 'no evidence JSON'
    Add-Check 'scope/NON-refusal evidence records the out-of-scope matches it ran next to' $false 'no evidence JSON'
    Add-Check 'scope/those out-of-scope entries carry a command line and a scope tag' $false 'no evidence JSON'
    Add-Check 'scope/NON-refusal evidence records the signature that was enforced' $false 'no evidence JSON'
}

Write-Output '--- window audit is selected by pid, never by title (source shape) ---'
# Two parallel slots both report "Minecraft* ...", so a window must be picked by the instance's own pid.
# A dry run has no windowed child, so this is asserted on the source shape rather than at runtime; the
# runtime path (-VerifyWindow) runs in every real round.
$runnerSource = Get-Content -LiteralPath $Runner -Raw
$geometryStart = $runnerSource.IndexOf('function Get-WindowGeometry')
$geometryEnd = if ($geometryStart -ge 0) { $runnerSource.IndexOf("`nfunction ", $geometryStart + 10) } else { -1 }
$geometryBody = ''
if ($geometryStart -ge 0 -and $geometryEnd -gt $geometryStart) { $geometryBody = $runnerSource.Substring($geometryStart, $geometryEnd - $geometryStart) }
elseif ($geometryStart -ge 0) { $geometryBody = $runnerSource.Substring($geometryStart) }
Add-Check 'window/Get-WindowGeometry found in the runner source' ($geometryBody.Length -gt 0)
Add-Check 'window/it records the pid it was asked about' ($geometryBody -match 'pid = \$ProcessId')
Add-Check 'window/it takes the handle from that pid (not from a title search)' ($geometryBody -match 'handle = \[long\]\$live\.MainWindowHandle')
Add-Check 'window/it never selects a window by title' (-not ($geometryBody -match 'MainWindowTitle\s+-match'))

# ---------------------------------------------------------------- result
if (-not $KeepEvidence) {
    try { Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue } catch { }
} else {
    Write-Output ('RUN-BOUNDED-TEST evidence kept at ' + $testRoot)
}
if ($script:Failed -eq 0) {
    Write-Output ('RUN-BOUNDED-TEST PASS (' + $script:Total + ' checks)')
    exit 0
}
Write-Output ('RUN-BOUNDED-TEST FAIL (' + $script:Passed + '/' + $script:Total + ' checks passed, ' + $script:Failed + ' failed)')
exit 1
