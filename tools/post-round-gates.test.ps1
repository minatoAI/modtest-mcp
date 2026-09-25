# SPDX-License-Identifier: GPL-3.0-or-later
# post-round-gates.test.ps1 -- self-test for the RESTORE gate of post-round-gates.ps1 (offline, ~2 s).
#
# WHY THIS EXISTS (task-46): the restore gate used to decide from the report alone (file present +
# `^ROUND_RESTORE=OK$`), which proves only that SOME report claims success. The reviewer's adversarial
# matrix (R5) showed it passing in four situations where nothing had been restored. The gate now CALLS
# verify-parallel-restore.ps1, and these are the reviewer's scenarios, kept here so the gate can never
# quietly go back to trusting a report:
#   A clean                                        -> PASS  (positive control)
#   B report says OK but the live file is dirty    -> FAIL  (was a false PASS)
#   C report says FAIL                            -> FAIL
#   D three files changed, one .orig              -> FAIL  (was a false PASS)
#   E no report                                    -> FAIL
#   F report inside round-setup\<tag>\ (-RunTag)   -> PASS with -RunTag (was a false FAIL)
#   G no setup-manifest (bypassed run-round.ps1)  -> FAIL  (was a false PASS)
# The shader gates are switched off so this runs offline and tests ONLY the restore gate (they have their
# own tests). Real on-disk formats are used: a manifest with gameDir= and one before-sha per managed file,
# .orig backups, and a report BOUND to the manifest by sha256.
#
# ASCII only. Usage: pwsh tools/post-round-gates.test.ps1 [-GatesScript <path>] [-KeepTemp]
# Exit: 0 all checks passed | 1 at least one failed | 2 setup error.

[CmdletBinding()]
param(
    [string]$GatesScript = '',
    [switch]$KeepTemp
)

$ErrorActionPreference = 'Stop'
if ($GatesScript.Length -eq 0) { $GatesScript = Join-Path $PSScriptRoot 'post-round-gates.ps1' }
if (-not (Test-Path -LiteralPath $GatesScript -PathType Leaf)) { Write-Output ('SETUP-ERROR: not found: ' + $GatesScript); exit 2 }
$GatesScript = (Resolve-Path -LiteralPath $GatesScript).Path

