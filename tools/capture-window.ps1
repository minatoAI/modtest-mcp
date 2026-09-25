# SPDX-License-Identifier: GPL-3.0-or-later
# capture-window.ps1 -- capture a window to PNG WITHOUT EVER ACTIVATING IT (never steals focus).
#
# WHY THIS EXISTS (read this before adding any other screenshot path):
#   2026-09-26 the user reported that a real-machine round STOLE THEIR WINDOW FOCUS. Cause: a round
#   script screenshotted by activating the game window and synthesising the vanilla F2 key --
#   ShowWindow(h, SW_RESTORE) + SetForegroundWindow(h) + keybd_event(VK_F2)
#   (docs/evidence/2026-09-27-release-smoke/drive-release-smoke.ps1:72-85). The same technique had
#   already been written off twice in this project (taclight RenderDocApi.java:8 and
#   docs/evidence/2026-09-25-renderdoc/run-round-renderdoc.ps1:54) -- it is an old mistake being
#   repeated, and it is unacceptable: an automated round must never take the user's foreground.
#   The reason it came back is that a RELEASE jar has no relay, so the in-mod `!shot` is unavailable
#   and somebody "just needed a screenshot". This tool is that missing path.
#
# WHAT IT DOES INSTEAD
#   1. PrintWindow(hwnd, hdc, PW_RENDERFULLCONTENT=0x2) -- asks the window to render itself; no
#      activation, no input. Works for DWM-composited windows (which includes normal app windows).
#   2. Fallback: GetWindowRect + Graphics.CopyFromScreen -- a plain screen grab of the window's
#      rectangle, still without touching focus. CAVEAT, printed on every use: this captures whatever
#      is ON SCREEN at those coordinates, so it is only valid while the window is NOT obscured.
#   If both produce an all-black frame, the tool says so and FAILS (exit 4). It never "fixes" a black
#   capture by activating the window or by synthesising keys -- that is the whole point.
#
# IT PROVES IT DID NOT CHANGE THE FOREGROUND: GetForegroundWindow is read before and after (read-only)
#   and reported as foregroundUnchanged. The tool also reports whether the target was the foreground
#   window to begin with.
#
# FORBIDDEN APIS (enforced by tools/no-focus-steal.test.ps1, which scans tools/**):
#   SetForegroundWindow, keybd_event, SendInput, mouse_event, ShowWindow(..., SW_RESTORE/SW_SHOW/
#   SW_MINIMIZE), SetActiveWindow, BringWindowToTop, and any AttachThreadInput+activation dance.
#   GetForegroundWindow / IsWindowVisible / GetWindowRect / PrintWindow are read-only and allowed.
#
# ASCII only. Usage:
#   pwsh tools/capture-window.ps1 -Out shot.png [-Title 'Minecraft'] [-ProcessId 1234] [-WindowHandle 0x..]
#                                [-Method auto|printwindow|rect] [-Json]
#   (with no selector it captures the CURRENT foreground window, read-only)
# Exit: 0 captured a non-black frame | 2 bad parameters / selector ambiguous / no window
#       3 both capture methods failed | 4 captured, but the frame is entirely black (method unusable)

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Out,
    [string]$Title = '',
    [int]$ProcessId = 0,
    [long]$WindowHandle = 0,
    [ValidateSet('auto', 'printwindow', 'rect')][string]$Method = 'auto',
    [switch]$Json
)

$ErrorActionPreference = 'Stop'

$EXIT_OK = 0
$EXIT_BAD_PARAMS = 2
$EXIT_CAPTURE_FAILED = 3
$EXIT_ALL_BLACK = 4

$PW_RENDERFULLCONTENT = 0x00000002

