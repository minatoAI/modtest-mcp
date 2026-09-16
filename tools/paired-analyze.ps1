# SPDX-License-Identifier: GPL-3.0-or-later
# paired (interleaved) A/B analysis -- ASCII only, Windows PowerShell 5.1 / PowerShell 7.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\paired-analyze.ps1 `
#       -Spec spec.json -Session PN `
#       -Summary <evidence>\PN-summary.json [-NoisePair "pn00-c8,pn99-c8"] [-Json out.json]
#
# Why paired: comparing cross-arm medians while arms run in configuration clusters lets session
# drift (two identical-config arms can differ by a wide margin) swamp the effect under test. Here each
# pair runs back-to-back, so the paired difference cancels the drift.
#
# Conventions (stated so the sign can never be misread):
#   dX_i  = FPS(c_i) - FPS(d_i)   -> positive means the d-variant is SLOWER
#   B_i   = FPS(d_i) - FPS(c_i)   -> positive means the d-variant is FASTER (the benefit)
#   D     = |FPS(noise_a) - FPS(noise_b)| for two IDENTICAL-config arms (drift scale)
#   X     = max(D, 1.0) FPS is the CONSERVATIVE minimum readable effect at 5 pairs.
param(
    [Parameter(Mandatory=$true)][string]$Spec,
    [Parameter(Mandatory=$true)][string]$Session,
    [Parameter(Mandatory=$true)][string]$Summary,
    [string]$NoisePair = '',
    [string]$Json = ''
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Spec)) { Write-Host "spec not found: $Spec"; exit 2 }
if (-not (Test-Path -LiteralPath $Summary)) { Write-Host "summary not found: $Summary"; exit 2 }
$specObj = Get-Content -LiteralPath $Spec -Raw -Encoding UTF8 | ConvertFrom-Json
$ses = @($specObj.sessions | Where-Object { $_.id -eq $Session })
if ($ses.Count -eq 0) { Write-Host "session '$Session' not in spec"; exit 2 }
$ses = $ses[0]
$sum = Get-Content -LiteralPath $Summary -Raw -Encoding UTF8 | ConvertFrom-Json
if ($sum.session -and ("spec:$Session" -ne $sum.session) -and ($sum.session -notlike "*$Session*")) { Write-Host ("WARN: summary session={0} but -Session={1}" -f $sum.session, $Session) }

# ---- FPS per arm: median of the 3 relay !bench windows (primary metric), cross-checked with the table
$fps = @{}; $reps = @{}; $accept = @{}
foreach ($row in @($sum.arms)) {
    $accept[$row.arm] = (-not $row.aborted) -and [bool]$row.arm_accept
    if ($row.relay_bench_avg) { $reps[$row.arm] = @($row.relay_bench_avg) }
    $v = $null
    if ($row.relay_median) { $v = [double]$row.relay_median }
    elseif ($reps.ContainsKey($row.arm) -and $reps[$row.arm].Count -gt 0) {
        $sorted = @($reps[$row.arm] | Sort-Object); $n = $sorted.Count
        $v = if ($n % 2 -eq 1) { [double]$sorted[[int](($n - 1) / 2)] } else { ([double]$sorted[$n / 2 - 1] + [double]$sorted[$n / 2]) / 2.0 }
    }
    if ($null -ne $v) { $fps[$row.arm] = $v }
}
Write-Host ("== paired analysis: spec={0} session={1}" -f (Split-Path $Spec -Leaf), $Session)
Write-Host ("   summary={0} arms_with_fps={1}" -f (Split-Path $Summary -Leaf), $fps.Count)

