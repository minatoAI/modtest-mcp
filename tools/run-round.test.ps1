# SPDX-License-Identifier: GPL-3.0-or-later
# run-round.test.ps1 -- offline checks for run-round.ps1 being the SINGLE SOURCE of the round entry
# point (never starts a JVM, never touches a game directory).
#
# WHY: until 2026-09-27 the round entry point existed ONLY as five byte-identical copies inside
# evidence directories (sha256 0FB721FD74B3..., 10,933 B) with a hardcoded
# $HarnessPath = 'E:\dshHome\modtest-mcp\tools\run-bounded.ps1'. Forgetting a copy cost a real round
# (docs/evidence/2026-09-26-classify/README.md 4.3: "harness 拒绝启动、驱动在白等"). This test pins the
# three properties that make single-sourcing work, and each of them is a negative control:
#   (A) the canonical tools/run-round.ps1 exists and carries no machine-absolute path;
#   (B) its CLI is a SUPERSET of the archived copies' CLI (old callers keep working);
#   (C) it resolves the bounded runner next to ITSELF, so it works from any directory -- which the
#       hardcoded copy by construction could not.
#   (D) the archived copies are still byte-identical to what actually ran (provenance is not damaged
#       by the move: nothing was rewritten, deleted or turned into a forwarder).
#
# The archived copies live in the workspace evidence tree, which is NOT part of this repository, so the
# root is a PARAMETER (or $env:MODTEST_ARCHIVED_EVIDENCE_ROOT) -- no machine path is hardcoded here
# either. Without it, section (D) reports SKIPPED (and says so; a skipped check is never a pass).
#
# ASCII only. Usage:
#   pwsh tools/run-round.test.ps1 [-ArchivedEvidenceRoot <dir>] [-KeepTemp]
# Exit: 0 all executed checks passed | 1 at least one failed | 2 setup error.

[CmdletBinding()]
param(
    [string]$RoundRunner = '',
    [string]$ArchivedEvidenceRoot = '',
    [switch]$KeepTemp
)

$ErrorActionPreference = 'Stop'

if ($RoundRunner.Length -eq 0) { $RoundRunner = Join-Path $PSScriptRoot 'run-round.ps1' }
if (-not (Test-Path -LiteralPath $RoundRunner -PathType Leaf)) {
    Write-Output ('RUN-ROUND-TEST SETUP-ERROR: runner not found: ' + $RoundRunner)
    exit 2
}
$RoundRunner = (Resolve-Path -LiteralPath $RoundRunner).Path
$ToolsDir = Split-Path -Parent $RoundRunner
if ($ArchivedEvidenceRoot.Length -eq 0 -and $env:MODTEST_ARCHIVED_EVIDENCE_ROOT) {
    $ArchivedEvidenceRoot = $env:MODTEST_ARCHIVED_EVIDENCE_ROOT
}
$script:Total = 0
$script:Passed = 0
$script:Failed = 0
$script:Skipped = 0

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

function Add-Skip {
    param([string]$Name, [string]$Reason)
    $script:Skipped++
    Write-Output ('  skip  ' + $Name + ' (' + $Reason + ')')
}

