# SPDX-License-Identifier: GPL-3.0-or-later
# fingerprint-parity.test.ps1 -- CROSS-IMPLEMENTATION test: the Java rule and the PowerShell port must
# agree, case by case, on a corpus that neither side generated.
#
# WHY THIS EXISTS: two implementations of one rule drifted apart in public. The mod normalized date
# headers (dev.taclight.interop.PackFingerprint.normalizeForDigest, task-23) while the harness fingerprint
# hashed a manifest of RAW per-file hashes, so the SAME jar produced 01555353... vs E72BEC56... in harness
# reports and equal digests on the mod side -- two contradictory "same package?" answers. Porting the rule
# to PowerShell fixes today's disagreement; this test is what stops the next one.
#
# WHY A SHARED CORPUS: if each side carried its own copy of the cases, the copies would drift too. Both
# read tools/fingerprint-corpus.json, every case carries an `expect` derived BY REASONING from the rule
# text, and the corpus file's own sha256 is printed so a later weakening of the corpus is visible.
#
# TWO CONDITIONS PER CASE (both required):
#   1. Java digest == PowerShell digest          (implementations agree)
#   2. that digest == the pre-declared expectation (both are actually right)
# Condition 1 alone would let a shared error pass -- the classic way a cross-check decays into
# self-certification.
#
# The Java side is really executed: a small driver is written to a temp directory and run with
# `java -cp <mod classes> Driver.java`, so it uses the SHIPPED PackFingerprint bytes, not a re-implementation.
#
# ASCII only. Usage:
#   pwsh tools/fingerprint-parity.test.ps1 [-StrictRealFiles] [-RequireJava] [-KeepTemp]
#        [-GameDirA <dir>] [-GameDirB <dir>] [-ArchivedBlockProperties <file>] [-ModClasses <dir>]
# Exit: 0 all executed checks passed | 1 at least one failed | 2 setup error.
# Real-file checks report SKIP loudly when the game directory is absent (they are never silently green).

[CmdletBinding()]
param(
    [string]$CorpusPath = '',
    [string]$ImplPath = '',
    [string]$ModClasses = 'E:\dshHome\mc-mod-spotlight-attachment\taclight\build\classes\java\main',
    [string]$JavaExe = 'java',
    [string]$GameDirA = 'E:\temp\mc-test\.minecraft\versions\1.20.1-Forge',
    [string]$GameDirB = 'E:\temp\mc-test\.minecraft\versions\1.20.1-Forge-B',
    [string]$ArchivedBlockProperties = 'E:\dshHome\ray-traced-spotlight-mod-dev\docs\evidence\2026-09-27-fingerprint-verify\run1-block.properties',
    # A file exists but its digests contradict the expectation: fail instead of skipping.
    [switch]$StrictRealFiles,
    # java or the mod classes missing is a FAIL rather than a loud SKIP.
    [switch]$RequireJava,
    [switch]$KeepTemp
)

$ErrorActionPreference = 'Stop'

