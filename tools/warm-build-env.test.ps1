# SPDX-License-Identifier: GPL-3.0-or-later
# warm-build-env.test.ps1 -- self-test for warm-build-env.ps1 (offline: no gradle, no real checkout).
#
# WHY: the whole point of the warm-up script is that a FRESH checkout is missing things, and that the
# script must SAY SO rather than quietly produce a build that yields nothing -- the earlier failure was
# exactly "gradle exited 0, no jar, log redirected to an empty file, cause unattributed". So this test
# pins the fail-loud contract with synthetic trees (tiny stand-in files), and pins idempotency:
#   * not-a-checkout target            -> exit 2
#   * -TargetDir == -SourceTree        -> exit 2
#   * libs/ absent in the source       -> exit 2 and it is named
#   * an SRG artifact absent in source -> exit 2 and it is named
#   * gradle user home absent          -> exit 2 and it is named
#   * -Check                           -> reports what WOULD be copied and writes NOTHING
#   * warm                             -> copies exactly the missing items, verdict WARMED, exit 0
#   * warm again (idempotent)          -> copied=0, nothing re-copied, exit 0
#
# ASCII only. Usage: pwsh tools/warm-build-env.test.ps1 [-KeepTemp]
# Exit: 0 all checks passed | 1 at least one failed | 2 setup error.

[CmdletBinding()]
param(
    [string]$WarmScript = '',
    [switch]$KeepTemp
)

$ErrorActionPreference = 'Stop'
if ($WarmScript.Length -eq 0) { $WarmScript = Join-Path $PSScriptRoot 'warm-build-env.ps1' }
if (-not (Test-Path -LiteralPath $WarmScript -PathType Leaf)) { Write-Output ('SETUP-ERROR: not found: ' + $WarmScript); exit 2 }
$WarmScript = (Resolve-Path -LiteralPath $WarmScript).Path

