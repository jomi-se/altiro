$ErrorActionPreference = 'Stop'
$logDirectory = Join-Path $PSScriptRoot 'logs'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$logPath = Join-Path $logDirectory 'startup.log'
try {
    & (Join-Path $PSScriptRoot 'Start-WSL-Test.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'WSL startup failed' }
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8875/health' -TimeoutSec 2
            if ($health.ready -and $health.device -eq 'cuda') {
                Add-Content -LiteralPath $logPath -Value ((Get-Date -Format o) + ' Isolated GPU worker ready.')
                exit 0
            }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw 'Isolated GPU worker did not become ready within 90 seconds.'
} catch {
    Add-Content -LiteralPath $logPath -Value ((Get-Date -Format o) + ' Startup failed; inspect Linux service logs.')
    throw
}
