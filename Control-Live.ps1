param([switch]$Stop,[switch]$Recenter,[switch]$Snapshot,[Nullable[double]]$Strength)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'scripts\Common.ps1')
Initialize-LiveSettings $PSScriptRoot
$control=Join-Path $PSScriptRoot 'live-control'
New-Item -ItemType Directory -Force -Path $control | Out-Null
if($Stop) { Set-Content -LiteralPath (Join-Path $control 'stop') -Value '' }
if($Recenter) { Set-Content -LiteralPath (Join-Path $control 'recenter') -Value '' }
if($Snapshot) { Set-Content -LiteralPath (Join-Path $control 'snapshot') -Value '' }
if($null -ne $Strength) {
    if($Strength -lt 0 -or $Strength -gt 1.5) { throw 'Strength must be 0 to 1.5.' }
    $path=Join-Path $control 'settings.properties'
    $text=Get-Content -LiteralPath $path -Raw
    $value=$Strength.ToString([Globalization.CultureInfo]::InvariantCulture)
    $text=[regex]::Replace($text,'(?m)^strength=.*$',"strength=$value")
    $temporary=Join-Path $control 'settings.tmp'
    [IO.File]::WriteAllText($temporary,$text,[Text.Encoding]::ASCII)
    Move-Item -LiteralPath $temporary -Destination $path -Force
}
