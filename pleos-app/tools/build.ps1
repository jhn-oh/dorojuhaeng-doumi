param([string[]]$Tasks = @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
$appRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$workspaceRoot = Split-Path -Parent $appRoot
$createdAlias = $false
$alias = $null
foreach ($line in (& subst)) {
    if ($line -match '^([A-Z]:)\\: => (.+)$' -and $Matches[2].TrimEnd('\') -eq $workspaceRoot.TrimEnd('\')) { $alias = $Matches[1]; break }
}
if (!$alias) {
    foreach ($letter in @('L','M','N','O','P','Q')) { if (!(Test-Path -LiteralPath "${letter}:\")) { $alias = "${letter}:"; break } }
    if (!$alias) { throw 'No free build drive alias; existing mappings were preserved.' }
    & subst $alias $workspaceRoot
    if ($LASTEXITCODE -ne 0) { throw 'Unable to create temporary build alias.' }
    $createdAlias = $true
}
$previousJava = $env:JAVA_HOME
$previousAndroid = $env:ANDROID_HOME
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    if (!$env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot' }
    if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = 'C:\AndroidSDK' }
    $env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
    Push-Location -LiteralPath (Join-Path "${alias}\" (Split-Path -Leaf $appRoot))
    try { & .\gradlew.bat @Tasks --console=plain --no-daemon; $result = $LASTEXITCODE }
    finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $previousJava
    $env:ANDROID_HOME = $previousAndroid
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
    if ($createdAlias) { & subst $alias /D }
}
exit $result
