# SPDX-License-Identifier: GPL-3.0-or-later
# verify-parallel-restore.test.ps1 -- self-test for verify-parallel-restore.ps1 (offline, no JVM).
#
# The tool answers "did every slot of that parallel round give its game directory back?", and its whole
# value is that it can say NO. task-46 added the things which exist because "a report SAYS OK" is not
# evidence: the report must be bound to THIS round's manifest and gameDir, and EVERY file the round's
# manifest says it changed must be covered (a round that restored one file of three must not look clean).
# These are the reviewer's adversarial scenarios plus the -RunTag layout.
#
# ASCII only. Usage: pwsh tools/verify-parallel-restore.test.ps1 [-KeepTemp]
# Exit: 0 all checks passed | 1 at least one failed | 2 setup error.

[CmdletBinding()]
param(
    [string]$VerifyScript = '',
    [switch]$KeepTemp
)

$ErrorActionPreference = 'Stop'
if ($VerifyScript.Length -eq 0) { $VerifyScript = Join-Path $PSScriptRoot 'verify-parallel-restore.ps1' }
if (-not (Test-Path -LiteralPath $VerifyScript -PathType Leaf)) { Write-Output ('SETUP-ERROR: not found: ' + $VerifyScript); exit 2 }
$VerifyScript = (Resolve-Path -LiteralPath $VerifyScript).Path

$script:Total = 0
$script:Failed = 0
function Add-Check {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    $script:Total++
    if ($Condition) { Write-Output ('  ok    ' + $Name) }
    else { $script:Failed++; Write-Output ('  FAIL  ' + $Name + $(if ($Detail.Length -gt 0) { ' -- ' + $Detail } else { '' })) }
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('verify-parallel-restore-test-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null

function Get-Sha([string]$Path) { if (Test-Path -LiteralPath $Path -PathType Leaf) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash } return '' }

function New-Slot {
    # A synthetic round in the REAL on-disk format: a manifest with gameDir= and one before-sha field per
    # managed file, .orig backups, and a restore report BOUND to the manifest by sha.
    #   -ManagedCount 1     -> only one of the three changed files was handled (the reviewer's scenario D)
    #   -CorruptLive        -> the live file no longer matches its backup (scenario B)
    #   -NoReport           -> scenario E
    #   -NoBackups          -> the manifest says files changed, but no .orig exists
    #   -StaleManifestSha   -> the report is bound to a DIFFERENT manifest (leftover evidence)
    #   -DropReportGameDir  -> the report carries no gameDir= line (unbound)
    #   -RunTag <tag>       -> files live in round-setup\<tag>\
    param(
        [string]$Root, [string]$Slot,
        [int]$ManagedCount = 3, [int]$BackupCount = -1, [switch]$CorruptLive, [switch]$NoReport, [switch]$NoBackups,
        [switch]$StaleManifestSha, [switch]$DropReportGameDir, [string]$RunTag = ''
    )
    $game = Join-Path $Root ('game-' + $Slot)
    $setup = Join-Path $Root 'round-setup'
    if ($RunTag.Length -gt 0) { $setup = Join-Path $setup $RunTag }
    New-Item -ItemType Directory -Force -Path (Join-Path $game 'config'), $setup | Out-Null
    Set-Content -LiteralPath (Join-Path $game 'options.txt') -Value "pauseOnLostFocus:true`n" -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $game 'config\taclight-client.toml') -Value "schema = 1`n" -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $game 'config\oculus.properties') -Value "enableDebugOptions=false`n" -Encoding UTF8

    $managed = @(
        @{ field = 'optionsBeforeSha256'; rel = 'options.txt'; orig = 'options.txt.orig' },
        @{ field = 'taclightClientTomlBeforeSha256'; rel = 'config\taclight-client.toml'; orig = 'taclight-client.toml.orig' },
        @{ field = 'oculusBeforeSha256'; rel = 'config\oculus.properties'; orig = 'oculus.properties.orig' }
    )
    if ($BackupCount -lt 0) { $BackupCount = $ManagedCount }
    $manifestLines = New-Object System.Collections.Generic.List[string]
    $manifestLines.Add('round=2026-09-26T02:20:00.0000000+08:00') | Out-Null
    $manifestLines.Add('gameDir=' + $game) | Out-Null
    $manifestLines.Add('timeoutSec=150') | Out-Null
    $i = 0
    foreach ($m in $managed) {
        if ($i -ge $ManagedCount) { break }
        $manifestLines.Add($m.field + '=' + (Get-Sha (Join-Path $game $m.rel))) | Out-Null
        if ((-not $NoBackups) -and ($i -lt $BackupCount)) { Copy-Item -LiteralPath (Join-Path $game $m.rel) -Destination (Join-Path $setup $m.orig) -Force }
        $i++
    }
    $manifestPath = Join-Path $setup 'setup-manifest.txt'
    [System.IO.File]::WriteAllLines($manifestPath, $manifestLines.ToArray(), (New-Object System.Text.UTF8Encoding($false)))
    $manifestSha = Get-Sha $manifestPath
    if ($StaleManifestSha) { $manifestSha = ('0' * 64) }

    if (-not $NoReport) {
        $lines = New-Object System.Collections.Generic.List[string]
        $lines.Add('slot=' + $Slot) | Out-Null
        if (-not $DropReportGameDir) { $lines.Add('gameDir=' + $game) | Out-Null }
        $lines.Add('setupManifestSha256=' + $manifestSha) | Out-Null
        $lines.Add('harnessExit=0') | Out-Null
        $lines.Add('ROUND_RESTORE=OK') | Out-Null
        $i = 0
        foreach ($m in $managed) {
            if ($i -ge $ManagedCount) { break }
            $lines.Add('[restore] slot=' + $Slot + ' file=' + $m.rel + ' before=AAA after=AAA match=True') | Out-Null
            $i++
        }
        Set-Content -LiteralPath (Join-Path $setup 'restore-report.txt') -Value ($lines.ToArray()) -Encoding UTF8
    }
    if ($CorruptLive) {
        # Written AFTER the backups: the live file no longer matches its pre-round bytes.
        Set-Content -LiteralPath (Join-Path $game 'config\oculus.properties') -Value "enableDebugOptions=true`n" -Encoding UTF8
    }
    return $game
}

