param(
    [string]$SdkRoot = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    [string]$AdbPath = 'C:\AndroidSDK\platform-tools\adb.exe',
    [string]$TempRoot = (Join-Path $env:LOCALAPPDATA 'Temp\LicenseNotePleos5580'),
    [string]$LogRoot,
    [ValidateSet('host','swiftshader_indirect')][string]$Gpu = 'host',
    [switch]$DisableVulkan = $true,
    [switch]$LegacyGnss,
    [switch]$NoPassiveGps,
    [switch]$GpsDiagnostics,
    [ValidateRange(15,600)][int]$WaitSeconds = 180,
    [switch]$NoWait
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$probeRoot = (Resolve-Path -LiteralPath (Join-Path $workspaceRoot '검증\Pleos_창배치_20261113')).Path
$drive = 'W:'
$avdName = 'pleos_window_probe'
$port = 5580
$serial = "emulator-$port"
$Gpu = $Gpu.ToLowerInvariant()
$SdkRoot = [IO.Path]::GetFullPath($SdkRoot)
$AdbPath = [IO.Path]::GetFullPath($AdbPath)
$runtimeRoot = Join-Path $SdkRoot 'emulator'
$emulatorPath = Join-Path $runtimeRoot 'emulator.exe'
if (!$LogRoot) { $LogRoot = Join-Path $workspaceRoot 'outputs\pleos-verified-runtime' }
$LogRoot = [IO.Path]::GetFullPath($LogRoot)
$TempRoot = [IO.Path]::GetFullPath($TempRoot)

foreach ($path in @($emulatorPath,$AdbPath,(Join-Path $runtimeRoot 'source.properties'),(Join-Path $probeRoot 'image-verification.json'))) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file missing: $path" }
}
$version = [regex]::Match((Get-Content -LiteralPath (Join-Path $runtimeRoot 'source.properties') -Raw),'(?m)^Pkg.Revision=(.+)$').Groups[1].Value.Trim()
if ($version -ne '36.6.11') { throw "Expected the previously verified Emulator 36.6.11; found '$version'. No SDK files were changed." }
foreach ($path in @($runtimeRoot,$TempRoot)) {
    if ($path -match '[^\x00-\x7F]') { throw "Native runtime and TEMP paths must be ASCII: $path" }
}

