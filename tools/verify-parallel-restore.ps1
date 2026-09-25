# SPDX-License-Identifier: GPL-3.0-or-later
# verify-parallel-restore.ps1 -- one command to answer: "did every slot of that parallel round give its
# game directory back?"
#
# WHY: after a two-slot parallel round the question "was everything restored?" used to be answered by
# comparing files by hand. It has three separate parts, and this tool checks all three per round, then
# adds an INDEPENDENT re-verification that does not trust the round's own report:
#
#   1. PROVENANCE -- does the evidence contain round-setup\setup-manifest.txt? If not, the round did not go
#      through run-round.ps1, which is the only thing that sets up and therefore the only thing that
#      restores. That is the bypass case: no report can exist, and "no evidence" must never read as "fine".
#   2. RECORDED VERDICT -- round-setup\restore-report.txt must say ROUND_RESTORE=OK, with one
#      `[restore] slot=… file=… before=… after=… match=True` line per file that has a .orig backup.
#   3. INDEPENDENT RE-VERIFICATION -- for every *.orig backup in round-setup\, hash the LIVE file in the
#      game directory (whose path comes from gameDir= in the manifest) and require it to equal the backup's
#      sha256 NOW. The report is evidence about what run-round.ps1 did at the time; this is evidence about
#      the state of the machine afterwards. A round whose report lies, or a file rewritten after the
#      restore, fails here.
#
# It also reports the slot of each round and FAILS when two rounds claim the same slot, because "two slots
# restored" is meaningless if both are actually the same one.
#
# ASCII only. Usage:
#   pwsh tools/verify-parallel-restore.ps1 -RoundEvidence <evA>,<evB> [-Evidence <dir>] [-Json] [-ExpectSlots A,B]
# Exit: 0 every round verified | 1 at least one failed | 2 setup error.

[CmdletBinding()]
param(
    # Each entry is a ROUND's evidence directory -- the one that contains round-setup\. Accepts either an
    # array (when splatted from PowerShell: -RoundEvidence @(a,b)) or a single comma-separated argument
    # (-RoundEvidence 'a,b'), because when this script is invoked as `pwsh -File ... -RoundEvidence a b`
    # the extra bare token binds to the NEXT positional parameter instead -- a trap that silently checked
    # only the first slot (measured 2026-09-26).
    [Parameter(Mandatory = $true)][string[]]$RoundEvidence,
    # Optional: where to write parallel-restore-verify.json (an archived verdict).
    [string]$Evidence = '',
    # Optional: the slot names you EXPECT. Checked as a set, so a missing or an extra slot is a FAIL.
    [string[]]$ExpectSlots = @(),
    [switch]$Json
)

$ErrorActionPreference = 'Stop'

# Which .orig backup corresponds to which live file (relative to the game directory). Unknown *.orig files
# are reported but do not count as failures -- the round may back up more than these three.
$origTargets = [ordered]@{
    'options.txt.orig' = 'options.txt'
    'taclight-client.toml.orig' = 'config\taclight-client.toml'
    'oculus.properties.orig' = 'config\oculus.properties'
}

function Get-Sha256([string]$Path) {
    if (Test-Path -LiteralPath $Path -PathType Leaf) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
    return ''
}
function Get-ManifestValue([string]$ManifestPath, [string]$Key) {
    if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) { return '' }
    foreach ($line in @(Get-Content -LiteralPath $ManifestPath)) {
        if ($line.StartsWith($Key + '=')) { return $line.Substring($Key.Length + 1) }
    }
    return ''
}

$failed = New-Object System.Collections.Generic.List[string]
$reports = New-Object System.Collections.Generic.List[object]
$slotNames = New-Object System.Collections.Generic.List[string]

# Normalise the input: an array element may itself be a comma-separated list (see the parameter note).
$roundDirs = New-Object System.Collections.Generic.List[string]
foreach ($item in $RoundEvidence) {
    foreach ($part in ($item -split ',')) {
        if ($part.Trim().Length -gt 0) { $roundDirs.Add($part.Trim()) | Out-Null }
    }
}
if ($roundDirs.Count -eq 0) { Write-Output 'ERROR: -RoundEvidence listed no directories'; exit 2 }

