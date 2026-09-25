# SPDX-License-Identifier: GPL-3.0-or-later
# parse-check.ps1 -- syntax check for PowerShell scripts.
#
# Two modes:
#   -Target <file>                      one file (the original behaviour, unchanged output)
#   -Scan [-ScanRoot a,b,...]           every *.ps1 under the given roots (default: this tools dir),
#                                       recursively; reports each file and a summary
#
# WHY THIS BLOCKS (exit != 0): a script that does not parse looks like a tool but is not one, and this
# project has repeatedly been bitten by checks that "only warned" or "ran but did nothing". So a parse
# error is a FAILURE, with the file and line named -- never a line of output a reader can ignore.
#
# WHY THE SCAN MUST REACH BEYOND tools/: on 2026-09-26 a signpost file named `assert-no-round.ps1`
# containing only English prose sat in an EVIDENCE directory. PowerShell resolves a relative name from the
# current working directory, so running `.\assert-no-round.ps1` from there parsed the PROSE and failed with
# "Missing closing ')' in expression" while the real tool parsed clean. That produced a false "the gate is
# broken" report: the gate had never run. Two lessons, both encoded here:
#   1. every .ps1 in the tree must parse, evidence directories included;
#   2. call tools by ABSOLUTE path -- a relative name can be shadowed by any same-named file in the cwd.
#
# Usage:
#   pwsh tools/parse-check.ps1 -Target tools\run-round.ps1
#   pwsh tools/parse-check.ps1 -Scan -ScanRoot E:\dshHome\modtest-mcp\tools,E:\dshHome\ray-traced-spotlight-mod-dev\docs\evidence
# Exit: 0 all parsed | 1 at least one parse error | 2 setup error.
param(
    [string]$Target = '',
    [switch]$Scan,
    # ONE comma-separated argument, not an array: `pwsh -File` cannot pass arrays, and a second bare value
    # would bind to the next positional parameter instead (the pitfall already recorded in tools/README.md).
    [string]$ScanRoot = ''
)

$ErrorActionPreference = 'Stop'

if ($Scan) {
    $roots = New-Object System.Collections.Generic.List[string]
    foreach ($part in ($ScanRoot -split ',')) { if ($part.Trim().Length -gt 0) { $roots.Add($part.Trim()) | Out-Null } }
    if ($roots.Count -eq 0) { $roots.Add($PSScriptRoot) | Out-Null }
    $files = New-Object System.Collections.Generic.List[string]
    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root -PathType Container)) {
            Write-Output ('SETUP-ERROR: scan root is not a directory: ' + $root)
            exit 2
        }
        foreach ($f in @(Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*.ps1' -ErrorAction SilentlyContinue)) {
            $files.Add($f.FullName) | Out-Null
        }
    }
    $sorted = @($files.ToArray() | Sort-Object -Unique)
    Write-Output ('PARSE-CHECK-SCAN roots=' + ($roots -join ',') + ' files=' + $sorted.Count)
    $failed = 0
    foreach ($path in $sorted) {
        $t = $null; $e = $null
        try {
            [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$t, [ref]$e) | Out-Null
        } catch {
            Write-Output ('  FAIL ' + $path + ' -- unreadable: ' + $_.Exception.Message)
            $failed++
            continue
        }
        if ($e.Count -gt 0) {
            $failed++
            Write-Output ('  FAIL ' + $path + ' ERRCOUNT=' + $e.Count)
            foreach ($err in $e) {
                Write-Output ('       line ' + $err.Extent.StartLineNumber + ' col ' + $err.Extent.StartColumnNumber + ': ' + $err.Message)
            }
        }
    }
    Write-Output ('PARSE-CHECK files=' + $sorted.Count + ' failed=' + $failed)
    if ($failed -eq 0) { Write-Output 'PARSE-CHECK=OK'; exit 0 }
    Write-Output 'PARSE-CHECK=FAIL'
    exit 1
}

if ($Target.Length -eq 0) {
    Write-Output 'SETUP-ERROR: pass -Target <file> or -Scan [-ScanRoot <dir,...>]'
    exit 2
}
if (-not (Test-Path -LiteralPath $Target -PathType Leaf)) {
    Write-Output ('SETUP-ERROR: not found: ' + $Target)
    exit 2
}
$t = $null; $e = $null
[System.Management.Automation.Language.Parser]::ParseFile($Target, [ref]$t, [ref]$e) | Out-Null
# The single-file mode keeps its historical first line exactly, so callers that grep 'ERRCOUNT=' keep working.
Write-Output ('ERRCOUNT=' + $e.Count)
$e | ForEach-Object { Write-Output ('E@{0} C{1}: {2}' -f $_.Extent.StartLineNumber, $_.Extent.StartColumnNumber, $_.Message) }
if ($e.Count -eq 0) { exit 0 }
exit 1