if (!('LicenseNoteVerifiedDosDevice' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class LicenseNoteVerifiedDosDevice {
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    public static extern uint QueryDosDevice(string name, StringBuilder value, uint capacity);
}
'@
}
function Get-DriveTarget {
    $buffer = [Text.StringBuilder]::new(32768)
    $length = [LicenseNoteVerifiedDosDevice]::QueryDosDevice($drive,$buffer,32768)
    if ($length -eq 0) { return $null }
    $value = $buffer.ToString().Split([char]0)[0]
    if ($value.StartsWith('\??\')) { $value = $value.Substring(4) }
    return $value.TrimEnd('\')
}

# Only a launcher mutex is held. The live emulator and its AVD are never stopped here.
$mutex = [Threading.Mutex]::new($false,'Local\LicenseNote.PleosVerifier.5580')
$mutexHeld = $false
$aliasCreated = $false
try {
    try { $mutexHeld = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $mutexHeld = $true }
    if (!$mutexHeld) { throw 'Another verified launcher is checking or starting port 5580. No second emulator was started.' }

    $target = Get-DriveTarget
    if ($target) {
        if (![String]::Equals($target,$probeRoot.TrimEnd('\'),[StringComparison]::OrdinalIgnoreCase)) {
            throw "W: belongs to another target ('$target'); it was preserved. Expected '$probeRoot'."
        }
    } else {
        if (Test-Path -LiteralPath 'W:\') { throw 'W: exists but its native target could not be verified. No mapping was changed.' }
        & subst $drive $probeRoot
        if ($LASTEXITCODE -ne 0) { throw 'Unable to create the verified W: mapping.' }
        $aliasCreated = $true
        if (![String]::Equals((Get-DriveTarget),$probeRoot.TrimEnd('\'),[StringComparison]::OrdinalIgnoreCase)) { throw 'W: mapping verification failed.' }
    }

    # These files may belong to a running probe. Read and validate, never rewrite them.
    $descriptor = 'W:\avd\pleos_window_probe.ini'
    $configuration = 'W:\avd\pleos_window_probe.avd\config.ini'
    function Read-IniValue([string]$Path,[string]$Key) {
        $pattern = '^' + [regex]::Escape($Key) + '=(.*)$'
        foreach ($line in (Get-Content -LiteralPath $Path)) { if ($line -match $pattern) { return $Matches[1].Trim() } }
        return $null
    }
    foreach ($path in @($descriptor,$configuration,'W:\image\crp_avd_out_x86_64\kernel-ranchu','W:\image\crp_avd_out_x86_64\system.img','W:\image\crp_avd_out_x86_64\ramdisk.img')) {
        if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Verified probe file missing: $path" }
    }
    if ((Read-IniValue $descriptor 'path').TrimEnd('\') -ne 'W:\avd\pleos_window_probe.avd') { throw 'Probe descriptor must already use W:\avd\pleos_window_probe.avd. It was not rewritten.' }
    if ((Read-IniValue $configuration 'image.sysdir.1').TrimEnd('\') -ne 'W:\image\crp_avd_out_x86_64') { throw 'Probe config must already use the verified W: image. It was not rewritten.' }

    New-Item -ItemType Directory -Path $LogRoot,$TempRoot -Force | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $launcherLog = Join-Path $LogRoot "launcher-$stamp.log"
    function Write-LaunchLog([string]$Message) {
        $line = '[' + (Get-Date -Format 'o') + '] ' + $Message
        Add-Content -LiteralPath $launcherLog -Value $line -Encoding UTF8
        Write-Host $line
    }
    function Invoke-Adb([string[]]$Arguments,[int]$TimeoutSeconds = 8) {
        # All caller-supplied native arguments here are fixed ASCII tokens, never paths or shell code.
        foreach ($argument in $Arguments) { if ($argument -match '[\s"]') { throw 'Unexpected whitespace or quote in an internal ADB argument.' } }
        $info = [Diagnostics.ProcessStartInfo]::new()
        $info.FileName = $AdbPath
        $info.Arguments = $Arguments -join ' '
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $info
        try {
            [void]$process.Start()
            $stdout = $process.StandardOutput.ReadToEndAsync()
            $stderr = $process.StandardError.ReadToEndAsync()
            if (!$process.WaitForExit($TimeoutSeconds * 1000)) {
                try { $process.Kill() } catch {}
                throw "ADB client timed out after ${TimeoutSeconds}s; the server and emulator were left running."
            }
            return [pscustomobject]@{ExitCode=$process.ExitCode;Output=$stdout.GetAwaiter().GetResult();Error=$stderr.GetAwaiter().GetResult()}
        } finally { $process.Dispose() }
    }
    function Get-VerifiedOwner {
        $portPattern = '(?:^|\s)-port(?:\s+|=)"?' + $port + '"?(?:\s|$)'
        $avdPattern = '(?:^|\s)-avd(?:\s+|=)"?' + [regex]::Escape($avdName) + '"?(?:\s|$)'
        $gpuPattern = '(?:^|\s)-gpu(?:\s+|=)"?' + [regex]::Escape($Gpu) + '"?(?:\s|$)'
        $candidates = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object { $_.Name -match '^(qemu-system-x86_64(-headless)?|emulator)\.exe$' -and $_.CommandLine -and $_.CommandLine -match $portPattern })
        foreach ($candidate in $candidates) {
            $exe = $candidate.ExecutablePath
            if (!$exe) { $exe = [regex]::Match($candidate.CommandLine,'^\s*(?:"(?<exe>[^"]+)"|(?<exe>\S+))').Groups['exe'].Value }
            if (!$exe -or !$exe.StartsWith($runtimeRoot.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase) -or $candidate.CommandLine -notmatch $avdPattern) {
                throw "Port 5580 is associated with an unverified emulator process $($candidate.ProcessId). It was preserved."
            }
            if ($candidate.CommandLine -notmatch '(?:^|\s)-memory\s+2048(?:\s|$)') {
                throw "Existing probe PID $($candidate.ProcessId) does not explicitly use the verified 2048MB memory setting. It was preserved; no duplicate was started."
            }
            if ($candidate.CommandLine -notmatch $gpuPattern) {
                throw "Existing probe PID $($candidate.ProcessId) does not use requested GPU mode '$Gpu'. It was preserved; no duplicate was started."
            }
            $hasLegacyGnss = $candidate.CommandLine -match '(?:^|\s)-feature\s+-GnssGrpcV1(?:\s|$)'
            if ($hasLegacyGnss -ne [bool]$LegacyGnss) {
                throw "Existing probe PID $($candidate.ProcessId) has a different GNSS transport override. It was preserved."
            }
            $hasDisabledVulkan = $candidate.CommandLine -match '(?:^|\s)-feature\s+-Vulkan(?:\s|$)'
            if ($hasDisabledVulkan -ne [bool]$DisableVulkan) {
                throw "Existing probe PID $($candidate.ProcessId) has a different Vulkan override. It was preserved."
            }
            $hasNoPassiveGps = $candidate.CommandLine -match '(?:^|\s)-no-passive-gps(?:\s|$)'
            if ($hasNoPassiveGps -ne [bool]$NoPassiveGps) {
                throw "Existing probe PID $($candidate.ProcessId) has a different passive GPS setting. It was preserved."
            }
        }
        $qemu = @($candidates | Where-Object { $_.Name -like 'qemu-*' })
        if ($qemu.Count -gt 1) { throw 'More than one QEMU claims port 5580; no process was stopped or started.' }
        if ($qemu.Count -eq 1) { return $qemu[0] }
        if ($candidates.Count -gt 0) { return $candidates[0] }
        return $null
    }

    $devices = Invoke-Adb -Arguments @('devices','-l')
    if ($devices.ExitCode -ne 0) { throw "Unable to read ADB devices: $($devices.Error)" }
    $serialPresent = $devices.Output -match ('(?m)^' + [regex]::Escape($serial) + '\s')
    $owner = Get-VerifiedOwner
    if ($serialPresent -and !$owner) { throw 'ADB reports emulator-5580 but its runtime/AVD owner could not be verified. No emulator was started.' }
    $reused = $null -ne $owner
    $stdoutLog = $null
    $stderrLog = $null
    $emulatorProcessId = if ($owner) { $owner.ProcessId } else { $null }

    if (!$owner) {
        $listeners = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
        if (@($listeners | Where-Object { $_.Port -eq $port -or $_.Port -eq ($port+1) }).Count -gt 0) { throw 'Port 5580/5581 is already occupied by an unverified listener. No process was changed.' }
        $stdoutLog = Join-Path $LogRoot "emulator-$stamp-stdout.log"
        $stderrLog = Join-Path $LogRoot "emulator-$stamp-stderr.log"
        $arguments = @('-avd',$avdName,'-port',"$port",'-memory','2048','-no-snapshot','-no-window','-no-audio','-no-boot-anim','-gpu',$Gpu,'-timezone','Asia/Seoul','-verbose')
        if ($LegacyGnss) { $arguments += @('-feature','-GnssGrpcV1') }
        if ($DisableVulkan) { $arguments += @('-feature','-Vulkan') }
        if ($NoPassiveGps) { $arguments += '-no-passive-gps' }
        if ($GpsDiagnostics) { $arguments += @('-debug','gps') }
        $oldEnvironment = @{}
        foreach ($name in @('ANDROID_HOME','ANDROID_SDK_ROOT','ANDROID_AVD_HOME','TEMP','TMP')) { $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
        try {
            $env:ANDROID_HOME = $SdkRoot
            $env:ANDROID_SDK_ROOT = $SdkRoot
            $env:ANDROID_AVD_HOME = 'W:\avd'
            $env:TEMP = $TempRoot
            $env:TMP = $TempRoot
            $process = Start-Process -FilePath $emulatorPath -ArgumentList $arguments -WorkingDirectory $runtimeRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog
            $emulatorProcessId = $process.Id
            Write-LaunchLog "Started verified AVD with launcher PID $emulatorProcessId; memory=2048MB, GPU=$Gpu, serial=$serial."
        } finally {
            foreach ($name in $oldEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$oldEnvironment[$name],'Process') }
        }
    } else { Write-LaunchLog "Reusing verified runtime/AVD PID $emulatorProcessId with GPU=$Gpu; no config, userdata or process was changed." }

    $state = 'starting'
    if (!$NoWait) {
        $deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
        $lastProgress = [DateTime]::MinValue
        do {
            try {
                $boot = Invoke-Adb -Arguments @('-s',$serial,'shell','getprop','sys.boot_completed') -TimeoutSeconds 5
                if ($boot.ExitCode -eq 0 -and $boot.Output.Trim() -eq '1') { $state = 'ready'; break }
            } catch { Write-LaunchLog $_.Exception.Message }
            if (([DateTime]::UtcNow-$lastProgress).TotalSeconds -ge 10) { Write-LaunchLog 'Waiting for Android boot; existing processes and data remain untouched.'; $lastProgress=[DateTime]::UtcNow }
            Start-Sleep -Seconds 2
        } while ([DateTime]::UtcNow -lt $deadline)
        if ($state -ne 'ready') { $state = 'boot-timeout' }
    }
    if ($state -eq 'ready') {
        $existingForwards = Invoke-Adb -Arguments @('forward','--list')
        if ($existingForwards.ExitCode -ne 0) { throw "Unable to verify existing console forwarding: $($existingForwards.Error)" }
        foreach ($line in ($existingForwards.Output -split '[\r\n]+')) {
            if ($line -match '^([^\s]+)\s+tcp:8088\s+([^\s]+)' -and ($Matches[1] -ne $serial -or $Matches[2] -ne 'tcp:8088')) {
                throw 'Local console port 8088 has another ADB forwarding owner. It was preserved.'
            }
        }
        $forward = Invoke-Adb -Arguments @('-s',$serial,'forward','tcp:8088','tcp:8088')
        if ($forward.ExitCode -ne 0) { throw "Console forwarding failed: $($forward.Error)" }
        Write-LaunchLog 'Android boot completed; Virtual Console forwarded to http://127.0.0.1:8088/.'
    }
    $currentOwner = Get-VerifiedOwner
    if ($currentOwner) { $emulatorProcessId = $currentOwner.ProcessId }
    # GPU records the validated command-line mode; native stdout identifies the actual adapter/fallback.
    $result = [pscustomobject]@{State=$state;Serial=$serial;Avd=$avdName;RuntimeVersion=$version;GpuMode=$Gpu;GpuValidation='process-argument';VulkanEnabled=(!$DisableVulkan);GnssMode=$(if ($LegacyGnss) {'legacy-nmea'} else {'runtime-default'});MemoryMb=2048;ProcessId=$emulatorProcessId;Reused=$reused;Alias=$drive;AliasCreated=$aliasCreated;ProbeRoot=$probeRoot;TempRoot=$TempRoot;LauncherLog=$launcherLog;StdoutLog=$stdoutLog;StderrLog=$stderrLog}
    $result | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $LogRoot "launch-$stamp.json") -Encoding UTF8
    Write-LaunchLog 'W: is intentionally retained because the live AVD references it. This launcher never removes or remaps it.'
    $result
    if ($state -eq 'boot-timeout') { throw "Boot did not complete within ${WaitSeconds}s. Logs were saved; the owned emulator was not killed." }
} finally {
    if ($mutexHeld) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
