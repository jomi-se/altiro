$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Stop-WSL-Test.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Could not stop the isolated service' }
