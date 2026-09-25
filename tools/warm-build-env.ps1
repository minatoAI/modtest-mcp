# SPDX-License-Identifier: GPL-3.0-or-later
# warm-build-env.ps1 -- make a FRESH checkout (git worktree / second clone) buildable, idempotently.
#
# WHY: the project wants to run things in parallel, which means building in more than one directory.
# A fresh checkout does NOT build out of the box, for three concrete and measured reasons (2026-09-26/27,
# taclight 0.11.0):
#   1. TWO jars under libs/ are NOT in git (`libs/*.jar` is gitignored; only 3 of 5 jars are tracked):
#      `freecam-forge-1.2.1+1.20.jar` (75,389 B) and
#      `player-animation-lib-forge-1.0.2-rc1+1.20.jar` (181,437 B).
#   2. THREE ForgeGradle intermediates live under the gitignored `build/` and are not produced from
#      scratch in a fresh directory:
#      `build/createSrgToMcp/output.srg` (20,871,086 B), `build/extractSrg/output.srg` (4,558,114 B),
#      `build/createMcpToSrg/output.tsrg` (9,129,252 B).
#   3. Gradle must be pointed at a WARMED user home (`-g <tree>/.gradle-user-home`). Without it the
#      deobfuscation cache is missing and the build fails with
#      `Error getting artifact: blank:tacz:1.1.8-hotfix_mapped_official_1.20.1 ... from DeobfuscatingRepo`.
#
# PLUS ONE TRAP THAT LOOKS LIKE A MISSING PREREQUISITE BUT IS NOT (measured 2026-09-27):
#   Gradle takes its PROJECT DIRECTORY from the CURRENT WORKING DIRECTORY, not from the location of the
#   `gradlew.bat` you invoked. Run the new directory's wrapper from another project's shell and Gradle
#   builds THAT project: it prints BUILD SUCCESSFUL, exits 0, and the new directory still has no jar
#   ("gradle exited 0 and produced nothing" -- which is exactly how an earlier attempt was recorded as
#   an unattributed failure). This script therefore always runs the build FROM the target directory.
#
# THIS SCRIPT IS IDEMPOTENT AND FAILS LOUD:
#   * every copy is skipped when the destination already matches (same size and SHA256);
#   * a required source artifact that is missing is REPORTED, not skipped -- exit 2, never a silent pass;
#   * `-Check` inspects only and changes nothing;
#   * `-RunBuild` additionally runs `jar --offline --rerun-tasks --no-daemon` and, with `-ExpectJarSha`,
#     verifies the produced jar byte-for-byte. The build log is captured to a FILE and its size is
#     checked -- an empty redirected log is exactly what made an earlier failure unattributable.
#
# The paths of the two trees are arguments: nothing about a particular machine is hardcoded here.
#
# ASCII only. Usage:
#   pwsh tools/warm-build-env.ps1 -TargetDir <fresh checkout> -SourceTree <warmed tree> [-Check]
#        [-GradleUserHome <dir>] [-RunBuild] [-ExpectJarSha <sha256>] [-GradleTask jar]
# Exit: 0 warmed (and, with -RunBuild, built as expected) | 1 build/jar mismatch | 2 missing prerequisite
#       / bad parameters.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$TargetDir,
    [Parameter(Mandatory = $true)][string]$SourceTree,
    [string]$GradleUserHome = '',
    # ForgeGradle intermediates that a fresh `build/` does not contain. Paths are relative to a tree root.
    [string[]]$SrgArtifacts = @('build\createSrgToMcp\output.srg', 'build\extractSrg\output.srg', 'build\createMcpToSrg\output.tsrg'),
    [switch]$RunBuild,
    [string]$ExpectJarSha = '',
    [string]$GradleTask = 'jar',
    [string]$BuildLog = '',
    [switch]$Json,
    [switch]$Check
)

$ErrorActionPreference = 'Stop'

