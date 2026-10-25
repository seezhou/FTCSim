# FTCSim — documentation freshness check.
#
# Each doc under docs/ can carry a `refs:` list in its front matter, naming the source
# locations the prose depends on:
#
#   ---
#   summary: 一句话
#   refs:
#     - path: sim/src/main/java/ftcsim/physics/MotorState.java
#       anchor: "public double controlStep(double dt)"
#       lines: 126-153
#       hash: 3f9a1c2b8e40
#       since: fb0d20e
#   ---
#
# The point of `anchor` (instead of trusting `lines`) is that line numbers drift: the
# checker re-locates the anchor, takes the same number of lines from wherever it is now,
# and hashes that. So "the code moved but did not change" is not reported as stale.
#
# Usage:
#   .\tools\docs-check.ps1            # check and report
#   .\tools\docs-check.ps1 -Update    # recompute hash/lines/since and rewrite the doc
#
# Exit code is 0 even when there is drift — this is a report, not a gate (yet).

param(
    [switch]$Update
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $repoRoot 'CLAUDE.md'))) {
    Write-Error "Cannot locate the repository root (expected CLAUDE.md next to $repoRoot)."
}

# short sha of the current HEAD, used as the `since` marker
$head = ''
try { $head = (& git -C $repoRoot rev-parse --short HEAD).Trim() } catch { $head = '' }

function Unquote([string]$s) {
    if ($null -eq $s) { return '' }
    $t = $s.Trim()
    if ($t.Length -ge 2 -and $t.StartsWith('"') -and $t.EndsWith('"')) { $t = $t.Substring(1, $t.Length - 2) }
    return $t
}

function Get-ShortHash([string[]]$lines) {
    $text = [string]::Join("`n", $lines)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
        $raw = $sha.ComputeHash($bytes)
        return ([System.BitConverter]::ToString($raw) -replace '-', '').Substring(0, 12).ToLowerInvariant()
    } finally { $sha.Dispose() }
}

function Get-Span([string]$lines) {
    # "126-153" -> 28 ; "38" -> 1 ; anything odd -> 0
    if ([string]::IsNullOrWhiteSpace($lines)) { return 0 }
    $t = $lines.Trim()
    if ($t -match '^\d+\s*-\s*\d+$') {
        $parts = $t -split '\s*-\s*'
        return ([int]$parts[1] - [int]$parts[0] + 1)
    }
    if ($t -match '^\d+$') { return 1 }
    return 0
}

# Parse a doc's front matter into: summary / refs (array of hashtables) / raw text / body
function Read-Doc([string]$path) {
    $raw = [System.IO.File]::ReadAllText($path, (New-Object System.Text.UTF8Encoding($false)))
    $d = @{ Path = $path; HasFm = $false; Summary = ''; Refs = @(); Raw = $raw; Body = $raw; Fm = '' }
    if (-not $raw.StartsWith("---")) { return $d }

    $lines = $raw -split "`n"
    $end = -1
    for ($i = 1; $i -lt $lines.Count; $i++) {
        if ($lines[$i].Trim() -eq '---') { $end = $i; break }
    }
    if ($end -lt 0) { return $d }

    $d.HasFm = $true
    $d.Fm = ($lines[1..($end - 1)] -join "`n")
    $d.Body = ($lines[($end + 1)..($lines.Count - 1)] -join "`n")

    $cur = $null
    foreach ($l in $lines[1..($end - 1)]) {
        if ($l -match '^\s*summary:\s*(.*)$') { $d.Summary = (Unquote $Matches[1]); continue }
        if ($l -match '^\s*refs:\s*$') { continue }
        if ($l -match '^\s*-\s*path:\s*(.*)$') {
            if ($cur) { $d.Refs += , $cur }
            $cur = @{ path = (Unquote $Matches[1]); anchor = ''; lines = ''; hash = ''; since = '' }
            continue
        }
        if ($cur -and $l -match '^\s+anchor:\s*(.*)$') { $cur.anchor = (Unquote $Matches[1]); continue }
        if ($cur -and $l -match '^\s+lines:\s*(.*)$') { $cur.lines = (Unquote $Matches[1]); continue }
        if ($cur -and $l -match '^\s+hash:\s*(.*)$') { $cur.hash = (Unquote $Matches[1]); continue }
        if ($cur -and $l -match '^\s+since:\s*(.*)$') { $cur.since = (Unquote $Matches[1]); continue }
    }
    if ($cur) { $d.Refs += , $cur }
    return $d
}

