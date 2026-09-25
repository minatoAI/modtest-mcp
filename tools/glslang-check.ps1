#requires -Version 7
<#
.SYNOPSIS
  宿主侧、模组无关的**离线 GLSL 校验门禁**（glslang standalone validator）。

.DESCRIPTION
  对给定目录下的 GLSL 做「不启动游戏」的语法/语义编译检查，输出 JSON + 人读报告 + 原文件清单
  （bytes + sha256），并支持与**基线**比对：只有出现"基线里没有的错误"才 FAIL。

  为什么需要它：注入到 Iris/Oculus 光影管线里的 GLSL，原先只能靠真机跑一轮（≈100-120 秒）
  才知道编译是否通过；而且编译失败可能被静默吞掉（画面正常、注入没生效）。
  离线校验把这一步压到**毫秒级**。

  设计要点（与 patched-shaders-audit.ps1 同一套纪律）：
   * 不改被测文件：校验在临时副本上进行，清单里的 sha256 永远算**原文件**；
   * 预处理注入是**显式且被记录**的（见 -Prelude），不是偷偷改；
   * 错误行号按注入行数**回正**，报告里的行号与真实源文件对齐；
   * 工具身份（exe 路径 + sha256 + --version）写进报告 ⇒「同名≠同产物」；
   * 基线缺失时**生成基线并退出 2**（强制人工过一眼），除非显式 -AcceptBaseline。

.PARAMETER ShadersDir
  待校验的 GLSL 目录（递归）。

.PARAMETER Evidence
  证据输出目录（将写入 glslang-report.json / .txt / glslang-manifest.txt）。

.PARAMETER Prelude
  分号分隔的**额外预处理行**，插入到每个文件的 `#version` 行之后。
  默认 `#extension GL_ARB_shading_language_420pack : enable`：
  Iris 的 glsl-transformer 只声明 `GL_ARB_shader_storage_buffer_object`，
  NVIDIA 驱动接受 `#version 410` 下的 `layout(binding=N) buffer`，
  但 glslang 严格要求 420pack ⇒ 不补这一行会把整类文件误报成失败（已实测）。

.PARAMETER StageMap
  分号分隔的 `扩展名=阶段` 映射；只有命中映射的文件才会被校验（`.glsl` 等 include 默认跳过）。

.PARAMETER Include
  分号分隔的 `-I` 头文件搜索目录（注意：patched_shaders 的 `#include` 残留为 0，通常不需要）。

.PARAMETER Baseline
  基线 JSON 路径。存在 ⇒ 逐文件比对错误签名；不存在 ⇒ 生成并退出 2。

.PARAMETER AcceptBaseline
  与 -Baseline 合用：基线不存在时生成后**继续**判定（仍按 MaxFail）。

.PARAMETER MinFiles
  至少要校验的文件数，低于此值视为 setup 失败（防止"目录写错 ⇒ 0 文件 ⇒ 假绿"）。

.PARAMETER MaxFail
  允许的失败文件数上限（默认 0）。

.OUTPUTS
  退出码：0 = PASS；1 = FAIL（编译失败或出现基线外新错误）；2 = setup 失败 / 基线缺失。

.EXAMPLE
  pwsh -File tools/glslang-check.ps1 -ShadersDir <dump> -Evidence <ev> -Label patched-shaders
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ShadersDir,
    [Parameter(Mandatory = $true)][string]$Evidence,
    [string]$Label = 'glslang',
    [string]$GlslangExe = '',
    [string]$Include = '',
    [string]$Prelude = '#extension GL_ARB_shading_language_420pack : enable',
    [string]$StageMap = 'fsh=frag;vsh=vert;gsh=geom;csh=comp;tesc=tesc;tese=tese;rgen=rgen;rint=rint;rahit=rahit;rchit=rchit;rmiss=rmiss;rcall=rcall;mesh=mesh;task=task',
    [string]$Baseline = '',
    [switch]$AcceptBaseline,
    [int]$MinFiles = 1,
    [int]$MaxFail = 0
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Fail-Setup([string]$msg) {
    Write-Output "VERDICT=FAIL:setup"
    Write-Output "REASON=$msg"
    exit 2
}

function Get-Sha256([string]$path) {
    return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
}

# ---------- 0. 前置检查 ----------
if (-not (Test-Path -LiteralPath $ShadersDir -PathType Container)) { Fail-Setup "ShadersDir not found: $ShadersDir" }

$candidates = @()
if ($GlslangExe) { $candidates += $GlslangExe }
$envExe = [Environment]::GetEnvironmentVariable('GLSLANG_VALIDATOR')
if ($envExe) { $candidates += $envExe }
$candidates += 'E:\dshHome\tools\glslang-16.6.0\bin\glslang.exe'
foreach ($n in 'glslangValidator', 'glslang') {
    $c = Get-Command $n -ErrorAction SilentlyContinue
    if ($c) { $candidates += $c.Source }
}
$exe = $null
foreach ($c in $candidates) { if ($c -and (Test-Path -LiteralPath $c -PathType Leaf)) { $exe = (Resolve-Path -LiteralPath $c).Path; break } }
if (-not $exe) { Fail-Setup "glslang validator not found (tried: $($candidates -join ', '))" }