function Invoke-Verify {
    param([string[]]$Rounds, [string[]]$Expect = @(), [string]$RunTag = '')
    # One comma-separated argument: with `pwsh -File`, a second bare value would bind to the NEXT positional
    # parameter (here -Evidence) instead of to -RoundEvidence, silently checking only the first slot.
    $argv = @('-NoProfile', '-File', $VerifyScript, '-RoundEvidence', ($Rounds -join ','))
    if ($Expect.Count -gt 0) { $argv += @('-ExpectSlots', ($Expect -join ',')) }
    if ($RunTag.Length -gt 0) { $argv += @('-RunTag', $RunTag) }
    $out = @(& pwsh @argv 2>&1 | ForEach-Object { [string]$_ })
    return [pscustomobject]@{ exit = $LASTEXITCODE; text = ($out -join "`n") }
}

Write-Output ('VERIFY-PARALLEL-RESTORE-TEST script=' + $VerifyScript)

# ---- A. the healthy two-slot round (positive control) ---------------------
Write-Output '--- A. two healthy slots ---'
$goodA = Join-Path $tempRoot 'evA'; New-Item -ItemType Directory -Force -Path $goodA | Out-Null
$goodB = Join-Path $tempRoot 'evB'; New-Item -ItemType Directory -Force -Path $goodB | Out-Null
New-Slot -Root $goodA -Slot 'A' | Out-Null
New-Slot -Root $goodB -Slot 'B' | Out-Null
$rA = Invoke-Verify -Rounds @($goodA, $goodB) -Expect @('A', 'B')
Add-Check 'A clean: two healthy slots -> PASS (exit 0)' (($rA.exit -eq 0) -and ($rA.text -match 'PARALLEL_RESTORE_VERDICT=PASS')) ('exit=' + $rA.exit)
Add-Check 'A clean: both slots reported' (($rA.text -match 'PARALLEL_RESTORE_SLOTS=A,B') -or ($rA.text -match 'PARALLEL_RESTORE_SLOTS=B,A'))
Add-Check 'A clean: the manifest-derived expected set is reported' ($rA.text -match 'managedFiles=options\.txt,config\\taclight-client\.toml,config\\oculus\.properties')
Add-Check 'A clean: independent re-verification ran' ($rA.text -match 're-verified options\.txt match=True')