function Write-Doc([hashtable]$d) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('---')
    if ($d.Summary) { [void]$sb.AppendLine('summary: ' + $d.Summary) }
    if ($d.Refs.Count -gt 0) {
        [void]$sb.AppendLine('refs:')
        foreach ($r in $d.Refs) {
            [void]$sb.AppendLine('  - path: ' + $r.path)
            [void]$sb.AppendLine('    anchor: "' + $r.anchor + '"')
            [void]$sb.AppendLine('    lines: ' + $r.lines)
            [void]$sb.AppendLine('    hash: ' + $r.hash)
            [void]$sb.AppendLine('    since: ' + $r.since)
        }
    }
    [void]$sb.Append('---')
    $out = $sb.ToString() + "`n" + $d.Body
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($d.Path, $out, $enc)
}

# ------------------------------------------------------------------ main
$docs = Get-ChildItem (Join-Path $repoRoot 'docs') -Recurse -Filter '*.md' | Sort-Object FullName

$stats = @{ ok = 0; changed = 0; missing = 0; noAnchor = 0; docsWithRefs = 0 }
$changedDocs = @()

foreach ($docPath in $docs) {
    # NOTE: $docPath is a FileInfo — must pass FullName, otherwise ReadAllText resolves it
    # relative to the process CWD and fails.
    $d = Read-Doc $docPath.FullName
    if (-not $d.HasFm -or $d.Refs.Count -eq 0) { continue }
    $stats.docsWithRefs++
    $relDoc = $docPath.FullName.Replace($repoRoot, '').TrimStart('\', '/')

    $docChanged = $false
    $report = @()

    foreach ($r in $d.Refs) {
        $srcPath = Join-Path $repoRoot $r.path
        if (-not (Test-Path -LiteralPath $srcPath)) {
            $report += "  MISSING-FILE  $($r.path)"
            $stats.missing++
            $docChanged = $true
            continue
        }
        $srcLines = [System.IO.File]::ReadAllLines($srcPath, (New-Object System.Text.UTF8Encoding($false)))

        if ([string]::IsNullOrWhiteSpace($r.anchor)) {
            $report += "  NO-ANCHOR     $($r.path)"
            $stats.noAnchor++
            continue
        }

        # locate the anchor
        $idx = -1
        for ($i = 0; $i -lt $srcLines.Count; $i++) {
            if ($srcLines[$i].Contains($r.anchor)) { $idx = $i; break }
        }
        if ($idx -lt 0) {
            $report += "  ANCHOR-GONE   $($r.path) :: $($r.anchor)"
            $stats.missing++
            $docChanged = $true
            continue
        }

        $span = Get-Span $r.lines
        if ($span -le 0) { $span = 40 }   # no recorded range -> hash a reasonable window
        $take = [Math]::Min($span, $srcLines.Count - $idx)
        $slice = $srcLines[$idx..($idx + $take - 1)]
        $curHash = Get-ShortHash $slice
        $curRange = ($idx + 1).ToString() + '-' + ($idx + $take).ToString()

        if ($Update) {
            $r.hash = $curHash
            $r.lines = $curRange
            $r.since = $head
            continue
        }

        if ($r.hash -eq $curHash) {
            $stats.ok++
        } else {
            $stats.changed++
            $docChanged = $true
            $rel = $r.path
            $report += "  CHANGED       $rel :: $($r.anchor)"
            $report += "                was lines $($r.lines) hash $($r.hash) (since $($r.since))"
            $report += "                now lines $curRange hash $curHash"
            if ($r.since -and $head) {
                $report += "                git diff $($r.since)..$head -- $rel"
            }
        }
    }

    if ($Update) {
        Write-Doc $d
        Write-Output ("UPDATED  " + $relDoc + "  (" + $d.Refs.Count + " refs)")
    } elseif ($docChanged) {
        $changedDocs += $relDoc
        Write-Output ("STALE    " + $relDoc)
        $report | ForEach-Object { Write-Output $_ }
    }
}

Write-Output ''
if ($Update) {
    Write-Output ("Done: refreshed refs in " + $stats.docsWithRefs + " doc(s) against HEAD " + $head + ".")
} else {
    Write-Output ("Docs with refs: " + $stats.docsWithRefs)
    Write-Output ("  unchanged : " + $stats.ok)
    Write-Output ("  changed   : " + $stats.changed)
    Write-Output ("  anchor gone / file missing : " + $stats.missing)
    Write-Output ("  refs without an anchor     : " + $stats.noAnchor)
    if ($stats.changed -gt 0 -or $stats.missing -gt 0) {
        Write-Output ''
        Write-Output 'Review the docs above; then run with -Update once the prose is correct again.'
    } else {
        Write-Output ''
        Write-Output 'All referenced code matches what the docs were written against.'
    }
}
