param(
    [string]$GameDir = '',
    [string]$JdkDir = '',
    [string]$SteamVrDir = '',
    [int]$GameProcessId = 0,
    [switch]$BuildOnly
)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
. (Join-Path $root 'scripts\Common.ps1')
$paths=Resolve-ProjectPaths -Root $root -GameDir $GameDir -JdkDir $JdkDir -SteamVrDir $SteamVrDir -NeedSteamVR:(!$BuildOnly)
$GameDir=$paths.GameDir; $JdkDir=$paths.JdkDir
Initialize-LiveSettings $root
$bootstrapClasses=Join-Path $root 'build\live-bootstrap-classes'
$runtimeClasses=Join-Path $root 'build\live-runtime-classes'
New-Item -ItemType Directory -Force -Path $bootstrapClasses,$runtimeClasses,(Join-Path $root 'live-control'),(Join-Path $root 'diagnostics') | Out-Null
$gameJar=Join-Path $GameDir 'projectzomboid.jar'
$javac=Join-Path $JdkDir 'bin\javac.exe'
$jar=Join-Path $JdkDir 'bin\jar.exe'
$java=Join-Path $JdkDir 'bin\java.exe'
$bootstrapSources=@(Get-ChildItem -LiteralPath (Join-Path $root 'src\bootstrap\pzvr') -Filter '*.java' | ForEach-Object FullName)
& $javac --release 25 --add-modules jdk.attach -cp $gameJar -d $bootstrapClasses @bootstrapSources (Join-Path $root 'src\pzvr\Attach.java')
if($LASTEXITCODE -ne 0) { throw 'Live bridge compilation failed.' }
$runtimeSources=@(Get-ChildItem -LiteralPath (Join-Path $root 'src\live\pzvr\live') -Filter '*.java' | ForEach-Object FullName)
& $javac --release 25 -cp $gameJar -d $runtimeClasses @bootstrapSources @runtimeSources
if($LASTEXITCODE -ne 0) { throw 'Live renderer compilation failed.' }
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$agentJar=Join-Path $root ("build\live-bootstrap-$stamp.jar")
$runtimeJar=Join-Path $root ("build\live-runtime-$stamp.jar")
$manifest=Join-Path $root 'build\LIVE-MANIFEST.MF'
Set-Content -LiteralPath $manifest -Encoding ascii -Value "Manifest-Version: 1.0`nAgent-Class: pzvr.LiveBootstrap`nCan-Redefine-Classes: false`nCan-Retransform-Classes: true`n"
& $jar --create --file $agentJar --manifest $manifest -C $bootstrapClasses .
if($LASTEXITCODE -ne 0) { throw 'Live agent packaging failed.' }
& $jar --create --file $runtimeJar -C $runtimeClasses pzvr/live
if($LASTEXITCODE -ne 0) { throw 'Live renderer packaging failed.' }
[IO.File]::WriteAllText((Join-Path $root 'build\live-runtime-path.txt'),$runtimeJar,(New-Object Text.UTF8Encoding $false))
if($BuildOnly) { Write-Output "Built $agentJar and $runtimeJar"; return }
$GameProcessId=Get-GameProcessId -RequestedId $GameProcessId -GameDir $GameDir
Ensure-HelperCopy -Root $root -GameDir $GameDir
$vrDll=Join-Path $paths.SteamVrDir 'bin\win64\openvr_api.dll'
[IO.File]::WriteAllText((Join-Path $root 'build\steamvr-dll-path.txt'),$vrDll,(New-Object Text.UTF8Encoding $false))
& $java --add-modules jdk.attach -cp $bootstrapClasses pzvr.Attach $GameProcessId $agentJar $root (Join-Path $GameDir 'jre64\bin\instrument.dll')
if($LASTEXITCODE -ne 0) { throw 'Could not start live stereo. See the game console and live-control status.' }
Write-Output 'Live stereo installed. Put on the connected headset; use Stop Live.cmd to stop.'