# ---- B. a report that says OK while the live file is dirty -----------------
Write-Output '--- B. report says OK, live is dirty ---'
$evB = Join-Path $tempRoot 'evB-dirty'; New-Item -ItemType Directory -Force -Path $evB | Out-Null
New-Slot -Root $evB -Slot 'A' -CorruptLive | Out-Null
$rB = Invoke-Verify -Rounds @($evB)
Add-Check 'B dirty live: FAIL (a report saying OK is not evidence)' (($rB.exit -eq 1) -and ($rB.text -match 'is not back to the pre-round bytes')) ('exit=' + $rB.exit)
Add-Check 'B dirty live: the re-verification line shows match=False' ($rB.text -match 're-verified .*oculus\.properties match=False')

# ---- C. a report that says FAIL -------------------------------------------
Write-Output '--- C. report says FAIL ---'
$evC = Join-Path $tempRoot 'evC'; New-Item -ItemType Directory -Force -Path $evC | Out-Null
New-Slot -Root $evC -Slot 'A' | Out-Null
(Get-Content -LiteralPath (Join-Path $evC 'round-setup\restore-report.txt')) -replace '^ROUND_RESTORE=OK$', 'ROUND_RESTORE=FAIL:oculus.properties' |
    Set-Content -LiteralPath (Join-Path $evC 'round-setup\restore-report.txt') -Encoding UTF8
$rC = Invoke-Verify -Rounds @($evC)
Add-Check 'C report FAIL: FAIL' (($rC.exit -eq 1) -and ($rC.text -match 'ROUND_RESTORE is not OK')) ('exit=' + $rC.exit)

# ---- D. the expected set is not fully covered (task-46) --------------------
Write-Output '--- D. the manifest lists three changed files but only ONE .orig exists ---'
$evD = Join-Path $tempRoot 'evD'; New-Item -ItemType Directory -Force -Path $evD | Out-Null
New-Slot -Root $evD -Slot 'A' -ManagedCount 3 -BackupCount 1 | Out-Null
$rD = Invoke-Verify -Rounds @($evD)
Add-Check 'D partial: FAIL -- the expected set must be FULLY covered' (($rD.exit -eq 1) -and ($rD.text -match 'has no \.orig backup \(expected-set coverage\)')) ('exit=' + $rD.exit)
Add-Check 'D partial: it names a file that was not covered' ($rD.text -match 'config\\taclight-client\.toml has no .*backup')

# ---- E. no report at all ---------------------------------------------------
Write-Output '--- E. no restore report ---'
$evE = Join-Path $tempRoot 'evE'; New-Item -ItemType Directory -Force -Path $evE | Out-Null
New-Slot -Root $evE -Slot 'A' -NoReport | Out-Null
$rE = Invoke-Verify -Rounds @($evE)
Add-Check 'E no report: FAIL' (($rE.exit -eq 1) -and ($rE.text -match 'no restore-report\.txt')) ('exit=' + $rE.exit)

# ---- F. a -RunTag round ----------------------------------------------------
Write-Output '--- F. a round written with -RunTag ---'
$evF = Join-Path $tempRoot 'evF'; New-Item -ItemType Directory -Force -Path $evF | Out-Null
New-Slot -Root $evF -Slot 'A' -RunTag 'r1' | Out-Null
$rFbad = Invoke-Verify -Rounds @($evF)
Add-Check 'F tagged round WITHOUT -RunTag: FAIL (bare round-setup\ is empty)' (($rFbad.exit -eq 1) -and ($rFbad.text -match 'setup-manifest\.txt: this round did not go through run-round\.ps1')) ('exit=' + $rFbad.exit)
$rF = Invoke-Verify -Rounds @($evF) -RunTag 'r1'
Add-Check 'F tagged round WITH -RunTag: PASS (the two features now agree)' (($rF.exit -eq 0) -and ($rF.text -match 'PARALLEL_RESTORE_VERDICT=PASS')) ('exit=' + $rF.exit)
Add-Check 'F it reports the tagged setup directory' ($rF.text -match 'round-setup\\r1')

# ---- G. no setup-manifest at all (bypass) ---------------------------------
Write-Output '--- G. bypass: no setup-manifest.txt ---'
$evG = Join-Path $tempRoot 'evG'; New-Item -ItemType Directory -Force -Path (Join-Path $evG 'round-setup') | Out-Null
Set-Content -LiteralPath (Join-Path $evG 'round-setup\restore-report.txt') -Value "slot=A`nROUND_RESTORE=OK`n" -Encoding UTF8
$rG = Invoke-Verify -Rounds @($evG)
Add-Check 'G bypass: FAIL (never read "no evidence" as "fine")' (($rG.exit -eq 1) -and ($rG.text -match 'did not go through run-round\.ps1')) ('exit=' + $rG.exit)