$EXIT_OK = 0
$EXIT_BUILD_MISMATCH = 1
$EXIT_MISSING = 2

function Get-Sha256OrEmpty([string]$Path) {
    if (Test-Path -LiteralPath $Path -PathType Leaf) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
    return ''
}

function Get-RelativeDisplay([string]$Path) {
    return ($Path -replace '^[A-Za-z]:\\', '')
}

# ---------------------------------------------------------------- validate the two trees
if (-not (Test-Path -LiteralPath $TargetDir -PathType Container)) { Write-Output ('ERROR: -TargetDir is not a directory: ' + $TargetDir); exit $EXIT_MISSING }
if (-not (Test-Path -LiteralPath $SourceTree -PathType Container)) { Write-Output ('ERROR: -SourceTree is not a directory: ' + $SourceTree); exit $EXIT_MISSING }
$TargetDir = (Resolve-Path -LiteralPath $TargetDir).Path
$SourceTree = (Resolve-Path -LiteralPath $SourceTree).Path
if ($TargetDir -eq $SourceTree) { Write-Output 'ERROR: -TargetDir and -SourceTree are the same directory; there is nothing to warm'; exit $EXIT_MISSING }

# A checkout is recognised by its build entry points. Refusing early beats copying into the wrong place.
foreach ($marker in @('gradlew.bat', 'build.gradle')) {
    if (-not (Test-Path -LiteralPath (Join-Path $TargetDir $marker))) {
        Write-Output ('ERROR: -TargetDir does not look like a checkout (no ' + $marker + '): ' + $TargetDir)
        exit $EXIT_MISSING
    }
}

$copied = New-Object System.Collections.Generic.List[string]
$skipped = New-Object System.Collections.Generic.List[string]
$missing = New-Object System.Collections.Generic.List[string]

Write-Output ('target=' + (Get-RelativeDisplay $TargetDir))
Write-Output ('source=' + (Get-RelativeDisplay $SourceTree))
Write-Output ('mode=' + $(if ($Check) { 'check-only' } else { 'warm' }))

# ---------------------------------------------------------------- 1. un-versioned libs/*.jar
$sourceLibs = Join-Path $SourceTree 'libs'
$targetLibs = Join-Path $TargetDir 'libs'
if (-not (Test-Path -LiteralPath $sourceLibs -PathType Container)) {
    $missing.Add('libs/ (directory not present in the source tree)') | Out-Null
} else {
    if (-not (Test-Path -LiteralPath $targetLibs -PathType Container) -and -not $Check) {
        New-Item -ItemType Directory -Force -Path $targetLibs | Out-Null
    }
    foreach ($sourceJar in @(Get-ChildItem -LiteralPath $sourceLibs -File -Filter '*.jar' -ErrorAction SilentlyContinue)) {
        $targetJar = Join-Path $targetLibs $sourceJar.Name
        $sourceSha = Get-Sha256OrEmpty $sourceJar.FullName
        $targetSha = Get-Sha256OrEmpty $targetJar
        if ($targetSha -eq $sourceSha) {
            $skipped.Add(('libs/' + $sourceJar.Name)) | Out-Null
            continue
        }
        if ($Check) {
            $copied.Add(('WOULD COPY libs/' + $sourceJar.Name)) | Out-Null
            continue
        }
        Copy-Item -LiteralPath $sourceJar.FullName -Destination $targetJar -Force
        $afterSha = Get-Sha256OrEmpty $targetJar
        if ($afterSha -ne $sourceSha) { $missing.Add(('libs/' + $sourceJar.Name + ' (copy did not verify)')) | Out-Null }
        else { $copied.Add(('libs/' + $sourceJar.Name)) | Out-Null }
    }
}

