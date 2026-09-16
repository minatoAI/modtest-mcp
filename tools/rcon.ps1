# rcon.ps1 - minimal Source RCON client (Windows PowerShell 5.1 / PowerShell 7, raw TCP, no deps).
# Use: send commands to a LOCAL test server over RCON (typically port 25575),
#      instead of typing into the server console. Never use it against servers you do not own.
# Usage: pwsh -NoProfile -File tools/rcon.ps1 -ServerHost 127.0.0.1 -Password <secret> -Command "list"
# No default host and no default password: both must be supplied (or set MODTEST_RCON_HOST / MODTEST_RCON_PASSWORD). Fails with a non-zero exit code; errors go to stderr.
param(
  [Parameter(Mandatory=$true)][string]$Command,
  [string]$ServerHost = $env:MODTEST_RCON_HOST,
  [int]$Port = 25575,
  [Parameter(Mandatory=$true)][string]$Password = $env:MODTEST_RCON_PASSWORD,
  [int]$TimeoutMs = 8000
)
$ErrorActionPreference = 'Stop'

function New-Packet([int]$id, [int]$type, [string]$payload) {
  $body = [Text.Encoding]::ASCII.GetBytes($payload)
  $len = 4 + 4 + $body.Length + 2
  $ms = New-Object System.IO.MemoryStream
  $bw = New-Object System.IO.BinaryWriter($ms)
  $bw.Write([int]$len); $bw.Write([int]$id); $bw.Write([int]$type)
  $bw.Write($body); $bw.Write([byte]0); $bw.Write([byte]0)
  $bw.Flush()
  return ,$ms.ToArray()
}

function Read-Exact([System.IO.NetworkStream]$s, [int]$n) {
  $buf = New-Object byte[] $n
  $off = 0
  while ($off -lt $n) {
    $r = $s.Read($buf, $off, $n - $off)
    if ($r -le 0) { throw 'RCON connection closed early' }
    $off += $r
  }
  return ,$buf
}

function Read-Packet([System.IO.NetworkStream]$s) {
  $lenBuf = Read-Exact $s 4
  $len = [BitConverter]::ToInt32($lenBuf, 0)
  if ($len -lt 10 -or $len -gt 8192) { throw ('RCON bad length ' + $len) }
  $rest = Read-Exact $s $len
  $id = [BitConverter]::ToInt32($rest, 0)
  $text = [Text.Encoding]::ASCII.GetString($rest, 8, $len - 10)
  return @{ id = $id; text = $text }
}

$client = New-Object System.Net.Sockets.TcpClient
$task = $client.ConnectAsync($ServerHost, $Port)
if (-not $task.Wait($TimeoutMs)) { throw ('RCON connect timeout ' + $ServerHost + ':' + $Port) }
$stream = $client.GetStream()
$stream.ReadTimeout = $TimeoutMs
try {
  $auth = New-Packet 1 3 $Password
  $stream.Write($auth, 0, $auth.Length)
  $resp = Read-Packet $stream
  if ($resp.id -eq -1) { throw 'RCON auth failed (check rcon.password)' }
  $cmd = New-Packet 2 2 $Command
  $stream.Write($cmd, 0, $cmd.Length)
  $resp2 = Read-Packet $stream
  Write-Output $resp2.text.TrimEnd()
  if ($resp2.id -eq -1) { exit 1 }
  exit 0
} finally {
  $client.Close()
}
