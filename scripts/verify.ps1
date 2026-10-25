[CmdletBinding()]
param([switch]$Ui, [switch]$Clean, [switch]$Preview)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$previousLocation = Get-Location
$previousJavaHome = $env:JAVA_HOME

function Invoke-CheckedNative {
    param([string]$ToolName, [string[]]$Arguments)
    $ErrorActionPreference = 'Continue' # Windows PowerShell treats native stderr warnings as error records.
    # npm.ps1/npx.ps1 reparse the caller's source line and corrupt a splatted dynamic invocation.
    # Their native .cmd launchers preserve the exact argument array.
    $resolvedName = if ($env:OS -eq 'Windows_NT' -and $ToolName -in @('npm', 'npx')) { "$ToolName.cmd" } else { $ToolName }
    $executable = (Get-Command $resolvedName -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    & $executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$ToolName failed with exit code $LASTEXITCODE" }
}

try {
    Set-Location -LiteralPath $projectRoot
    $candidates = @()
    $portableRoot = Join-Path $projectRoot '.tools/jdk21'
    if (Test-Path -LiteralPath $portableRoot) {
        $candidates += Get-ChildItem -LiteralPath $portableRoot -Directory | ForEach-Object { $_.FullName }
    }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCommand) { $candidates += Split-Path -Parent (Split-Path -Parent $javaCommand.Source) }
    $selectedJdk = $candidates | Where-Object {
        $release = Join-Path $_ 'release'
        (Test-Path -LiteralPath (Join-Path $_ 'bin/javac.exe')) -and
        (Test-Path -LiteralPath $release) -and
        ([IO.File]::ReadAllText($release) -match 'JAVA_VERSION="(\d+)' -and [int]$Matches[1] -ge 21)
    } | Select-Object -First 1
    if (!$selectedJdk) { throw 'Install a JDK 21 or newer and set JAVA_HOME to its installation directory.' }
    $env:JAVA_HOME = $selectedJdk
    Write-Host "Using JDK: $selectedJdk"

    if ($Preview) {
        Invoke-CheckedNative 'mvn' @('-q', 'test-compile', 'dependency:build-classpath', '-Dmdep.outputFile=target/ui-test-classpath.txt')
        Invoke-CheckedNative 'node' @('scripts/ui-test-server.cjs')
    } else {
        $mavenArguments = @('-B')
        if ($Clean) { $mavenArguments += 'clean' }
        $mavenArguments += 'verify'
        Invoke-CheckedNative 'mvn' $mavenArguments
        if ($Ui) {
            Invoke-CheckedNative 'npm' @('ci', '--ignore-scripts', '--no-fund')
            Invoke-CheckedNative 'npx' @('playwright', 'install', 'chromium')
            Invoke-CheckedNative 'npm' @('run', 'test:ui')
        }
    }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    Set-Location -LiteralPath $previousLocation
}