$script:Total = 0
$script:Failed = 0
function Add-Check {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    $script:Total++
    if ($Condition) { Write-Output ('  ok    ' + $Name) }
    else { $script:Failed++; Write-Output ('  FAIL  ' + $Name + $(if ($Detail.Length -gt 0) { ' -- ' + $Detail } else { '' })) }
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('post-round-gates-test-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
$shaders = Join-Path $tempRoot 'shaders'
New-Item -ItemType Directory -Force -Path $shaders | Out-Null
Set-Content -LiteralPath (Join-Path $shaders 'dummy.fsh') -Value 'void main(){}' -Encoding UTF8

function Get-Sha([string]$Path) { if (Test-Path -LiteralPath $Path -PathType Leaf) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash } return '' }

function New-Round {
    # A synthetic round in the real on-disk format. -Scenario picks the intended state.
    param([string]$Scenario, [string]$RunTag = '')
    $root = Join-Path $tempRoot ('round-' + $Scenario + $(if ($RunTag.Length -gt 0) { '-' + $RunTag } else { '' }))
    $game = Join-Path $root 'game'
    $setup = Join-Path $root 'round-setup'
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
    $backupCount = if ($Scenario -eq 'D') { 1 } else { 3 }
    if ($Scenario -ne 'G') {
        $lines = New-Object System.Collections.Generic.List[string]
        $lines.Add('round=2026-09-26T02:20:00.0000000+08:00') | Out-Null
        $lines.Add('gameDir=' + $game) | Out-Null
        $i = 0
        foreach ($m in $managed) {
            $lines.Add($m.field + '=' + (Get-Sha (Join-Path $game $m.rel))) | Out-Null
            if ($i -lt $backupCount) { Copy-Item -LiteralPath (Join-Path $game $m.rel) -Destination (Join-Path $setup $m.orig) -Force }
            $i++
        }
        [System.IO.File]::WriteAllLines((Join-Path $setup 'setup-manifest.txt'), $lines.ToArray(), (New-Object System.Text.UTF8Encoding($false)))
    }
    if ($Scenario -ne 'E') {
        $manifestSha = Get-Sha (Join-Path $setup 'setup-manifest.txt')
        $body = New-Object System.Collections.Generic.List[string]
        $body.Add('slot=A') | Out-Null
        $body.Add('gameDir=' + $game) | Out-Null
        if ($manifestSha.Length -gt 0) { $body.Add('setupManifestSha256=' + $manifestSha) | Out-Null }
        $body.Add('harnessExit=4') | Out-Null
        $body.Add($(if ($Scenario -eq 'C') { 'ROUND_RESTORE=FAIL:oculus.properties' } else { 'ROUND_RESTORE=OK' })) | Out-Null
        $body.Add('[restore] slot=A file=options.txt before=AAA after=AAA match=True') | Out-Null
        Set-Content -LiteralPath (Join-Path $setup 'restore-report.txt') -Value ($body.ToArray()) -Encoding UTF8
    }
    if ($Scenario -eq 'B') {
        # Written after the backups: the live file no longer matches its pre-round bytes.
        Set-Content -LiteralPath (Join-Path $game 'config\oculus.properties') -Value "enableDebugOptions=true`n" -Encoding UTF8
    }
    return $root
}

function Invoke-Gates {
    param([string[]]$Extra)
    $argv = @('-NoProfile', '-File', $GatesScript, '-ShadersDir', $shaders, '-Evidence', (Join-Path $tempRoot 'gates'), '-SkipAudit', '-SkipGlslang') + $Extra
    $out = @(& pwsh @argv 2>&1 | ForEach-Object { [string]$_ })
    return [pscustomobject]@{ exit = $LASTEXITCODE; text = ($out -join "`n") }
}

Write-Output ('POST-ROUND-GATES-TEST script=' + $GatesScript)

# ---- 1. no round evidence at all -------------------------------------------
Write-Output '--- 1. without -RoundEvidence ---'
$r1 = Invoke-Gates -Extra @()
Add-Check 'no -RoundEvidence, not required -> PASS (shader-only callers unaffected)' (($r1.exit -eq 0) -and ($r1.text -match 'GATES_VERDICT=PASS')) ('exit=' + $r1.exit)
Add-Check 'and the skip is announced, not silent' ($r1.text -match '\[restore-gate\] SKIPPED')
$r2 = Invoke-Gates -Extra @('-RequireRestore')
Add-Check '-RequireRestore without -RoundEvidence -> FAIL' (($r2.exit -eq 1) -and ($r2.text -match 'GATES_VERDICT=FAIL')) ('exit=' + $r2.exit)

# ---- 2. the reviewer's adversarial matrix ---------------------------------
Write-Output '--- 2. scenarios A-G ---'
$scenarios = @(
    @{ id = 'A'; expect = 0; why = 'clean round' },
    @{ id = 'B'; expect = 1; why = 'report OK but live dirty' },
    @{ id = 'C'; expect = 1; why = 'report says FAIL' },
    @{ id = 'D'; expect = 1; why = 'three files changed, one .orig' },
    @{ id = 'E'; expect = 1; why = 'no report' },
    @{ id = 'G'; expect = 1; why = 'no setup-manifest (bypass)' }
)
foreach ($s in $scenarios) {
    $round = New-Round -Scenario $s.id
    $res = Invoke-Gates -Extra @('-RequireRestore', '-RoundEvidence', $round)
    Add-Check ('scenario ' + $s.id + ' (' + $s.why + ') -> exit ' + $s.expect) ($res.exit -eq $s.expect) ('exit=' + $res.exit)
}
$rA = Invoke-Gates -Extra @('-RequireRestore', '-RoundEvidence', (Join-Path $tempRoot 'round-A'))
Add-Check 'A: the gate reports the verifier as the decision maker' ($rA.text -match '\[restore\] exit=0')
$reportA = Get-Content -LiteralPath (Join-Path $tempRoot 'gates\post-round-gates.json') -Raw | ConvertFrom-Json
Add-Check 'A: the reason names the verifier, not "the report says OK"' ([string]$reportA.gates.restore.reason -match 'verifier confirmed every file the round changed') ([string]$reportA.gates.restore.reason)

# ---- 3. -RunTag (the incompatibility R5 found) ----------------------------
Write-Output '--- 3. -RunTag rounds ---'
$tagged = New-Round -Scenario 'A' -RunTag 'r1'
$rF1 = Invoke-Gates -Extra @('-RequireRestore', '-RoundEvidence', $tagged)
Add-Check 'F: a tagged round without -RunTag FAILs (nothing in the bare round-setup)' ($rF1.exit -eq 1) ('exit=' + $rF1.exit)
$rF2 = Invoke-Gates -Extra @('-RequireRestore', '-RoundEvidence', $tagged, '-RunTag', 'r1')
Add-Check 'F: the same round WITH -RunTag r1 PASSes (task-38 and the gate now agree)' (($rF2.exit -eq 0) -and ($rF2.text -match 'GATES_VERDICT=PASS')) ('exit=' + $rF2.exit)

# ---- 4. the gate report ---------------------------------------------------
Write-Output '--- 4. the gate report ---'
$reportPath = Join-Path $tempRoot 'gates\post-round-gates.json'
$reportJson = $null
if (Test-Path -LiteralPath $reportPath) { $reportJson = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json }
Add-Check 'the report records the restore gate and its verifier' (($null -ne $reportJson) -and ($null -ne $reportJson.gates.restore) -and ([string]$reportJson.gates.restore.verifier -match 'verify-parallel-restore\.ps1'))
Add-Check 'the report records that restore was required' (($null -ne $reportJson) -and ([bool]$reportJson.restoreRequired))

if (-not $KeepTemp) {
    try { Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue } catch { }
} else {
    Write-Output ('POST-ROUND-GATES-TEST temp kept at ' + $tempRoot)
}
Write-Output ('POST-ROUND-GATES-TEST ' + $(if ($script:Failed -eq 0) { 'PASS' } else { 'FAIL' }) + ' (' + ($script:Total - $script:Failed) + '/' + $script:Total + ' checks passed)')
if ($script:Failed -eq 0) { exit 0 }
exit 1
