# Requires PowerShell 7. Each EXE invocation has a bounded, redirected lifetime.
[CmdletBinding()]
param(
    [string]$ZipPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows) { throw 'The portable EXE smoke test requires Windows.' }
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $ZipPath) { $ZipPath = Join-Path $repository 'bridge/build/portable/windows-x64/PocketPhotoBridge-0.4.2-windows-x64.zip' }
$ZipPath = [IO.Path]::GetFullPath($ZipPath)
$work = Join-Path $repository 'bridge/build/portable/Windows smoke extraction with spaces'
$reports = Join-Path $repository 'bridge/build/reports/smoke/windows'
foreach ($directory in @($work, $reports)) {
    if (Test-Path -LiteralPath $directory) { Remove-Item -LiteralPath $directory -Recurse -Force }
    New-Item -ItemType Directory -Path $directory | Out-Null
}

$expectedHash = ((Get-Content -LiteralPath "$ZipPath.sha256" -Raw) -split '\s+')[0]
if ((Get-FileHash -LiteralPath $ZipPath -Algorithm SHA256).Hash -ine $expectedHash) { throw 'The portable ZIP checksum does not match.' }
Expand-Archive -LiteralPath $ZipPath -DestinationPath $work
$image = Join-Path $work 'PocketPhotoBridge'
$launcher = Join-Path $image 'PocketPhotoBridge.exe'
foreach ($file in @('PocketPhotoBridge.exe', 'runtime/bin/java.exe', 'runtime/lib/modules', 'licenses/mizink-LICENSE.txt', 'licenses/PocketPhotoBridge-LICENSE.txt', 'licenses/jna/LICENSE.txt', 'WindowsLive.cmd', 'windows-live.md', 'windows-simulation.md', 'linux-bridge.md')) {
    if (-not (Test-Path -LiteralPath (Join-Path $image $file) -PathType Leaf)) { throw 'The extracted app image is incomplete.' }
}
if (-not (Test-Path -LiteralPath (Join-Path $image 'runtime/legal') -PathType Container)) { throw 'Runtime legal notices are absent.' }
$script:checks = 0

