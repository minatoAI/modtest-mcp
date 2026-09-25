# SPDX-License-Identifier: GPL-3.0-or-later
# post-round-gates.test.ps1 -- self-test for the RESTORE gate added to post-round-gates.ps1 (task-40).
#
# WHY THIS GATE EXISTS: a round driven straight through run-bounded.ps1 bypasses run-round.ps1's transient
# setup/restore BY DESIGN, and the result is a silently dirty game directory (measured on the 2026-09-27
# task-30 parallel run, whose evidence has no round-setup\ at all). Nothing can force a caller to use the
# entry point after the fact -- but the evidence can be required to prove it was used, which turns a
# documentation convention into a machine verdict. This test pins that verdict:
#   * a missing round-setup\restore-report.txt            -> FAIL
#   * a report without ROUND_RESTORE=OK                   -> FAIL
#   * a report with ROUND_RESTORE=OK                      -> PASS (with the per-file line count recorded)
#   * -RequireRestore without -RoundEvidence              -> FAIL (forgetting the flag cannot pass)
#   * neither flag given                                  -> SKIPPED, shader gates unaffected
#
# The shader gates are switched off (-SkipAudit -SkipGlslang) so this runs offline in about a second and
# tests ONLY the restore gate; the shader gates have their own tests.
#
# ASCII only. Usage: pwsh tools/post-round-gates.test.ps1 [-KeepTemp]
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
# A dummy file so -ShadersDir is not empty; the shader gates are skipped anyway.
Set-Content -LiteralPath (Join-Path $shaders 'dummy.fsh') -Value 'void main(){}' -Encoding UTF8

function Invoke-Gates {
    param([string[]]$Extra)
    $argv = @('-NoProfile', '-File', $GatesScript, '-ShadersDir', $shaders, '-Evidence', (Join-Path $tempRoot 'gates'), '-SkipAudit', '-SkipGlslang') + $Extra
    $out = @(& pwsh @argv 2>&1 | ForEach-Object { [string]$_ })
    return [pscustomobject]@{ exit = $LASTEXITCODE; text = ($out -join "`n") }
}

Write-Output ('POST-ROUND-GATES-TEST script=' + $GatesScript)

# ---- 1. no round evidence at all -> skipped unless required ------------------
Write-Output '--- 1. without -RoundEvidence ---'
$r1 = Invoke-Gates -Extra @()
Add-Check 'no -RoundEvidence, not required -> PASS (restore gate skipped, shader-only callers unaffected)' (($r1.exit -eq 0) -and ($r1.text -match 'GATES_VERDICT=PASS')) ('exit=' + $r1.exit)
Add-Check 'and the skip is announced, not silent' ($r1.text -match '\[restore-gate\] SKIPPED')
$r2 = Invoke-Gates -Extra @('-RequireRestore')
Add-Check '-RequireRestore without -RoundEvidence -> FAIL' (($r2.exit -eq 1) -and ($r2.text -match 'GATES_VERDICT=FAIL')) ('exit=' + $r2.exit)
Add-Check 'and it says why' ($r2.text -match 'no -RoundEvidence given')

# ---- 2. the round never ran through run-round.ps1 (no restore report) --------
Write-Output '--- 2. round evidence with no restore report (the bypass case) ---'
$evBypass = Join-Path $tempRoot 'ev-bypass'
New-Item -ItemType Directory -Force -Path $evBypass | Out-Null
$r3 = Invoke-Gates -Extra @('-RequireRestore', '-RoundEvidence', $evBypass)
Add-Check 'a round with no round-setup\restore-report.txt -> FAIL' (($r3.exit -eq 1) -and ($r3.text -match 'GATES_VERDICT=FAIL')) ('exit=' + $r3.exit)
Add-Check 'and the reason names the missing artifact' ($r3.text -match 'restore-report\.txt is missing')

# ---- 3. a report that does NOT say ROUND_RESTORE=OK -------------------------
Write-Output '--- 3. a report that does not say ROUND_RESTORE=OK ---'
$evBad = Join-Path $tempRoot 'ev-not-ok'
New-Item -ItemType Directory -Force -Path (Join-Path $evBad 'round-setup') | Out-Null
Set-Content -LiteralPath (Join-Path $evBad 'round-setup\restore-report.txt') -Encoding UTF8 -Value @'
slot=A
harnessExit=0
ROUND_RESTORE=FAIL:oculus.properties
[restore] slot=A file=oculus.properties before=AAA after=BBB match=False
'@
$r4 = Invoke-Gates -Extra @('-RequireRestore', '-RoundEvidence', $evBad)
Add-Check 'ROUND_RESTORE=FAIL -> the gate FAILs' (($r4.exit -eq 1) -and ($r4.text -match 'GATES_VERDICT=FAIL')) ('exit=' + $r4.exit)
Add-Check 'and it points at the missing OK marker' ($r4.text -match 'no ROUND_RESTORE=OK')

# ---- 4. a properly restored round -> PASS, with the line count recorded -----
Write-Output '--- 4. a restored round ---'
$evOk = Join-Path $tempRoot 'ev-ok'
New-Item -ItemType Directory -Force -Path (Join-Path $evOk 'round-setup') | Out-Null
Set-Content -LiteralPath (Join-Path $evOk 'round-setup\restore-report.txt') -Encoding UTF8 -Value @'
slot=A
harnessExit=4
ROUND_RESTORE=OK
[restore] slot=A file=options.txt before=AAA after=AAA match=True
[restore] slot=A file=taclight-client.toml before=BBB after=BBB match=True
[restore] slot=A file=oculus.properties before=CCC after=CCC match=True
'@
$r5 = Invoke-Gates -Extra @('-RequireRestore', '-RoundEvidence', $evOk)
Add-Check 'a restored round -> PASS even though the round itself exited 4' (($r5.exit -eq 0) -and ($r5.text -match 'GATES_VERDICT=PASS')) ('exit=' + $r5.exit)
Add-Check 'it reports the restore gate exit 0' ($r5.text -match '\[restore\] exit=0')
$reportPath = Join-Path $tempRoot 'gates\post-round-gates.json'
$reportJson = $null
if (Test-Path -LiteralPath $reportPath) { $reportJson = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json }
Add-Check 'the report records the restore gate with its per-file line count' (($null -ne $reportJson) -and ($null -ne $reportJson.gates.restore) -and ([int]$reportJson.gates.restore.perFileLines -eq 3)) ('lines=' + $(if ($null -ne $reportJson) { [int]$reportJson.gates.restore.perFileLines } else { '<no report>' }))
Add-Check 'the report records that restore was required' (($null -ne $reportJson) -and ([bool]$reportJson.restoreRequired))

if (-not $KeepTemp) {
    try { Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue } catch { }
} else {
    Write-Output ('POST-ROUND-GATES-TEST temp kept at ' + $tempRoot)
}
Write-Output ('POST-ROUND-GATES-TEST ' + $(if ($script:Failed -eq 0) { 'PASS' } else { 'FAIL' }) + ' (' + ($script:Total - $script:Failed) + '/' + $script:Total + ' checks passed)')
if ($script:Failed -eq 0) { exit 0 }
exit 1
