# SPDX-License-Identifier: GPL-3.0-or-later
# assert-no-round.ps1 -- GATE: fail unless the machine is free for exclusivity-sensitive work.
#
# WHY THIS EXISTS (2026-09-26/27, twice):
#   1. I edited tools/run-bounded.ps1 while a real round was running. The check was written as an
#      INFORMATION LINE at the top of a larger command, so it printed "a round is running" and the
#      command carried on and made the edit anyway.
#   2. I then ran two gradle builds without any such check at all; one of them overlapped a live round
#      (its own output printed `real rounds = 1` before proceeding).
#   The lesson, stated once: A CHECK THAT DOES NOT BLOCK IS NOT A CHECK. So this is a separate command
#   with an EXIT CODE, meant to be run immediately before the action it guards, not inlined:
#
#       pwsh tools/assert-no-round.ps1                        # before editing a runner: 0 = go
#       pwsh tools/assert-no-round.ps1 -What gradle-build      # before any gradle build
#
# JUDGEMENT IS BY COMMAND LINE, NOT BY "is there any java": a teammate's Gradle build or daemon is java
# but is NOT a round. A game round is a JVM whose command line mentions BootstrapLauncher / forgeclient /
# net.minecraft, or a visible Minecraft window.
#
# -What gradle-build additionally requires that nobody holds the team's Gradle lock, because two
# concurrent builds in one warmed user home contend. The lock path is an ARGUMENT or an environment
# variable (MODTEST_GRADLE_LOCK) -- no machine path is hardcoded here; with neither set, the lock is not
# checked and that is stated in the output rather than silently assumed.
#
# ASCII only. Usage: pwsh tools/assert-no-round.ps1 [-What edit-runner|gradle-build] [-GradleLock <dir>] [-Quiet]
# Exit: 0 clear to proceed | 1 blocked (round running, or the gradle lock is held).

[CmdletBinding()]
param(
    [ValidateSet('edit-runner', 'gradle-build')][string]$What = 'edit-runner',
    [string]$GradleLock = '',
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'

if ($GradleLock.Length -eq 0 -and $env:MODTEST_GRADLE_LOCK) { $GradleLock = $env:MODTEST_GRADLE_LOCK }

$rounds = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -in @('java.exe', 'javaw.exe') -and [string]$_.CommandLine -match 'BootstrapLauncher|forgeclient|net\.minecraft' })
$windows = @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -match 'Minecraft' })
$lockHeld = ($GradleLock.Length -gt 0) -and (Test-Path -LiteralPath $GradleLock)

if (-not $Quiet) {
    Write-Output ('ASSERT-NO-ROUND at ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  what=' + $What)
    Write-Output ('  game JVMs (BootstrapLauncher/forgeclient) = ' + $rounds.Count)
    foreach ($round in $rounds) {
        Write-Output ('    pid={0} started={1}' -f $round.ProcessId, $round.CreationDate)
        Write-Output ('      ' + ([string]$round.CommandLine).Substring(0, [Math]::Min(160, ([string]$round.CommandLine).Length)))
    }
    Write-Output ('  Minecraft windows = ' + $windows.Count)
    foreach ($window in $windows) { Write-Output ('    pid={0} title={1}' -f $window.Id, $window.MainWindowTitle) }
    if ($What -eq 'gradle-build') {
        if ($GradleLock.Length -gt 0) { Write-Output ('  gradle lock = ' + $GradleLock + '  held=' + $lockHeld) }
        else { Write-Output '  gradle lock = NOT CONFIGURED (pass -GradleLock or set MODTEST_GRADLE_LOCK); only the round check applies' }
    }
}

if ($rounds.Count -gt 0 -or $windows.Count -gt 0) {
    if ($What -eq 'gradle-build') {
        Write-Output 'ASSERT-NO-ROUND=BLOCKED: a real round is running -- do NOT start a gradle build (it is CPU/RAM/disk noise the round did not ask for); tell Lead and wait.'
    } else {
        Write-Output 'ASSERT-NO-ROUND=BLOCKED: a real round is running -- do NOT edit tools/run-bounded.ps1; tell Lead and wait.'
    }
    exit 1
}
if ($What -eq 'gradle-build' -and $lockHeld) {
    Write-Output ('ASSERT-NO-ROUND=BLOCKED: the team gradle lock is held (' + $GradleLock + ') -- another build is in progress; wait for it.')
    exit 1
}
if ($What -eq 'gradle-build') {
    Write-Output 'ASSERT-NO-ROUND=OK: no real round and no gradle lock holder; a build may proceed (take the lock first).'
} else {
    Write-Output 'ASSERT-NO-ROUND=OK: no real round; a runner edit may proceed (re-run this immediately before each edit).'
}
exit 0