function Invoke-SmokeCase {
    param(
        [string]$Name,
        [int]$ExpectedExit,
        [string[]]$CommandArguments = @(),
        [string[]]$ExpectedPatterns = @(),
        [bool]$CheckMarkers = $true
    )
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $launcher
    $start.WorkingDirectory = $image
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = [Text.Encoding]::UTF8
    $start.StandardErrorEncoding = [Text.Encoding]::UTF8
    foreach ($argument in $CommandArguments) { $start.ArgumentList.Add($argument) }
    # Remove system Java and injected VM flags only from the child environment.
    foreach ($variable in @('JAVA_HOME', 'JDK_HOME', 'JRE_HOME', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')) {
        $start.Environment.Remove($variable) | Out-Null
    }
    $start.Environment['PATH'] = ''
    $start.Environment['LOCALAPPDATA'] = Join-Path $work 'isolated profile with spaces'
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw "Smoke case $Name could not start." }
        $process.StandardInput.Close()
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(60000)) {
            $process.Kill($true)
            $process.WaitForExit()
            throw "Smoke case $Name exceeded its 60 second limit."
        }
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        $exitCode = $process.ExitCode
        [IO.File]::WriteAllText((Join-Path $reports "$Name.stdout.txt"), $stdout, [Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText((Join-Path $reports "$Name.stderr.txt"), $stderr, [Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText((Join-Path $reports "$Name.exit.txt"), "$exitCode`n", [Text.UTF8Encoding]::new($false))
        if ($exitCode -ne $ExpectedExit) { throw "Smoke case $Name returned $exitCode; expected $ExpectedExit." }
        if ($CheckMarkers) {
            foreach ($marker in @('(?m)^SIMULATION_ONLY\r?$', '(?m)^NO_PHYSICAL_PRINT\r?$', '(?m)^BRIDGE_[A-Z_]+(?:[ =].*)?\r?$')) {
                if ($stdout -notmatch $marker) { throw "Smoke case $Name is missing a safe simulation marker." }
            }
        }
        foreach ($pattern in $ExpectedPatterns) {
            if ($stdout -notmatch $pattern) {
                if ($Name -eq 'native-bindings') { Write-Output $stdout }
                throw "Smoke case $Name did not produce its expected result."
            }
        }
        $script:checks++
    } finally {
        $process.Dispose()
    }
}

function Get-StateDirectory([string]$Name) { return Join-Path $work "state with spaces/$Name" }
$zeroPrints = '(?m)^SIMULATED_PRINTS count=0\r?$'
$onePrint = '(?m)^SIMULATED_PRINTS count=1\r?$'
$suitePass = '(?m)^SIMULATION_SUITE_PASS checks=[0-9]+\r?$'

Invoke-SmokeCase -Name help -ExpectedExit 0 -CommandArguments @('--help') -ExpectedPatterns @('--simulate-all') -CheckMarkers $false
Invoke-SmokeCase -Name native-bindings -ExpectedExit 0 -CommandArguments @('--windows-native-check') -ExpectedPatterns @('WINDOWS_WINSOCK_BINDINGS_READY', 'WINDOWS_CAMERA_LINK_CANDIDATES count=', 'NO_PHYSICAL_PRINT') -CheckMarkers $false
Invoke-SmokeCase -Name live-console-refused -ExpectedExit 64 -CommandArguments @('--windows-live') -ExpectedPatterns @('WINDOWS_LIVE_CONSOLE_REQUIRED') -CheckMarkers $false
Invoke-SmokeCase -Name suite -ExpectedExit 0 -CommandArguments @('--simulate-all', "--state-dir=$(Get-StateDirectory suite)") -ExpectedPatterns @($suitePass)
# No console exists with redirected stdio, so the Explorer default must not pause.
Invoke-SmokeCase -Name default-suite -ExpectedExit 0 -ExpectedPatterns @($suitePass)
$normalState = Get-StateDirectory normal
Invoke-SmokeCase -Name normal -ExpectedExit 0 -CommandArguments @('--simulate', "--state-dir=$normalState") -ExpectedPatterns @($onePrint)
Invoke-SmokeCase -Name duplicate -ExpectedExit 0 -CommandArguments @('--simulate', "--state-dir=$normalState") -ExpectedPatterns @($zeroPrints)
Invoke-SmokeCase -Name crop -ExpectedExit 0 -CommandArguments @('--simulate', '--crop', "--state-dir=$(Get-StateDirectory crop)") -ExpectedPatterns @($onePrint)
foreach ($scenario in @('missing-ssid', 'camera-offline', 'camera-timeout', 'no-selected-jpeg', 'interrupted-transfer', 'printer-offline', 'printer-busy')) {
    Invoke-SmokeCase -Name $scenario -ExpectedExit 2 -CommandArguments @("--simulate-failure=$scenario", "--state-dir=$(Get-StateDirectory $scenario)") -ExpectedPatterns @($zeroPrints)
}
foreach ($scenario in @('dropped-after-submit', 'uncertain-completion')) {
    Invoke-SmokeCase -Name $scenario -ExpectedExit 3 -CommandArguments @("--simulate-failure=$scenario", "--state-dir=$(Get-StateDirectory $scenario)")
}
foreach ($scenario in @('duplicate-trigger', 'duplicate-photo', 'restart', 'manual-recovery')) {
    Invoke-SmokeCase -Name $scenario -ExpectedExit 0 -CommandArguments @("--simulate-failure=$scenario", "--state-dir=$(Get-StateDirectory $scenario)")
}
Invoke-SmokeCase -Name hardware-refused -ExpectedExit 64 -CommandArguments @('--hardware') -CheckMarkers $false
Invoke-SmokeCase -Name invalid-usage -ExpectedExit 64 -CommandArguments @('--unsupported-option') -CheckMarkers $false
$crashState = Get-StateDirectory power-cut
Invoke-SmokeCase -Name power-cut -ExpectedExit 71 -CommandArguments @('--simulate-failure=power-cut', "--state-dir=$crashState") -CheckMarkers $false
Invoke-SmokeCase -Name after-power-cut -ExpectedExit 3 -CommandArguments @('--simulate', "--state-dir=$crashState") -ExpectedPatterns @($zeroPrints)
Invoke-SmokeCase -Name acknowledge -ExpectedExit 0 -CommandArguments @('--acknowledge-checked-printer', '--confirm', "--state-dir=$crashState") -ExpectedPatterns @($zeroPrints)
Invoke-SmokeCase -Name after-acknowledge -ExpectedExit 0 -CommandArguments @('--simulate', "--state-dir=$crashState") -ExpectedPatterns @($zeroPrints)

$summary = "SMOKE_WINDOWS_PASS checks=$script:checks bundled_runtime=true`n"
[IO.File]::WriteAllText((Join-Path $reports 'summary.txt'), $summary, [Text.UTF8Encoding]::new($false))
Write-Output $summary.TrimEnd()