$exeSha = Get-Sha256 $exe
$versionLines = @(& $exe --version 2>&1)
$version = ($versionLines | Select-Object -First 1)
if ($LASTEXITCODE -ne 0) { Fail-Setup "glslang --version failed (exit $LASTEXITCODE)" }

$preludeLines = @()
foreach ($p in ($Prelude -split ';')) { $t = $p.Trim(); if ($t) { $preludeLines += $t } }

$incDirs = @()
foreach ($p in ($Include -split ';')) { $t = $p.Trim(); if ($t) { $incDirs += $t } }

$stage = @{}
foreach ($pair in ($StageMap -split ';')) {
    $t = $pair.Trim(); if (-not $t) { continue }
    $kv = $t -split '=', 2
    if ($kv.Count -ne 2) { Fail-Setup "bad StageMap entry: $t" }
    $stage[$kv[0].Trim().ToLowerInvariant().TrimStart('.')] = $kv[1].Trim()
}

if (-not (Test-Path -LiteralPath $Evidence -PathType Container)) { New-Item -ItemType Directory -Force -Path $Evidence | Out-Null }
$Evidence = (Resolve-Path -LiteralPath $Evidence).Path

# ---------- 1. 枚举 ----------
$files = @(Get-ChildItem -LiteralPath $ShadersDir -Recurse -File | Where-Object {
        $stage.ContainsKey($_.Extension.TrimStart('.').ToLowerInvariant())
    } | Sort-Object FullName)

if ($files.Count -lt $MinFiles) { Fail-Setup "only $($files.Count) shader file(s) matched (MinFiles=$MinFiles) under $ShadersDir" }

