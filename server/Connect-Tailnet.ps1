param([int]$HttpsPort = 443)
$ErrorActionPreference = 'Stop'
$health = Invoke-RestMethod -Uri 'http://127.0.0.1:8875/health' -TimeoutSec 5
if (-not $health.ready -or $health.device -ne 'cuda') { throw 'Isolated GPU service is not ready' }
$reportPath = Join-Path $PSScriptRoot '.runtime\wsl-install\isolation-report.json'
$report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
if (-not $report.passed) { throw 'Isolation verification is required first' }
$tailscale = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
& $tailscale serve --bg ('--https=' + $HttpsPort) --yes http://127.0.0.1:8875
if ($LASTEXITCODE -ne 0) { throw 'Could not configure private HTTPS proxy' }
& $tailscale serve status