$script:Total = 0
$script:Failed = 0
function Add-Check {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    $script:Total++
    if ($Condition) { Write-Output ('  ok    ' + $Name) }
    else { $script:Failed++; Write-Output ('  FAIL  ' + $Name + $(if ($Detail.Length -gt 0) { ' -- ' + $Detail } else { '' })) }
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('warm-build-env-test-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null

function New-FakeTree {
    # A minimal stand-in for a checkout: the two entry-point markers the script refuses to run without.
    param([string]$Root, [switch]$WithLibs, [switch]$WithSrg, [switch]$WithGradleHome)
    New-Item -ItemType Directory -Force -Path $Root | Out-Null
    Set-Content -LiteralPath (Join-Path $Root 'gradlew.bat') -Value '@echo off' -Encoding utf8
    Set-Content -LiteralPath (Join-Path $Root 'build.gradle') -Value '// fake' -Encoding utf8
    if ($WithLibs) {
        New-Item -ItemType Directory -Force -Path (Join-Path $Root 'libs') | Out-Null
        Set-Content -LiteralPath (Join-Path $Root 'libs\untracked-a.jar') -Value 'jar-a' -Encoding utf8
        Set-Content -LiteralPath (Join-Path $Root 'libs\untracked-b.jar') -Value 'jar-bbbb' -Encoding utf8
    }
    if ($WithSrg) {
        foreach ($rel in @('build\createSrgToMcp\output.srg', 'build\extractSrg\output.srg', 'build\createMcpToSrg\output.tsrg')) {
            $full = Join-Path $Root $rel
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $full) | Out-Null
            Set-Content -LiteralPath $full -Value ('mapping for ' + $rel) -Encoding utf8
        }
    }
    if ($WithGradleHome) { New-Item -ItemType Directory -Force -Path (Join-Path $Root '.gradle-user-home') | Out-Null }
}

function Invoke-Warm {
    param([string[]]$WarmArgs)
    $argv = @('-NoProfile', '-File', $WarmScript) + $WarmArgs
    $out = @(& pwsh @argv 2>&1 | ForEach-Object { [string]$_ })
    return [pscustomobject]@{ exit = $LASTEXITCODE; text = ($out -join "`n") }
}

Write-Output ('WARM-BUILD-ENV-TEST script=' + $WarmScript)

# ---------------------------------------------------------------- 1. bad parameters
Write-Output '--- 1. bad parameters ---'
# NOTE: the target and the source must be DIFFERENT here, otherwise the same-directory check fires first
# and the not-a-checkout check is never exercised (that ordering bug made this assertion fail once).
$plainTarget = Join-Path $tempRoot 'plain-target'
$plainSource = Join-Path $tempRoot 'plain-source'
New-FakeTree -Root $plainSource
New-Item -ItemType Directory -Force -Path $plainTarget | Out-Null
$notCheckout = Invoke-Warm -WarmArgs @('-TargetDir', $plainTarget, '-SourceTree', $plainSource)
Add-Check 'a target that is not a checkout is refused (exit 2)' ($notCheckout.exit -eq 2) ('exit=' + $notCheckout.exit)
Add-Check 'and the refusal says why' ($notCheckout.text -match 'does not look like a checkout') ($notCheckout.text.Trim())

$goodSource = Join-Path $tempRoot 'src-good'
New-FakeTree -Root $goodSource -WithLibs -WithSrg -WithGradleHome
$sameTree = Invoke-Warm -WarmArgs @('-TargetDir', $goodSource, '-SourceTree', $goodSource)
Add-Check 'target == source is refused (exit 2)' ($sameTree.exit -eq 2) ('exit=' + $sameTree.exit)

# ---------------------------------------------------------------- 2. fail-loud on missing prerequisites
Write-Output '--- 2. a missing prerequisite is REPORTED, never silently skipped ---'
$sourceNoLibs = Join-Path $tempRoot 'src-nolibs'
New-FakeTree -Root $sourceNoLibs -WithSrg -WithGradleHome
$targetA = Join-Path $tempRoot 'tgt-a'
New-FakeTree -Root $targetA
$noLibs = Invoke-Warm -WarmArgs @('-TargetDir', $targetA, '-SourceTree', $sourceNoLibs)
Add-Check 'source without libs/ -> exit 2' ($noLibs.exit -eq 2) ('exit=' + $noLibs.exit)
Add-Check 'source without libs/ names it and says missing-prerequisites' (($noLibs.text -match 'MISSING: libs/') -and ($noLibs.text -match 'WARM_VERDICT=FAIL:missing-prerequisites'))

$sourceNoSrg = Join-Path $tempRoot 'src-nosrg'
New-FakeTree -Root $sourceNoSrg -WithLibs -WithGradleHome
$targetB = Join-Path $tempRoot 'tgt-b'
New-FakeTree -Root $targetB
$noSrg = Invoke-Warm -WarmArgs @('-TargetDir', $targetB, '-SourceTree', $sourceNoSrg)
Add-Check 'source without the SRG artifacts -> exit 2' ($noSrg.exit -eq 2) ('exit=' + $noSrg.exit)
Add-Check 'the missing SRG artifact is named' ($noSrg.text -match 'createSrgToMcp\\output\.srg .*not present')

$sourceNoGradleHome = Join-Path $tempRoot 'src-nohome'
New-FakeTree -Root $sourceNoGradleHome -WithLibs -WithSrg
$targetC = Join-Path $tempRoot 'tgt-c'
New-FakeTree -Root $targetC
$noHome = Invoke-Warm -WarmArgs @('-TargetDir', $targetC, '-SourceTree', $sourceNoGradleHome)
Add-Check 'a missing gradle user home -> exit 2' ($noHome.exit -eq 2) ('exit=' + $noHome.exit)
Add-Check 'and it is named' ($noHome.text -match 'gradle user home not found')

# ---------------------------------------------------------------- 3. -Check writes nothing
Write-Output '--- 3. -Check inspects only ---'
$sourceFull = Join-Path $tempRoot 'src-full'
New-FakeTree -Root $sourceFull -WithLibs -WithSrg -WithGradleHome
$targetD = Join-Path $tempRoot 'tgt-d'
New-FakeTree -Root $targetD
# one jar already present and identical => must be reported as already matching, not re-copied
New-Item -ItemType Directory -Force -Path (Join-Path $targetD 'libs') | Out-Null
Copy-Item -LiteralPath (Join-Path $sourceFull 'libs\untracked-a.jar') -Destination (Join-Path $targetD 'libs\untracked-a.jar')
$checkRun = Invoke-Warm -WarmArgs @('-TargetDir', $targetD, '-SourceTree', $sourceFull, '-Check')
Add-Check '-Check exits 0 when the source is complete' ($checkRun.exit -eq 0) ('exit=' + $checkRun.exit)
Add-Check '-Check reports CHECK-OK' ($checkRun.text -match 'WARM_VERDICT=CHECK-OK')
Add-Check '-Check lists the items it would copy' ($checkRun.text -match 'WOULD COPY libs/untracked-b\.jar')
# The one already-matching jar is the only SKIP; the other 4 are "WOULD COPY" (in -Check mode a missing
# item is reported as would-copy, not as a skip).
Add-Check '-Check does NOT re-copy an item that already matches' ($checkRun.text -match 'skippedAlreadyMatching=1') (($checkRun.text -split "`r?`n") | Where-Object { $_ -match 'copied=' })
Add-Check '-Check wrote nothing (the SRG tree is still absent in the target)' (-not (Test-Path (Join-Path $targetD 'build\createSrgToMcp\output.srg')))

# ---------------------------------------------------------------- 4. warm + idempotency
Write-Output '--- 4. warm copies exactly what is missing, and is idempotent ---'
$targetE = Join-Path $tempRoot 'tgt-e'
New-FakeTree -Root $targetE
$warm1 = Invoke-Warm -WarmArgs @('-TargetDir', $targetE, '-SourceTree', $sourceFull)
Add-Check 'warm exits 0' ($warm1.exit -eq 0) ('exit=' + $warm1.exit)
Add-Check 'warm reports WARMED' ($warm1.text -match 'WARM_VERDICT=WARMED')
Add-Check 'warm copied 5 items (2 jars + 3 SRG)' ($warm1.text -match 'copied=5')
Add-Check 'the untracked jars now exist in the target' ((Test-Path (Join-Path $targetE 'libs\untracked-a.jar')) -and (Test-Path (Join-Path $targetE 'libs\untracked-b.jar')))
Add-Check 'the SRG artifacts now exist in the target' (Test-Path (Join-Path $targetE 'build\createSrgToMcp\output.srg'))
Add-Check 'the copies are byte-identical to the source' ((Get-FileHash (Join-Path $targetE 'libs\untracked-b.jar')).Hash -eq (Get-FileHash (Join-Path $sourceFull 'libs\untracked-b.jar')).Hash)
$warm2 = Invoke-Warm -WarmArgs @('-TargetDir', $targetE, '-SourceTree', $sourceFull)
Add-Check 'a second warm run is a no-op (idempotent: copied=0)' (($warm2.exit -eq 0) -and ($warm2.text -match 'copied=0 skippedAlreadyMatching=5')) ('exit=' + $warm2.exit + ' / ' + (($warm2.text -split "`r?`n") | Where-Object { $_ -match 'copied=' }))

if (-not $KeepTemp) {
    try { Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue } catch { }
} else {
    Write-Output ('WARM-BUILD-ENV-TEST temp kept at ' + $tempRoot)
}
Write-Output ('WARM-BUILD-ENV-TEST ' + $(if ($script:Failed -eq 0) { 'PASS' } else { 'FAIL' }) + ' (' + ($script:Total - $script:Failed) + '/' + $script:Total + ' checks passed)')
if ($script:Failed -eq 0) { exit 0 }
exit 1
