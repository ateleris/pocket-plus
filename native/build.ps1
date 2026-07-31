# Build pocketplus.dll headlessly (GenC + ClangCL compile via pocketplus.vcxproj) — the Windows
# counterpart of build.sh, usable on CI or outside a Developer Command Prompt.
# Usage: powershell -File native\build.ps1 [-Configuration Debug|Release]
param([ValidateSet("Debug", "Release")][string]$Configuration = "Release")
$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path

# Prefer msbuild on PATH (Developer prompt, CI with setup-msbuild); else locate it via vswhere.
$msbuild = (Get-Command msbuild -ErrorAction SilentlyContinue).Source
if (-not $msbuild) {
    $vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
    if (-not (Test-Path $vswhere)) {
        throw "msbuild not on PATH and vswhere.exe not found; install Visual Studio or Build Tools."
    }
    $msbuild = & $vswhere -latest -requires Microsoft.Component.MSBuild `
        -find "MSBuild\**\Bin\MSBuild.exe" | Select-Object -First 1
    if (-not $msbuild) { throw "MSBuild not found via vswhere." }
}

& $msbuild "$here\pocketplus.vcxproj" /p:Configuration=$Configuration /p:Platform=x64 /nologo /m
if ($LASTEXITCODE -ne 0) { throw "msbuild failed with exit code $LASTEXITCODE" }
Write-Host "Built native\x64\$Configuration\pocketplus.dll"
