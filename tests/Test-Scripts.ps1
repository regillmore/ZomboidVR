$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
. (Join-Path $root 'scripts\Common.ps1')
function Assert-True { param([bool]$Value,[string]$Message); if(!$Value) { throw $Message } }
function Assert-Throws { param([scriptblock]$Action); $threw=$false; try { & $Action | Out-Null } catch { $threw=$true }; Assert-True $threw 'Expected an operation to be rejected.' }

# Parse every shipped script before invoking any functions. No game installation required.
foreach($file in (Get-ChildItem -LiteralPath $root -Recurse -File | Where-Object { $_.Extension -in @('.ps1','.psd1') -and $_.FullName -notlike "$root\build\*" })) {
    $parseErrors=$null; $tokens=$null
    $null=[Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tokens,[ref]$parseErrors)
    if($parseErrors.Count) { throw "$($file.Name): $($parseErrors -join '; ')" }
}

# All file mutations below use disposable fixtures inside the ignored build directory.
$fixture=Join-Path $root ('build\script-tests\' + [guid]::NewGuid().ToString('N'))
$steam=Join-Path $fixture 'Steam Home'
$library=Join-Path $fixture 'Second Steam Library'
$game=Join-Path $library 'steamapps\common\ProjectZomboid'
$vr=Join-Path $library 'steamapps\common\SteamVR'
$jdk=Join-Path $fixture 'JDK with spaces'
$checkout=Join-Path $fixture 'Checkout'
New-Item -ItemType Directory -Force -Path "$steam\steamapps","$game\jre64\bin","$vr\bin\win64","$jdk\bin","$checkout\config" | Out-Null
[IO.File]::WriteAllText("$steam\steamapps\libraryfolders.vdf",('"libraryfolders" { "1" { "path" "' + $library.Replace('\','\\') + '" } }'))
Set-Content -LiteralPath "$game\projectzomboid.jar","$vr\bin\win64\openvr_api.dll" -Value 'fixture'
$libraries=Get-SteamLibraries -SteamRoots @($steam)
Assert-True ($libraries -contains $library) 'A second Steam library with spaces was not discovered.'
Assert-True ((Resolve-SteamApp -Config @{} -Key GameDir -Folder ProjectZomboid -RequiredFile projectzomboid.jar -Libraries $libraries) -eq $game) 'Game discovery failed.'
Assert-True ((Resolve-SteamApp -Config @{SteamVrDir=$vr} -Key SteamVrDir -Folder SteamVR -RequiredFile 'bin\win64\openvr_api.dll') -eq $vr) 'Configured SteamVR path failed.'
Assert-Throws { Resolve-SteamApp -ExplicitPath $fixture -Config @{} -Key GameDir -Folder ProjectZomboid -RequiredFile projectzomboid.jar -Libraries $libraries }
foreach($exe in @('java','javac','jar')) { Set-Content -LiteralPath "$jdk\bin\$exe.exe" -Value 'fixture' }
Set-Content -LiteralPath "$jdk\release" -Value @('JAVA_VERSION="25.0.1"','OS_ARCH="amd64"')
Assert-True ((Resolve-Jdk -ExplicitPath $jdk -Config @{}) -eq $jdk) 'JDK 25 discovery failed.'
Set-Content -LiteralPath "$jdk\release" -Value @('JAVA_VERSION="21.0.1"','OS_ARCH="amd64"')
Assert-Throws { Resolve-Jdk -ExplicitPath $jdk -Config @{} }
Copy-Item -LiteralPath "$root\config\defaults.properties" -Destination "$checkout\config\defaults.properties"
Initialize-LiveSettings $checkout
Set-Content -LiteralPath "$checkout\live-control\settings.properties" -Value 'strength=0.3'
Initialize-LiveSettings $checkout
Assert-True ((Get-Content -LiteralPath "$checkout\live-control\settings.properties" -Raw).Trim() -eq 'strength=0.3') 'Settings were overwritten.'

# Exercise the real control script in the fixture checkout, never the live settings.
New-Item -ItemType Directory -Force -Path "$checkout\scripts" | Out-Null
Copy-Item -LiteralPath "$root\scripts\Common.ps1" -Destination "$checkout\scripts\Common.ps1"
Copy-Item -LiteralPath "$root\Control-Live.ps1" -Destination "$checkout\Control-Live.ps1"
$newline=[Environment]::NewLine
$cases=@(
    @{Name='missing strength'; Before="# Keep custom settings.`nfpsLimit=45`n"; Prefix="# Keep custom settings.`nfpsLimit=45`n"; Suffix=$newline},
    @{Name='no final newline'; Before='fpsLimit=45'; Prefix="fpsLimit=45$newline"; Suffix=$newline},
    @{Name='empty settings'; Before=''; Prefix=''; Suffix=$newline},
    @{Name='existing strength'; Before="# Keep custom settings.`r`nstrength=0.3`r`nfpsLimit=45`r`n"; Prefix="# Keep custom settings.`r`n"; Suffix="`r`nfpsLimit=45`r`n"}
)
foreach($case in $cases) {
    $settings="$checkout\live-control\settings.properties"
    [IO.File]::WriteAllText($settings,$case.Before,[Text.Encoding]::ASCII)
    foreach($level in @('0','0.7')) {
        & "$checkout\Control-Live.ps1" -Strength ([double]::Parse($level,[Globalization.CultureInfo]::InvariantCulture))
        $actual=[IO.File]::ReadAllText($settings)
        $expected=$case.Prefix+"strength=$level"+$case.Suffix
        Assert-True ($actual -ceq $expected) "Strength control failed for $($case.Name) at $level, or changed unrelated settings."
    }
}

$original=Join-Path $game 'jre64\bin\jli.dll'
$copy=Join-Path $game 'jli.dll'
Set-Content -LiteralPath $original -Value 'owned fixture bytes'
Ensure-HelperCopy -Root $checkout -GameDir $game
Assert-True (Test-Path -LiteralPath "$checkout\diagnostics\helper-copy.json") 'Copy ownership was not recorded.'
Set-Content -LiteralPath $copy -Value 'changed'
Assert-Throws { Remove-OwnedHelperCopy -Root $checkout -GameDir $game }
Assert-Throws { Ensure-HelperCopy -Root $checkout -GameDir $game }
Copy-Item -LiteralPath $original -Destination $copy -Force
$receipt=Get-Content -LiteralPath "$checkout\diagnostics\helper-copy.json" -Raw | ConvertFrom-Json
$receipt.path=$original
$receipt | ConvertTo-Json | Set-Content -LiteralPath "$checkout\diagnostics\helper-copy.json"
Assert-Throws { Remove-OwnedHelperCopy -Root $checkout -GameDir $game }
$receipt.path=$copy
$receipt | ConvertTo-Json | Set-Content -LiteralPath "$checkout\diagnostics\helper-copy.json"
Remove-OwnedHelperCopy -Root $checkout -GameDir $game | Out-Null
Assert-True ((Test-Path -LiteralPath $original) -and !(Test-Path -LiteralPath $copy)) 'Cleanup touched the original or left the owned copy.'
Copy-Item -LiteralPath $original -Destination $copy
Ensure-HelperCopy -Root $checkout -GameDir $game
Assert-True (!(Test-Path -LiteralPath "$checkout\diagnostics\helper-copy.json")) 'A pre-existing file was incorrectly claimed.'
Write-Output 'PASS: script syntax, second-library discovery, explicit paths, JDK validation, settings preservation, strength controls, and helper ownership/path/hash checks.'