foreach ($roundDirRaw in $roundDirs) {
    $roundDir = $roundDirRaw
    $entry = [ordered]@{
        roundEvidence = $roundDir
        slot = ''
        gameDir = ''
        manifestPresent = $false
        restoreReportPresent = $false
        roundRestoreOk = $false
        recordedMatchLines = 0
        reVerifiedFiles = @()
        problems = @()
    }
    if (-not (Test-Path -LiteralPath $roundDir -PathType Container)) {
        $entry.problems += ('round evidence directory not found: ' + $roundDir)
        $failed.Add($roundDir + ': directory not found') | Out-Null
        $reports.Add([pscustomobject]$entry) | Out-Null
        continue
    }
    $roundDir = (Resolve-Path -LiteralPath $roundDir).Path
    $entry.roundEvidence = $roundDir
    $setupDir = Join-Path $roundDir 'round-setup'
    $manifestPath = Join-Path $setupDir 'setup-manifest.txt'
    $reportPath = Join-Path $setupDir 'restore-report.txt'

    # ---- 1. provenance -----------------------------------------------------
    $entry.manifestPresent = Test-Path -LiteralPath $manifestPath -PathType Leaf
    if (-not $entry.manifestPresent) {
        $entry.problems += 'no round-setup\setup-manifest.txt: this round did not go through run-round.ps1, so it had no transient setup and no teardown (the bypass case)'
        $failed.Add($roundDir + ': no round-setup\setup-manifest.txt (bypass)') | Out-Null
    }
    $entry.gameDir = Get-ManifestValue -ManifestPath $manifestPath -Key 'gameDir'

    # ---- 2. recorded verdict ----------------------------------------------
    $entry.restoreReportPresent = Test-Path -LiteralPath $reportPath -PathType Leaf
    $reportText = ''
    if ($entry.restoreReportPresent) { $reportText = [System.IO.File]::ReadAllText($reportPath) }
    else {
        $entry.problems += 'no round-setup\restore-report.txt: nothing proves the game directory was put back'
        $failed.Add($roundDir + ': no restore-report.txt') | Out-Null
    }
    if ($reportText.Length -gt 0) {
        $entry.roundRestoreOk = ($reportText -match '(?m)^ROUND_RESTORE=OK\s*$')
        $entry.recordedMatchLines = @([regex]::Matches($reportText, '(?m)^\[restore\] slot=.+ match=True\s*$')).Count
        if ($reportText -match '(?m)^slot=(.+)$') { $entry.slot = $Matches[1].Trim() }
        if (-not $entry.roundRestoreOk) {
            $entry.problems += 'restore-report.txt does not say ROUND_RESTORE=OK'
            $failed.Add($roundDir + ': ROUND_RESTORE is not OK') | Out-Null
        }
    }
    if ($entry.slot.Length -gt 0) { $slotNames.Add($entry.slot) | Out-Null }

    # ---- 3. independent re-verification against the live game directory ----
    if ($entry.gameDir.Length -eq 0) {
        $entry.problems += 'cannot re-verify: the manifest has no gameDir= line'
        $failed.Add($roundDir + ': no gameDir in the manifest') | Out-Null
    } elseif (-not (Test-Path -LiteralPath $entry.gameDir -PathType Container)) {
        $entry.problems += ('cannot re-verify: the game directory does not exist: ' + $entry.gameDir)
        $failed.Add($roundDir + ': game directory missing') | Out-Null
    } else {
        foreach ($origName in $origTargets.Keys) {
            $backupPath = Join-Path $setupDir $origName
            if (-not (Test-Path -LiteralPath $backupPath -PathType Leaf)) { continue }
            $targetPath = Join-Path $entry.gameDir $origTargets[$origName]
            $backupSha = Get-Sha256 $backupPath
            $liveSha = Get-Sha256 $targetPath
            $same = ($liveSha.Length -gt 0) -and ($liveSha -eq $backupSha)
            $entry.reVerifiedFiles += [pscustomobject]@{
                file = $origTargets[$origName]; backupSha256 = $backupSha; liveSha256 = $liveSha; match = $same
            }
            if (-not $same) {
                $entry.problems += ($origTargets[$origName] + ' does NOT match its pre-round backup on disk now')
                $failed.Add($roundDir + ': ' + $origTargets[$origName] + ' is not back to the pre-round bytes') | Out-Null
            }
        }
        if (@($entry.reVerifiedFiles).Count -eq 0) {
            $entry.problems += 'no *.orig backup was found, so "restored" cannot be re-verified for this round'
            $failed.Add($roundDir + ': no .orig backups to re-verify against') | Out-Null
        }
    }
    $reports.Add([pscustomobject]$entry) | Out-Null
}

