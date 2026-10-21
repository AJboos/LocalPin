#requires -Version 5.1
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$JdkHome
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-CheckedNative {
    param(
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][string[]]$NativeArguments,
        [Parameter(Mandatory = $true)][string]$Step
    )
    & $Executable @NativeArguments
    $nativeExitCode = $LASTEXITCODE
    if ($nativeExitCode -ne 0) {
        throw ($Step + ' failed with exit code ' + $nativeExitCode)
    }
}

$jdkDirectory = (Resolve-Path -LiteralPath $JdkHome -ErrorAction Stop).ProviderPath
$java = Join-Path $jdkDirectory 'bin/java.exe'
$javac = Join-Path $jdkDirectory 'bin/javac.exe'
foreach ($tool in @($java, $javac)) {
    if (!(Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw ('Missing JDK executable: ' + $tool)
    }
}

$sources = @(
    'app/src/main/java/local/pin/MapMath.java',
    'app/src/main/java/local/pin/PositionCheck.java',
    'app/src/main/java/local/pin/SimulationEngine.java',
    'app/src/main/java/local/pin/RoutePlan.java',
    'app/src/main/java/local/pin/MotionSession.java',
    'app/src/main/java/local/pin/SubmittedFixes.java',
    'app/src/main/java/local/pin/RouteEditor.java',
    'app/src/main/java/local/pin/MapSource.java',
    'tests/MapMathTest.java',
    'tests/PositionCheckTest.java',
    'tests/SimulationEngineTest.java',
    'tests/RouteMotionTest.java',
    'tests/MotionEngineTest.java',
    'tests/SubmittedFixesTest.java',
    'tests/RouteEditorTest.java',
    'tests/MapSourceTest.java'
) | ForEach-Object {
    $source = Join-Path $PSScriptRoot $_
    if (!(Test-Path -LiteralPath $source -PathType Leaf)) {
        throw ('Missing test source: ' + $source)
    }
    $source
}

$previousJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
$previousPath = [Environment]::GetEnvironmentVariable('PATH', 'Process')
try {
    $env:JAVA_HOME = $jdkDirectory
    $env:PATH = (Join-Path $jdkDirectory 'bin') + [IO.Path]::PathSeparator + $previousPath

    $compilerVersion = @(Invoke-CheckedNative -Executable $javac -NativeArguments @('--version') -Step 'JDK compiler check')
    $runtimeVersion = @(Invoke-CheckedNative -Executable $java -NativeArguments @('--version') -Step 'JDK runtime check')
    if (($compilerVersion -join "`n") -notmatch '(?m)^javac 17(?:[.\s]|$)' -or
        ($runtimeVersion -join "`n") -notmatch '(?m)^(?:openjdk|java) 17(?:[.\s]|$)') {
        throw 'JdkHome must contain both the JDK 17 compiler and runtime.'
    }

    # Every invocation gets separate classes; existing .build entries stay intact.
    $testBuild = Join-Path $PSScriptRoot ('.build/tests-' + [Guid]::NewGuid().ToString('N'))
    $classes = Join-Path $testBuild 'classes'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null

    $compileArguments = @('-proc:none', '-encoding', 'UTF-8', '--release', '17',
                          '-classpath', $classes, '-d', $classes) + @($sources)
    Invoke-CheckedNative -Executable $javac -NativeArguments $compileArguments -Step 'Pure JVM source compilation'
    foreach ($testMain in @('local.pin.MapMathTest', 'local.pin.PositionCheckTest', 'local.pin.SimulationEngineTest',
                          'local.pin.RouteMotionTest', 'local.pin.MotionEngineTest', 'local.pin.SubmittedFixesTest',
                          'local.pin.RouteEditorTest', 'local.pin.MapSourceTest')) {
        Invoke-CheckedNative -Executable $java -NativeArguments @('-ea', '-cp', $classes, $testMain) -Step $testMain
    }
    Write-Output ('All JVM test mains passed. Classes: ' + $classes)
    Write-Output 'These JVM tests do not verify Android or other apps.'
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
