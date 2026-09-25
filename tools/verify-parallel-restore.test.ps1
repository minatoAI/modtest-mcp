# SPDX-License-Identifier: GPL-3.0-or-later
# verify-parallel-restore.test.ps1 -- self-test for verify-parallel-restore.ps1 (offline, no JVM).
#
# The tool answers "did every slot of that parallel round give its game directory back?". Its whole value
# is that it can say NO, so the test spends most of its checks on the failure modes:
#   * a healthy two-slot round (manifest + report + .orig matching the live files)   -> PASS
#   * the bypass case: no round-setup\ at all                                        -> FAIL
#   * a report that does not say ROUND_RESTORE=OK                                    -> FAIL
#   * a live file that does NOT match its .orig backup (dirty after the round)        -> FAIL
#   * two rounds claiming the same slot                                               -> FAIL
#   * -ExpectSlots naming a slot that is not there                                    -> FAIL
#   * no .orig backup at all: "restored" cannot be re-verified                        -> FAIL
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

function New-Slot {
    # A synthetic restored slot: game directory with the three files, a round-setup\ holding the .orig
    # backups, the manifest (gameDir=) and a restore report. -Corrupt then rewrites one live file so the
    # independent re-verification has something to catch.
    param([string]$Root, [string]$Slot, [switch]$Corrupt, [switch]$NoReport, [switch]$NoBackups, [string]$ReportBody = '')
    $game = Join-Path $Root ('game-' + $Slot)
    $setup = Join-Path $Root 'round-setup'
    New-Item -ItemType Directory -Force -Path (Join-Path $game 'config'),$setup | Out-Null
    Set-Content -LiteralPath (Join-Path $game 'options.txt') -Value "pauseOnLostFocus:true`n" -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $game 'config\taclight-client.toml') -Value "schema = 1`n" -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $game 'config\oculus.properties') -Value "enableDebugOptions=false`n" -Encoding UTF8
    if (-not $NoBackups) {
        Copy-Item -LiteralPath (Join-Path $game 'options.txt') -Destination (Join-Path $setup 'options.txt.orig') -Force
        Copy-Item -LiteralPath (Join-Path $game 'config\taclight-client.toml') -Destination (Join-Path $setup 'taclight-client.toml.orig') -Force
        Copy-Item -LiteralPath (Join-Path $game 'config\oculus.properties') -Destination (Join-Path $setup 'oculus.properties.orig') -Force
    }
    Set-Content -LiteralPath (Join-Path $setup 'setup-manifest.txt') -Encoding UTF8 -Value @"
round=2026-09-26T02:20:00.0000000+08:00
gameDir=$game
timeoutSec=150
"@
    if (-not $NoReport) {
        $body = if ($ReportBody.Length -gt 0) { $ReportBody } else { @"
slot=$Slot
harnessExit=0
ROUND_RESTORE=OK
[restore] slot=$Slot file=options.txt before=AAA after=AAA match=True
[restore] slot=$Slot file=taclight-client.toml before=BBB after=BBB match=True
[restore] slot=$Slot file=oculus.properties before=CCC after=CCC match=True
"@ }
        Set-Content -LiteralPath (Join-Path $setup 'restore-report.txt') -Value $body -Encoding UTF8
    }
    if ($Corrupt) {
        # Written AFTER the backups, so the live file no longer matches its pre-round bytes.
        Set-Content -LiteralPath (Join-Path $game 'config\oculus.properties') -Value "enableDebugOptions=true`n" -Encoding UTF8
    }
    return $game
}

function Invoke-Verify {
    param([string[]]$Rounds, [string[]]$Expect = @())
    # One comma-separated argument: with `pwsh -File`, a second bare value would bind to the NEXT positional
    # parameter (here -Evidence) instead of to -RoundEvidence, silently checking only the first slot.
    $argv = @('-NoProfile', '-File', $VerifyScript, '-RoundEvidence', ($Rounds -join ','))
    if ($Expect.Count -gt 0) { $argv += @('-ExpectSlots', ($Expect -join ',')) }
    $out = @(& pwsh @argv 2>&1 | ForEach-Object { [string]$_ })
    return [pscustomobject]@{ exit = $LASTEXITCODE; text = ($out -join "`n") }
}

Write-Output ('VERIFY-PARALLEL-RESTORE-TEST script=' + $VerifyScript)

# ---- 1. the healthy two-slot round ----------------------------------------
Write-Output '--- 1. two healthy slots ---'
$goodA = Join-Path $tempRoot 'evA'; New-Item -ItemType Directory -Force -Path $goodA | Out-Null
$goodB = Join-Path $tempRoot 'evB'; New-Item -ItemType Directory -Force -Path $goodB | Out-Null
New-Slot -Root $goodA -Slot 'A' | Out-Null
New-Slot -Root $goodB -Slot 'B' | Out-Null
$r1 = Invoke-Verify -Rounds @($goodA, $goodB) -Expect @('A', 'B')
Add-Check 'two healthy slots -> PASS (exit 0)' (($r1.exit -eq 0) -and ($r1.text -match 'PARALLEL_RESTORE_VERDICT=PASS')) ('exit=' + $r1.exit)
Add-Check 'both slots are reported' (($r1.text -match 'PARALLEL_RESTORE_SLOTS=A,B') -or ($r1.text -match 'PARALLEL_RESTORE_SLOTS=B,A'))
Add-Check 'the independent re-verification ran for the files' ($r1.text -match 're-verified options\.txt match=True')

