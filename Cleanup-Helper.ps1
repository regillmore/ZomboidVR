param([string]$GameDir='', [switch]$WaitForGameExit)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'scripts\Common.ps1')
if(!(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'diagnostics\helper-copy.json'))) { Write-Output 'No helper copy owned by this checkout.'; return }
$paths=Resolve-ProjectPaths -Root $PSScriptRoot -GameDir $GameDir -GameOnly
$games=@(Get-Process -Name ProjectZomboid64 -ErrorAction SilentlyContinue)
if($WaitForGameExit) { $games | Wait-Process }
elseif($games.Count) { throw 'Close Project Zomboid before cleaning up its loaded helper library.' }
Remove-OwnedHelperCopy -Root $PSScriptRoot -GameDir $paths.GameDir
