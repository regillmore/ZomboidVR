# Shared by the Windows PowerShell 5.1 and PowerShell 7 entry points.
function Get-LocalPaths {
    param([string]$Root)
    $file=Join-Path $Root 'local.paths.psd1'
    if(Test-Path -LiteralPath $file -PathType Leaf) { return Import-PowerShellDataFile -LiteralPath $file }
    return @{}
}

function Get-SteamLibraries {
    param([string[]]$SteamRoots)
    if(!$SteamRoots) {
        $SteamRoots=@()
        foreach($key in @('HKCU:\Software\Valve\Steam','HKLM:\SOFTWARE\WOW6432Node\Valve\Steam','HKLM:\SOFTWARE\Valve\Steam')) {
            $entry=Get-ItemProperty -LiteralPath $key -ErrorAction SilentlyContinue
            if($entry.SteamPath) { $SteamRoots += $entry.SteamPath }
            if($entry.InstallPath) { $SteamRoots += $entry.InstallPath }
        }
        if(${env:ProgramFiles(x86)}) { $SteamRoots += Join-Path ${env:ProgramFiles(x86)} 'Steam' }
    }
    $libraries=@()
    foreach($steamRoot in $SteamRoots) {
        if(!(Test-Path -LiteralPath $steamRoot -PathType Container)) { continue }
        $libraries += [IO.Path]::GetFullPath($steamRoot)
        $vdf=Join-Path $steamRoot 'steamapps\libraryfolders.vdf'
        if(Test-Path -LiteralPath $vdf -PathType Leaf) {
            $content=Get-Content -LiteralPath $vdf -Raw
            foreach($match in [regex]::Matches($content,'"path"\s+"([^"]+)"')) {
                $libraries += [IO.Path]::GetFullPath($match.Groups[1].Value.Replace('\\','\'))
            }
        }
    }
    return @($libraries | Select-Object -Unique)
}

function Resolve-SteamApp {
    param([string]$ExplicitPath,[hashtable]$Config,[string]$Key,[string]$Folder,[string]$RequiredFile,[string[]]$Libraries)
    $candidate=$ExplicitPath
    if(!$candidate) { $candidate=$Config[$Key] }
    if($candidate) {
        $candidate=[IO.Path]::GetFullPath($candidate)
        if(!(Test-Path -LiteralPath (Join-Path $candidate $RequiredFile) -PathType Leaf)) { throw "$Key does not contain $RequiredFile`: $candidate" }
        return $candidate
    }
    foreach($library in $Libraries) {
        $candidate=Join-Path $library "steamapps\common\$Folder"
        if(Test-Path -LiteralPath (Join-Path $candidate $RequiredFile) -PathType Leaf) { return [IO.Path]::GetFullPath($candidate) }
    }
    throw "Cannot find $Folder. Set -$Key or copy config\local.paths.example.psd1 to local.paths.psd1 and fill in $Key."
}

function Resolve-Jdk {
    param([string]$ExplicitPath,[hashtable]$Config)
    $requested=$ExplicitPath
    if(!$requested) { $requested=$Config.JdkDir }
    $candidates=@()
    if($requested) { $candidates=@($requested) }
    else {
        if($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
        $compiler=Get-Command javac.exe -ErrorAction SilentlyContinue
        if($compiler) { $candidates += Split-Path (Split-Path $compiler.Source -Parent) -Parent }
        foreach($base in @('Java','Eclipse Adoptium','Microsoft')) {
            if($env:ProgramFiles) {
                $baseDir=Join-Path $env:ProgramFiles $base
                if(Test-Path -LiteralPath $baseDir -PathType Container) {
                    $candidates += @(Get-ChildItem -LiteralPath $baseDir -Directory -Filter 'jdk*' | Sort-Object Name -Descending | ForEach-Object FullName)
                }
            }
        }
    }
    foreach($candidate in ($candidates | Select-Object -Unique)) {
        $valid=$true
        foreach($exe in @('java','javac','jar')) { if(!(Test-Path -LiteralPath (Join-Path $candidate "bin\$exe.exe") -PathType Leaf)) { $valid=$false } }
        $release=Join-Path $candidate 'release'
        if(!$valid -or !(Test-Path -LiteralPath $release -PathType Leaf)) { continue }
        $info=Get-Content -LiteralPath $release -Raw
        $version=[regex]::Match($info,'JAVA_VERSION="(\d+)')
        $arch=[regex]::Match($info,'OS_ARCH="([^"]+)"')
        if($version.Success -and [int]$version.Groups[1].Value -ge 25 -and $arch.Groups[1].Value -in @('amd64','x86_64')) { return [IO.Path]::GetFullPath($candidate) }
    }
    throw 'A 64-bit JDK 25 or newer is required (25 is tested). Set -JdkDir, JdkDir in local.paths.psd1, or JAVA_HOME. The game JRE is not a compiler.'
}

function Resolve-ProjectPaths {
    param([string]$Root,[string]$GameDir,[string]$JdkDir,[string]$SteamVrDir,[switch]$NeedSteamVR,[switch]$GameOnly)
    $config=Get-LocalPaths $Root
    $libraries=Get-SteamLibraries
    $game=Resolve-SteamApp -ExplicitPath $GameDir -Config $config -Key GameDir -Folder ProjectZomboid -RequiredFile projectzomboid.jar -Libraries $libraries
    $jdk=$null; $vr=$null
    if(!$GameOnly) { $jdk=Resolve-Jdk -ExplicitPath $JdkDir -Config $config }
    if($NeedSteamVR) { $vr=Resolve-SteamApp -ExplicitPath $SteamVrDir -Config $config -Key SteamVrDir -Folder SteamVR -RequiredFile 'bin\win64\openvr_api.dll' -Libraries $libraries }
    return [pscustomobject]@{ GameDir=$game; JdkDir=$jdk; SteamVrDir=$vr }
}

function Initialize-LiveSettings {
    param([string]$Root)
    $control=Join-Path $Root 'live-control'
    New-Item -ItemType Directory -Force -Path $control | Out-Null
    $settings=Join-Path $control 'settings.properties'
    if(!(Test-Path -LiteralPath $settings)) { Copy-Item -LiteralPath (Join-Path $Root 'config\defaults.properties') -Destination $settings }
}

function Get-GameProcessId {
    param([int]$RequestedId,[string]$GameDir)
    if($RequestedId) { $games=@(Get-Process -Id $RequestedId -ErrorAction Stop) }
    else { $games=@(Get-Process -Name ProjectZomboid64 -ErrorAction SilentlyContinue) }
    if($games.Count -ne 1 -or $games[0].ProcessName -ne 'ProjectZomboid64') { throw 'Start exactly one Project Zomboid game, or supply its -GameProcessId.' }
    $expected=Join-Path $GameDir 'ProjectZomboid64.exe'
    if(!$games[0].Path -or [IO.Path]::GetFullPath($games[0].Path) -ne $expected) { throw 'The running game does not match GameDir, or its executable path cannot be read. Run as the same Windows user as the game.' }
    return $games[0].Id
}

function Ensure-HelperCopy {
    param([string]$Root,[string]$GameDir)
    $source=Join-Path $GameDir 'jre64\bin\jli.dll'
    $target=Join-Path $GameDir 'jli.dll'
    $hash=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    if(Test-Path -LiteralPath $target) {
        if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $hash) { throw 'A different jli.dll is present in the game directory. It will not be overwritten.' }
        return # Do not claim ownership of a pre-existing file.
    }
    New-Item -ItemType Directory -Force -Path (Join-Path $Root 'diagnostics') | Out-Null
    Copy-Item -LiteralPath $source -Destination $target
    @{path=$target;sha256=$hash} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Root 'diagnostics\helper-copy.json') -Encoding utf8
}

function Remove-OwnedHelperCopy {
    param([string]$Root,[string]$GameDir)
    $recordPath=Join-Path $Root 'diagnostics\helper-copy.json'
    if(!(Test-Path -LiteralPath $recordPath)) { Write-Output 'No helper copy owned by this checkout.'; return }
    $record=Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
    $expected=[IO.Path]::GetFullPath((Join-Path $GameDir 'jli.dll'))
    if(!$record.path -or [IO.Path]::GetFullPath($record.path) -ne $expected) { throw 'Recorded helper path does not match this game installation. Refusing cleanup.' }
    if(!(Test-Path -LiteralPath $expected)) { Write-Output 'Helper copy is already removed.'; return }
    if((Get-FileHash -LiteralPath $expected -Algorithm SHA256).Hash -ne $record.sha256) { throw 'Helper has changed since it was copied. Refusing cleanup.' }
    Remove-Item -LiteralPath $expected -ErrorAction Stop
    Remove-Item -LiteralPath $recordPath
    Write-Output 'Removed only the recorded helper copy; the original runtime library remains.'
}
