param([string]$Name=('layers-' + (Get-Date -Format 'yyyyMMdd-HHmmss')), [string]$GameDir='', [string]$JdkDir='', [int]$GameProcessId=0, [switch]$BuildOnly)
$ErrorActionPreference='Stop'
if($Name -notmatch '^[a-z0-9-]+$') { throw 'Use a simple diagnostic folder name.' }
$root=$PSScriptRoot
. (Join-Path $root 'scripts\Common.ps1')
$paths=Resolve-ProjectPaths -Root $root -GameDir $GameDir -JdkDir $JdkDir
$jdk=Join-Path $paths.JdkDir 'bin'
$game=$paths.GameDir
$classes=Join-Path $root 'build\layer-probe'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& "$jdk\javac.exe" --release 25 --add-modules jdk.attach -cp "$game\projectzomboid.jar" -d $classes "$root\src\diagnostics\pzvr\LayerProbe.java" "$root\src\pzvr\Attach.java"
if($LASTEXITCODE -ne 0) { throw 'Layer probe compilation failed.' }
$manifest=Join-Path $classes 'MANIFEST.MF'
Set-Content -LiteralPath $manifest -Encoding ascii -Value "Manifest-Version: 1.0`nAgent-Class: pzvr.LayerProbe`n"
$jar=Join-Path $root ('build\layer-probe-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.jar')
& "$jdk\jar.exe" --create --file $jar --manifest $manifest -C $classes .
if($LASTEXITCODE -ne 0) { throw 'Layer probe packaging failed.' }
if($BuildOnly) { Write-Output "Built $jar"; return }
if(Test-Path -LiteralPath "$root\diagnostics\$Name") { throw 'That diagnostic folder already exists. Use a new -Name.' }
$GameProcessId=Get-GameProcessId -RequestedId $GameProcessId -GameDir $game
Ensure-HelperCopy -Root $root -GameDir $game
& "$jdk\java.exe" --add-modules jdk.attach -cp $classes pzvr.Attach $GameProcessId $jar "$root\diagnostics\$Name" "$game\jre64\bin\instrument.dll"
if($LASTEXITCODE -ne 0) { throw 'Layer probe attach failed.' }