# ---- 2. the bypass case: no round-setup\ ----------------------------------
Write-Output '--- 2. a round that never went through run-round.ps1 ---'
$bypass = Join-Path $tempRoot 'ev-bypass'; New-Item -ItemType Directory -Force -Path $bypass | Out-Null
$r2 = Invoke-Verify -Rounds @($bypass)
Add-Check 'no round-setup\ -> FAIL (exit 1)' (($r2.exit -eq 1) -and ($r2.text -match 'PARALLEL_RESTORE_VERDICT=FAIL')) ('exit=' + $r2.exit)
Add-Check 'and it names the bypass' ($r2.text -match 'did not go through run-round\.ps1')

# ---- 3. a report that does not say ROUND_RESTORE=OK -----------------------
Write-Output '--- 3. a report without ROUND_RESTORE=OK ---'
$badReport = Join-Path $tempRoot 'ev-badreport'; New-Item -ItemType Directory -Force -Path $badReport | Out-Null
New-Slot -Root $badReport -Slot 'A' -ReportBody "slot=A`nharnessExit=0`nROUND_RESTORE=FAIL:oculus.properties`n" | Out-Null
$r3 = Invoke-Verify -Rounds @($badReport)
Add-Check 'ROUND_RESTORE=FAIL -> FAIL' (($r3.exit -eq 1) -and ($r3.text -match 'ROUND_RESTORE is not OK')) ('exit=' + $r3.exit)

# ---- 4. a live file that does not match its backup ------------------------
Write-Output '--- 4. a file left dirty on disk ---'
$dirty = Join-Path $tempRoot 'ev-dirty'; New-Item -ItemType Directory -Force -Path $dirty | Out-Null
New-Slot -Root $dirty -Slot 'A' -Corrupt | Out-Null
$r4 = Invoke-Verify -Rounds @($dirty)
Add-Check 'a live file differing from its .orig -> FAIL, even though the report says OK' (($r4.exit -eq 1) -and ($r4.text -match 'is not back to the pre-round bytes')) ('exit=' + $r4.exit)
Add-Check 'and the re-verification line shows match=False' ($r4.text -match 're-verified .*oculus\.properties match=False')

# ---- 5. two rounds claiming the same slot --------------------------------
Write-Output '--- 5. two rounds, one slot name ---'
$dupA = Join-Path $tempRoot 'ev-dupA'; New-Item -ItemType Directory -Force -Path $dupA | Out-Null
$dupB = Join-Path $tempRoot 'ev-dupB'; New-Item -ItemType Directory -Force -Path $dupB | Out-Null
New-Slot -Root $dupA -Slot 'A' | Out-Null
New-Slot -Root $dupB -Slot 'A' | Out-Null
$r5 = Invoke-Verify -Rounds @($dupA, $dupB)
Add-Check 'the same slot twice -> FAIL' (($r5.exit -eq 1) -and ($r5.text -match 'two rounds report the same slot')) ('exit=' + $r5.exit)

# ---- 6. -ExpectSlots is a set check --------------------------------------
Write-Output '--- 6. expected slots ---'
$r6 = Invoke-Verify -Rounds @($goodA, $goodB) -Expect @('A', 'B', 'C')
Add-Check 'a missing expected slot -> FAIL' (($r6.exit -eq 1) -and ($r6.text -match 'expected slot\(s\) missing: C')) ('exit=' + $r6.exit)

# ---- 7. no backups: cannot re-verify -------------------------------------
Write-Output '--- 7. nothing to re-verify against ---'
$noBackups = Join-Path $tempRoot 'ev-nobackups'; New-Item -ItemType Directory -Force -Path $noBackups | Out-Null
New-Slot -Root $noBackups -Slot 'A' -NoBackups | Out-Null
$r7 = Invoke-Verify -Rounds @($noBackups)
Add-Check 'no .orig backup -> FAIL (cannot claim "restored" without something to compare)' (($r7.exit -eq 1) -and ($r7.text -match 'no \*\.orig backup was found')) ('exit=' + $r7.exit)

# ---- 8. the archived verdict ---------------------------------------------
Write-Output '--- 8. archived verdict ---'
$evOut = Join-Path $tempRoot 'verdict'
$argv8 = @('-NoProfile', '-File', $VerifyScript, '-RoundEvidence', ($goodA + ',' + $goodB), '-Evidence', $evOut)
$out8 = @(& pwsh @argv8 2>&1 | ForEach-Object { [string]$_ }) -join "`n"
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