function Get-ParamNames {
    param([string]$Path)
    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$parseErrors)
    $names = New-Object System.Collections.Generic.List[string]
    if ($null -ne $ast.ParamBlock) {
        foreach ($parameterAst in $ast.ParamBlock.Parameters) {
            $names.Add($parameterAst.Name.VariablePath.UserPath) | Out-Null
        }
    }
    return $names
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('run-round-test-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null

Write-Output ('RUN-ROUND-TEST runner=' + $RoundRunner)

# ---------------------------------------------------------------- (A) canonical file shape
Write-Output '--- A. canonical file shape ---'
$source = Get-Content -LiteralPath $RoundRunner -Raw
# Scan CODE lines only. An explanatory comment is allowed to mention a machine path (e.g. to describe
# what the old copy did); what must not exist is a machine path in executable code. (Same discipline as
# the taclight contracts: behaviour assertions must skip comment lines, or a comment can feed them.)
$codeBody = (@(Get-Content -LiteralPath $RoundRunner | Where-Object { $_.TrimStart() -notmatch '^#' }) -join "`n")
Add-Check 'canonical declares itself canonical (do-not-copy banner)' ($source -match 'canonical=tools/run-round\.ps1')
$driveLetterHits = @([regex]::Matches($codeBody, '[A-Za-z]:\\[^\s"'']*') | ForEach-Object { $_.Value })
Add-Check 'canonical CODE carries no machine-absolute path literal' ($driveLetterHits.Count -eq 0) ('hits=' + ($driveLetterHits -join ', '))
Add-Check 'canonical resolves the runner via $PSScriptRoot' ($source -match '\$PSScriptRoot')
Add-Check 'canonical supports MODTEST_HARNESS_PATH' ($source -match 'MODTEST_HARNESS_PATH')
Add-Check 'canonical offers -PreflightOnly (read-only mode)' ($source -match '\$PreflightOnly')
$parseTokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($RoundRunner, [ref]$parseTokens, [ref]$parseErrors) | Out-Null
Add-Check 'canonical parses (ERRCOUNT=0)' ($parseErrors.Count -eq 0) ('errcount=' + $parseErrors.Count)

# ---------------------------------------------------------------- (B) CLI superset of the archived copies
Write-Output '--- B. CLI surface vs the archived copies ---'
$archivedDirs = @()
if ($ArchivedEvidenceRoot.Length -gt 0 -and (Test-Path -LiteralPath $ArchivedEvidenceRoot -PathType Container)) {
    $archivedDirs = @(Get-ChildItem -LiteralPath $ArchivedEvidenceRoot -Recurse -File -Filter 'run-round.ps1' -ErrorAction SilentlyContinue | ForEach-Object { Split-Path -Parent $_.FullName } | Select-Object -Unique)
}
if ($archivedDirs.Count -eq 0) {
    Add-Skip 'archived copies: CLI superset + provenance' 'pass -ArchivedEvidenceRoot (or set MODTEST_ARCHIVED_EVIDENCE_ROOT)'
} else {
    $canonicalParams = @(Get-ParamNames -Path $RoundRunner)
    $copies = @(Get-ChildItem -LiteralPath $ArchivedEvidenceRoot -Recurse -File -Filter 'run-round.ps1' -ErrorAction SilentlyContinue)
    Add-Check 'archived copies found (the historical entry points exist)' ($copies.Count -gt 0) ('count=' + $copies.Count)
    $allSupersets = $true
    $missingDetail = ''
    $allUnchanged = $true
    $shaDetail = ''
    foreach ($copy in $copies) {
        foreach ($oldParam in @(Get-ParamNames -Path $copy.FullName)) {
            if ($canonicalParams -notcontains $oldParam) {
                $allSupersets = $false
                $missingDetail = $missingDetail + (' [' + $copy.Directory.Name + ':' + $oldParam + ']')
            }
        }
        # provenance: the copy must still be the exact bytes that ran (sha256 0FB721FD74B3...), i.e. the
        # single-sourcing move did NOT rewrite, delete or forward any archived round script.
        $copyHash = (Get-FileHash -LiteralPath $copy.FullName -Algorithm SHA256).Hash
        if (-not $copyHash.StartsWith('0FB721FD74B3')) {
            $allUnchanged = $false
            $shaDetail = $shaDetail + (' [' + $copy.Directory.Name + ':' + $copyHash.Substring(0, 12) + ']')
        }
    }
    Add-Check 'canonical CLI is a SUPERSET of every archived copy (old callers keep working)' $allSupersets $missingDetail
    Add-Check 'every archived copy is still the bytes that ran (sha 0FB721FD74B3..., untouched)' $allUnchanged $shaDetail
    # the property being fixed: the archived copies really do bind to one machine path
    $hardcodedCopies = @($copies | Where-Object { (Get-Content -LiteralPath $_.FullName -Raw) -match "\`$HarnessPath = '[A-Za-z]:\\" })
    Add-Check 'archived copies still show the old hardcoded $HarnessPath (what this change removes)' ($hardcodedCopies.Count -eq $copies.Count) `
        ('hardcoded=' + $hardcodedCopies.Count + '/' + $copies.Count)
}

# ---------------------------------------------------------------- (C) directory independence (the negative control)
Write-Output '--- C. directory independence ---'
function New-FakeEvidence {
    param([string]$Directory, [string]$GameDirectory)
    New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    $payload = [ordered]@{
        java = (Get-Process -Id $PID).Path
        jvm_arguments = @('-Xmx1G')
        main_class = 'com.example.FakeMain'
        game_arguments = @('--gameDir', $GameDirectory)
        game_directory = $GameDirectory
    }
    $payload | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $Directory 'launch-args.json') -Encoding UTF8
}

function Invoke-Preflight {
    param([string]$Script, [string]$EvidenceDir)
    $argv = @('-NoProfile', '-File', $Script, '-Evidence', $EvidenceDir, '-PreflightOnly')
    $stdout = @(& pwsh @argv 2>&1 | ForEach-Object { [string]$_ })
    return [pscustomobject]@{ exit = $LASTEXITCODE; stdout = ($stdout -join "`n") }
}

# C1: from the tools/ directory the runner beside this script is used
$fakeEvidenceTools = Join-Path $tempRoot 'ev-tools'
New-FakeEvidence -Directory $fakeEvidenceTools -GameDirectory (Join-Path $tempRoot 'game-tools')
$fromTools = Invoke-Preflight -Script $RoundRunner -EvidenceDir $fakeEvidenceTools
Add-Check 'preflight exits 0' ($fromTools.exit -eq 0) ('exit=' + $fromTools.exit)
Add-Check 'preflight reports ROUND_PREFLIGHT=OK' ($fromTools.stdout -match 'ROUND_PREFLIGHT=OK')
$expectedSibling = Join-Path $ToolsDir 'run-bounded.ps1'
$reportedPath = ''
if ($fromTools.stdout -match '(?m)^\[round\] harnessPath=(.+)$') { $reportedPath = $Matches[1].Trim() }
Add-Check 'preflight resolves the runner BESIDE the script (no machine path)' ($reportedPath -eq $expectedSibling) ('reported=' + $reportedPath + ' expected=' + $expectedSibling)

# C2: the same file, placed in a DIFFERENT directory next to a stub runner, resolves the STUB there.
# That is exactly what the hardcoded copy could not do, and it is this change's negative control.
$isolatedDir = Join-Path $tempRoot 'isolated-tools'
New-Item -ItemType Directory -Force -Path $isolatedDir | Out-Null
Copy-Item -LiteralPath $RoundRunner -Destination (Join-Path $isolatedDir 'run-round.ps1') -Force
$stubPath = Join-Path $isolatedDir 'run-bounded.ps1'
Set-Content -LiteralPath $stubPath -Value '# test stub: exists only to prove $PSScriptRoot resolution' -Encoding UTF8
$isolatedEvidence = Join-Path $tempRoot 'ev-isolated'
New-FakeEvidence -Directory $isolatedEvidence -GameDirectory (Join-Path $tempRoot 'game-isolated')
$fromIsolated = Invoke-Preflight -Script (Join-Path $isolatedDir 'run-round.ps1') -EvidenceDir $isolatedEvidence
$isolatedReported = ''
if ($fromIsolated.stdout -match '(?m)^\[round\] harnessPath=(.+)$') { $isolatedReported = $Matches[1].Trim() }
Add-Check 'in a foreign directory the runner follows the script (resolves the sibling stub)' ($isolatedReported -eq $stubPath) ('reported=' + $isolatedReported)
Add-Check 'and it is NOT the machine-path runner' ($isolatedReported -ne $expectedSibling) ('reported=' + $isolatedReported)

# C3: MODTEST_HARNESS_PATH overrides the sibling resolution
$overrideStub = Join-Path $tempRoot 'override-run-bounded.ps1'
Set-Content -LiteralPath $overrideStub -Value '# test stub for MODTEST_HARNESS_PATH' -Encoding UTF8
$savedOverride = $env:MODTEST_HARNESS_PATH
try {
    $env:MODTEST_HARNESS_PATH = $overrideStub
    $fromEnv = Invoke-Preflight -Script $RoundRunner -EvidenceDir $fakeEvidenceTools
} finally {
    $env:MODTEST_HARNESS_PATH = $savedOverride
}
$envReported = ''
if ($fromEnv.stdout -match '(?m)^\[round\] harnessPath=(.+)$') { $envReported = $Matches[1].Trim() }
Add-Check 'MODTEST_HARNESS_PATH overrides the sibling resolution' ($envReported -eq $overrideStub) ('reported=' + $envReported)

# C4: an explicit -HarnessPath still wins (old callers that pass it are untouched)
$argvExplicit = @('-NoProfile', '-File', $RoundRunner, '-Evidence', $fakeEvidenceTools, '-PreflightOnly', '-HarnessPath', $overrideStub)
$explicitOut = @(& pwsh @argvExplicit 2>&1 | ForEach-Object { [string]$_ }) -join "`n"
$explicitReported = ''
if ($explicitOut -match '(?m)^\[round\] harnessPath=(.+)$') { $explicitReported = $Matches[1].Trim() }
Add-Check 'explicit -HarnessPath wins over everything' ($explicitReported -eq $overrideStub) ('reported=' + $explicitReported)

# C5: preflight must NOT create the round-setup dir (it touches nothing). Conjoined with exit 0 so it
# cannot pass vacuously on a runner that simply failed to accept -PreflightOnly at all.
Add-Check 'preflight touched nothing (no round-setup/ written)' (($fromTools.exit -eq 0) -and (-not (Test-Path -LiteralPath (Join-Path $fakeEvidenceTools 'round-setup'))))

# C6: a missing launch-args.json is a clean setup error, not a crash
$emptyEvidence = Join-Path $tempRoot 'ev-empty'
New-Item -ItemType Directory -Force -Path $emptyEvidence | Out-Null
$missingOut = @(& pwsh -NoProfile -File $RoundRunner -Evidence $emptyEvidence -PreflightOnly 2>&1 | ForEach-Object { [string]$_ }) -join "`n"
Add-Check 'missing launch-args.json -> exit 2 with ROUND_PREFLIGHT=FAIL:no-launch-args' (($LASTEXITCODE -eq 2) -and ($missingOut -match 'ROUND_PREFLIGHT=FAIL:no-launch-args')) ('exit=' + $LASTEXITCODE)

# ---------------------------------------------------------------- result
if (-not $KeepTemp) {
    try { Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue } catch { }
} else {
    Write-Output ('RUN-ROUND-TEST temp kept at ' + $tempRoot)
}
$summary = 'RUN-ROUND-TEST ' + $(if ($script:Failed -eq 0) { 'PASS' } else { 'FAIL' }) + ' (' + $script:Passed + '/' + $script:Total + ' checks passed'
if ($script:Skipped -gt 0) { $summary = $summary + ', ' + $script:Skipped + ' skipped' }
$summary = $summary + ')'
Write-Output $summary
if ($script:Failed -eq 0) { exit 0 }
exit 1