if ($CorpusPath.Length -eq 0) { $CorpusPath = Join-Path $PSScriptRoot 'fingerprint-corpus.json' }
if ($ImplPath.Length -eq 0) { $ImplPath = Join-Path $PSScriptRoot 'pack-fingerprint.ps1' }
foreach ($required in @($CorpusPath, $ImplPath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { Write-Output ('SETUP-ERROR: not found: ' + $required); exit 2 }
}
$CorpusPath = (Resolve-Path -LiteralPath $CorpusPath).Path
$ImplPath = (Resolve-Path -LiteralPath $ImplPath).Path

$script:Total = 0
$script:Passed = 0
$script:Failed = 0
$script:Skipped = 0
function Add-Check {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    $script:Total++
    if ($Condition) { $script:Passed++; Write-Output ('  ok    ' + $Name) }
    else { $script:Failed++; Write-Output ('  FAIL  ' + $Name + $(if ($Detail.Length -gt 0) { ' -- ' + $Detail } else { '' })) }
}
function Add-Skip {
    param([string]$Name, [string]$Reason)
    $script:Skipped++
    Write-Output ('  SKIP  ' + $Name + ' -- ' + $Reason)
}

function ConvertTo-Visible([string]$Text) {
    # First 80 characters with control characters made visible, for mismatch reporting.
    if ($null -eq $Text) { return '<null>' }
    $head = $Text.Substring(0, [Math]::Min(80, $Text.Length))
    return ($head -replace "`r", '\r' -replace "`n", '\n' -replace "`t", '\t' -replace "`b", '\b' -replace "`f", '\f')
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('fingerprint-parity-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null

# ---------------------------------------------------------------- corpus
$corpusSha = (Get-FileHash -LiteralPath $CorpusPath -Algorithm SHA256).Hash
$implShaBefore = (Get-FileHash -LiteralPath $ImplPath -Algorithm SHA256).Hash
$corpus = Get-Content -LiteralPath $CorpusPath -Raw | ConvertFrom-Json
Write-Output ('FINGERPRINT-PARITY corpus=' + $CorpusPath)
Write-Output ('FINGERPRINT-PARITY corpusSha256=' + $corpusSha)
Write-Output ('FINGERPRINT-PARITY impl=' + $ImplPath + ' implSha256=' + $implShaBefore)

$cases = @($corpus.cases)
Add-Check 'the corpus declares its schema' ($corpus.schema -eq 'fingerprint-corpus/v1') ('schema=' + $corpus.schema)
Add-Check 'the corpus has cases' ($cases.Count -gt 0) ('count=' + $cases.Count)
$ids = @($cases | ForEach-Object { $_.id })
Add-Check 'case ids are unique' ((@($ids | Sort-Object -Unique).Count) -eq $ids.Count)
Add-Check 'every case declares an expectation' (@($cases | Where-Object { $null -eq $_.expect }).Count -eq 0) ('without=' + (@($cases | Where-Object { $null -eq $_.expect }).Count))
$handWritten = @($cases | Where-Object { $_.kind -ne 'real-sample' })
Add-Check 'every hand-written case has an explicit expected normalized TEXT' (@($handWritten | Where-Object { $null -eq $_.expect.normalized }).Count -eq 0)
Add-Check 'the corpus covers the divergence traps (D: ascii classes / java line terminators)' ((@($cases | Where-Object { $_.group -eq 'D' }).Count -ge 6))
Add-Check 'the corpus covers the \\R members VT and FF (E2/E3)' ((@($cases | Where-Object { $_.id -in @('E2', 'E3') }).Count -eq 2))

# ---------------------------------------------------------------- PowerShell side
. $ImplPath
$psResults = [ordered]@{}
foreach ($case in $cases) {
    $text = $null
    if ($case.kind -eq 'real-sample') {
        $role = [string]$case.sourceRole
        $file = switch ($role) {
            'slotA' { Join-Path $GameDirA 'patched_shaders\block.properties' }
            'slotB' { Join-Path $GameDirB 'patched_shaders\block.properties' }
            'archived' { $ArchivedBlockProperties }
            default { '' }
        }
        if ($file.Length -eq 0 -or -not (Test-Path -LiteralPath $file -PathType Leaf)) { $psResults[$case.id] = $null; continue }
        $text = [System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($file))
        # Slice: real first line + N middle lines + N tail lines (keeps the corpus small but real).
        $lines = $text -split "`r?`n"
        $head = [int]$case.slice.headLines
        $mid = [int]$case.slice.middleLines
        $tail = [int]$case.slice.tailLines
        $headPart = @($lines | Select-Object -First $head)
        $midPart = @($lines | Select-Object -Skip 1 -First $mid)
        $tailPart = if ($lines.Count -gt ($tail + 1)) { @($lines | Select-Object -Last $tail) } else { @() }
        $text = (@($headPart + $midPart + $tailPart) -join "`r`n")
    } else {
        $text = [string]$case.text
    }
    $fp = Get-PackTextFingerprint -RelPath ([string]$case.relPath) -Text $text
    $psResults[$case.id] = [pscustomobject]@{ normalizedText = $fp.normalizedText; digest = $fp.normalized; raw = $fp.raw }
}

# ---------------------------------------------------------------- Java side (really executed)
$javaDriverPath = Join-Path $tempRoot 'FingerprintParityDriver.java'
$driverSource = @'
import dev.taclight.interop.PackFingerprint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the SHARED corpus, applies the SHIPPED PackFingerprint rule, prints one line per case. */
public final class FingerprintParityDriver {

    public static void main(String[] args) throws Exception {
        Map<String, String> roles = readRoles(Path.of(args[1]));
        String corpus = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        for (Map<String, Object> c : Json.cases(corpus)) {
            String id = (String) c.get("id");
            String relPath = (String) c.get("relPath");
            String role = (String) c.get("sourceRole");
            String text;
            if (role != null) {
                Path p = Path.of(roles.get(role));
                if (!Files.isRegularFile(p)) { System.out.println(id + "\t<SKIP>"); continue; }
                text = slice(Files.readString(p, StandardCharsets.UTF_8), c);
            } else {
                text = (String) c.get("text");
            }
            String norm = PackFingerprint.normalizeForDigest(relPath, text);
            System.out.println(id + "\t" + Base64.getEncoder().encodeToString(norm.getBytes(StandardCharsets.UTF_8))
                    + "\t" + PackFingerprint.sha256Prefix16(norm));
        }
    }

    private static Map<String, String> readRoles(Path p) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        if (!Files.isRegularFile(p)) return m;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            int i = line.indexOf('=');
            if (i > 0) m.put(line.substring(0, i), line.substring(i + 1));
        }
        return m;
    }

    /** real first line + middle N + tail N, matching the PowerShell side's slicing. */
    private static String slice(String text, Map<String, Object> c) {
        Map<String, Object> s = (Map<String, Object>) c.get("slice");
        int head = (int) (double) s.get("headLines");
        int mid = (int) (double) s.get("middleLines");
        int tail = (int) (double) s.get("tailLines");
        String[] lines = text.split("\r?\n", -1);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(head, lines.length); i++) out.add(lines[i]);
        for (int i = 1; i < Math.min(1 + mid, lines.length); i++) out.add(lines[i]);
        for (int i = Math.max(0, lines.length - tail); i < lines.length; i++) out.add(lines[i]);
        return String.join("\r\n", out);
    }

    // ---- minimal JSON reader (objects, arrays, strings with escapes, numbers, booleans, null) ----
    static final class Json {
        private final String s;
        private int i;
        private Json(String s) { this.s = s; }

        @SuppressWarnings("unchecked")
        static List<Map<String, Object>> cases(String text) {
            Map<String, Object> root = (Map<String, Object>) new Json(text).value();
            return (List<Map<String, Object>>) (Object) root.get("cases");
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                i++; // ':'
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
            }
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case '/': sb.append('/'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new IllegalStateException("bad escape \\" + e);
                }
            }
        }

        private Double number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.valueOf(s.substring(start, i));
        }
    }
}
'@
[System.IO.File]::WriteAllText($javaDriverPath, $driverSource, (New-Object System.Text.UTF8Encoding($false)))
Write-Output ('FINGERPRINT-PARITY driverSha256=' + (Get-FileHash -LiteralPath $javaDriverPath -Algorithm SHA256).Hash)