# ---------------------------------------------------------------- 2. SRG / mapping intermediates
foreach ($relative in $SrgArtifacts) {
    $sourcePath = Join-Path $SourceTree $relative
    $targetPath = Join-Path $TargetDir $relative
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        # Reported, never silently skipped: this is the prerequisite that a fresh `createSrgToMcp` did
        # not produce, and a build that "succeeds" without it produces nothing.
        $missing.Add(($relative + ' (not present in the source tree -- warm that tree first)')) | Out-Null
        continue
    }
    $sourceSha = Get-Sha256OrEmpty $sourcePath
    $targetSha = Get-Sha256OrEmpty $targetPath
    if ($targetSha -eq $sourceSha) { $skipped.Add($relative) | Out-Null; continue }
    if ($Check) { $copied.Add(('WOULD COPY ' + $relative)) | Out-Null; continue }
    $parent = Split-Path -Parent $targetPath
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    Copy-Item -LiteralPath $sourcePath -Destination $targetPath -Force
    if ((Get-Sha256OrEmpty $targetPath) -ne $sourceSha) { $missing.Add(($relative + ' (copy did not verify)')) | Out-Null }
    else { $copied.Add($relative) | Out-Null }
}

# ---------------------------------------------------------------- 3. gradle user home
if ($GradleUserHome.Length -eq 0) { $GradleUserHome = Join-Path $SourceTree '.gradle-user-home' }
$gradleHomeExists = Test-Path -LiteralPath $GradleUserHome -PathType Container
if (-not $gradleHomeExists) {
    $missing.Add(('gradle user home not found: ' + (Get-RelativeDisplay $GradleUserHome) + ' (warm it, or pass -GradleUserHome)')) | Out-Null
}

Write-Output ''
Write-Output ('copied=' + $copied.Count + ' skippedAlreadyMatching=' + $skipped.Count + ' missing=' + $missing.Count)
foreach ($item in $copied) { Write-Output ('  copy: ' + $item) }
foreach ($item in $missing) { Write-Output ('  MISSING: ' + $item) }

$gradlew = Join-Path $TargetDir 'gradlew.bat'
$buildArgs = @($GradleTask, '--offline', '--rerun-tasks', '--no-daemon', '-g', $GradleUserHome, '--console=plain')
$buildCommand = ('"{0}" {1}' -f $gradlew, ($buildArgs -join ' '))
Write-Output ('gradle: ' + $buildCommand)

if ($missing.Count -gt 0) {
    Write-Output 'WARM_VERDICT=FAIL:missing-prerequisites'
    Write-Output 'REASON=the list above is incomplete; nothing was guessed and nothing was silently skipped'
    if ($Json) { ([ordered]@{ ok = $false; target = $TargetDir; source = $SourceTree; copied = $copied.ToArray(); skipped = $skipped.ToArray(); missing = $missing.ToArray(); gradleCommand = $buildCommand } | ConvertTo-Json -Depth 5) }
    exit $EXIT_MISSING
}
if ($Check) {
    Write-Output ('WARM_VERDICT=CHECK-OK:would-copy=' + $copied.Count)
    if ($Json) { ([ordered]@{ ok = $true; checkOnly = $true; target = $TargetDir; source = $SourceTree; wouldCopy = $copied.ToArray(); skipped = $skipped.ToArray(); gradleCommand = $buildCommand } | ConvertTo-Json -Depth 5) }
    exit $EXIT_OK
}

if (-not $RunBuild) {
    Write-Output 'WARM_VERDICT=WARMED'
    Write-Output 'NOTE=run the gradle command above to build (or re-run this script with -RunBuild)'
    if ($Json) { ([ordered]@{ ok = $true; checkOnly = $false; target = $TargetDir; source = $SourceTree; copied = $copied.ToArray(); skipped = $skipped.ToArray(); gradleCommand = $buildCommand } | ConvertTo-Json -Depth 5) }
    exit $EXIT_OK
}

