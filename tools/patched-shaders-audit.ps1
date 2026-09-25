# patched-shaders-audit.ps1 -- audit the FINAL shader source that Iris/Oculus actually compiled.
#
# WHY THIS EXISTS
#   With `enableDebugOptions=true` in config/oculus.properties, Iris writes EVERY shader it compiled
#   -- after #include expansion, macro substitution and any mod injection -- into
#   <gameDir>/patched_shaders/. That directory is the ground truth for "what the shader pipeline
#   really ran": no GPU capture, no F12, and compiler error line numbers refer to exactly these files.
#   Before this tool, the same information was dug out of an 8 MB RenderDoc XML by hand.
#
# WHAT IT CHECKS (mod-agnostic -- patterns are supplied by the caller)
#   1. the dump exists and is non-empty;
#   2. includes are expanded: residual '#include' occurrences <= -MaxIncludeResidual (default 0);
#   3. every -RequirePattern appears in at least -MinFiles files;
#   4. every -ForbidPattern appears in 0 files (use it to prove an OLD implementation is gone);
#   5. writes a manifest (path/bytes/sha256) + a dump fingerprint into the evidence dir, so two runs
#      can be compared ("same name != same artifact").
#
# NOTE ON INJECTION MARKERS: Iris strips comments while transforming, so a mod's marker comment may
# be absent from the dumped file even when its code is present. Assert on CODE CONTENT, not markers.
#
# NOTE ON ARGUMENT PASSING: patterns are SEMICOLON-SEPARATED SINGLE STRINGS on purpose. `pwsh -File`
# flattens an array argument into separate argv tokens (the same trap documented in run-round.ps1),
# which silently hands a pattern to the next parameter. Semicolon strings are immune to that.
#
# ASCII only. Usage:
#   pwsh patched-shaders-audit.ps1 -ShadersDir <gameDir>\patched_shaders -Evidence <dir> `
#        -Require 'taclight_vox_transmit;code >= 4.0' -MinFiles 1
#   pwsh patched-shaders-audit.ps1 ... -Forbid '/4u'          # prove an OLD implementation is gone
# Exit: 0 = all assertions passed, 1 = an assertion failed, 2 = usage/IO error.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ShadersDir,
    [Parameter(Mandatory = $true)][string]$Evidence,
    [string]$Require = '',
    [string]$Forbid = '',
    [int]$MinFiles = 1,
    [int]$MaxIncludeResidual = 0,
    [string]$Label = 'patched-shaders'
)

$ErrorActionPreference = 'Stop'
$failures = New-Object System.Collections.Generic.List[string]

# 分号分隔 → 模式数组(见头部 NOTE:pwsh -File 会拍平数组参数)
$RequirePattern = @()
if ($Require.Trim().Length -gt 0) {
    $RequirePattern = @($Require -split ';' | ForEach-Object { $_.Trim() } | Where-Object { $_.Length -gt 0 })
}
$ForbidPattern = @()
if ($Forbid.Trim().Length -gt 0) {
    $ForbidPattern = @($Forbid -split ';' | ForEach-Object { $_.Trim() } | Where-Object { $_.Length -gt 0 })
}

function Assert([bool]$ok, [string]$what) {
    if ($ok) {
        Write-Output ('  PASS ' + $what)
    } else {
        Write-Output ('  FAIL ' + $what)
        $failures.Add($what)
    }
}

if (-not (Test-Path -LiteralPath $ShadersDir)) {
    Write-Output ('[psa] FAIL dump 目录不存在: ' + $ShadersDir)
    exit 2
}
$files = @(Get-ChildItem -LiteralPath $ShadersDir -Recurse -File | Sort-Object FullName)
if ($files.Count -eq 0) {
    Write-Output ('[psa] FAIL dump 目录为空: ' + $ShadersDir)
    exit 2
}
New-Item -ItemType Directory -Force -Path $Evidence | Out-Null

Write-Output ('[psa] dump=' + $ShadersDir + '  files=' + $files.Count)

# ---- manifest + fingerprints (name != artifact: hash what we actually audited) ----
# TWO fields, because they answer two different questions (task-43):
#   fingerprintRaw        -- sha256 of the manifest of RAW per-file hashes. Any byte change moves it:
#                            "was this artifact modified?" Use for provenance/integrity of a dump.
#                            It is ROUND-SENSITIVE: the .properties files are rewritten every round with a
#                            new date header, so this value MUST NOT be used for a same-package judgment.
#   fingerprintNormalized -- the same manifest idea, but each file's hash is taken over the NORMALIZED
#                            text (normalizeForDigest: date headers removed from *.properties), reusing the
#                            single PowerShell implementation of the mod's rule (tools/pack-fingerprint.ps1)
#                            so the harness and the mod cannot disagree again. This is the field for
#                            "are these two artifacts the same package?".
# tools/fingerprint-parity.test.ps1 feeds a fixed corpus to this rule and to the SHIPPED Java class and
# compares them case by case; the two implementations drifting apart is exactly what went wrong on
# 2026-09-27 (harness said 01555353... / E72BEC56..., the mod said equal).
$packFingerprintPath = Join-Path $PSScriptRoot 'pack-fingerprint.ps1'
if (-not (Test-Path -LiteralPath $packFingerprintPath -PathType Leaf)) {
    Write-Output ('ERROR: tools/pack-fingerprint.ps1 is required for fingerprintNormalized: ' + $packFingerprintPath)
    exit 2
}
. $packFingerprintPath

