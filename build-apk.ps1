param(
    [Parameter(Mandatory=$true)][string]$JdkHome,
    [Parameter(Mandatory=$true)][string]$AndroidJar,
    [Parameter(Mandatory=$true)][string]$BuildTools,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'dist'),
    [string]$KeyStorePath = (Join-Path $PSScriptRoot '.localpin-test.p12'),
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$')]
    [string]$ApplicationId = 'local.pin',
    [ValidateNotNullOrEmpty()][string]$ApplicationLabel = '点位'
)
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$build = Join-Path $project ('.build/run-' + [Guid]::NewGuid().ToString('N'))
function Check-Exit([string]$Step) { if ($LASTEXITCODE -ne 0) { throw ($Step + ' failed: ' + $LASTEXITCODE) } }
$previousJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
$previousPath = [Environment]::GetEnvironmentVariable('PATH', 'Process')
try {
    $env:JAVA_HOME = $JdkHome
    $env:PATH = (Join-Path $JdkHome 'bin') + [IO.Path]::PathSeparator + $previousPath
    New-Item -ItemType Directory -Force -Path $build,(Join-Path $build 'classes'),(Join-Path $build 'generated'),(Join-Path $build 'dex'),$OutputDirectory | Out-Null
    $resourceZip = Join-Path $build 'resources.zip'
    & (Join-Path $BuildTools 'aapt2.exe') compile --dir (Join-Path $project 'app/src/main/res') -o $resourceZip
    Check-Exit 'Resource compilation'
    $unsigned = Join-Path $build 'unsigned.apk'
    # AGP uses namespace from app/build.gradle; standalone aapt2 needs a package in its copy.
    [xml]$manifest = Get-Content -Raw -Encoding UTF8 (Join-Path $project 'app/src/main/AndroidManifest.xml')
    $manifest.DocumentElement.SetAttribute('package', $ApplicationId)
    $androidNamespace = 'http://schemas.android.com/apk/res/android'
    $application = $manifest.SelectSingleNode('/manifest/application')
    if ($null -eq $application) { throw 'Manifest has no application element' }
    [void]$application.SetAttribute('label', $androidNamespace, $ApplicationLabel)
    $versionName = $manifest.DocumentElement.GetAttribute('versionName', $androidNamespace)
    $versionCode = $manifest.DocumentElement.GetAttribute('versionCode', $androidNamespace)
    $standaloneManifest = Join-Path $build 'AndroidManifest.xml'
    $manifest.Save($standaloneManifest)
    & (Join-Path $BuildTools 'aapt2.exe') link -o $unsigned --manifest $standaloneManifest -I $AndroidJar --java (Join-Path $build 'generated') --custom-package local.pin --min-sdk-version 31 --target-sdk-version 36 --version-code $versionCode --version-name $versionName $resourceZip
    Check-Exit 'Manifest and resource linking'
    $javaFiles = @(Get-ChildItem (Join-Path $project 'app/src/main/java'),(Join-Path $build 'generated') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
    & (Join-Path $JdkHome 'bin/javac.exe') -encoding UTF-8 --release 8 -classpath $AndroidJar -d (Join-Path $build 'classes') @javaFiles
    Check-Exit 'Java compilation'
    $classJar = Join-Path $build 'classes.jar'
    & (Join-Path $JdkHome 'bin/jar.exe') cf $classJar -C (Join-Path $build 'classes') .
    Check-Exit 'Class packaging'
    & (Join-Path $BuildTools 'd8.bat') --release --min-api 31 --lib $AndroidJar --output (Join-Path $build 'dex') $classJar
    Check-Exit 'DEX conversion'
    # jar updates the APK ZIP without modifying the binary Android manifest or compiled resources.
    & (Join-Path $JdkHome 'bin/jar.exe') uf $unsigned -C (Join-Path $build 'dex') classes.dex
    Check-Exit 'DEX packaging'
    $aligned = Join-Path $build 'aligned.apk'
    & (Join-Path $BuildTools 'zipalign.exe') -f -p 4 $unsigned $aligned
    Check-Exit 'ZIP alignment'
    if (!(Test-Path -LiteralPath $KeyStorePath)) {
        $keyDirectory = Split-Path -Parent ([IO.Path]::GetFullPath($KeyStorePath))
        New-Item -ItemType Directory -Force -Path $keyDirectory | Out-Null
        & (Join-Path $JdkHome 'bin/keytool.exe') -genkeypair -keystore $KeyStorePath -storetype PKCS12 -storepass localpin-testing -keypass localpin-testing -alias localpin -keyalg RSA -keysize 3072 -validity 3650 -dname 'CN=LocalPin Personal Test Build'
        Check-Exit 'Test signing key generation'
    }
    $apk = Join-Path $OutputDirectory ('LocalPin-' + $versionName + '.apk')
    & (Join-Path $BuildTools 'apksigner.bat') sign --ks $KeyStorePath --ks-pass pass:localpin-testing --key-pass pass:localpin-testing --ks-key-alias localpin --v4-signing-enabled false --out $apk $aligned
    Check-Exit 'APK signing'
    & (Join-Path $BuildTools 'apksigner.bat') verify --verbose --print-certs $apk
    Check-Exit 'APK signature verification'
    & (Join-Path $BuildTools 'zipalign.exe') -c -v 4 $apk
    Check-Exit 'Alignment verification'
    Write-Output ('APK ready: ' + $apk)
    Get-FileHash -Algorithm SHA256 -LiteralPath $apk
}
finally {
    # PowerShell may coerce null to an empty .NET string; remove absent variables explicitly.
    if ($null -eq $previousJavaHome) {
        Remove-Item -LiteralPath 'Env:JAVA_HOME' -ErrorAction SilentlyContinue
    }
    else { [Environment]::SetEnvironmentVariable('JAVA_HOME', $previousJavaHome, 'Process') }
    if ($null -eq $previousPath) {
        Remove-Item -LiteralPath 'Env:PATH' -ErrorAction SilentlyContinue
    }
    else { [Environment]::SetEnvironmentVariable('PATH', $previousPath, 'Process') }
}