# ---- bindings: stale evidence and unbound reports (task-46) ---------------
Write-Output '--- bindings: the report must belong to THIS round ---'
$evH = Join-Path $tempRoot 'evH'; New-Item -ItemType Directory -Force -Path $evH | Out-Null
New-Slot -Root $evH -Slot 'A' -StaleManifestSha | Out-Null
$rH = Invoke-Verify -Rounds @($evH)
Add-Check 'stale: a report bound to a DIFFERENT manifest FAILs' (($rH.exit -eq 1) -and ($rH.text -match 'stale report \(manifest sha mismatch\)')) ('exit=' + $rH.exit)
$evI = Join-Path $tempRoot 'evI'; New-Item -ItemType Directory -Force -Path $evI | Out-Null
New-Slot -Root $evI -Slot 'A' -DropReportGameDir | Out-Null
$rI = Invoke-Verify -Rounds @($evI)
Add-Check 'unbound: a report with no gameDir= FAILs' (($rI.exit -eq 1) -and ($rI.text -match 'not bound to a gameDir')) ('exit=' + $rI.exit)

# ---- other invariants ------------------------------------------------------
Write-Output '--- invariants ---'
$evJ = Join-Path $tempRoot 'evJ'; New-Item -ItemType Directory -Force -Path $evJ | Out-Null
New-Slot -Root $evJ -Slot 'A' -NoBackups | Out-Null
$rJ = Invoke-Verify -Rounds @($evJ)
Add-Check 'manifest says files changed but no .orig exists -> FAIL' (($rJ.exit -eq 1) -and ($rJ.text -match 'expected-set coverage')) ('exit=' + $rJ.exit)

$dupA = Join-Path $tempRoot 'ev-dupA'; New-Item -ItemType Directory -Force -Path $dupA | Out-Null
$dupB = Join-Path $tempRoot 'ev-dupB'; New-Item -ItemType Directory -Force -Path $dupB | Out-Null
New-Slot -Root $dupA -Slot 'A' | Out-Null
New-Slot -Root $dupB -Slot 'A' | Out-Null
$rK = Invoke-Verify -Rounds @($dupA, $dupB)
Add-Check 'two rounds claiming the same slot -> FAIL' (($rK.exit -eq 1) -and ($rK.text -match 'two rounds report the same slot')) ('exit=' + $rK.exit)

$rL = Invoke-Verify -Rounds @($goodA, $goodB) -Expect @('A', 'B', 'C')
Add-Check 'a missing expected slot -> FAIL' (($rL.exit -eq 1) -and ($rL.text -match 'expected slot\(s\) missing: C')) ('exit=' + $rL.exit)

# ---- the archived verdict --------------------------------------------------
Write-Output '--- archived verdict ---'
$evOut = Join-Path $tempRoot 'verdict'
$argvZ = @('-NoProfile', '-File', $VerifyScript, '-RoundEvidence', ($goodA + ',' + $goodB), '-Evidence', $evOut)
$null = @(& pwsh @argvZ 2>&1 | ForEach-Object { [string]$_ })
$jsonPath = Join-Path $evOut 'parallel-restore-verify.json'
Add-Check 'the verdict is written where asked' ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $jsonPath))
$parsed = $null
if (Test-Path -LiteralPath $jsonPath) { $parsed = Get-Content -LiteralPath $jsonPath -Raw | ConvertFrom-Json }
Add-Check 'and it records both slots and the verdict' (($null -ne $parsed) -and ($parsed.verdict -eq 'PASS') -and (@($parsed.slots).Count -eq 2)) ('slots=' + $(if ($null -ne $parsed) { @($parsed.slots) -join ',' } else { '<none>' }))

if (-not $KeepTemp) {
    try { Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue } catch { }
} else {
    Write-Output ('VERIFY-PARALLEL-RESTORE-TEST temp kept at ' + $tempRoot)
}
Write-Output ('VERIFY-PARALLEL-RESTORE-TEST ' + $(if ($script:Failed -eq 0) { 'PASS' } else { 'FAIL' }) + ' (' + ($script:Total - $script:Failed) + '/' + $script:Total + ' checks passed)')
if ($script:Failed -eq 0) { exit 0 }
exit 1