# Read-only user32 helpers + PrintWindow. NOTE: no activation primitive appears here, by design.
$signature = @'
[DllImport("user32.dll")] public static extern System.IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern bool IsWindowVisible(System.IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool GetWindowRect(System.IntPtr hWnd, out RECT lpRect);
[DllImport("user32.dll")] public static extern bool PrintWindow(System.IntPtr hWnd, System.IntPtr hdcBlt, uint nFlags);
public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
'@
try { Add-Type -Namespace ModtestCapture -Name Win -MemberDefinition $signature -ErrorAction SilentlyContinue } catch { }
try { Add-Type -AssemblyName System.Drawing -ErrorAction SilentlyContinue } catch { }

function Invoke-CaptureWindow {
    param([long]$Handle, [string]$Which)
    $hwnd = [System.IntPtr]$Handle
    $rect = New-Object ModtestCapture.Win+RECT
    if (-not [ModtestCapture.Win]::GetWindowRect($hwnd, [ref]$rect)) { throw 'GetWindowRect failed' }
    $width = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top
    if ($width -le 0 -or $height -le 0) { throw ('window has a non-positive size: {0}x{1}' -f $width, $height) }
    $bitmap = New-Object System.Drawing.Bitmap($width, $height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        if ($Which -eq 'printwindow') {
            $hdc = $graphics.GetHdc()
            try {
                $ok = [ModtestCapture.Win]::PrintWindow($hwnd, $hdc, $PW_RENDERFULLCONTENT)
            } finally { $graphics.ReleaseHdc($hdc) }
            if (-not $ok) { throw 'PrintWindow returned false' }
        } else {
            $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, (New-Object System.Drawing.Size($width, $height)))
        }
    } finally { $graphics.Dispose() }
    return [pscustomobject]@{ bitmap = $bitmap; width = $width; height = $height; rect = ('{0},{1},{2},{3}' -f $rect.Left, $rect.Top, $rect.Right, $rect.Bottom) }
}

function Measure-Blackness {
    # Sample a fixed 16x16 grid: cheap, and enough to tell "rendered content" from "nothing". Reports
    # the fraction of exactly-black samples and the maximum luminance seen.
    param([System.Drawing.Bitmap]$Bitmap)
    $grid = 16
    $sampled = 0
    $black = 0
    $maxLuminance = 0.0
    for ($gy = 0; $gy -lt $grid; $gy++) {
        for ($gx = 0; $gx -lt $grid; $gx++) {
            $px = [int](($gx + 0.5) * $Bitmap.Width / $grid)
            $py = [int](($gy + 0.5) * $Bitmap.Height / $grid)
            if ($px -ge $Bitmap.Width) { $px = $Bitmap.Width - 1 }
            if ($py -ge $Bitmap.Height) { $py = $Bitmap.Height - 1 }
            if ($px -lt 0) { $px = 0 }
            if ($py -lt 0) { $py = 0 }
            $colour = $Bitmap.GetPixel($px, $py)
            $luminance = 0.299 * $colour.R + 0.587 * $colour.G + 0.114 * $colour.B
            if ($luminance -gt $maxLuminance) { $maxLuminance = $luminance }
            if ($colour.R -eq 0 -and $colour.G -eq 0 -and $colour.B -eq 0) { $black++ }
            $sampled++
        }
    }
    return [pscustomobject]@{ blackFraction = [math]::Round($black / [double]$sampled, 4); maxLuminance = [math]::Round($maxLuminance, 1); allBlack = ($black -eq $sampled) }
}

# ---------------------------------------------------------------- pick the window (no activation)
$selectors = 0
if ($Title.Length -gt 0) { $selectors++ }
if ($ProcessId -ne 0) { $selectors++ }
if ($WindowHandle -ne 0) { $selectors++ }
if ($selectors -gt 1) { Write-Output 'ERROR: give only one of -Title / -ProcessId / -WindowHandle'; exit $EXIT_BAD_PARAMS }