# ---- group arms by pair
$pairs = @{}
foreach ($a in @($ses.arms)) {
    $p = [string]$a.pair
    if (-not $pairs.ContainsKey($p)) { $pairs[$p] = @() }
    $pairs[$p] += $a
}
$rows = @()
foreach ($p in ($pairs.Keys | Sort-Object)) {
    $armsIn = @($pairs[$p])
    $c = @($armsIn | Where-Object { $_.role -eq 'c' }); $d = @($armsIn | Where-Object { $_.role -eq 'd' })
    if ($c.Count -eq 1 -and $d.Count -eq 1) {
        $idc = [string]$c[0].id; $idd = [string]$d[0].id
        if ($fps.ContainsKey($idc) -and $fps.ContainsKey($idd)) {
            $dx = $fps[$idc] - $fps[$idd]
            $rows += [pscustomobject]@{ pair = $p; kind = 'cd'; dist = $c[0].dist; total = $c[0].totalCount
                arm_c = $idc; fps_c = [math]::Round($fps[$idc], 1); arm_d = $idd; fps_d = [math]::Round($fps[$idd], 1)
                dX = [math]::Round($dx, 2); benefit = [math]::Round(-$dx, 2) }
        } else { Write-Host ("   SKIP pair {0}: missing FPS for {1}/{2}" -f $p, $idc, $idd) }
    }
}
# ---- noise scale: D_adj (adjacent identical pair, roles x/y) = WITHIN-PAIR noise -> primary threshold
#                   D_span (session-spanning identical pair, roles a/b or -NoisePair) = drift scale -> contrast
$Dadj = $null; $adjNote = ''; $Dspan = $null; $spanNote = ''
foreach ($p in $pairs.Keys) {
    $xy = @($pairs[$p] | Where-Object { $_.role -eq 'x' -or $_.role -eq 'y' })
    if ($xy.Count -eq 2 -and $fps.ContainsKey([string]$xy[0].id) -and $fps.ContainsKey([string]$xy[1].id)) {
        $Dadj = [math]::Abs($fps[[string]$xy[0].id] - $fps[[string]$xy[1].id])
        $adjNote = ("{0} vs {1} (pair={2}, back-to-back)" -f $xy[0].id, $xy[1].id, $p)
    }
}
if ($NoisePair -ne '') {
    $ids = @($NoisePair -split ',')
    if ($ids.Count -eq 2 -and $fps.ContainsKey($ids[0]) -and $fps.ContainsKey($ids[1])) {
        $Dspan = [math]::Abs($fps[$ids[0]] - $fps[$ids[1]])
        $spanNote = ("{0} vs {1} (identical config, session-spanning)" -f $ids[0], $ids[1])
    }
} else {
    foreach ($p in $pairs.Keys) {
        $ab = @($pairs[$p] | Where-Object { $_.role -eq 'a' -or $_.role -eq 'b' })
        if ($ab.Count -eq 2 -and $fps.ContainsKey([string]$ab[0].id) -and $fps.ContainsKey([string]$ab[1].id)) {
            $Dspan = [math]::Abs($fps[[string]$ab[0].id] - $fps[[string]$ab[1].id])
            $spanNote = ("{0} vs {1} (pair={2}, session-spanning)" -f $ab[0].id, $ab[1].id, $p)
        }
    }
}
$D = $(if ($null -ne $Dadj) { $Dadj } else { $Dspan })
Write-Host ''
Write-Host 'PAIRED (correct reading: within-pair, drift cancels)'
Write-Host ("  {0,-8} {1,-14} {2,-6} {3,-10} {4,-10} {5,-8} {6}" -f 'pair','kind','dist','FPS(occl off)','FPS(occl on)','dX','benefit')
foreach ($r in ($rows | Sort-Object pair)) {
    Write-Host ("  {0,-8} {1,-14} {2,-6} {3,-10} {4,-10} {5,-8} {6}" -f $r.pair, 'c/d', $r.dist, $r.fps_c, $r.fps_d, $r.dX, $r.benefit)
}
$dxs = @($rows | ForEach-Object { [double]$_.dX })
if ($dxs.Count -gt 0) {
    $sorted = @($dxs | Sort-Object); $n = $sorted.Count
    $med = if ($n % 2 -eq 1) { $sorted[[int](($n - 1) / 2)] } else { ($sorted[$n / 2 - 1] + $sorted[$n / 2]) / 2.0 }
    $pos = @($dxs | Where-Object { $_ -gt 0 }).Count; $neg = @($dxs | Where-Object { $_ -lt 0 }).Count
    Write-Host ''
    Write-Host ("PAIRED MEDIAN dX = {0:N2} FPS over {1} pair(s)  (benefit {2:N2} FPS); signs: {3} positive / {4} negative" -f $med, $n, (-$med), $pos, $neg)
    if ($null -ne $Dadj) {
        $X = [math]::Max($Dadj, 1.0)
        Write-Host ("NOISE (adjacent) D_adj = {0:N2} FPS  [{1}]  <- within-pair scale = THE ONLY valid threshold" -f $Dadj, $adjNote)
        if ($null -ne $Dspan) { Write-Host ("NOISE (span)     D_span= {0:N2} FPS  [{1}]  <- drift scale, CONTRAST ONLY (never a threshold)" -f $Dspan, $spanNote) }
        Write-Host ("THRESHOLD    X = max(D_adj, 1.0) = {0:N2} FPS  <- minimum readable effect at {1} pairs" -f $X, $n)
        $verdict = if ([math]::Abs($med) -ge $X) { 'READABLE' } else { 'BELOW NOISE FLOOR' }
        Write-Host ("VERDICT      |paired median| = {0:N2} vs X = {1:N2}  ->  {2}" -f [math]::Abs($med), $X, $verdict)
        if ($verdict -eq 'BELOW NOISE FLOOR') {
            Write-Host ("             => this rig cannot resolve effects smaller than {0:N2} FPS here; report that as the" -f $X)
            Write-Host  '                effective NEGATIVE result (not "unmeasurable because the fixture failed").'
        }
    } else {
        # Rule: without a back-to-back identical-config pair there is NO valid noise scale.
        # Refuse to fall back to D_span (that is the drift scale, not the measurement noise).
        Write-Host 'NO VALID NOISE SCALE: this session has no back-to-back identical-config pair (no D_adj).'
        Write-Host '                     D_span (if any) only indicates session drift and MUST NOT be used as a threshold.'
        Write-Host ("VERDICT      paired median = {0:N2} FPS over {1} pair(s), but WITHOUT a noise scale the result is NOT READABLE." -f $med, $n)
        Write-Host '             => fix the spec: add >=1 back-to-back identical-config arm pair (checker invariant #7 enforces it when _paired=true), then re-run.'
        $verdict = 'NO VALID NOISE SCALE (NOT READABLE)'
    }
    # ---- legacy WRONG reading, kept only to explain the earlier failure
    $byGroup = @{}
    foreach ($r in $rows) { $k = ("dist={0} total={1}" -f $r.dist, $r.total); if (-not $byGroup.ContainsKey($k)) { $byGroup[$k] = @() }; $byGroup[$k] += $r }
    Write-Host ''
    Write-Host 'LEGACY (WRONG reading, do not use: cross-arm medians)'
    foreach ($k in ($byGroup.Keys | Sort-Object)) {
        $g = @($byGroup[$k]); $cm = @($g | ForEach-Object { [double]$_.fps_c } | Sort-Object); $dm = @($g | ForEach-Object { [double]$_.fps_d } | Sort-Object)
        $cmv = ($cm | Measure-Object -Average).Average; $dmv = ($dm | Measure-Object -Average).Average
        Write-Host ("  {0,-22} mean(FPS c)={1:N1} mean(FPS d)={2:N1}  cross-arm diff={3:N2}  <- inflates/masks by session drift" -f $k, $cmv, $dmv, ($cmv - $dmv))
    }
    if ($Json -ne '') {
        $payload = [ordered]@{ spec = (Split-Path $Spec -Leaf); session = $Session; summary = (Split-Path $Summary -Leaf)
            pairs = $rows; paired_median_dX = $med; benefit = (-$med); signs_positive = $pos; signs_negative = $neg
            noise_D_adj = $Dadj; noise_D_span = $Dspan; threshold_X = $(if ($null -ne $Dadj) { [math]::Max($Dadj, 1.0) } else { $null })
            verdict = $verdict }
        [System.IO.File]::WriteAllText($Json, ($payload | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
        Write-Host ("`nwrote {0}" -f $Json)
    }
    if ($null -eq $Dadj) { Write-Host 'EXIT 3: no valid noise scale (see VERDICT above).'; exit 3 }
} else { Write-Host 'no complete c/d pairs with FPS -- nothing to analyse'; exit 1 }
exit 0