$rolesPath = Join-Path $tempRoot 'roles.properties'
# Build the lines one at a time: `@('a=' + $x, 'b=' + $y)` parses as string concatenation with an array
# (the comma binds tighter than +), which silently produced ONE space-joined line and made the Java driver
# see a bogus path. That is the pitfall already recorded in this repository's README list.
$roleLines = New-Object System.Collections.Generic.List[string]
$roleLines.Add('slotA=' + (Join-Path $GameDirA 'patched_shaders\block.properties')) | Out-Null
$roleLines.Add('slotB=' + (Join-Path $GameDirB 'patched_shaders\block.properties')) | Out-Null
$roleLines.Add('archived=' + $ArchivedBlockProperties) | Out-Null
[System.IO.File]::WriteAllLines($rolesPath, $roleLines.ToArray(), (New-Object System.Text.UTF8Encoding($false)))

$javaResults = [ordered]@{}
$javaAvailable = $true
$javaReason = ''
$javaPath = (Get-Command $JavaExe -ErrorAction SilentlyContinue)
if ($null -eq $javaPath) { $javaAvailable = $false; $javaReason = ('java not found: ' + $JavaExe) }
elseif (-not (Test-Path -LiteralPath (Join-Path $ModClasses 'dev\taclight\interop\PackFingerprint.class'))) {
    $javaAvailable = $false; $javaReason = ('mod classes not built (no PackFingerprint.class under ' + $ModClasses + ')')
}
if ($javaAvailable) {
    $javaOut = @(& $JavaExe -cp $ModClasses $javaDriverPath $CorpusPath $rolesPath 2>&1 | ForEach-Object { [string]$_ })
    $javaExit = $LASTEXITCODE
    if ($javaExit -ne 0) {
        Write-Output '--- java driver output ---'
        $javaOut | Select-Object -Last 20 | ForEach-Object { Write-Output ('    ' + $_) }
    }
    Add-Check 'the java driver ran against the SHIPPED PackFingerprint' ($javaExit -eq 0) ('exit=' + $javaExit)
    foreach ($line in $javaOut) {
        $parts = $line -split "`t"
        if ($parts.Count -ge 3 -and $parts[1] -ne '<SKIP>') {
            $javaResults[$parts[0]] = [pscustomobject]@{
                normalizedText = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($parts[1]))
                digest = $parts[2]
            }
        }
    }
    Add-Check 'the java driver reported at least one case' ($javaResults.Count -gt 0) ('cases=' + $javaResults.Count)
} else {
    if ($RequireJava) { Add-Check 'java side available' $false $javaReason } else { Add-Skip 'java side' $javaReason }
}

