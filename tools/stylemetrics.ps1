#Requires -Version 5.1
# SPDX-License-Identifier: GPL-3.0-or-later
# stylemetrics.ps1 - four style metrics for screenshots (compare against a reference image, not by eye).
# 定义(公开数学,Rec.709 亮度域):
#   SAT      = 全像素平均 HSV 饱和度 S = (max-min)/max
#   SHADOW   = luma < 0.25 的像素占比
#   CONTRAST = 中央 80% 裁剪内 luma 的 RMS 偏差
#   TEMPAXIS = 平均 (B-R)/max(luma,0.02);负 = 偏暖,正 = 偏冷
# 用法:powershell -File stylemetrics.ps1 -Images a.png,b.png [-Json]
# 输出(-Json):[{image,SAT,SHADOW,CONTRAST,TEMPAXIS}](与输入同序)
param(
  [Parameter(Mandatory = $true)][string]$Images,
  [switch]$Json
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

function Get-Metrics([string]$path) {
  $bmp = [System.Drawing.Bitmap]::new($path)
  try {
    $w = $bmp.Width; $h = $bmp.Height
    $rect = [System.Drawing.Rectangle]::new(0, 0, $w, $h)
    $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
                          [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
      $stride = $data.Stride
      $bytes = [byte[]]::new($stride * $h)
      [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
    } finally { $bmp.UnlockBits($data) }

    $satSum = 0.0; $shadowCount = [long]0; $tempSum = 0.0
    $x0 = [int][math]::Floor($w * 0.10); $x1 = [int][math]::Ceiling($w * 0.90)
    $y0 = [int][math]::Floor($h * 0.10); $y1 = [int][math]::Ceiling($h * 0.90)
    $cropSum = 0.0; $cropSq = 0.0; $cropN = [long]0

    for ($y = 0; $y -lt $h; $y++) {
      $row = $y * $stride
      for ($x = 0; $x -lt $w; $x++) {
        $i = $row + $x * 4
        $b = $bytes[$i] / 255.0; $g = $bytes[$i + 1] / 255.0; $r = $bytes[$i + 2] / 255.0
        $mx = $r; if ($g -gt $mx) { $mx = $g }; if ($b -gt $mx) { $mx = $b }
        $mn = $r; if ($g -lt $mn) { $mn = $g }; if ($b -lt $mn) { $mn = $b }
        if ($mx -gt 0) { $satSum += ($mx - $mn) / $mx }
        $luma = 0.2126 * $r + 0.7152 * $g + 0.0722 * $b
        if ($luma -lt 0.25) { $shadowCount++ }
        $tempSum += ($b - $r) / [math]::Max($luma, 0.02)
        if ($x -ge $x0 -and $x -lt $x1 -and $y -ge $y0 -and $y -lt $y1) {
          $cropSum += $luma; $cropSq += $luma * $luma; $cropN++
        }
      }
    }
    $n = [double]($w * $h)
    $mean = $cropSum / [math]::Max($cropN, 1)
    $variance = [math]::Max(($cropSq / [math]::Max($cropN, 1)) - $mean * $mean, 0.0)
    return [pscustomobject]@{
      image = (Split-Path -Leaf $path)
      SAT = [math]::Round($satSum / $n, 4)
      SHADOW = [math]::Round($shadowCount / $n, 4)
      CONTRAST = [math]::Round([math]::Sqrt($variance), 4)
      TEMPAXIS = [math]::Round($tempSum / $n, 4)
    }
  } finally { $bmp.Dispose() }
}

$results = New-Object System.Collections.Generic.List[object]
foreach ($p in $Images.Split(',')) {
  $t = $p.Trim()
  if ($t -ne '') { $results.Add((Get-Metrics $t)) }
}
if ($Json) {
  $results | ConvertTo-Json -Compress -Depth 3
} else {
  foreach ($m in $results) {
    Write-Output ("IMG {0} SAT={1} SHADOW={2} CONTRAST={3} TEMPAXIS={4}" -f `
      $m.image, $m.SAT, $m.SHADOW, $m.CONTRAST, $m.TEMPAXIS)
  }
}
