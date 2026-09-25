# SPDX-License-Identifier: GPL-3.0-or-later
# no-focus-steal.test.ps1 -- POLICY TEST: nothing in tools/ may steal the user's window focus.
#
# WHY (2026-09-26): a real-machine round STOLE THE USER'S FOREGROUND by screenshotting through
# ShowWindow(SW_RESTORE) + SetForegroundWindow(h) + keybd_event(VK_F2) -- a technique this project had
# already written off twice (taclight RenderDocApi.java:8; docs/evidence/2026-09-25-renderdoc/
# run-round-renderdoc.ps1:54). It came back because a RELEASE jar has no relay, so the in-mod `!shot`
# was unavailable and somebody "just needed a screenshot". Documentation alone did not stop it, so this
# is a machine-checked policy: an automated round must never take the user's foreground.
#
# WHAT IT ENFORCES
#   * every *.ps1 / *.js / *.md in tools/ is scanned for activation/input-synthesis primitives;
#   * for *.ps1 / *.js only CODE lines are scanned (a comment is allowed to explain the ban) -- same
#     discipline as the taclight contracts, so a comment can never feed the assertion;
#   * for *.md only the text OUTSIDE the documented exemption block is scanned, so tools/README.md can
#     still publish the banned list:
#         <!-- BEGIN-BANNED-API-LIST --> ... <!-- END-BANNED-API-LIST -->
#   * this scanner names the forbidden tokens, so it is exempt from the token scan -- but it is still
#     scanned for activation CALL syntax, so a real call cannot hide here;
#   * structural checks: capture-window.ps1 exists, reports which method it used, and reports the
#     foreground comparison (the tool that must be used instead).
#
# NEGATIVE CONTROL (repeatable, no need to edit the tree): -ExtraScanPath <file> adds one more file to
# the scan set, so a temp file containing `[X]::SetForegroundWindow(0)` must turn this RED.
#
# ASCII only. Usage:
#   pwsh tools/no-focus-steal.test.ps1 [-ExtraScanPath <file>]
# Exit: 0 policy holds | 1 a violation was found | 2 setup error.

[CmdletBinding()]
param(
    [string]$ExtraScanPath = ''
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $PSScriptRoot -PathType Container)) { Write-Output 'NO-FOCUS-STEAL-TEST SETUP-ERROR: PSScriptRoot not found'; exit 2 }

$script:Total = 0
$script:Failed = 0
function Add-Check {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    $script:Total++
    if ($Condition) { Write-Output ('  ok    ' + $Name) }
    else { $script:Failed++; Write-Output ('  FAIL  ' + $Name + $(if ($Detail.Length -gt 0) { ' -- ' + $Detail } else { '' })) }
}

# --- the policy -------------------------------------------------------------
# Activation / focus-change primitives and input synthesis.
$activationTokens = @('SetForegroundWindow', 'SetActiveWindow', 'BringWindowToTop', 'SwitchToThisWindow',
    'AttachThreadInput', 'keybd_event', 'SendInput', 'mouse_event')
$activationPattern = ($activationTokens | ForEach-Object { [regex]::Escape($_) }) -join '|'
# ShowWindow is only a violation with a "make me visible/active" flag -- by NAME or by NUMBER (the real
# incident used the numeric form `ShowWindow(h,9)`; SW_SHOWNORMAL=1, SW_SHOWMINIMIZED=2,
# SW_SHOWMAXIMIZED=3, SW_SHOW=5, SW_MINIMIZE=6, SW_RESTORE=9, SW_SHOWDEFAULT=10, SW_FORCEMINIMIZE=11).
$showWindowFlags = @('SW_RESTORE', 'SW_SHOW', 'SW_MINIMIZE', 'SW_SHOWNORMAL', 'SW_SHOWDEFAULT', 'SW_MAXIMIZE')
$showWindowPattern = 'ShowWindow\s*\([^)]*(' + (($showWindowFlags | ForEach-Object { [regex]::Escape($_) }) -join '|') + '|,\s*(1|2|3|5|6|9|10|11)\s*\))'
# In this scanner itself only CALL syntax counts (the token strings above are data, not calls).
$callSyntaxPattern = '(::|\.)\s*(' + (($activationTokens | ForEach-Object { [regex]::Escape($_) }) -join '|') + ')\s*\('

$selfName = 'no-focus-steal.test.ps1'
$beginMarker = '<!-- BEGIN-BANNED-API-LIST -->'
$endMarker = '<!-- END-BANNED-API-LIST -->'

