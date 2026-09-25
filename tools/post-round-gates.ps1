# post-round-gates.ps1 -- run the offline shader gates as ONE step after a real round.
#
# WHY: two host-side gates already exist and are proven individually --
#   * patched-shaders-audit.ps1 : is the injection actually IN the final GLSL Iris compiled?
#   * glslang-check.ps1         : does that final GLSL still compile (offline, ~5 s, no game)?
# Running them by hand after every round is exactly the kind of step that gets skipped. This wrapper
# makes "collect patched_shaders -> audit -> compile-check -> one verdict" a single command, so a
# round's shader health is a machine-checked fact instead of a memory.
#
# Both gates are mod-agnostic: all project-specific patterns arrive as parameters.
#
# ASCII only. Usage:
#   pwsh post-round-gates.ps1 -ShadersDir <gameDir>\patched_shaders -Evidence <ev>\gates `
#        -Require 'taclight_vox_transmit;code >= 4.0' -Forbid '/4u' `
#        -GlslangBaseline <ev>\glslang-baseline.json -MinFiles 100
#
# Exit: 0 = all gates passed, 1 = at least one gate failed, 2 = setup error.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ShadersDir,
    [Parameter(Mandatory = $true)][string]$Evidence,
    [string]$Label = 'post-round-gates',
    # forwarded to patched-shaders-audit.ps1 (semicolon-separated, see that tool's NOTE)
    [string]$Require = '',
    [string]$Forbid = '',
    # Minimum number of files each -Require pattern must appear in (audit gate). Default 1.
    # NOTE: this is a DIFFERENT threshold from -MinFiles below -- the audit's -MinFiles counts files
    # matched by a Require pattern (our injection core is in ~30 of 228 files), while the glslang
    # gate's -MinFiles guards "did we even find the dump" (159 shader files). Passing one number to
    # both would produce a false red.
    [int]$RequireMinFiles = 1,
    # Minimum number of shader files the compile gate must find (guards against a wrong directory).
    [int]$MinFiles = 1,
    [int]$MaxIncludeResidual = 0,
    # forwarded to glslang-check.ps1
    [string]$GlslangBaseline = '',
    [string]$Prelude = '',
    [string]$StageMap = '',
    [switch]$AcceptGlslangBaseline,
    # tool location (defaults to this script's directory)
    [string]$ToolsDir = '',
    [switch]$SkipAudit,
    [switch]$SkipGlslang
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ShadersDir -PathType Container)) {
    Write-Output "GATES_VERDICT=FAIL:setup"
    Write-Output "REASON=ShadersDir not found: $ShadersDir"
    exit 2
}
if ($ToolsDir.Length -eq 0) { $ToolsDir = $PSScriptRoot }
$auditTool = Join-Path $ToolsDir 'patched-shaders-audit.ps1'
$glslangTool = Join-Path $ToolsDir 'glslang-check.ps1'
New-Item -ItemType Directory -Force -Path $Evidence | Out-Null
$Evidence = (Resolve-Path -LiteralPath $Evidence).Path

function Get-Sha256([string]$p) { if (Test-Path -LiteralPath $p) { return (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash } return '' }

$gates = [ordered]@{}
$failed = New-Object System.Collections.Generic.List[string]

function Invoke-Gate {
    param([string]$Name, [string]$Tool, [string[]]$ToolArgs, [string]$OutDir)
    $result = [ordered]@{ name = $Name; tool = $Tool; toolSha256 = (Get-Sha256 $Tool); outDir = $OutDir }
    if ($Tool.Length -eq 0 -or -not (Test-Path -LiteralPath $Tool -PathType Leaf)) {
        $result.exit = -1
        $result.reason = "tool not found: $Tool"
        $result.output = @()
        return $result
    }
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $argv = @('-NoProfile', '-File', $Tool) + $ToolArgs
    $out = @(& pwsh @argv 2>&1 | ForEach-Object { [string]$_ })
    $result.exit = $LASTEXITCODE
    $result.output = $out
    return $result
}

# ---- gate 1: is the injection in the final GLSL? -------------------------
if (-not $SkipAudit) {
    $a = @('-ShadersDir', $ShadersDir, '-Evidence', (Join-Path $Evidence 'audit'), '-Label', $Label,
        '-MinFiles', "$RequireMinFiles", '-MaxIncludeResidual', "$MaxIncludeResidual")
    if ($Require.Length -gt 0) { $a += @('-Require', $Require) }
    if ($Forbid.Length -gt 0) { $a += @('-Forbid', $Forbid) }
    $r = Invoke-Gate -Name 'patched-shaders-audit' -Tool $auditTool -ToolArgs $a -OutDir (Join-Path $Evidence 'audit')
    $gates['audit'] = $r
    if ($r.exit -ne 0) { $failed.Add("patched-shaders-audit exit=$($r.exit)") }
}

# ---- gate 2: does the final GLSL still compile offline? ------------------
if (-not $SkipGlslang) {
    $g = @('-ShadersDir', $ShadersDir, '-Evidence', (Join-Path $Evidence 'glslang'), '-Label', $Label,
        '-MinFiles', "$MinFiles")
    if ($GlslangBaseline.Length -gt 0) { $g += @('-Baseline', $GlslangBaseline) }
    if ($AcceptGlslangBaseline) { $g += @('-AcceptBaseline') }
    if ($Prelude.Length -gt 0) { $g += @('-Prelude', $Prelude) }
    if ($StageMap.Length -gt 0) { $g += @('-StageMap', $StageMap) }
    $r = Invoke-Gate -Name 'glslang-check' -Tool $glslangTool -ToolArgs $g -OutDir (Join-Path $Evidence 'glslang')
    $gates['glslang'] = $r
    if ($r.exit -ne 0) { $failed.Add("glslang-check exit=$($r.exit)") }
}

$verdict = if ($failed.Count -eq 0) { 'PASS' } else { 'FAIL' }
$report = [ordered]@{
    label = $Label
    shadersDir = $ShadersDir
    require = $Require
    forbid = $Forbid
    minFiles = $MinFiles
    glslangBaseline = $GlslangBaseline
    verdict = $verdict
    failed = $failed.ToArray()
    gates = $gates
}
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $Evidence 'post-round-gates.json') -Encoding UTF8

foreach ($k in $gates.Keys) {
    Write-Output ("[$k] exit=" + $gates[$k].exit + " toolSha256=" + $gates[$k].toolSha256.Substring(0, [Math]::Min(16, $gates[$k].toolSha256.Length)))
    $gates[$k].output | Select-Object -Last 6 | ForEach-Object { Write-Output ("    " + $_) }
}
Write-Output ("GATES_REPORT=" + (Join-Path $Evidence 'post-round-gates.json'))
if ($failed.Count -gt 0) { Write-Output ("GATES_FAILED=" + ($failed -join ' ; ')) }
Write-Output ("GATES_VERDICT=" + $verdict)
if ($verdict -eq 'PASS') { exit 0 }
exit 1
