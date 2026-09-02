<#
.SYNOPSIS
Führt Wanderns lokale Tests unter Windows ohne sichtbares Konsolenfenster aus.

.EXAMPLE
.\scripts\test-hidden.ps1 check
.\scripts\test-hidden.ps1 emulator -DebugScenesOnly
.\scripts\test-hidden.ps1 navigation -LiveRoute -DeviceSerial emulator-5556
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('check', 'emulator', 'navigation')]
    [string]$Suite = 'check',

    [Parameter(Position = 1, ValueFromRemainingArguments)]
    [string[]]$SuiteArguments = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptName = switch ($Suite) {
    'check' { 'check.ps1' }
    'emulator' { 'test-emulator.ps1' }
    'navigation' { 'test-navigation-simulation.ps1' }
}
$target = Join-Path $PSScriptRoot $scriptName
$powerShell = Join-Path $PSHOME 'pwsh.exe'
if (-not (Test-Path -LiteralPath $powerShell -PathType Leaf)) {
    $powerShell = (Get-Command powershell.exe -ErrorAction Stop).Source
}

$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $powerShell
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.WorkingDirectory = Split-Path -Parent $PSScriptRoot
$startInfo.ArgumentList.Add('-NoLogo')
$startInfo.ArgumentList.Add('-NoProfile')
$startInfo.ArgumentList.Add('-NonInteractive')
$startInfo.ArgumentList.Add('-ExecutionPolicy')
$startInfo.ArgumentList.Add('Bypass')
$startInfo.ArgumentList.Add('-File')
$startInfo.ArgumentList.Add($target)
foreach ($argument in $SuiteArguments) {
    $startInfo.ArgumentList.Add($argument)
}

$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $startInfo
if (-not $process.Start()) {
    throw "Headless-Testprozess für '$Suite' konnte nicht gestartet werden."
}

# Beide Streams parallel lesen, damit ein voller Redirect-Puffer den Test nie blockiert.
$standardOutput = $process.StandardOutput.ReadToEndAsync()
$standardError = $process.StandardError.ReadToEndAsync()
$process.WaitForExit()
$output = $standardOutput.GetAwaiter().GetResult()
$errorOutput = $standardError.GetAwaiter().GetResult()

if (-not [string]::IsNullOrWhiteSpace($output)) {
    [Console]::Out.Write($output)
}
if (-not [string]::IsNullOrWhiteSpace($errorOutput)) {
    [Console]::Error.Write($errorOutput)
}

exit $process.ExitCode
