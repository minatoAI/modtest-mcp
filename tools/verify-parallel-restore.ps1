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
    # Optional: for rounds written with -RunTag, whose files live in round-setup\<tag>\.
    [string]$RunTag = '',
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

# Which manifest field proves run-round.ps1 CHANGED which file (it records a before-sha for every file it
# touches). This is what makes "expected set" derivable instead of guessed.
$managedShaFields = [ordered]@{
    'optionsBeforeSha256'            = 'options.txt'
    'taclightClientTomlBeforeSha256' = 'config\taclight-client.toml'
    'oculusBeforeSha256'             = 'config\oculus.properties'
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
        setupDir = ''
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
    # -RunTag rounds keep the same file names inside round-setup\<tag>\ (task-38). Hardcoding
    # 'round-setup\' made every tagged round unverifiable -- and 6aa016e recommends tagging precisely for
    # rounds that share one evidence root, so the two features have to agree.
    $setupDir = Join-Path $roundDir 'round-setup'
    if ($RunTag.Length -gt 0) { $setupDir = Join-Path $setupDir $RunTag }
    $entry.setupDir = $setupDir
    $manifestPath = Join-Path $setupDir 'setup-manifest.txt'
    $reportPath = Join-Path $setupDir 'restore-report.txt'

    # ---- 1. provenance -----------------------------------------------------
    $entry.manifestPresent = Test-Path -LiteralPath $manifestPath -PathType Leaf
    if (-not $entry.manifestPresent) {
        $entry.problems += ('no ' + (Split-Path -Leaf $setupDir) + '\setup-manifest.txt: this round did not go through run-round.ps1, so it had no transient setup and no teardown (the bypass case)')
        $failed.Add($roundDir + ': no setup-manifest.txt (bypass)') | Out-Null
    }
    $entry.gameDir = Get-ManifestValue -ManifestPath $manifestPath -Key 'gameDir'
    $entry.manifestSha256 = Get-Sha256 $manifestPath

    # ---- 2. recorded verdict, BOUND to this manifest ----------------------
    # "A report says ROUND_RESTORE=OK" is not evidence that anything was restored: a leftover report from an
    # earlier round, or one written by a driver that never ran the setup, would satisfy it. So the report
    # must name the SAME gameDir and the SAME setup-manifest bytes as the round we are looking at.
    $entry.restoreReportPresent = Test-Path -LiteralPath $reportPath -PathType Leaf
    $reportText = ''
    if ($entry.restoreReportPresent) { $reportText = [System.IO.File]::ReadAllText($reportPath) }
    else {
        $entry.problems += ('no ' + (Split-Path -Leaf $setupDir) + '\restore-report.txt: nothing proves the game directory was put back')
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
        # binding 1: the report must name the round's game directory
        $reportGameDir = ''
        if ($reportText -match '(?m)^gameDir=(.+)$') { $reportGameDir = $Matches[1].Trim() }
        $entry.reportGameDir = $reportGameDir
        if ($reportGameDir.Length -eq 0) {
            $entry.problems += 'the restore report carries no gameDir= line: it is not bound to a round, so it cannot be evidence for THIS one (reports written before task-46 lack it)'
            $failed.Add($roundDir + ': report is not bound to a gameDir') | Out-Null
        } elseif ($entry.gameDir.Length -gt 0 -and $reportGameDir -ne $entry.gameDir) {
            $entry.problems += ('the report names a different game directory than the manifest: ' + $reportGameDir + ' vs ' + $entry.gameDir)
            $failed.Add($roundDir + ': report/manifest gameDir mismatch') | Out-Null
        }
        # binding 2: the report must be bound to the exact setup-manifest bytes (kills STALE evidence)
        $reportManifestSha = ''
        if ($reportText -match '(?m)^setupManifestSha256=([0-9A-Fa-f]+)\s*$') { $reportManifestSha = $Matches[1].Trim().ToUpperInvariant() }
        $entry.reportManifestSha256 = $reportManifestSha
        if ($reportManifestSha.Length -eq 0) {
            $entry.problems += 'the restore report carries no setupManifestSha256= line, so it cannot be tied to this round''s manifest (reports written before task-46 lack it)'
            $failed.Add($roundDir + ': report is not bound to the manifest') | Out-Null
        } elseif ($entry.manifestSha256.Length -gt 0 -and $reportManifestSha -ne $entry.manifestSha256) {
            $entry.problems += 'the report was written against a DIFFERENT setup-manifest (stale evidence)'
            $failed.Add($roundDir + ': stale report (manifest sha mismatch)') | Out-Null
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
        # WHICH files must be covered comes from the round's own manifest: run-round.ps1 records a
        # before-sha field for every file it changed. Iterating only over the *.orig files that happen to
        # exist let the reviewer's scenario D through -- a round that restored ONE of three files looked
        # clean. The expected set is therefore derived, not discovered.
        $managed = New-Object System.Collections.Generic.List[string]
        $managedBefore = @{}
        foreach ($field in $managedShaFields.Keys) {
            $expectedSha = (Get-ManifestValue -ManifestPath $manifestPath -Key $field).Trim().ToUpperInvariant()
            if ($expectedSha.Length -gt 0) {
                $rel = $managedShaFields[$field]
                $managed.Add($rel) | Out-Null
                $managedBefore[$rel] = $expectedSha
            }
        }
        $entry.managedFiles = @($managed.ToArray())
        if ($managed.Count -eq 0) {
            # -SkipOptionsSetup rounds change nothing, so there is nothing to restore: say so instead of
            # pretending a vacuous check passed.
            $entry.problems += 'the manifest lists NO managed file (a -SkipOptionsSetup round changes nothing), so this round had nothing to restore'
        }
        foreach ($relTarget in @($managed.ToArray())) {
            $origName = (Split-Path -Leaf $relTarget) + '.orig'
            $backupPath = Join-Path $setupDir $origName
            $targetPath = Join-Path $entry.gameDir $relTarget
            if (-not (Test-Path -LiteralPath $backupPath -PathType Leaf)) {
                $entry.problems += ($relTarget + ' was changed by the round but has NO ' + $origName + ' backup, so it cannot be re-verified')
                $failed.Add($roundDir + ': ' + $relTarget + ' has no .orig backup (expected-set coverage)') | Out-Null
                $entry.reVerifiedFiles += [pscustomobject]@{ file = $relTarget; backupSha256 = ''; liveSha256 = ''; match = $false }
                continue
            }
            $backupSha = Get-Sha256 $backupPath
            $liveSha = Get-Sha256 $targetPath
            $same = ($liveSha.Length -gt 0) -and ($liveSha -eq $backupSha)
            # THREE-WAY check (task-58). Comparing only live-vs-.orig is two-way: a FOREIGN .orig -- one left
            # behind by another round -- passes as long as live was put back to THAT file's bytes, and then
            # both sides agree while neither is this round's baseline. The manifest recorded what the file
            # looked like before THIS round, so the backup must equal it too.
            $expectedBefore = [string]$managedBefore[$relTarget]
            $backupIsBaseline = ($expectedBefore.Length -eq 0) -or ($backupSha -eq $expectedBefore)
            $entry.reVerifiedFiles += [pscustomobject]@{
                file = $relTarget; backupSha256 = $backupSha; liveSha256 = $liveSha; match = $same
                expectedBeforeSha256 = $expectedBefore; backupIsThisRoundsBaseline = $backupIsBaseline
            }
            if (-not $backupIsBaseline) {
                $entry.problems += ($origName + ' is NOT this round''s pre-round bytes: the backup disagrees with the manifest (a foreign or stale .orig)')
                $failed.Add($roundDir + ': ' + $origName + ' does not match the manifest BeforeSha256 (foreign .orig)') | Out-Null
            }
            if (-not $same) {
                $entry.problems += ($relTarget + ' does NOT match its pre-round backup on disk now')
                $failed.Add($roundDir + ': ' + $relTarget + ' is not back to the pre-round bytes') | Out-Null
            }
        }
        if ($managed.Count -gt 0) {
            # Any .orig present that the manifest does not mention is reported, not counted.
            $extra = @()
            foreach ($origName in $origTargets.Keys) {
                if ((Test-Path -LiteralPath (Join-Path $setupDir $origName) -PathType Leaf) -and ($managed -notcontains $origTargets[$origName])) { $extra += $origTargets[$origName] }
            }
            if ($extra.Count -gt 0) { $entry.problems += ('note: extra .orig backups not listed in the manifest: ' + ($extra -join ',')) }
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
    Write-Output ('       setupDir=' + $entry.setupDir)
    Write-Output ('       manifest=' + $entry.manifestPresent + ' restoreReport=' + $entry.restoreReportPresent + ' ROUND_RESTORE=OK:' + $entry.roundRestoreOk + ' recordedMatchLines=' + $entry.recordedMatchLines + ' managedFiles=' + (@($entry.managedFiles) -join ','))
    foreach ($file in @($entry.reVerifiedFiles)) {
        Write-Output ('       re-verified ' + $file.file + ' match=' + $file.match + ' backupIsBaseline=' + $file.backupIsThisRoundsBaseline + ' backup=' + $file.backupSha256.Substring(0, [Math]::Min(16, $file.backupSha256.Length)) + ' live=' + $file.liveSha256.Substring(0, [Math]::Min(16, $file.liveSha256.Length)))
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
