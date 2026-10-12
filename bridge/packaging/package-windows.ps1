# Requires PowerShell 7 and a Windows x64 JDK 17 (build machine only).
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows -or -not [Environment]::Is64BitOperatingSystem) {
    throw 'Packaging requires Windows x64.'
}

$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$distribution = Join-Path $repository 'bridge/build/install/bridge'
$output = Join-Path $repository 'bridge/build/portable/windows-x64'
$runtime = Join-Path $output 'runtime-build'
$imageParent = Join-Path $output 'image'
$image = Join-Path $imageParent 'PocketPhotoBridge'
$zip = Join-Path $output 'PocketPhotoBridge-0.4.1-windows-x64.zip'

if (-not $env:JAVA_HOME) { throw 'JAVA_HOME must identify the build JDK 17.' }
$jdk = [IO.Path]::GetFullPath($env:JAVA_HOME)
$java = Join-Path $jdk 'bin/java.exe'
$jlink = Join-Path $jdk 'bin/jlink.exe'
$jpackage = Join-Path $jdk 'bin/jpackage.exe'
foreach ($tool in @($java, $jlink, $jpackage)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw 'The build JDK is missing a packaging tool.' }
}
$release = Get-Content -LiteralPath (Join-Path $jdk 'release') -Raw
if ($release -notmatch '(?m)^JAVA_VERSION="17(?:\.|\+|\")' -or $release -notmatch '(?m)^OS_ARCH="(?:amd64|x86_64)"') {
    throw 'The build JDK must be Java 17 for Windows x64.'
}
if (-not (Test-Path -LiteralPath (Join-Path $distribution 'lib/bridge-0.4.1.jar') -PathType Leaf)) {
    throw 'Run :bridge:installDist with -PheadlessOnly=true before packaging.'
}

if (Test-Path -LiteralPath $output) { Remove-Item -LiteralPath $output -Recurse -Force }
New-Item -ItemType Directory -Path $output, $imageParent | Out-Null

# Keep the full java.desktop module: ImageIO, Java2D and their native libraries
# are used even with java.awt.headless=true. jlink retains module legal notices.
& $jlink --module-path (Join-Path $jdk 'jmods') `
    --add-modules 'java.base,java.desktop,java.logging,java.xml,java.net.http,jdk.crypto.ec,jdk.unsupported' `
    --strip-debug --no-header-files --no-man-pages --compress=2 --output $runtime
if ($LASTEXITCODE -ne 0) { throw 'jlink failed.' }

& $jpackage --type app-image --win-console --name PocketPhotoBridge --app-version 0.4.1 `
    --input (Join-Path $distribution 'lib') --main-jar bridge-0.4.1.jar `
    --main-class dev.pocketphotobridge.bridge.BridgeCliKt --runtime-image $runtime `
    --java-options '-Djava.awt.headless=true' --java-options '-Dfile.encoding=UTF-8' `
    --java-options '-Xmx512m' --dest $imageParent
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }

Copy-Item -LiteralPath (Join-Path $distribution 'licenses') -Destination $image -Recurse
Copy-Item -LiteralPath (Join-Path $repository 'LICENSE') -Destination (Join-Path $image 'licenses/PocketPhotoBridge-LICENSE.txt')
foreach ($document in @('windows-simulation.md', 'windows-live.md', 'linux-bridge.md')) {
    Copy-Item -LiteralPath (Join-Path $distribution $document) -Destination $image
}
Copy-Item -LiteralPath (Join-Path $repository 'bridge/packaging/WindowsLive.cmd') -Destination $image
Copy-Item -LiteralPath (Join-Path $distribution 'deploy') -Destination $image -Recurse
$runtimeNotices = Join-Path $image 'licenses/java-runtime'
New-Item -ItemType Directory -Path $runtimeNotices | Out-Null
foreach ($notice in @('LICENSE', 'NOTICE', 'release')) {
    $source = Join-Path $jdk $notice
    if (Test-Path -LiteralPath $source -PathType Leaf) { Copy-Item -LiteralPath $source -Destination $runtimeNotices }
}
if (-not (Test-Path -LiteralPath (Join-Path $image 'runtime/legal') -PathType Container)) {
    throw 'The bundled runtime is missing module license notices.'
}

# Preserve the complete app image, including app/, runtime/, licenses and docs.
Compress-Archive -LiteralPath $image -DestinationPath $zip -CompressionLevel Optimal
$hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$zip.sha256", "$hash  $([IO.Path]::GetFileName($zip))`n", [Text.UTF8Encoding]::new($false))
Write-Output 'WINDOWS_PORTABLE_PACKAGE_CREATED version=0.4.1 architecture=x64'
