#Requires -Version 5.1
# SPDX-License-Identifier: GPL-3.0-or-later
# griddiff.ps1 - 两张同尺寸截图按 NxN 网格对比,输出每格 RGB 差异(热图之外的精确读数)。
# 判定:格内最大像素差 maxDiff > -Threshold(默认10)则标记 FLAG。
# 用法:powershell -File griddiff.ps1 -A a.png -B b.png [-Cells 8] [-Threshold 10] [-Json]
# 输出(-Json):{cells,w,h,threshold,flagged:[{c,r,mean,max}],worst:{c,r,mean,max},meanDiff,maxDiff}
# c/r 从 0 起;x 方向 = 列 c,y 方向 = 行 r(与图像坐标一致)。
param(
  [Parameter(Mandatory = $true)][string]$A,
  [Parameter(Mandatory = $true)][string]$B,
  [int]$Cells = 8,
  [int]$Threshold = 10,
  [switch]$Json
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

function Get-Pixels([string]$path) {
  $bmp = [System.Drawing.Bitmap]::new($path)
  try {
    $w = $bmp.Width; $h = $bmp.Height
    $rect = [System.Drawing.Rectangle]::new(0, 0, $w, $h)
    $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
                          [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
      $bytes = [byte[]]::new($data.Stride * $h)
      [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
    } finally { $bmp.UnlockBits($data) }
    return @{ w = $w; h = $h; b = $bytes; stride = $data.Stride }
  } finally { $bmp.Dispose() }
}

$pa = Get-Pixels $A
$pb = Get-Pixels $B
if ($pa.w -ne $pb.w -or $pa.h -ne $pb.h) { throw "size mismatch: $($pa.w)x$($pa.h) vs $($pb.w)x$($pb.h)" }
$w = $pa.w; $h = $pa.h
$cs = [math]::Ceiling($w / $Cells)   # 格宽
$rs = [math]::Ceiling($h / $Cells)   # 格高

$flagged = New-Object System.Collections.Generic.List[object]
$worst = $null
$sum = 0.0; $maxAll = 0; $n = 0

for ($r = 0; $r -lt $Cells; $r++) {
  for ($c = 0; $c -lt $Cells; $c++) {
    $x0 = $c * $cs; $x1 = [math]::Min(($x0 + $cs), $w)
    $y0 = $r * $rs; $y1 = [math]::Min(($y0 + $rs), $h)
    $cellSum = 0.0; $cellMax = 0; $cellN = 0
    for ($y = $y0; $y -lt $y1; $y++) {
      $row = $y * $pa.stride
      for ($x = $x0; $x -lt $x1; $x++) {
        $i = $row + $x * 4
        $dr = [math]::Abs($pa.b[$i + 2] - $pb.b[$i + 2])   # BGRA 布局:R 在 +2
        $dg = [math]::Abs($pa.b[$i + 1] - $pb.b[$i + 1])
        $db = [math]::Abs($pa.b[$i] - $pb.b[$i])
        $d = $dr; if ($dg -gt $d) { $d = $dg }; if ($db -gt $d) { $d = $db }
        $cellSum += $d; $cellN++
        if ($d -gt $cellMax) { $cellMax = $d }
      }
    }
    $mean = $cellSum / [math]::Max($cellN, 1)
    $sum += $cellSum; $n += $cellN
    if ($cellMax -gt $maxAll) { $maxAll = $cellMax }
    if ($cellMax -gt $Threshold) {
      $cell = [pscustomobject]@{ c = $c; r = $r; mean = [math]::Round($mean, 2); max = $cellMax }
      $flagged.Add($cell)
      if ($null -eq $worst -or $cellMax -gt $worst.max) { $worst = $cell }
    }
  }
}

$meanAll = $sum / [math]::Max($n, 1)
if ($Json) {
  [pscustomobject]@{
    cells = $Cells; w = $w; h = $h; threshold = $Threshold
    flagged = $flagged; worst = $worst
    meanDiff = [math]::Round($meanAll, 3); maxDiff = $maxAll
  } | ConvertTo-Json -Compress -Depth 4
} else {
  Write-Output ("griddiff {0}x{1} cells={2} threshold={3}" -f $w, $h, $Cells, $Threshold)
  foreach ($cell in $flagged) {
    Write-Output ("FLAG c={0} r={1} mean={2} max={3}" -f $cell.c, $cell.r, $cell.mean, $cell.max)
  }
  if ($null -ne $worst) {
    Write-Output ("WORST c={0} r={1} mean={2} max={3}" -f $worst.c, $worst.r, $worst.mean, $worst.max)
  }
  Write-Output ("SUMMARY mean={0} max={1} flagged={2}" -f ([math]::Round($meanAll, 3)), $maxAll, $flagged.Count)
}
