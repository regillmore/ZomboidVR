param(
    [string]$GameDir = '',
    [string]$JdkDir = '',
    [int]$GameProcessId = 0,
    [switch]$BuildOnly
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
. (Join-Path $root 'scripts\Common.ps1')
$paths=Resolve-ProjectPaths -Root $root -GameDir $GameDir -JdkDir $JdkDir
$GameDir=$paths.GameDir; $JdkDir=$paths.JdkDir
$classes = Join-Path $root 'build\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$gameJar = Join-Path $GameDir 'projectzomboid.jar'
if (!(Test-Path -LiteralPath $gameJar)) { throw "Game jar not found: $gameJar" }
$sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src\pzvr') -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JdkDir 'bin\javac.exe') --release 25 --add-modules jdk.attach -classpath $gameJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
$manifest = Join-Path $root 'build\MANIFEST.MF'
Set-Content -LiteralPath $manifest -Encoding ascii -Value "Manifest-Version: 1.0`nAgent-Class: pzvr.DepthProbe`nCan-Redefine-Classes: false`nCan-Retransform-Classes: false`n"
$agentJar = Join-Path $root ('build\pzvr-depth-probe-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.jar')
& (Join-Path $JdkDir 'bin\jar.exe') --create --file $agentJar --manifest $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed.' }
if ($BuildOnly) { Write-Output "Built $agentJar"; return }
$GameProcessId=Get-GameProcessId -RequestedId $GameProcessId -GameDir $GameDir
Ensure-HelperCopy -Root $root -GameDir $GameDir
$outputDir = Join-Path $root ('captures\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
& (Join-Path $JdkDir 'bin\java.exe') --add-modules jdk.attach -cp $classes pzvr.Attach $GameProcessId $agentJar $outputDir (Join-Path $GameDir 'jre64\bin\instrument.dll')
if ($LASTEXITCODE -ne 0) { throw "Could not attach to Project Zomboid. Diagnostics: $outputDir" }
$deadline = (Get-Date).AddSeconds(30)
while ((Get-Date) -lt $deadline) {
    $errorFile = Join-Path $outputDir 'error.txt'
    if (Test-Path -LiteralPath $errorFile) { throw (Get-Content -LiteralPath $errorFile -Raw) }
    $statusFile = Join-Path $outputDir 'status.txt'
    if ((Test-Path -LiteralPath $statusFile) -and (Get-Content -LiteralPath $statusFile -Raw) -eq 'Complete') {
        Write-Output "Capture complete: $outputDir"
        Get-Content -LiteralPath (Join-Path $outputDir 'capture.properties')
        return
    }
    Start-Sleep -Milliseconds 250
}
throw "Capture still pending; check $outputDir"
