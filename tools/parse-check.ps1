# SPDX-License-Identifier: GPL-3.0-or-later
# parse-check.ps1 — PS 5.1 解析器校验(继承旧项目纪律:多行 hashtable 会解析失败,改后必须 ERRCOUNT=0)
param([Parameter(Mandatory = $true)][string]$Target)
$t = $null; $e = $null
[System.Management.Automation.Language.Parser]::ParseFile($Target, [ref]$t, [ref]$e) | Out-Null
Write-Output ('ERRCOUNT=' + $e.Count)
$e | ForEach-Object { Write-Output ('E@{0} C{1}: {2}' -f $_.Extent.StartLineNumber, $_.Extent.StartColumnNumber, $_.Message) }