$candidates = New-Object System.Collections.Generic.List[object]
if ($selectors -eq 0) {
    $foreground = [ModtestCapture.Win]::GetForegroundWindow()
    if ($foreground -eq [System.IntPtr]::Zero) { Write-Output 'ERROR: no selector given and there is no foreground window'; exit $EXIT_BAD_PARAMS }
    foreach ($processItem in @(Get-Process -ErrorAction SilentlyContinue)) {
        if ([long]$processItem.MainWindowHandle -eq [long]$foreground) {
            $candidates.Add([pscustomobject]@{ handle = [long]$processItem.MainWindowHandle; pid = $processItem.Id; name = $processItem.ProcessName; title = $processItem.MainWindowTitle }) | Out-Null
        }
    }
} else {
    foreach ($processItem in @(Get-Process -ErrorAction SilentlyContinue)) {
        $handle = [long]$processItem.MainWindowHandle
        if ($handle -eq 0) { continue }
        $processTitle = ''
        try { $processTitle = [string]$processItem.MainWindowTitle } catch { $processTitle = '' }
        $isMatch = $false
        if ($WindowHandle -ne 0) { $isMatch = ($handle -eq $WindowHandle) }
        elseif ($ProcessId -ne 0) { $isMatch = ($processItem.Id -eq $ProcessId) }
        elseif ($Title.Length -gt 0) { $isMatch = ($processTitle -match $Title) }
        if ($isMatch) { $candidates.Add([pscustomobject]@{ handle = $handle; pid = $processItem.Id; name = $processItem.ProcessName; title = $processTitle }) | Out-Null }
    }
}
if ($candidates.Count -eq 0) {
    Write-Output ('ERROR: no window matched (-Title ''{0}'' -ProcessId {1} -WindowHandle {2})' -f $Title, $ProcessId, $WindowHandle)
    exit $EXIT_BAD_PARAMS
}
if ($candidates.Count -gt 1) {
    Write-Output ('ERROR: the selector matched {0} windows; refusing to guess. Narrow it with -ProcessId or -WindowHandle:' -f $candidates.Count)
    foreach ($entry in $candidates) { Write-Output ('  hwnd={0} pid={1} name={2} title={3}' -f $entry.handle, $entry.pid, $entry.name, $entry.title) }
    exit $EXIT_BAD_PARAMS
}
$target = $candidates[0]
$targetHwnd = [System.IntPtr]$target.handle
if (-not [ModtestCapture.Win]::IsWindowVisible($targetHwnd)) {
    Write-Output ('ERROR: the matched window is not visible (hwnd={0}); refusing to capture a hidden window' -f $target.handle)
    exit $EXIT_BAD_PARAMS
}