function Get-ScannableText {
    # Comments are stripped for code files; Markdown keeps everything OUTSIDE the exemption block.
    param([string]$Path)
    $extension = [System.IO.Path]::GetExtension($Path).ToLowerInvariant()
    $raw = Get-Content -LiteralPath $Path -Raw
    if ($extension -eq '.md') {
        $inside = $false
        $kept = New-Object System.Collections.Generic.List[string]
        foreach ($line in ($raw -split "`r?`n")) {
            if ($line -match [regex]::Escape($beginMarker)) { $inside = $true; continue }
            if ($line -match [regex]::Escape($endMarker)) { $inside = $false; continue }
            if (-not $inside) { $kept.Add($line) | Out-Null }
        }
        return ($kept -join "`n")
    }
    $kept = New-Object System.Collections.Generic.List[string]
    foreach ($line in ($raw -split "`r?`n")) {
        $trimmed = $line.TrimStart()
        if ($trimmed.StartsWith('#') -or $trimmed.StartsWith('//') -or $trimmed.StartsWith('*') -or $trimmed.StartsWith('/*')) { continue }
        $kept.Add($line) | Out-Null
    }
    return ($kept -join "`n")
}

Write-Output 'NO-FOCUS-STEAL-TEST scanning tools/ for activation/input primitives'

$files = @(Get-ChildItem -LiteralPath $PSScriptRoot -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Extension -in @('.ps1', '.js', '.md') } | Sort-Object Name)
Add-Check 'scan set is non-empty (the policy actually looks at something)' ($files.Count -gt 0) ('count=' + $files.Count)
$extra = @()
if ($ExtraScanPath.Length -gt 0) {
    if (-not (Test-Path -LiteralPath $ExtraScanPath -PathType Leaf)) { Write-Output ('SETUP-ERROR: -ExtraScanPath not found: ' + $ExtraScanPath); exit 2 }
    $extra = @(Get-Item -LiteralPath $ExtraScanPath)
}

$violations = New-Object System.Collections.Generic.List[string]
$scanned = 0
foreach ($file in @($files) + @($extra)) {
    $text = Get-ScannableText -Path $file.FullName
    $scanned++
    if ($file.Name -eq $selfName -and $file.FullName.StartsWith($PSScriptRoot)) {
        # exempt from the token scan, still checked for real calls
        $hits = @([regex]::Matches($text, $callSyntaxPattern))
        foreach ($hit in $hits) { $violations.Add(($file.Name + ': activation CALL syntax "' + $hit.Value + '"')) | Out-Null }
        continue
    }
    $hits = @([regex]::Matches($text, $activationPattern))
    foreach ($hit in $hits) { $violations.Add(($file.Name + ': banned primitive "' + $hit.Value + '"')) | Out-Null }
    $showHits = @([regex]::Matches($text, $showWindowPattern))
    foreach ($hit in $showHits) { $violations.Add(($file.Name + ': focus-grabbing ShowWindow "' + $hit.Value + '"')) | Out-Null }
}
Add-Check 'no activation/input-synthesis primitive in tools/ code' ($violations.Count -eq 0) (($violations | Select-Object -First 6) -join ' ; ')
Add-Check 'the scan really covered the files (each file read once)' ($scanned -eq ($files.Count + $extra.Count)) ('scanned=' + $scanned + ' expected=' + ($files.Count + $extra.Count))

# --- structural: the sanctioned replacement exists and is self-reporting ----
$capturePath = Join-Path $PSScriptRoot 'capture-window.ps1'
$captureExists = Test-Path -LiteralPath $capturePath -PathType Leaf
Add-Check 'tools/capture-window.ps1 exists (the focus-safe screenshot path)' $captureExists
if ($captureExists) {
    $captureText = Get-Content -LiteralPath $capturePath -Raw
    Add-Check 'capture-window.ps1 uses PrintWindow as the primary method' ($captureText -match 'PrintWindow')
    Add-Check 'capture-window.ps1 reports which method it used' ($captureText -match 'CAPTURE_METHOD=')
    Add-Check 'capture-window.ps1 reports the read-only foreground comparison' (($captureText -match 'GetForegroundWindow') -and ($captureText -match 'foregroundUnchanged'))
    Add-Check 'capture-window.ps1 states the rect-method caveat' ($captureText -match 'obscured')
}

# --- structural: the README publishes the policy with its exemption markers --
$readmePath = Join-Path $PSScriptRoot 'README.md'
if (Test-Path -LiteralPath $readmePath -PathType Leaf) {
    $readmeText = Get-Content -LiteralPath $readmePath -Raw
    Add-Check 'README.md carries the banned-API exemption block markers' (($readmeText -match [regex]::Escape($beginMarker)) -and ($readmeText -match [regex]::Escape($endMarker)))
    Add-Check 'README.md points at capture-window.ps1 as the replacement' ($readmeText -match 'capture-window\.ps1')
    Add-Check 'README.md says synthetic keypresses must never be used for screenshots' ($readmeText -match '(?i)never.{0,40}(synthe|keypress|keybd)|(synthe|keypress|keybd).{0,40}never')
} else {
    Add-Check 'README.md exists' $false
}

Write-Output ('NO-FOCUS-STEAL-TEST ' + $(if ($script:Failed -eq 0) { 'PASS' } else { 'FAIL' }) + ' (' + ($script:Total - $script:Failed) + '/' + $script:Total + ' checks passed)')
if ($script:Failed -eq 0) { exit 0 }
exit 1