# ---------------------------------------------------------------- comparison (both conditions)
foreach ($case in $cases) {
    $ps = $psResults[$case.id]
    if ($null -eq $ps) { Add-Skip ('case ' + $case.id) 'real sample unavailable (game dir or archive missing)'; continue }
    $java = $null
    if ($javaAvailable) { $java = $javaResults[$case.id] }
    if ($null -ne $java) {
        $agree = ($ps.digest -eq $java.digest)
        Add-Check ('case ' + $case.id + ' [1] java and powershell agree') $agree ('ps=' + $ps.digest + ' java=' + $java.digest)
        if (-not $agree) {
            Write-Output ('        ps  normalized: ' + (ConvertTo-Visible $ps.normalizedText))
            Write-Output ('        java normalized: ' + (ConvertTo-Visible $java.normalizedText))
        }
    }
    if ($case.kind -eq 'real-sample') { continue }
    # condition 2: BOTH must match the pre-declared expectation
    $expectText = [string]$case.expect.normalized
    Add-Check ('case ' + $case.id + ' [2] powershell matches the declared expectation') ($ps.normalizedText -eq $expectText) ('got=' + (ConvertTo-Visible $ps.normalizedText) + ' want=' + (ConvertTo-Visible $expectText))
    if ($null -ne $java -and $java.normalizedText -ne $expectText) {
        Add-Check ('case ' + $case.id + ' [2] java matches the declared expectation') $false ('got=' + (ConvertTo-Visible $java.normalizedText) + ' want=' + (ConvertTo-Visible $expectText))
    }
}

