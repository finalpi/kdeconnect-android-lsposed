# SPDX-License-Identifier: GPL-3.0-only
param(
    [string]$InstallDir = "$env:ProgramFiles\KDE Connect",
    [string]$PluginFile = (Join-Path $PSScriptRoot 'kdeconnect_clipboard.dll')
)
$ErrorActionPreference = 'Stop'
$metadata = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'runtime-manifest.json') -Raw | ConvertFrom-Json
$bin = Join-Path $InstallDir 'bin'
$core = Join-Path $bin 'kdeconnectcore.dll'
if ((Get-FileHash -LiteralPath $core -Algorithm SHA256).Hash.ToLowerInvariant() -ne $metadata.runtimeCoreSha256) {
    throw 'KDE Connect runtime does not match this native plugin. Rebuild against the installed runtime instead of replacing it.'
}
if (-not (Test-Path -LiteralPath $PluginFile)) { throw 'Compiled clipboard plugin is missing' }
if ([Threading.Thread]::CurrentThread.GetApartmentState() -ne 'STA') {
    throw 'Run this one-shot installer using powershell.exe -STA -File install-plugin.ps1'
}
Add-Type -AssemblyName System.Windows.Forms
if ([System.Windows.Forms.Clipboard]::ContainsImage()) {
    $existingImage = [System.Windows.Forms.Clipboard]::GetImage()
    try { [System.Windows.Forms.Clipboard]::SetDataObject($existingImage, $true) }
    finally { $existingImage.Dispose() }
}
$destination = Join-Path $bin 'kdeconnect\kdeconnect_clipboard.dll'
$backup = Join-Path $env:LOCALAPPDATA ('kdeconnect-native-clipboard-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup | Out-Null
Copy-Item -LiteralPath $destination -Destination (Join-Path $backup 'kdeconnect_clipboard.dll')
$processes = @(Get-Process -Name kdeconnect-app,kdeconnectd,kdeconnect-indicator,kdeconnect-cli -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -like (Join-Path $bin '*') })
foreach ($process in $processes) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
foreach ($process in $processes) { $null = $process.WaitForExit(5000) }
Copy-Item -LiteralPath $PluginFile -Destination $destination -Force
if ((Get-FileHash -LiteralPath $PluginFile).Hash -ne (Get-FileHash -LiteralPath $destination).Hash) {
    throw 'Native plugin copy verification failed'
}
Start-Process -FilePath (Join-Path $bin 'kdeconnectd.exe') -WindowStyle Hidden
Start-Process -FilePath (Join-Path $bin 'kdeconnect-indicator.exe') -WindowStyle Hidden
Start-Process -FilePath (Join-Path $bin 'kdeconnect-app.exe')
Write-Output "Existing native clipboard plugin updated. Backup: $backup"