# ---------- 2. 逐文件校验 ----------
$tmpRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('glslang-check-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmpRoot | Out-Null

$results = @()
$manifest = @()
$totalErrors = 0
$failCount = 0

try {
    foreach ($f in $files) {
        $rel = $f.FullName.Substring($ShadersDir.Length).TrimStart('\', '/')
        $ext = $f.Extension.TrimStart('.').ToLowerInvariant()
        $st = $stage[$ext]

        $rawLines = [System.IO.File]::ReadAllLines($f.FullName)
        $insertAt = 0
        for ($i = 0; $i -lt $rawLines.Count; $i++) {
            if ($rawLines[$i] -match '^\s*#\s*version\b') { $insertAt = $i + 1; break }
        }
        $outLines = @()
        if ($insertAt -gt 0) { $outLines += $rawLines[0..($insertAt - 1)] }
        $outLines += $preludeLines
        if ($insertAt -lt $rawLines.Count) { $outLines += $rawLines[$insertAt..($rawLines.Count - 1)] }

        $tmpFile = Join-Path $tmpRoot (($rel -replace '[\\/]', '__'))
        [System.IO.File]::WriteAllLines($tmpFile, $outLines)

        $argv = @('-S', $st)
        foreach ($d in $incDirs) { $argv += @('-I', $d) }
        $argv += $tmpFile

        $sw = [Diagnostics.Stopwatch]::StartNew()
        $raw = @(& $exe @argv 2>&1)
        $code = $LASTEXITCODE
        $sw.Stop()

        $errs = @()
        foreach ($line in $raw) {
            $s = [string]$line
            $s = $s.Replace($tmpFile, $rel)
            if ($s -match '^\s*ERROR:\s*\d+:(\d+):\s*(.*)$') {
                $ln = [int]$Matches[1]
                if ($preludeLines.Count -gt 0) { $ln = $ln - $preludeLines.Count; if ($ln -lt 1) { $ln = 1 } }
                $errs += "ERROR: ${ln}: $($Matches[2].Trim())"
            }
            elseif ($s -match 'ERROR:|WARNING:') {
                $errs += ($s.Trim() -replace '\s+', ' ')
            }
        }
        $errs = @($errs | Sort-Object -Unique)

        $totalErrors += $errs.Count
        if ($code -ne 0) { $failCount++ }

        $results += [ordered]@{
            file   = $rel
            stage  = $st
            exit   = $code
            ms     = $sw.ElapsedMilliseconds
            errors = $errs
        }
        $manifest += ("{0}  {1}  {2}" -f (Get-Sha256 $f.FullName), $f.Length, $rel)
    }
}
finally {
    Remove-Item -LiteralPath $tmpRoot -Recurse -Force -ErrorAction SilentlyContinue
}

# ---------- 3. 基线比对 ----------
$baselineInfo = [ordered]@{ path = $Baseline; compared = $false; generated = $false; newErrors = @(); fixedErrors = @(); missing = @() }
$newErrorFiles = @()

$current = [ordered]@{}
foreach ($r in $results) { $current[$r.file] = @{ exit = $r.exit; errors = $r.errors } }

if ($Baseline) {
    if (Test-Path -LiteralPath $Baseline -PathType Leaf) {
        $base = Get-Content -LiteralPath $Baseline -Raw | ConvertFrom-Json
        $baselineInfo.compared = $true
        foreach ($r in $results) {
            $b = $base.files.PSObject.Properties[$r.file]
            if ($null -eq $b) { $baselineInfo.missing += $r.file; $newErrorFiles += $r.file; continue }
            $bErr = @($b.Value.errors)
            $new = @($r.errors | Where-Object { $bErr -notcontains $_ })
            $fixed = @($bErr | Where-Object { $r.errors -notcontains $_ })
            if ($new.Count -gt 0) { $newErrorFiles += $r.file }
            if ($new.Count -gt 0 -or $fixed.Count -gt 0) {
                $baselineInfo.newErrors += ("{0}: {1}" -f $r.file, ($new -join ' | '))
                $baselineInfo.fixedErrors += ("{0}: {1}" -f $r.file, ($fixed -join ' | '))
            }
        }
    }
    else {
        $baselineInfo.generated = $true
        [ordered]@{ label = $Label; tool = @{ exe = $exe; sha256 = $exeSha; version = $version }; prelude = $preludeLines; files = $current } |
            ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Baseline -Encoding UTF8
    }
}

# ---------- 4. 报告 ----------
$verdict = 'PASS'
$reasons = @()
$needsBaseline = $false
if ($failCount -gt $MaxFail) { $verdict = 'FAIL'; $reasons += "failFiles=$failCount > MaxFail=$MaxFail" }
if ($baselineInfo.compared -and $newErrorFiles.Count -gt 0) { $verdict = 'FAIL'; $reasons += "newErrorsVsBaseline=$($newErrorFiles.Count)" }
if ($baselineInfo.generated -and -not $AcceptBaseline) { $needsBaseline = $true; $verdict = 'NEEDS-BASELINE'; $reasons += "baseline generated (review it, then re-run with -Baseline)" }

$report = [ordered]@{
    label      = $Label
    tool       = [ordered]@{ exe = $exe; sha256 = $exeSha; version = $version }
    shadersDir = $ShadersDir
    prelude    = $preludeLines
    include    = $incDirs
    counts     = [ordered]@{ files = $results.Count; pass = ($results.Count - $failCount); fail = $failCount; errors = $totalErrors }
    baseline   = $baselineInfo
    verdict    = $verdict
    reasons    = $reasons
    results    = $results
}

$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $Evidence 'glslang-report.json') -Encoding UTF8
$manifest | Set-Content -LiteralPath (Join-Path $Evidence 'glslang-manifest.txt') -Encoding UTF8

$txt = @()
$txt += "LABEL=$Label"
$txt += "VERDICT=$verdict"
$txt += "GLSLANG=$exe"
$txt += "GLSLANG_SHA256=$exeSha"
$txt += "GLSLANG_VERSION=$version"
$txt += "SHADERS_DIR=$ShadersDir"
$txt += "PRELUDE=$($preludeLines -join ' ;; ')"
$txt += "FILES=$($results.Count) PASS=$($results.Count - $failCount) FAIL=$failCount ERRORS=$totalErrors"
$txt += "BASELINE=$Baseline compared=$($baselineInfo.compared) generated=$($baselineInfo.generated)"
if ($reasons.Count -gt 0) { $txt += "REASONS=$($reasons -join ' ; ')" }
$txt += ''
foreach ($r in $results) {
    $txt += ("[{0}] {1} (stage={2}, exit={3}, {4} ms)" -f $(if ($r.exit -eq 0) { 'OK' } else { 'FAIL' }), $r.file, $r.stage, $r.exit, $r.ms)
    foreach ($e in $r.errors) { $txt += "    $e" }
}
$txt | Set-Content -LiteralPath (Join-Path $Evidence 'glslang-report.txt') -Encoding UTF8

# ---------- 5. 输出 ----------
Write-Output "LABEL=$Label"
Write-Output "GLSLANG=$exe ($version)"
Write-Output "FILES=$($results.Count) PASS=$($results.Count - $failCount) FAIL=$failCount ERRORS=$totalErrors"
Write-Output "BASELINE=$Baseline compared=$($baselineInfo.compared) generated=$($baselineInfo.generated)"
if ($newErrorFiles.Count -gt 0) { Write-Output "NEW_ERROR_FILES=$($newErrorFiles -join ', ')" }
Write-Output "REPORT=$(Join-Path $Evidence 'glslang-report.json')"
Write-Output "VERDICT=$verdict"

if ($needsBaseline) { exit 2 }
if ($verdict -eq 'PASS') { exit 0 }
exit 1
