param(
    [switch]$InstallPackages,
    [string]$Serial = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $root '.tools\android-sdk\platform-tools\adb.exe'
if (!(Test-Path $adb)) {
    throw 'Run scripts\build.ps1 first to prepare the Android SDK.'
}
$device = @()
if ($Serial) { $device = @('-s', $Serial) }

Push-Location $root
try {
    if ($InstallPackages) {
        & (Join-Path $PSScriptRoot 'build.ps1') -Tasks @('assembleDebug', 'assembleDebugAndroidTest')
        Write-Host 'Keep the phone unlocked and approve both installation prompts.'
        & $adb @device install -r (Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk')
        if ($LASTEXITCODE -ne 0) { throw 'AZify installation was not approved.' }
        & $adb @device install -r -t (Join-Path $root 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')
        if ($LASTEXITCODE -ne 0) { throw 'Test APK installation was not approved.' }
        Write-Host 'Open AZify and sign in on the phone, then run this script again without -InstallPackages.'
        return
    }
    $output = & $adb @device shell am instrument -w -r `
        -e class 'org.azify.client.MediaIntegrationTest' `
        'org.azify.client.test/androidx.test.runner.AndroidJUnitRunner'
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { Write-Host $_ }
    $text = $output -join "`n"
    if ($exitCode -ne 0 -or $text -notmatch 'OK \(3 tests\)' -or
        $text -match 'INSTRUMENTATION_STATUS_CODE: -3|FAILURES!!!|INSTRUMENTATION_FAILED') {
        throw 'Device tests failed or were skipped. Check installation, sign-in and network access.'
    }
} finally {
    Pop-Location
}