# ---------------------------------------------------------------- field semantics (the three difference kinds)
# Acceptance for the two fields needs three comparisons, not one: a header re-stamp must move raw only; a
# real content change and a change in a NON-.properties file must move BOTH (the rule must not over-reach).
Write-Output '--- field semantics: raw vs normalized on three kinds of difference ---'
$auditTool = Join-Path $PSScriptRoot 'patched-shaders-audit.ps1'
if (-not (Test-Path -LiteralPath $auditTool -PathType Leaf)) {
    Add-Check 'patched-shaders-audit.ps1 is available for the field-semantics checks' $false ('not found: ' + $auditTool)
} else {
    function New-Dump {
        param([string]$Root, [string]$HeaderDate, [string]$KeyValue, [string]$FshComment)
        $dir = Join-Path $tempRoot ('dump-' + $Root)
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        Set-Content -LiteralPath (Join-Path $dir 'block.properties') -Encoding UTF8 -Value ("#Sat Sep 26 $HeaderDate CST 2026`nkey=$KeyValue`nkey2=value2`n")
        Set-Content -LiteralPath (Join-Path $dir 'shader.fsh') -Encoding UTF8 -Value ("// $FshComment`nvoid main(){}`n")
        return $dir
    }
    function Invoke-AuditFields {
        param([string]$DumpDir, [string]$Label)
        $ev = Join-Path $tempRoot ('ev-' + $Label)
        New-Item -ItemType Directory -Force -Path $ev | Out-Null
        $argv = @('-NoProfile', '-File', $auditTool, '-ShadersDir', $DumpDir, '-Evidence', $ev, '-Label', $Label, '-MinFiles', '1')
        $null = @(& pwsh @argv 2>&1 | ForEach-Object { [string]$_ })
        $reportPath = Join-Path $ev ($Label + '-report.json')
        if (-not (Test-Path -LiteralPath $reportPath)) { return $null }
        $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
        return [pscustomobject]@{ raw = $report.fingerprintRaw; normalized = $report.fingerprintNormalized; alias = $report.manifestSha256 }
    }
    $base = New-Dump -Root 'base' -HeaderDate '01:00:00' -KeyValue 'value' -FshComment 'base'
    $headerOnly = New-Dump -Root 'header' -HeaderDate '02:00:00' -KeyValue 'value' -FshComment 'base'
    $realChange = New-Dump -Root 'real' -HeaderDate '01:00:00' -KeyValue 'CHANGED' -FshComment 'base'
    $fshChange = New-Dump -Root 'fsh' -HeaderDate '01:00:00' -KeyValue 'value' -FshComment 'a #Sat Sep 26 03:00:00 CST 2026 style line'
    $fBase = Invoke-AuditFields -DumpDir $base -Label 'sem-base'
    $fHeader = Invoke-AuditFields -DumpDir $headerOnly -Label 'sem-header'
    $fReal = Invoke-AuditFields -DumpDir $realChange -Label 'sem-real'
    $fFsh = Invoke-AuditFields -DumpDir $fshChange -Label 'sem-fsh'
    if ($null -eq $fBase -or $null -eq $fHeader -or $null -eq $fReal -or $null -eq $fFsh) {
        Add-Check 'the audit tool produced both fields for every synthetic dump' $false 'a report was missing'
    } else {
        Add-Check 'header re-stamp: raw DIFFERS, normalized is EQUAL' (($fBase.raw -ne $fHeader.raw) -and ($fBase.normalized -eq $fHeader.normalized)) ('raw=' + $fBase.raw.Substring(0, 12) + '/' + $fHeader.raw.Substring(0, 12) + ' norm=' + $fBase.normalized.Substring(0, 12))
        Add-Check 'real content change: BOTH differ (normalized must not hide it)' (($fBase.raw -ne $fReal.raw) -and ($fBase.normalized -ne $fReal.normalized)) ('norm=' + $fBase.normalized.Substring(0, 12) + '/' + $fReal.normalized.Substring(0, 12))
        Add-Check 'change in a .fsh only: BOTH differ (the rule must not over-reach past *.properties)' (($fBase.raw -ne $fFsh.raw) -and ($fBase.normalized -ne $fFsh.normalized)) ('norm=' + $fBase.normalized.Substring(0, 12) + '/' + $fFsh.normalized.Substring(0, 12))
        Add-Check 'the deprecated manifestSha256 alias still reports the RAW value' (($fBase.alias -eq $fBase.raw) -and ($fHeader.alias -ne $fHeader.normalized))
    }
}