# ---------------------------------------------------------------- 4. optionally build and verify
if ($BuildLog.Length -eq 0) { $BuildLog = Join-Path $TargetDir 'warm-build.log' }
Write-Output ('building; log -> ' + (Get-RelativeDisplay $BuildLog))
# RUN THE BUILD FROM THE TARGET DIRECTORY. This is not cosmetic: `gradlew.bat` starts a JVM that takes
# its PROJECT DIRECTORY from the CURRENT WORKING DIRECTORY, not from the wrapper's own location. Invoked
# from somewhere else it happily builds THAT project, exits 0, and leaves no jar in the directory you
# asked about -- which is precisely the "gradle exited 0, no jar, cause unattributed" symptom. Measured
# 2026-09-27: invoked from the workspace repo it built that repo's `spotviz` project and wrote
# `ray-traced-spotlight-mod-dev/build/libs/spotviz-0.1.0.jar` instead.
Push-Location -LiteralPath $TargetDir
try {
    $buildOutput = @(& $gradlew @buildArgs 2>&1 | ForEach-Object { [string]$_ })
    $buildExit = $LASTEXITCODE
} finally {
    Pop-Location
}
[System.IO.File]::WriteAllLines($BuildLog, $buildOutput, (New-Object System.Text.UTF8Encoding($false)))
$logBytes = (Get-Item -LiteralPath $BuildLog).Length
Write-Output ('build exit=' + $buildExit + ' logBytes=' + $logBytes)
Write-Output '--- last 15 log lines ---'
$buildOutput | Select-Object -Last 15 | ForEach-Object { Write-Output ('  ' + $_) }
if ($logBytes -eq 0) {
    # An empty log is the failure mode that was previously unattributable: say so explicitly.
    Write-Output 'WARM_VERDICT=FAIL:empty-build-log'
    Write-Output 'REASON=the build produced no output at all; do not conclude anything about the cause from this run'
    exit $EXIT_BUILD_MISMATCH
}
if ($buildExit -ne 0) {
    Write-Output ('WARM_VERDICT=FAIL:gradle-exit-' + $buildExit)
    exit $EXIT_BUILD_MISMATCH
}

$jars = @(Get-ChildItem -LiteralPath (Join-Path $TargetDir 'build\libs') -File -Filter '*.jar' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime)
if ($jars.Count -eq 0) {
    Write-Output 'WARM_VERDICT=FAIL:no-jar-produced'
    Write-Output ('REASON=gradle exited 0 but build/libs contains no jar in ' + (Get-RelativeDisplay $TargetDir))
    exit $EXIT_BUILD_MISMATCH
}
$builtJar = $jars[$jars.Count - 1]
$builtSha = (Get-FileHash -LiteralPath $builtJar.FullName -Algorithm SHA256).Hash
Write-Output ('built=' + $builtJar.Name + ' bytes=' + $builtJar.Length + ' sha256=' + $builtSha)
if ($ExpectJarSha.Length -gt 0) {
    if ($builtSha -ne $ExpectJarSha.ToUpperInvariant()) {
        Write-Output ('WARM_VERDICT=FAIL:sha-mismatch expected=' + $ExpectJarSha.ToUpperInvariant() + ' got=' + $builtSha)
        exit $EXIT_BUILD_MISMATCH
    }
    Write-Output 'WARM_VERDICT=BUILD-OK:sha-matches'
} else {
    Write-Output 'WARM_VERDICT=BUILD-OK:sha-not-checked'
}
if ($Json) {
    ([ordered]@{
            ok = $true; target = $TargetDir; source = $SourceTree
            copied = $copied.ToArray(); skipped = $skipped.ToArray()
            gradleCommand = $buildCommand; buildExit = $buildExit; logBytes = $logBytes
            jar = $builtJar.Name; jarBytes = $builtJar.Length; jarSha256 = $builtSha; expectedSha256 = $ExpectJarSha
        } | ConvertTo-Json -Depth 5)
}
exit $EXIT_OK
