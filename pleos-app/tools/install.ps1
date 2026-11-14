param([string]$Serial = 'emulator-5580', [switch]$WithUiDriver)
$ErrorActionPreference = 'Stop'
$appRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'C:\AndroidSDK' }
$adbExe = Join-Path $sdkRoot 'platform-tools\adb.exe'
if ((& $adbExe -s $Serial shell getprop ro.kernel.qemu).Trim() -ne '1') { throw 'This first build is intended for the Pleos emulator.' }
$apkPath = Join-Path $appRoot 'app\build\outputs\apk\debug\app-debug.apk'
& $adbExe -s $Serial install -r $apkPath
if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
if ($WithUiDriver) {
    & $adbExe -s $Serial install -r (Join-Path $appRoot 'ui-driver\build\outputs\apk\debug\ui-driver-debug.apk')
    if ($LASTEXITCODE -ne 0) { throw 'UI driver installation failed.' }
}
& $adbExe -s $Serial forward tcp:8088 tcp:8088
& $adbExe -s $Serial shell am start -n kr.juhyu.licensenote/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
