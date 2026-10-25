[CmdletBinding()]
param([switch]$Ui, [switch]$SmokeTest)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$previousLocation = Get-Location
$utf8NoBom = New-Object Text.UTF8Encoding($false)

function Write-Utf8File([string]$Path, [string]$Text) {
    [IO.File]::WriteAllText($Path, $Text, $utf8NoBom)
}

try {
    Set-Location -LiteralPath $projectRoot
    [xml]$pom = Get-Content -LiteralPath pom.xml -Raw
    $artifactId = [string]$pom.project.artifactId
    $version = [string]$pom.project.version
    if ($artifactId -notmatch '^[a-z0-9][a-z0-9-]*$' -or $version -notmatch '^\d+\.\d+\.\d+(?:[-.][0-9A-Za-z]+)*$') {
        throw 'Unexpected artifact ID/version; refusing to construct package paths.'
    }
    $packageName = "$artifactId-$version"

    # Build first. A failure leaves the previous distribution untouched.
    & (Join-Path $PSScriptRoot 'verify.ps1') -Clean -Ui:$Ui
    $reports = @(Get-ChildItem -LiteralPath 'target/surefire-reports' -Filter 'TEST-*.xml' -File)
    if ($reports.Count -eq 0) { throw 'No fresh Java test reports found.' }
    $testTotals = @{ tests = 0; failures = 0; errors = 0; skipped = 0 }
    foreach ($report in $reports) {
        [xml]$suite = Get-Content -LiteralPath $report.FullName -Raw
        foreach ($key in @('tests', 'failures', 'errors', 'skipped')) { $testTotals[$key] += [int]$suite.testsuite.$key }
    }
    if ($testTotals.tests -lt 1 -or $testTotals.failures -gt 0 -or $testTotals.errors -gt 0 -or $testTotals.skipped -gt 0) {
        throw 'Java verification is incomplete or contains failures/skipped tests.'
    }
    if ($Ui) {
        $uiResult = Get-Content -LiteralPath 'test-results/.last-run.json' -Raw | ConvertFrom-Json
        if ($uiResult.status -ne 'passed') { throw 'Browser verification is not marked passed.' }
    }

    $jarPath = Join-Path $projectRoot "target/$packageName.jar"
    if (!(Test-Path -LiteralPath $jarPath -PathType Leaf)) { throw "Executable JAR missing: $jarPath" }
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $jar = [IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $entries = @($jar.Entries | ForEach-Object { $_.FullName })
        if ($entries -notcontains 'BOOT-INF/classes/de/zeus/power/ClientApplication.class') { throw 'Production main class is missing.' }
        foreach ($resource in @('messages.properties', 'messages_de.properties', 'messages_en.properties', 'templates/chargingStatusView.html', 'static/js/main.js', 'static/js/chart.min.js', 'static/css/darkmode.css', 'static/webfonts/fa-solid-900.woff2')) {
            if ($entries -notcontains "BOOT-INF/classes/$resource") { throw "Required GUI resource missing: $resource" }
        }
        if ($entries | Where-Object { $_ -match 'UiTestApplication|TestEndpoints|SampleDataAdvice|DashboardIntegrationTest|ProductionContextSmokeTest|jquery\.slim|BOOT-INF/classes/application-(local|dev|prod|test)\.(properties|ya?ml)|(^|/)\.env($|\.)' }) {
            throw 'Test/demo artifacts or removed jQuery found in release JAR.'
        }
        $runtimeLibraries = @($entries | Where-Object { $_ -match '^BOOT-INF/lib/.+\.jar$' } | ForEach-Object { $_.Substring('BOOT-INF/lib/'.Length) } | Sort-Object)
        if ($runtimeLibraries.Count -lt 1 -or ($runtimeLibraries | Where-Object { $_ -match '^(mockito|junit|spring-test-|spring-boot-test-)' })) { throw 'Invalid runtime dependency set.' }
        $manifestReader = New-Object IO.StreamReader($jar.GetEntry('META-INF/MANIFEST.MF').Open())
        try { $jarManifest = $manifestReader.ReadToEnd() } finally { $manifestReader.Dispose() }
        if ($jarManifest -notmatch 'Start-Class: de\.zeus\.power\.ClientApplication' -or $jarManifest -notmatch 'Main-Class: org\.springframework\.boot\.loader\.launch\.JarLauncher') {
            throw 'JAR is not the executable Spring Boot application.'
        }
    } finally { $jar.Dispose() }

    # Unique staging directory; never recursively delete or reuse an operator's configuration.
    $runId = [Guid]::NewGuid().ToString('N')
    $stageParent = Join-Path $projectRoot "target/deployment-stage/$runId"
    $packageRoot = Join-Path $stageParent $packageName
    foreach ($directory in @('lib', 'config', 'data', 'logs', 'licenses/browsers')) {
        New-Item -ItemType Directory -Path (Join-Path $packageRoot $directory) -Force | Out-Null
    }
    Copy-Item -LiteralPath $jarPath -Destination (Join-Path $packageRoot 'lib/zeus-charge-control.jar')
    Copy-Item -LiteralPath 'LICENSE' -Destination (Join-Path $packageRoot 'LICENSE')
    Copy-Item -LiteralPath 'deployment/application.properties.example' -Destination (Join-Path $packageRoot 'config/application.properties.example')
    Copy-Item -LiteralPath 'docs/deployment.md' -Destination (Join-Path $packageRoot 'README.md')
    foreach ($licenseFile in Get-ChildItem -LiteralPath 'src/main/resources/static/vendor-licenses' -File) {
        Copy-Item -LiteralPath $licenseFile.FullName -Destination (Join-Path $packageRoot 'licenses/browsers')
    }
    $batchSource = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'zeus-charge-control.bat')).Replace("`r`n", "`n").TrimEnd()
    $shellSource = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'zeus-charge-control.sh')).Replace("`r`n", "`n").TrimEnd()
    Write-Utf8File (Join-Path $packageRoot 'start.bat') ($batchSource.Replace("`n", "`r`n") + "`r`n")
    Write-Utf8File (Join-Path $packageRoot 'start.sh') ($shellSource + "`n")
    Write-Utf8File (Join-Path $packageRoot 'data/.gitkeep') ''
    Write-Utf8File (Join-Path $packageRoot 'logs/.gitkeep') ''
    Write-Utf8File (Join-Path $packageRoot 'runtime-dependencies.txt') (($runtimeLibraries -join "`n") + "`n")

    $sourceRevision = (git rev-parse HEAD).Trim()
    $dirty = @(git status --porcelain).Count -gt 0
    $buildInfo = [ordered]@{
        application = $artifactId; version = $version; builtAtUtc = [DateTime]::UtcNow.ToString('o')
        sourceRevision = $sourceRevision; workingTreeDirty = $dirty; javaMinimum = 21
        javaTests = $testTotals; uiTestsPassed = $(if ($Ui) { $true } else { $null })
        packageSmokeTestRequiredBeforePublication = [bool]$SmokeTest
        jarSha256 = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    Write-Utf8File (Join-Path $packageRoot 'build-info.json') (($buildInfo | ConvertTo-Json -Depth 5) + "`n")
    $checksums = @(Get-ChildItem -LiteralPath $packageRoot -File -Recurse -Force | Sort-Object FullName | ForEach-Object {
        $relativePath = $_.FullName.Substring($packageRoot.Length + 1).Replace('\', '/')
        (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $relativePath
    })
    Write-Utf8File (Join-Path $packageRoot 'SHA256SUMS') (($checksums -join "`n") + "`n")

    $distRoot = Join-Path $projectRoot 'dist'
    New-Item -ItemType Directory -Path $distRoot -Force | Out-Null
    $temporaryZip = Join-Path $distRoot "$packageName.$runId.tmp.zip"
    # .NET Framework's CreateFromDirectory can emit Windows backslashes in ZIP names.
    # Explicit forward-slash entries keep the archive portable across extractors and OSes.
    $archive = [IO.Compression.ZipFile]::Open($temporaryZip, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in Get-ChildItem -LiteralPath $stageParent -File -Recurse -Force | Sort-Object FullName) {
            $entryName = $file.FullName.Substring($stageParent.Length + 1).Replace('\', '/')
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $entryName, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $archive.Dispose() }
    if ($SmokeTest) {
        $ErrorActionPreference = 'Continue'
        & node (Join-Path $PSScriptRoot 'smoke-package.cjs') $temporaryZip $packageName
        $smokeExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($smokeExit -ne 0) { throw 'Extracted-package smoke test failed; existing distribution was not replaced.' }
    }
    $archiveHash = (Get-FileHash -LiteralPath $temporaryZip -Algorithm SHA256).Hash.ToLowerInvariant()
    $finalZip = Join-Path $distRoot "$packageName.zip"
    $temporaryChecksum = Join-Path $distRoot "$packageName.$runId.tmp.sha256"
    Write-Utf8File $temporaryChecksum "$archiveHash  $packageName.zip`n"
    Move-Item -LiteralPath $temporaryZip -Destination $finalZip -Force
    Move-Item -LiteralPath $temporaryChecksum -Destination "$finalZip.sha256" -Force
    Write-Host "Deployment package: $finalZip"
    Write-Host "SHA256: $archiveHash"
    Write-Host "Java tests passed: $($testTotals.tests); UI checks requested: $Ui; package smoke test requested: $SmokeTest"
} finally {
    Set-Location -LiteralPath $previousLocation
}
