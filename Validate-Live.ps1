param(
    [string]$GameDir = '',
    [string]$JdkDir = ''
)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
. (Join-Path $root 'scripts\Common.ps1')
$paths=Resolve-ProjectPaths -Root $root -GameDir $GameDir -JdkDir $JdkDir
$GameDir=$paths.GameDir; $JdkDir=$paths.JdkDir
$gameJar=Join-Path $GameDir 'projectzomboid.jar'
$classes=Join-Path $root 'build\validation'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources=@(Get-ChildItem -LiteralPath (Join-Path $root 'src\bootstrap\pzvr'),(Join-Path $root 'src\live\pzvr\live') -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JdkDir 'bin\javac.exe') --release 25 -cp $gameJar -d $classes @sources (Join-Path $root 'src\validation\pzvr\live\ValidateLive.java')
if($LASTEXITCODE -ne 0) { throw 'Validation compilation failed.' }
& (Join-Path $JdkDir 'bin\java.exe') --enable-native-access=ALL-UNNAMED -cp "$classes;$gameJar" pzvr.live.ValidateLive $root $gameJar
if($LASTEXITCODE -ne 0) { throw 'Live renderer validation failed.' }