# ---- slots: two rounds must not claim the same one -------------------------
$distinctSlots = @($slotNames | Sort-Object -Unique)
if ($slotNames.Count -ne $distinctSlots.Count) {
    $failed.Add('two rounds report the same slot: ' + ($slotNames -join ',')) | Out-Null
}
if ($ExpectSlots.Count -gt 0) {
    # Same normalisation as -RoundEvidence: a single 'A,B' argument must mean two slots, not one named 'A,B'.
    $expectedList = New-Object System.Collections.Generic.List[string]
    foreach ($item in $ExpectSlots) {
        foreach ($part in ($item -split ',')) { if ($part.Trim().Length -gt 0) { $expectedList.Add($part.Trim()) | Out-Null } }
    }
    $expected = @($expectedList.ToArray() | Sort-Object -Unique)
    $missing = @($expected | Where-Object { $distinctSlots -notcontains $_ })
    $extra = @($distinctSlots | Where-Object { $expected -notcontains $_ })
    if ($missing.Count -gt 0) { $failed.Add('expected slot(s) missing: ' + ($missing -join ',')) | Out-Null }
    if ($extra.Count -gt 0) { $failed.Add('unexpected slot(s) present: ' + ($extra -join ',')) | Out-Null }
}

# ---- report ---------------------------------------------------------------
foreach ($entry in $reports) {
    Write-Output ('[slot] ' + $(if ($entry.slot.Length -gt 0) { $entry.slot } else { '<unknown>' }) + '  evidence=' + $entry.roundEvidence)
    Write-Output ('       gameDir=' + $(if ($entry.gameDir.Length -gt 0) { $entry.gameDir } else { '<unknown>' }))
    Write-Output ('       manifest=' + $entry.manifestPresent + ' restoreReport=' + $entry.restoreReportPresent + ' ROUND_RESTORE=OK:' + $entry.roundRestoreOk + ' recordedMatchLines=' + $entry.recordedMatchLines)
    foreach ($file in @($entry.reVerifiedFiles)) {
        Write-Output ('       re-verified ' + $file.file + ' match=' + $file.match + ' backup=' + $file.backupSha256.Substring(0, [Math]::Min(16, $file.backupSha256.Length)) + ' live=' + $file.liveSha256.Substring(0, [Math]::Min(16, $file.liveSha256.Length)))
    }
    foreach ($problem in @($entry.problems)) { Write-Output ('       PROBLEM: ' + $problem) }
}

$verdict = if ($failed.Count -eq 0) { 'PASS' } else { 'FAIL' }
Write-Output ('PARALLEL_RESTORE_SLOTS=' + ($distinctSlots -join ','))
if ($failed.Count -gt 0) { Write-Output ('PARALLEL_RESTORE_FAILED=' + ($failed -join ' ; ')) }
Write-Output ('PARALLEL_RESTORE_VERDICT=' + $verdict)

if ($Evidence.Length -gt 0) {
    New-Item -ItemType Directory -Force -Path $Evidence | Out-Null
    $payload = [ordered]@{
        generatedAt = (Get-Date).ToString('o')
        verdict = $verdict
        slots = $distinctSlots
        expectedSlots = @($ExpectSlots)
        failed = $failed.ToArray()
        # .ToArray(), never @(<List[object]>): PowerShell throws 'Argument types do not match' for that.
        rounds = $reports.ToArray()
    }
    $jsonPath = Join-Path $Evidence 'parallel-restore-verify.json'
    $payload | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
    Write-Output ('PARALLEL_RESTORE_REPORT=' + $jsonPath)
}
if ($Json) {
    ([ordered]@{ verdict = $verdict; slots = $distinctSlots; failed = $failed.ToArray(); rounds = $reports.ToArray() } | ConvertTo-Json -Depth 8)
}
if ($verdict -eq 'PASS') { exit 0 }
exit 1
