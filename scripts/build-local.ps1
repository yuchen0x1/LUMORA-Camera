[CmdletBinding()]
param(
    [switch]$Offline,
    [string]$StagingRoot = [System.IO.Path]::GetTempPath(),
    [string[]]$Tasks = @(
        ':app:testDebugUnitTest', ':app:assembleDebug', ':app:assembleDebugAndroidTest',
        ':app:assembleRelease', ':app:lintDebug', ':app:lintRelease'
    )
)

$ErrorActionPreference = 'Stop'
$source = Split-Path -Parent $PSScriptRoot
$root = [System.IO.Path]::GetFullPath($StagingRoot)
if ($root -match '[^\x00-\x7F]') {
    throw 'Use an ASCII-only -StagingRoot: the Windows Android/JVM toolchain cannot reliably build Unicode paths.'
}
$sourceRoot = [System.IO.Path]::GetFullPath($source).TrimEnd('\')
if ($root.TrimEnd('\').Equals($sourceRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
    $root.StartsWith($sourceRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'StagingRoot must be outside the source tree.'
}
$staging = Join-Path $root ('lumora-build-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $staging | Out-Null

# Copy current source, including uncommitted edits; omit caches and local signing secrets.
& robocopy.exe $source $staging /E /XD .git .gradle .kotlin build .idea .cxx .externalNativeBuild `
    /XF '*.jks' '*.keystore' '*.p12' '*.pfx' '.env' '.env.*' 'keystore.properties' /R:2 /W:1 /NFL /NDL /NJH /NJS /NP
if ($LASTEXITCODE -ge 8) {
    throw "Source copy failed ($LASTEXITCODE). Staging retained at $staging"
}

$arguments = @($Tasks)
if ($Offline) { $arguments += '--offline' }
Push-Location $staging
try {
    & (Join-Path $staging 'gradlew.bat') @arguments
    $buildExit = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($buildExit -ne 0) {
    throw "Gradle failed ($buildExit). Sources and reports retained at $staging"
}

$build = Join-Path $source 'app/build'
New-Item -ItemType Directory -Force -Path (Join-Path $build 'outputs') | Out-Null
$apk = Join-Path $staging 'app/build/outputs/apk'
if (Test-Path $apk) { Copy-Item $apk (Join-Path $build 'outputs') -Recurse -Force }
foreach ($folder in @('reports', 'test-results')) {
    $result = Join-Path $staging "app/build/$folder"
    if (Test-Path $result) { Copy-Item $result $build -Recurse -Force }
}
Write-Output "Build completed. APKs and reports copied to $build"
Write-Output "Staging retained for inspection: $staging"
