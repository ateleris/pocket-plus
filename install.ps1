# Windows counterpart of install.sh: download the Stainless toolchain (jar + z3 + cvc5)
# from GitHub releases into tools/stainless. Keep $Version in sync with install.sh.
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Version = "0.10.2"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$dest = Join-Path $root "tools\stainless"
$url = "https://github.com/epfl-lara/stainless/releases/download/v$Version/stainless-dotty-standalone-$Version-win.zip"

$zip = Join-Path ([System.IO.Path]::GetTempPath()) "stainless-$Version-win.zip"
Write-Host "Downloading $url"
Invoke-WebRequest -Uri $url -OutFile $zip

if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Expand-Archive -Path $zip -DestinationPath $dest
Remove-Item $zip
Write-Host "Installed Stainless $Version to $dest"
