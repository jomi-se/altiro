$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$python = Join-Path $projectRoot '.venv\Scripts\python.exe'
$pidFile = Join-Path $projectRoot 'server.pid'
$logs = Join-Path $projectRoot 'logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
if (Test-Path -LiteralPath $pidFile) {
    $serverProcessId = [int](Get-Content -LiteralPath $pidFile)
    $existing = Get-Process -Id $serverProcessId -ErrorAction SilentlyContinue
    if ($existing -and $existing.Path -eq $python) {
        Write-Output ('Altiro is already running (PID ' + $serverProcessId + ').')
        exit 0
    }
}
$env:HF_HOME = Join-Path $projectRoot '.cache\huggingface'
$env:HF_HUB_OFFLINE = '1'
$env:HF_HUB_DISABLE_TELEMETRY = '1'
$process = Start-Process -FilePath $python -ArgumentList @('-u', ('"' + (Join-Path $projectRoot 'supervisor.py') + '"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'server.out.log') -RedirectStandardError (Join-Path $logs 'server.err.log') -PassThru
Set-Content -LiteralPath $pidFile -Value $process.Id -Encoding ascii
Write-Output ('Started Altiro (PID ' + $process.Id + '). Health: http://127.0.0.1:8765/health')
