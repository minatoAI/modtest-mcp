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
# root is resolved from (in order): -ArchivedEvidenceRoot, $env:MODTEST_ARCHIVED_EVIDENCE_ROOT, then a
# content-validated AUTO-DISCOVERY of sibling checkouts (a sibling directory whose docs/evidence holds
# the marker report the equivalence replay needs). No machine path is hardcoded here.
#
# FAIL-CLOSED ON THE PROVENANCE CHECK (2026-09-27, R3 review): the archived-copy checks are the most
# valuable part of this test, and they used to be SKIPPED -- silently -- whenever the root was not
# configured; the 19/19 quoted in tools/README was a full-configuration number. Now an unresolved root
# is a FAIL with an actionable hint, and waiving it takes the EXPLICIT -SkipArchivedEquivalence switch.
# This is an intentional observable change: `pwsh tools/run-round.test.ps1` with no archive available
# used to pass, and now fails until you either provide the archive or waive the checks on purpose.
#
# ASCII only. Usage:
#   pwsh tools/run-round.test.ps1 [-ArchivedEvidenceRoot <dir>] [-SkipArchivedEquivalence] [-KeepTemp]
# Exit: 0 all executed checks passed | 1 at least one failed | 2 setup error.

[CmdletBinding()]
param(
    [string]$RoundRunner = '',
    [string]$ArchivedEvidenceRoot = '',
    # Optional: the PRE-CHANGE run-round.ps1. When supplied, a single untagged round is run through both
    # versions and their outputs are compared field by field -- that is the strongest available statement
    # of "existing single-round behaviour is unchanged". Supplying it is optional so the suite still runs
    # anywhere, but the byte-identical check is reported as SKIPPED (never as a pass) when it is absent.
    [string]$OldRunner = '',
    [switch]$SkipArchivedEquivalence,
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

# The report that proves a candidate root really is the archived evidence tree we mean.
$ArchivedMarker = Join-Path '2026-09-26-default-day' (Join-Path 'r1-day' 'region-analysis.txt')
$ArchivedRootSource = 'none'
if ($ArchivedEvidenceRoot.Length -gt 0) {
    $ArchivedRootSource = 'parameter'
} elseif ($env:MODTEST_ARCHIVED_EVIDENCE_ROOT) {
    $ArchivedEvidenceRoot = $env:MODTEST_ARCHIVED_EVIDENCE_ROOT
    $ArchivedRootSource = 'MODTEST_ARCHIVED_EVIDENCE_ROOT'
} else {
    # Sibling checkouts of this repository, accepted only if they contain the marker report. Zero or
    # several matches stay unresolved (ambiguity must not be guessed away).
    $ArchivedRootSource = 'auto-discovery'
    $checkoutParent = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $matches = New-Object System.Collections.Generic.List[string]
    foreach ($sibling in @(Get-ChildItem -LiteralPath $checkoutParent -Directory -ErrorAction SilentlyContinue)) {
        $candidate = Join-Path $sibling.FullName 'docs\evidence'
        if (Test-Path -LiteralPath (Join-Path $candidate $ArchivedMarker)) { $matches.Add($candidate) | Out-Null }
    }
    if ($matches.Count -eq 1) { $ArchivedEvidenceRoot = $matches[0] } else { $ArchivedEvidenceRoot = '' }
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
Write-Output ('RUN-ROUND-TEST archivedRoot=' + $(if ($ArchivedEvidenceRoot.Length -gt 0) { $ArchivedEvidenceRoot } else { '<unresolved>' }) + ' (source=' + $ArchivedRootSource + ')')

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
if ($SkipArchivedEquivalence) {
    Add-Skip 'archived copies: CLI superset + provenance' 'waived on purpose with -SkipArchivedEquivalence'
} elseif ($ArchivedEvidenceRoot.Length -eq 0) {
    # FAIL-CLOSED (see the header): an unresolved root must not silently downgrade the provenance check.
    Add-Check 'archived evidence root resolved (provenance checks must run)' $false `
        ('not resolved via parameter/env/auto-discovery; pass -ArchivedEvidenceRoot <dir>, set ' +
         'MODTEST_ARCHIVED_EVIDENCE_ROOT, or waive on purpose with -SkipArchivedEquivalence')
} elseif (-not (Test-Path -LiteralPath $ArchivedEvidenceRoot -PathType Container)) {
    $configuredDetail = $ArchivedEvidenceRoot
    Add-Check 'configured archived evidence root exists' $false ('not a directory: ' + $configuredDetail)
} elseif ($archivedDirs.Count -eq 0) {
    Add-Check 'archived copies present under the configured root' $false ('no run-round.ps1 found under ' + $ArchivedEvidenceRoot)
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

# ---------------------------------------------------------------- (D) per-round outputs never clobber
Write-Output '--- D. per-round setup outputs never clobber ---'
function Add-Skip {
    param([string]$Name, [string]$Reason)
    $script:Skipped++
    Write-Output ('  SKIP  ' + $Name + ' -- ' + $Reason)
}
# These scenarios run a REAL round against a synthetic gameDir, but through a STUB harness, so no JVM is
# ever started. run-round.ps1 refuses to run while any java/javaw exists (a safety property we must not
# weaken for a test), so the scenarios that reach the write path report SKIP on a busy machine rather
# than passing vacuously.
$stubHarness = Join-Path $tempRoot 'stub-harness-bounded.ps1'
@'
# Test double for run-bounded.ps1: accepts the harness contract and exits 0 without launching anything.
# Declaring the parameters is deliberate -- it also PINS the contract run-round.ps1 splats.
param(
    [string]$FilePath, [string[]]$ArgumentList, [string]$WorkingDirectory, [string]$OptionsFile,
    [int]$PerInstanceTimeoutSec, [int]$MaxInstances, [string]$EvidenceDir,
    [switch]$Windowed, [switch]$VerifyWindow, [string]$BlockingWindowTitlePattern, [switch]$RestoreCursor
)
Write-Output 'STUB-HARNESS: nothing launched'
exit 0
'@ | Set-Content -LiteralPath $stubHarness -Encoding UTF8

function New-FakeGameDir {
    param([string]$Directory)
    New-Item -ItemType Directory -Force -Path (Join-Path $Directory 'config') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $Directory 'saves') | Out-Null
    Set-Content -LiteralPath (Join-Path $Directory 'options.txt') -Value "pauseOnLostFocus:true`nfov:0.0`n" -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $Directory 'config\taclight-client.toml') -Value "schema = 1`n" -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $Directory 'config\oculus.properties') -Value "enableDebugOptions=false`n" -Encoding UTF8
}

function Invoke-Round {
    param([string]$Script, [string]$EvidenceDir, [string[]]$ExtraArgs = @())
    $argv = @('-NoProfile', '-File', $Script, '-Evidence', $EvidenceDir, '-HarnessPath', $stubHarness) + $ExtraArgs
    $stdout = @(& pwsh @argv 2>&1 | ForEach-Object { [string]$_ })
    return [pscustomobject]@{ exit = $LASTEXITCODE; stdout = ($stdout -join "`n") }
}

function Get-TreeShas {
    param([string]$Directory)
    $map = @{}
    if (Test-Path -LiteralPath $Directory) {
        Get-ChildItem -LiteralPath $Directory -Recurse -File | ForEach-Object {
            $rel = $_.FullName.Substring($Directory.Length).TrimStart('\')
            $map[$rel] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
        }
    }
    return $map
}

function Get-ManifestMap {
    param([string]$Path)
    $map = [ordered]@{}
    foreach ($line in @(Get-Content -LiteralPath $Path)) {
        $index = $line.IndexOf('=')
        if ($index -lt 1) { continue }
        $map[$line.Substring(0, $index)] = $line.Substring($index + 1)
    }
    return $map
}

$anyJava = @(Get-Process java, javaw -ErrorAction SilentlyContinue).Count -gt 0
$javaReason = 'a java/javaw process is running, and run-round.ps1 rightly refuses to start (exit 5)'

# D1/D2: first untagged round writes the historical layout; a second one refuses and touches nothing.
$evD = Join-Path $tempRoot 'ev-noclobber'
New-FakeEvidence -Directory $evD -GameDirectory (Join-Path $tempRoot 'game-noclobber')
New-FakeGameDir -Directory (Join-Path $tempRoot 'game-noclobber')
if ($anyJava) {
    Add-Skip 'D1 first untagged round + layout' $javaReason
    Add-Skip 'D2 second untagged round refuses (exit 2, names conflicts)' $javaReason
    Add-Skip 'D2 round 1 evidence untouched by the refused round' $javaReason
    Add-Skip 'D3 -RunTag writes into round-setup\<tag>\' $javaReason
    Add-Skip 'D4 a reused -RunTag refuses' $javaReason
} else {
    $setupDirD = Join-Path $evD 'round-setup'
    $d1 = Invoke-Round -Script $RoundRunner -EvidenceDir $evD
    Add-Check 'D1 a first untagged round still succeeds' ($d1.exit -eq 0) ('exit=' + $d1.exit)
    Add-Check 'D1 the three outputs land DIRECTLY in round-setup\ (historical layout, no subdir)' (
        (Test-Path -LiteralPath (Join-Path $setupDirD 'residue-before.txt')) -and
        (Test-Path -LiteralPath (Join-Path $setupDirD 'setup-manifest.txt')) -and
        (Test-Path -LiteralPath (Join-Path $setupDirD 'restore-report.txt')))
    $shasD1 = Get-TreeShas -Directory $setupDirD

    $d2 = Invoke-Round -Script $RoundRunner -EvidenceDir $evD
    Add-Check 'D2 a second untagged round REFUSES (exit 2)' ($d2.exit -eq 2) ('exit=' + $d2.exit)
    Add-Check 'D2 it reports ROUND_PREFLIGHT=FAIL:outputs-exist' ($d2.stdout -match 'ROUND_PREFLIGHT=FAIL:outputs-exist')
    Add-Check 'D2 it names EACH conflicting file' (
        ($d2.stdout -match 'conflict: .*setup-manifest\.txt') -and
        ($d2.stdout -match 'conflict: .*restore-report\.txt') -and
        ($d2.stdout -match 'conflict: .*residue-before\.txt')) ('named=' + (($d2.stdout -split "`n") | Where-Object { $_ -match 'conflict:' }).Count)
    Add-Check 'D2 it tells the caller how to proceed (subdirectory or -RunTag)' (($d2.stdout -match '\-RunTag') -and ($d2.stdout -match 'subdirectory'))
    $shasD2 = Get-TreeShas -Directory $setupDirD
    Add-Check 'D2 round 1 evidence is byte-identical after the refusal (nothing was touched)' (
        ($shasD1.Count -eq $shasD2.Count) -and (@($shasD1.Keys | Where-Object { $shasD2[$_] -ne $shasD1[$_] }).Count -eq 0)) ('files=' + $shasD1.Count)

    # D3: -RunTag puts the same file names in a per-round subdirectory, leaving the untagged set alone.
    $d3 = Invoke-Round -Script $RoundRunner -EvidenceDir $evD -ExtraArgs @('-RunTag', 't1')
    Add-Check 'D3 a tagged round succeeds' ($d3.exit -eq 0) ('exit=' + $d3.exit)
    $tagDir = Join-Path $setupDirD 't1'
    Add-Check 'D3 the three files land in round-setup\t1\ with UNCHANGED names' (
        (Test-Path -LiteralPath (Join-Path $tagDir 'residue-before.txt')) -and
        (Test-Path -LiteralPath (Join-Path $tagDir 'setup-manifest.txt')) -and
        (Test-Path -LiteralPath (Join-Path $tagDir 'restore-report.txt')))
    $tagManifestPath = Join-Path $tagDir 'setup-manifest.txt'
    $tagManifest = $null
    if (Test-Path -LiteralPath $tagManifestPath) { $tagManifest = Get-ManifestMap -Path $tagManifestPath }
    Add-Check 'D3 the tagged manifest records runTag=t1' (($null -ne $tagManifest) -and ($tagManifest['runTag'] -eq 't1')) ('runTag=' + $(if ($null -ne $tagManifest) { [string]$tagManifest['runTag'] } else { '<no manifest>' }))
    $untaggedManifest = Get-ManifestMap -Path (Join-Path $setupDirD 'setup-manifest.txt')
    Add-Check 'D3 the UNtagged manifest has no runTag field (the addition is tagged-only)' (($null -ne $untaggedManifest) -and (-not $untaggedManifest.Contains('runTag')))
    $shasD3 = Get-TreeShas -Directory $setupDirD
    Add-Check 'D3 the untagged files are untouched by the tagged round' (
        @($shasD1.Keys | Where-Object { $shasD3[$_] -ne $shasD1[$_] }).Count -eq 0)

    # D4: reusing a tag is refused too -- a tag is a new round, never a place to overwrite.
    $d4 = Invoke-Round -Script $RoundRunner -EvidenceDir $evD -ExtraArgs @('-RunTag', 't1')
    Add-Check 'D4 reusing -RunTag t1 REFUSES (exit 2, no silent overwrite)' (($d4.exit -eq 2) -and ($d4.stdout -match 'ROUND_PREFLIGHT=FAIL:run-tag-exists')) ('exit=' + $d4.exit)
    $shasD4 = Get-TreeShas -Directory $setupDirD
    Add-Check 'D4 the tagged round evidence is unchanged after the refusal' (
        @($shasD3.Keys | Where-Object { $shasD4[$_] -ne $shasD3[$_] }).Count -eq 0)

    # D5: a tag must be a directory NAME -- anything that could escape the directory is rejected.
    $d5 = Invoke-Round -Script $RoundRunner -EvidenceDir $evD -ExtraArgs @('-RunTag', '..\escape')
    Add-Check 'D5 a path-like -RunTag is rejected (exit 2)' (($d5.exit -eq 2) -and ($d5.stdout -match 'ROUND_PREFLIGHT=FAIL:bad-run-tag')) ('exit=' + $d5.exit)
    Add-Check 'D5 nothing was written outside the evidence root' (-not (Test-Path -LiteralPath (Join-Path $evD 'escape')))
}

# D6: compatibility -- one untagged round must produce the same bytes as the pre-change runner.
if ($OldRunner.Length -eq 0) {
    Add-Skip 'D6 single untagged round is byte-identical to the pre-change runner' 'pass -OldRunner <pre-change run-round.ps1> to enable this comparison'
} elseif (-not (Test-Path -LiteralPath $OldRunner -PathType Leaf)) {
    Add-Check 'D6 -OldRunner path exists' $false ('not a file: ' + $OldRunner)
} elseif ($anyJava) {
    Add-Skip 'D6 single untagged round is byte-identical to the pre-change runner' $javaReason
} else {
    $evOld = Join-Path $tempRoot 'ev-old-runner'
    New-FakeEvidence -Directory $evOld -GameDirectory (Join-Path $tempRoot 'game-old-runner')
    New-FakeGameDir -Directory (Join-Path $tempRoot 'game-old-runner')
    $evNew = Join-Path $tempRoot 'ev-new-runner'
    New-FakeEvidence -Directory $evNew -GameDirectory (Join-Path $tempRoot 'game-new-runner')
    New-FakeGameDir -Directory (Join-Path $tempRoot 'game-new-runner')
    $runOld = Invoke-Round -Script (Resolve-Path -LiteralPath $OldRunner).Path -EvidenceDir $evOld
    $runNew = Invoke-Round -Script $RoundRunner -EvidenceDir $evNew
    Add-Check 'D6 both the old and the new runner complete one untagged round' (($runOld.exit -eq 0) -and ($runNew.exit -eq 0)) ('old=' + $runOld.exit + ' new=' + $runNew.exit)
    # Everything below is guarded rather than thrown on: when this suite is pointed at the PRE-CHANGE
    # runner as a red control, those files legitimately do not exist, and the suite must still reach its
    # summary and report the remaining checks (a throw under $ErrorActionPreference='Stop' hid D4/D5 once).
    foreach ($report in @('residue-before.txt', 'restore-report.txt')) {
        $oldPath = Join-Path $evOld ('round-setup\' + $report)
        $newPath = Join-Path $evNew ('round-setup\' + $report)
        $oldText = if (Test-Path -LiteralPath $oldPath) { [System.IO.File]::ReadAllText($oldPath) } else { '<missing>' }
        $newText = if (Test-Path -LiteralPath $newPath) { [System.IO.File]::ReadAllText($newPath) } else { '<missing>' }
        Add-Check ('D6 ' + $report + ' is byte-identical to the old runner') (($oldText -ne '<missing>') -and ($oldText -eq $newText))
    }
    # setup-manifest.txt legitimately differs in the fields that name THIS run's paths and time; every
    # other field -- and the field ORDER, which is the file's shape -- must be identical.
    $mapOld = $null
    $mapNew = $null
    $oldManifestPath = Join-Path $evOld 'round-setup\setup-manifest.txt'
    $newManifestPath = Join-Path $evNew 'round-setup\setup-manifest.txt'
    if (Test-Path -LiteralPath $oldManifestPath) { $mapOld = Get-ManifestMap -Path $oldManifestPath }
    if (Test-Path -LiteralPath $newManifestPath) { $mapNew = Get-ManifestMap -Path $newManifestPath }
    $volatileKeys = @('round', 'gameDir', 'harnessPath', 'canonicalScript')
    $stableOld = @()
    $stableNew = @()
    $mismatch = @()
    if (($null -ne $mapOld) -and ($null -ne $mapNew)) {
        $stableOld = @($mapOld.Keys | Where-Object { $volatileKeys -notcontains $_ })
        $stableNew = @($mapNew.Keys | Where-Object { $volatileKeys -notcontains $_ })
        $mismatch = @($stableOld | Where-Object { $mapOld[$_] -ne $mapNew[$_] })
    }
    Add-Check 'D6 the manifest has the same STABLE fields in the same order' (($stableOld.Count -gt 0) -and (($stableOld -join '|') -eq ($stableNew -join '|'))) ('old=' + ($stableOld -join ',') + ' new=' + ($stableNew -join ','))
    Add-Check 'D6 every stable manifest field has the same value' (($stableOld.Count -gt 0) -and ($mismatch.Count -eq 0)) ('differing=' + ($mismatch -join ','))
    Add-Check 'D6 no per-tag subdirectory is created without -RunTag' (-not (Test-Path -LiteralPath (Join-Path $evNew 'round-setup\t1')))
}

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