$outPath = $Out
$outDir = Split-Path -Parent $outPath
if ($outDir.Length -gt 0 -and -not (Test-Path -LiteralPath $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }

$foregroundBefore = [long][ModtestCapture.Win]::GetForegroundWindow()
$targetWasForeground = ($foregroundBefore -eq $target.handle)

Write-Output ('[capture] target hwnd={0} pid={1} name={2}' -f $target.handle, $target.pid, $target.name)
Write-Output ('[capture] title={0}' -f $target.title)
Write-Output ('[capture] foregroundBefore={0} targetWasForeground={1}' -f $foregroundBefore, $targetWasForeground)

# ---------------------------------------------------------------- capture (first method that renders)
$order = switch ($Method) {
    'printwindow' { @('printwindow') }
    'rect' { @('rect') }
    default { @('printwindow', 'rect') }
}
$result = $null
$attempts = New-Object System.Collections.Generic.List[object]
foreach ($which in $order) {
    $capture = $null
    try {
        $capture = Invoke-CaptureWindow -Handle $target.handle -Which $which
    } catch {
        $attempts.Add([pscustomobject]@{ method = $which; error = $_.Exception.Message; blackFraction = $null }) | Out-Null
        Write-Output ('[capture] method={0} FAILED: {1}' -f $which, $_.Exception.Message)
        continue
    }
    $blackness = Measure-Blackness -Bitmap $capture.bitmap
    $attempts.Add([pscustomobject]@{ method = $which; error = ''; blackFraction = $blackness.blackFraction }) | Out-Null
    Write-Output ('[capture] method={0} size={1}x{2} blackFraction={3} maxLuminance={4}' -f $which, $capture.width, $capture.height, $blackness.blackFraction, $blackness.maxLuminance)
    if (-not $blackness.allBlack) {
        $result = [pscustomobject]@{ method = $which; capture = $capture; blackness = $blackness }
        break
    }
    $capture.bitmap.Dispose()
    Write-Output ('[capture] method={0} produced an ALL-BLACK frame -- unusable for this window' -f $which)
}
if ($null -eq $result) {
        Write-Output 'CAPTURE_VERDICT=FAIL:all-methods-black-or-failed'
    Write-Output 'REASON=no non-black frame could be obtained without activating the window; this tool will NEVER activate it or synthesise keys to work around that'
    if ($Json) {
        ([ordered]@{ ok = $false; method = ''; out = ''; attempts = $attempts.ToArray(); foregroundBefore = $foregroundBefore; reason = 'all methods black or failed' } | ConvertTo-Json -Depth 5)
    }
    if (($attempts | Where-Object { $_.error.Length -gt 0 }).Count -eq $attempts.Count) { exit $EXIT_CAPTURE_FAILED }
    exit $EXIT_ALL_BLACK
}

$bitmap = $result.capture.bitmap
try { $bitmap.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png) } finally { $bitmap.Dispose() }

# ---------------------------------------------------------------- prove the foreground did not change
$foregroundAfter = [long][ModtestCapture.Win]::GetForegroundWindow()
$foregroundUnchanged = ($foregroundAfter -eq $foregroundBefore)
$sha256 = (Get-FileHash -LiteralPath $outPath -Algorithm SHA256).Hash
$bytes = (Get-Item -LiteralPath $outPath).Length
$rectMethodCaveat = ''
if ($result.method -eq 'rect') {
    $rectMethodCaveat = 'the rect method captures the SCREEN area of the window rectangle: valid only while the window is not obscured'
    Write-Output ('[capture] CAVEAT: ' + $rectMethodCaveat)
}
Write-Output ('[capture] method={0} wrote={1} bytes={2} sha256={3}' -f $result.method, $outPath, $bytes, $sha256)
Write-Output ('[capture] foregroundAfter={0} foregroundUnchanged={1}' -f $foregroundAfter, $foregroundUnchanged)
if (-not $foregroundUnchanged) {
    # This would be a bug in this tool, and the caller must know immediately.
    Write-Output 'CAPTURE_VERDICT=FAIL:foreground-changed'
    Write-Output 'REASON=this tool must never change the foreground window; report this immediately'
    exit $EXIT_CAPTURE_FAILED
}
Write-Output 'CAPTURE_VERDICT=OK'
Write-Output ('CAPTURE_METHOD=' + $result.method)
Write-Output ('CAPTURE_BLACKFRACTION=' + $result.blackness.blackFraction)

if ($Json) {
    ([ordered]@{
            ok = $true
            method = $result.method
            out = $outPath
            bytes = $bytes
            sha256 = $sha256
            width = $result.capture.width
            height = $result.capture.height
            rect = $result.capture.rect
            blackFraction = $result.blackness.blackFraction
            maxLuminance = $result.blackness.maxLuminance
            window = [ordered]@{ handle = $target.handle; pid = $target.pid; process = $target.name; title = $target.title }
            targetWasForeground = $targetWasForeground
            foregroundBefore = $foregroundBefore
            foregroundAfter = $foregroundAfter
            foregroundUnchanged = $foregroundUnchanged
            attempts = $attempts.ToArray()
            caveat = $rectMethodCaveat
        } | ConvertTo-Json -Depth 5)
}
exit $EXIT_OK