$manifest = New-Object System.Collections.Generic.List[string]
$manifestNormalized = New-Object System.Collections.Generic.List[string]
$texts = @{}
$totalBytes = 0L
foreach ($f in $files) {
    $rel = $f.FullName.Substring($ShadersDir.Length).TrimStart('\', '/')
    $sha = (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash
    $manifest.Add(('{0}  {1}  {2}' -f $rel, $f.Length, $sha))
    $totalBytes += $f.Length
    $rawText = [System.IO.File]::ReadAllText($f.FullName)
    $texts[$rel] = $rawText
    # The normalized hash is taken over the normalized TEXT (UTF-8 bytes), exactly like the mod side.
    $normalizedText = Get-PackNormalizedText -RelPath $rel -Text $rawText
    $normalizedSha = (Get-PackSha256Prefix16 -Text $normalizedText)
    $manifestNormalized.Add(('{0}  {1}' -f $rel, $normalizedSha))
}
$manifestPath = Join-Path $Evidence ($Label + '-manifest.txt')
$manifest | Set-Content -LiteralPath $manifestPath -Encoding UTF8
$fingerprintRaw = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash
$manifestNormalizedPath = Join-Path $Evidence ($Label + '-manifest-normalized.txt')
$manifestNormalized | Set-Content -LiteralPath $manifestNormalizedPath -Encoding UTF8
$fingerprintNormalized = (Get-FileHash -LiteralPath $manifestNormalizedPath -Algorithm SHA256).Hash
Write-Output ('[psa] totalBytes=' + $totalBytes + '  fingerprintRaw=' + $fingerprintRaw + '  fingerprintNormalized=' + $fingerprintNormalized)

# ---- assertion 1: dump non-empty ----
Assert ($files.Count -gt 0) ('dump 非空(' + $files.Count + ' 个文件)')

# ---- assertion 2: includes expanded ----
$includeCount = 0
foreach ($rel in $texts.Keys) {
    $includeCount += ([regex]::Matches($texts[$rel], '(?m)^\s*#include')).Count
}
Assert ($includeCount -le $MaxIncludeResidual) (
    '#include 残留=' + $includeCount + ' <= ' + $MaxIncludeResidual + '(展开后的最终源码)')

# ---- assertion 3/4: required / forbidden patterns ----
$patternResults = New-Object System.Collections.Generic.List[object]
foreach ($p in $RequirePattern) {
    $hit = @()
    foreach ($rel in $texts.Keys) {
        if ($texts[$rel].Contains($p)) { $hit += $rel }
    }
    $ok = ($hit.Count -ge $MinFiles)
    Assert $ok ('必需模式 "' + $p + '" 命中 ' + $hit.Count + ' 个文件(要求 >= ' + $MinFiles + ')')
    if ($hit.Count -gt 0 -and $hit.Count -le 5) {
        Write-Output ('        命中: ' + ($hit -join ', '))
    }
    $patternResults.Add([pscustomobject]@{ pattern = $p; kind = 'require'; files = $hit.Count; ok = $ok })
}
foreach ($p in $ForbidPattern) {
    $hit = @()
    foreach ($rel in $texts.Keys) {
        if ($texts[$rel].Contains($p)) { $hit += $rel }
    }
    $ok = ($hit.Count -eq 0)
    Assert $ok ('禁止模式 "' + $p + '" 命中 ' + $hit.Count + ' 个文件(要求 0)')
    $patternResults.Add([pscustomobject]@{ pattern = $p; kind = 'forbid'; files = $hit.Count; ok = $ok })
}

# ---- report ----
$report = [pscustomobject]@{
    shadersDir       = $ShadersDir
    fileCount        = $files.Count
    totalBytes       = $totalBytes
    # ROUND-SENSITIVE: raw bytes of the audited dump. Any change moves it; NOT a same-package judgment.
    fingerprintRaw   = $fingerprintRaw
    # Same-package judgment: date headers of *.properties removed with the mod's own rule.
    fingerprintNormalized = $fingerprintNormalized
    # DEPRECATED alias kept for existing readers: it is the RAW value. Archived reports carry this name
    # with raw semantics, so anything citing them must say so (see the README's raw-fingerprint list).
    manifestSha256   = $fingerprintRaw
    manifestPath     = $manifestPath
    manifestNormalizedPath = $manifestNormalizedPath
    includeResidual  = $includeCount
    requirePatterns  = $RequirePattern
    forbidPatterns   = $ForbidPattern
    minFiles         = $MinFiles
    patterns         = $patternResults
    failures         = $failures
    verdict          = if ($failures.Count -eq 0) { 'PASS' } else { 'FAIL' }
}
$jsonPath = Join-Path $Evidence ($Label + '-report.json')
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
Write-Output ('[psa] report=' + $jsonPath)

if ($failures.Count -eq 0) {
    Write-Output ('[psa] VERDICT=PASS  ' + $files.Count + ' 文件 / ' + $totalBytes + ' bytes')
    exit 0
}
Write-Output ('[psa] VERDICT=FAIL  失败 ' + $failures.Count + ' 项:')
foreach ($f in $failures) { Write-Output ('   - ' + $f) }
exit 1
