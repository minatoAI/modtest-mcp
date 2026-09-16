#Requires -Version 5.1
# SPDX-License-Identifier: GPL-3.0-or-later
# clusterprobe.ps1 - saturated-colour cluster detection (connected components) in a screenshot.
# Use: locate solid-colour markers programmatically (centroid, area, bounding box) instead of by eye.
#
# 用法:powershell -File clusterprobe.ps1 -Image img.png [-Color green] [-MinSat 0.4] [-MinVal 0.15] [-MinArea 40] [-Json]
# 输出(-Json):{image,color,count,clusters:[{area,cx,cy,cxPct,cyPct,x0,y0,x1,y1}]}(按面积降序,前 10)
param(
  [Parameter(Mandatory = $true)][string]$Image,
  [ValidateSet('green', 'red', 'cyan', 'blue', 'magenta')][string]$Color = 'green',
  [double]$MinSat = 0.4,
  [double]$MinVal = 0.15,
  [int]$MinArea = 40,
  [switch]$Json
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# 色相窗口(HSV hue 0..360):green 85..155 / red 绕 0 / cyan 165..195 / blue 220..260 / magenta 280..320
$windows = @{ green = @(85, 155); cyan = @(165, 195); blue = @(220, 260); magenta = @(280, 320) }

$bmp = [System.Drawing.Bitmap]::new($Image)
try {
  $w = $bmp.Width; $h = $bmp.Height
  $rect = [System.Drawing.Rectangle]::new(0, 0, $w, $h)
  $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
                        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $bytes = [byte[]]::new($data.Stride * $h)
  [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
  $bmp.UnlockBits($data)
} finally { $bmp.Dispose() }

$stride = $data.Stride
$mask = [byte[]]::new($w * $h)
function HueInWindow([double]$hue) {
  if ($Color -eq 'red') { return ($hue -ge 340 -or $hue -le 20) }
  $win = $windows[$Color]
  return ($hue -ge $win[0] -and $hue -le $win[1])
}

for ($y = 0; $y -lt $h; $y++) {
  $row = $y * $stride
  for ($x = 0; $x -lt $w; $x++) {
    $i = $row + $x * 4
    $b = $bytes[$i] / 255.0; $g = $bytes[$i + 1] / 255.0; $r = $bytes[$i + 2] / 255.0
    $mx = $r; if ($g -gt $mx) { $mx = $g }; if ($b -gt $mx) { $mx = $b }
    $mn = $r; if ($g -lt $mn) { $mn = $g }; if ($b -lt $mn) { $mn = $b }
    if ($mx -lt $MinVal) { continue }
    $sat = 0.0; if ($mx -gt 0) { $sat = ($mx - $mn) / $mx }
    if ($sat -lt $MinSat) { continue }
    $d = $mx - $mn
    $hue = 0.0
    if ($d -gt 0) {
      if ($mx -eq $r) { $hue = 60 * (((($g - $b) / $d) % 6 + 6) % 6) }
      elseif ($mx -eq $g) { $hue = 60 * ((($b - $r) / $d) + 2) }
      else { $hue = 60 * ((($r - $g) / $d) + 4) }
    }
    if (HueInWindow $hue) { $mask[$y * $w + $x] = 1 }
  }
}

# 连通域(BFS 栈式 flood fill,4 邻接)
$visited = [byte[]]::new($w * $h)
$stack = New-Object System.Collections.Generic.List[int]
$clusters = New-Object System.Collections.Generic.List[object]
for ($p0 = 0; $p0 -lt ($w * $h); $p0++) {
  if ($mask[$p0] -eq 0 -or $visited[$p0] -ne 0) { continue }
  $stack.Clear(); $stack.Add($p0); $visited[$p0] = 1
  $area = 0; $sx = 0.0; $sy = 0.0
  $x0 = $w; $y0 = $h; $x1 = 0; $y1 = 0
  while ($stack.Count -gt 0) {
    $p = $stack[$stack.Count - 1]; $stack.RemoveAt($stack.Count - 1)
    $px = $p % $w; $py = [int]($p / $w)
    $area++; $sx += $px; $sy += $py
    if ($px -lt $x0) { $x0 = $px }; if ($px -gt $x1) { $x1 = $px }
    if ($py -lt $y0) { $y0 = $py }; if ($py -gt $y1) { $y1 = $py }
    if ($px -gt 0 -and $mask[$p - 1] -eq 1 -and $visited[$p - 1] -eq 0) { $visited[$p - 1] = 1; $stack.Add($p - 1) }
    if ($px -lt $w - 1 -and $mask[$p + 1] -eq 1 -and $visited[$p + 1] -eq 0) { $visited[$p + 1] = 1; $stack.Add($p + 1) }
    if ($py -gt 0 -and $mask[$p - $w] -eq 1 -and $visited[$p - $w] -eq 0) { $visited[$p - $w] = 1; $stack.Add($p - $w) }
    if ($py -lt $h - 1 -and $mask[$p + $w] -eq 1 -and $visited[$p + $w] -eq 0) { $visited[$p + $w] = 1; $stack.Add($p + $w) }
  }
  if ($area -ge $MinArea) {
    $clusters.Add([pscustomobject]@{
      area = $area
      cx = [math]::Round($sx / $area, 1); cy = [math]::Round($sy / $area, 1)
      cxPct = [math]::Round(100 * $sx / $area / $w, 2); cyPct = [math]::Round(100 * $sy / $area / $h, 2)
      x0 = $x0; y0 = $y0; x1 = $x1; y1 = $y1
    })
  }
}
$sorted = $clusters | Sort-Object -Property area -Descending | Select-Object -First 10

if ($Json) {
  [pscustomobject]@{ image = (Split-Path -Leaf $Image); color = $Color; count = @($sorted).Count; clusters = @($sorted) } |
    ConvertTo-Json -Compress -Depth 4
} else {
  Write-Output ("clusterprobe {0} color={1} clusters={2}" -f (Split-Path -Leaf $Image), $Color, @($sorted).Count)
  $n = 0
  foreach ($cl in $sorted) {
    $n++
    Write-Output ("CLUSTER #{0} area={1} center=({2},{3}) pct=({4}%,{5}%) bbox=({6},{7})-({8},{9})" -f `
      $n, $cl.area, $cl.cx, $cl.cy, $cl.cxPct, $cl.cyPct, $cl.x0, $cl.y0, $cl.x1, $cl.y1)
  }
}
