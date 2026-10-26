param(
    [switch]$AcceptSdkLicenses,
    [string[]]$Tasks = @('testDebugUnitTest', 'lintDebug', 'assembleDebug')
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$tools = Join-Path $root '.tools'
[System.IO.Directory]::CreateDirectory($tools) | Out-Null

function Get-Archive([string]$Url, [string]$Destination, [string]$ExtractTo) {
    if (!(Test-Path $Destination)) {
        & curl.exe --fail --location --retry 2 --connect-timeout 30 --max-time 1200 --output $Destination $Url
        if ($LASTEXITCODE -ne 0) {
            Remove-Item $Destination -ErrorAction SilentlyContinue
            throw "Download failed: $Url"
        }
    }
    [System.IO.Directory]::CreateDirectory($ExtractTo) | Out-Null
    & tar.exe -xf $Destination -C $ExtractTo
    if ($LASTEXITCODE -ne 0) { throw "Could not extract $Destination" }
}

Push-Location $root
try {
    $jdk = Get-ChildItem $tools -Directory -Filter 'jdk-17*' | Select-Object -First 1
    if (!$jdk) {
        Get-Archive 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' `
            (Join-Path $tools 'jdk17.zip') $tools
        $jdk = Get-ChildItem $tools -Directory -Filter 'jdk-17*' | Select-Object -First 1
    }
    if (!$jdk) { throw 'JDK 17 was not found after extraction.' }
    $env:JAVA_HOME = $jdk.FullName
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    $env:ANDROID_HOME = Join-Path $tools 'android-sdk'
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    $sdkmanager = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (!(Test-Path $sdkmanager)) {
        $cli = Join-Path $tools 'android-cli'
        Get-Archive 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' `
            (Join-Path $tools 'android-cli.zip') $cli
        [System.IO.Directory]::CreateDirectory((Join-Path $env:ANDROID_HOME 'cmdline-tools')) | Out-Null
        Move-Item (Join-Path $cli 'cmdline-tools') (Join-Path $env:ANDROID_HOME 'cmdline-tools\latest')
    }
    if (!(Test-Path (Join-Path $env:ANDROID_HOME 'licenses\android-sdk-license'))) {
        if (!$AcceptSdkLicenses) {
            throw 'Review SDK licenses, then rerun with -AcceptSdkLicenses if you agree: https://developer.android.com/studio/terms'
        }
        1..30 | ForEach-Object { 'y' } | & $sdkmanager "--sdk_root=$env:ANDROID_HOME" --licenses
        if ($LASTEXITCODE -ne 0) { throw 'SDK license acceptance failed.' }
    }
    if (!(Test-Path (Join-Path $env:ANDROID_HOME 'platforms\android-35\android.jar')) -or
        !(Test-Path (Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\aapt2.exe'))) {
        & $sdkmanager "--sdk_root=$env:ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0'
        if ($LASTEXITCODE -ne 0) { throw 'Android SDK installation failed.' }
    }
    $gradle = Join-Path $tools 'gradle-8.11.1\bin\gradle.bat'
    if (!(Test-Path $gradle)) {
        Get-Archive 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' `
            (Join-Path $tools 'gradle.zip') $tools
    }
    & $gradle --no-daemon --console=plain @Tasks
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
} finally {
    Pop-Location
}