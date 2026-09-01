<#
.SYNOPSIS
    Generate the HTML reference for the NetLogo model from its `;;` comments.

.DESCRIPTION
    Runs NlsDoc.java with the NetLogo bundled JVM (or any Java 17+ on PATH) and
    writes the site to simulation_model/docs/api.

.EXAMPLE
    .\nlsdoc.ps1 -Open
    .\nlsdoc.ps1 -Check
#>
param(
    [switch]$Open,
    [switch]$Check,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rest
)

$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
$out = Join-Path $here 'api'

function Find-Java {
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { return "$env:JAVA_HOME\bin\java.exe" }
    # NetLogo ships its own JVM, which is enough to run a single Java source file.
    $bases = @($env:ProgramFiles, ${env:ProgramFiles(x86)}, "$env:LOCALAPPDATA\Programs")
    foreach ($base in $bases) {
        if (-not $base) { continue }
        if (-not (Test-Path $base)) { continue }
        $dirs = Get-ChildItem $base -Directory -Filter 'NetLogo*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending
        foreach ($dir in $dirs) {
            $candidate = Join-Path $dir.FullName 'runtime\bin\java.exe'
            if (Test-Path $candidate) { return $candidate }
        }
    }
    return $null
}

$java = Find-Java
if (-not $java) {
    Write-Error "nlsdoc: no Java 17+ found. Install a JDK, or NetLogo, and try again."
    exit 1
}

$argv = @((Join-Path $here 'NlsDoc.java'), '--root', $root, '--out', $out)
if ($Check) { $argv += '--check' }
if ($Rest) { $argv += $Rest }

& $java @argv
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if ($Open) { Start-Process (Join-Path $out 'index.html') }