# ---------------------------------------------------------------- real files end to end
Write-Output '--- real files: raw must differ, normalized must match ---'
$realPaths = [ordered]@{
    slotA = (Join-Path $GameDirA 'patched_shaders\block.properties')
    slotB = (Join-Path $GameDirB 'patched_shaders\block.properties')
    archived = $ArchivedBlockProperties
}
$present = @()
foreach ($role in $realPaths.Keys) { if (Test-Path -LiteralPath $realPaths[$role] -PathType Leaf) { $present += $role } }
if ($present.Count -lt 3) {
    $missing = @($realPaths.Keys | Where-Object { $present -notcontains $_ })
    if ($StrictRealFiles) {
        Add-Check 'all three real samples are present (strict mode)' $false ('missing=' + ($missing -join ',') + ' -- strict mode turns "cannot verify" into a failure')
    } else {
        Add-Skip 'real-file end-to-end (raw differs, normalized matches)' ('missing=' + ($missing -join ',') + '; pass -StrictRealFiles to fail instead')
    }
} else {
    $raws = @()
    $norms = @()
    foreach ($role in $realPaths.Keys) {
        $fp = Get-PackFileFingerprint -RelPath 'patched_shaders/block.properties' -Path $realPaths[$role]
        $raws += $fp.raw
        $norms += $fp.normalized
    }
    Add-Check 'raw digests of the three rounds DIFFER (the field is round-sensitive)' ((@($raws | Sort-Object -Unique).Count) -eq 3) ('raw=' + ($raws -join ','))
    Add-Check 'normalized digests of the three rounds are EQUAL (same package)' ((@($norms | Sort-Object -Unique).Count) -eq 1) ('normalized=' + ($norms -join ','))
}

# ---------------------------------------------------------------- red control: the naive port must fail
Write-Output '--- red control: naive \w / \d and .NET (?m) must break the killers ---'
$naivePath = Join-Path $tempRoot 'pack-fingerprint-naive.ps1'
$implText = [System.IO.File]::ReadAllText($ImplPath)
# The naive variant = the real implementation with ONLY the pattern line replaced by the two mistakes this
# test exists to catch: .NET's Unicode \w/\d, and .NET's (?m)^ (which ignores lone CR / U+2028), plus a
# terminator class that omits VT and FF.
$naivePattern = '(?m)^#\w{3} \w{3} \d{2} \d{2}:\d{2}:\d{2} \w+ \d{4}(?:\r\n|[\n\r\u0085\u2028\u2029])?'
$literalOld = "`$script:PackDateHeaderPattern = '" + $script:PackDateHeaderPattern + "'"
$literalNew = "`$script:PackDateHeaderPattern = '" + $naivePattern + "'"
$naiveText = $implText.Replace($literalOld, $literalNew)
Add-Check 'the naive variant could be built from the real implementation' ($naiveText -ne $implText)
[System.IO.File]::WriteAllText($naivePath, $naiveText, (New-Object System.Text.UTF8Encoding($false)))
. $naivePath
$naiveVerdicts = [ordered]@{}
foreach ($case in $cases) {
    if ($case.kind -eq 'real-sample') { continue }
    $got = Get-PackNormalizedText -RelPath ([string]$case.relPath) -Text ([string]$case.text)
    $naiveVerdicts[$case.id] = ($got -eq [string]$case.expect.normalized)
}
$mustBreak = @('D1', 'D2', 'D3', 'D4', 'D5', 'D6', 'E2', 'E3')
$mustHold = @('B1', 'B7', 'B8', 'C10')
$broke = @($mustBreak | Where-Object { $naiveVerdicts[$_] -eq $true })
$held = @($mustHold | Where-Object { $naiveVerdicts[$_] -eq $false })
Add-Check 'the naive port BREAKS every killer case (the corpus has teeth)' ($broke.Count -eq 0) ('still green under the naive port: ' + ($broke -join ','))
Add-Check 'the naive port still passes the non-killer cases (not a blanket failure)' ($held.Count -eq 0) ('wrongly red: ' + ($held -join ','))
$implShaAfter = (Get-FileHash -LiteralPath $ImplPath -Algorithm SHA256).Hash
Add-Check 'the tested implementation file was not modified by this test (sha256 unchanged)' ($implShaAfter -eq $implShaBefore) ('before=' + $implShaBefore + ' after=' + $implShaAfter)
Write-Output ('FINGERPRINT-PARITY implSha256-after=' + $implShaAfter)

if (-not $KeepTemp) {
    try { Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue } catch { }
} else {
    Write-Output ('FINGERPRINT-PARITY temp kept at ' + $tempRoot)
}
Write-Output ('FINGERPRINT-PARITY ' + $(if ($script:Failed -eq 0) { 'PASS' } else { 'FAIL' }) + ' (' + $script:Passed + '/' + $script:Total + ' checks passed' + $(if ($script:Skipped -gt 0) { ', ' + $script:Skipped + ' skipped' } else { '' }) + ')')
if ($script:Failed -eq 0) { exit 0 }
exit 1
