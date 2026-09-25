# SPDX-License-Identifier: GPL-3.0-or-later
# pack-fingerprint.ps1 -- the ONE PowerShell implementation of the package-digest rule used for
# "same package" judgments. It is a faithful port of the mod side's
# dev.taclight.interop.PackFingerprint.normalizeForDigest + sha256Prefix16.
#
# WHY A SINGLE PORT: the harness used to judge "same package" with its own rule (sha256 of a manifest
# built from RAW per-file hashes), while the mod judged it with a normalized rule. The two therefore
# disagreed in public: on 2026-09-27 the same jar produced 01555353... and E72BEC56... in harness reports
# and equal digests on the mod side. Two implementations of one rule drift; tools/fingerprint-parity.test.ps1
# now feeds a fixed corpus to BOTH sides and compares, so drift is a test failure instead of a surprise.
#
# THE RULE (authoritative, Java):
#   normalizeForDigest(relPath, text):
#     if relPath is null or does not (case-insensitively) end with ".properties": return text unchanged
#     else remove every occurrence of  (?m)^#\w{3} \w{3} \d{2} \d{2}:\d{2}:\d{2} \w+ \d{4}\R?
#   digest = first 16 lowercase hex chars of SHA-256 over the UTF-8 bytes of the NORMALIZED TEXT
#            (not the file bytes, and not a hash of hashes)
#
# THREE JAVA DETAILS THAT A NAIVE .NET PORT GETS WRONG (each has a corpus case):
#   1. \w and \d in Java are ASCII by default: `\w` = [A-Za-z0-9_], `\d` = [0-9]. .NET's are Unicode, so a
#      date header containing e.g. 'M\u00e4r' or Arabic-Indic digits would be stripped by .NET and kept by
#      Java. Written out explicitly below.                      (corpus D1-D4)
#   2. \R = \u000D\u000A | [\u000A\u000B\u000C\u000D\u0085\u2028\u2029]. It includes VT (\u000B) and FF
#      (\u000C); a terminator class of \r\n|[\n\r\u0085\u2028\u2029] silently omits both.   (corpus E2/E3)
#   3. (?m)^ in Java matches after ANY Java line terminator (\n, \r, \r\n, \u0085, \u2028, \u2029), while
#      .NET's (?m)^ matches only after \n. Emulated with an explicit lookbehind instead of (?m).   (D5/D6/D7)
#
# FIXED DEFECT (task-49, found by this rule's own corpus): java.util.Properties.store() writes
# single-digit days RIGHT-ALIGNED in a two-character field, i.e. "#Thu Jan  1 ...", and the original rule
# required \d{2}. Such a header was NOT stripped, so on days 1-9 the normalized digest still drifted and
# "same package?" silently failed exactly when a month started. The day field now accepts all the shapes
# Properties.store() can emit -- " 1", "1", "01", "10" -- as (?:[0-9]{2}|[0-9]| [0-9]).
# ONLY the day field was widened: the hour stays \d{2} (corpus C12: "#Thu Jan  1 2:03:04 ..." must NOT be
# stripped), and the three Java semantics below are untouched.
# BOTH SIDES MUST CHANGE TOGETHER: this port is task-49 and the Java class is task-50. Until task-50 lands
# the cross-implementation test reports these cases as a DECLARED, LOUD divergence (the corpus says
# pendingJavaSide) rather than quietly agreeing with a half-updated rule -- and the test FAILS if a side
# changes without the other, or if the declaration outlives the divergence.
#
# ASCII only. Dot-source it to use the functions, or run it to self-report.
#   . tools/pack-fingerprint.ps1
#   Get-PackNormalizedText -RelPath 'a.properties' -Text $t
#   Get-PackTextFingerprint -RelPath 'a.properties' -Text $t   # -> object with raw + normalized digests

# (?:\A|(?<=[\n\r\u0085\u2028\u2029])) emulates Java's multiline ^ (see note 3 above).
# The trailing (?:\r\n|[\n\u000B\u000C\r\u0085\u2028\u2029])? emulates Java's \R? (see note 2 above).
$script:PackDateHeaderPattern = '(?:\A|(?<=[\n\r\u0085\u2028\u2029]))#[A-Za-z0-9_]{3} [A-Za-z0-9_]{3} (?:[0-9]{2}|[0-9]| [0-9]) [0-9]{2}:[0-9]{2}:[0-9]{2} [A-Za-z0-9_]+ [0-9]{4}(?:\r\n|[\n\u000B\u000C\r\u0085\u2028\u2029])?'

function Test-PackPropertiesPath([string]$RelPath) {
    # Java: relPath.toLowerCase(Locale.ROOT).endsWith(".properties"). ToLowerInvariant is the locale-free
    # equivalent (avoids the Turkish-I trap); the extension test is deliberately case-insensitive.
    if ([string]::IsNullOrEmpty($RelPath)) { return $false }
    return $RelPath.ToLowerInvariant().EndsWith('.properties')
}

function Get-PackNormalizedText([string]$RelPath, [string]$Text) {
    if ($null -eq $Text) { return $null }
    if (-not (Test-PackPropertiesPath -RelPath $RelPath)) { return $Text }
    return [regex]::Replace($Text, $script:PackDateHeaderPattern, '')
}

function Get-PackSha256Prefix16([string]$Text) {
    # First 16 lowercase hex chars = first 8 bytes of SHA-256 over the text's UTF-8 bytes.
    if ($null -eq $Text) { return '' }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $digest = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Text))
    } finally {
        $sha.Dispose()
    }
    $sb = New-Object System.Text.StringBuilder
    for ($i = 0; $i -lt 8; $i++) { [void]$sb.Append($digest[$i].ToString('x2')) }
    return $sb.ToString()
}

function Get-PackTextFingerprint([string]$RelPath, [string]$Text) {
    # BOTH fields, each with a distinct question it can answer:
    #   raw        -- any byte change at all; "was this artifact modified?"
    #   normalized -- changes that matter for package identity; "are these two artifacts the same package?"
    $normalized = Get-PackNormalizedText -RelPath $RelPath -Text $Text
    return [pscustomobject]@{
        raw = (Get-PackSha256Prefix16 -Text $Text)
        normalized = (Get-PackSha256Prefix16 -Text $normalized)
        normalizedText = $normalized
    }
}

function Get-PackFileFingerprint([string]$RelPath, [string]$Path) {
    # File-level convenience: decodes as UTF-8, exactly as Java's readFile does (new String(bytes, UTF_8)).
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    return Get-PackTextFingerprint -RelPath $RelPath -Text $text
}

if ($MyInvocation.InvocationName -ne '.') {
    Write-Output 'pack-fingerprint.ps1 -- single PowerShell implementation of the package digest rule.'
    Write-Output '  dot-source it:  . tools/pack-fingerprint.ps1'
    Write-Output '  then:           Get-PackTextFingerprint -RelPath a.properties -Text $t   (raw + normalized)'
    Write-Output '  parity proof:   pwsh tools/fingerprint-parity.test.ps1'
}
