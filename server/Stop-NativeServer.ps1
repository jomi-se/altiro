$ErrorActionPreference = 'Stop'
$pidFile = Join-Path $PSScriptRoot 'server.pid'
if (-not (Test-Path -LiteralPath $pidFile)) { Write-Output 'No Altiro PID file.'; exit 0 }
$serverProcessId = [int](Get-Content -LiteralPath $pidFile)
$process = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $serverProcessId)
$expectedPython = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
$expectedScript = Join-Path $PSScriptRoot 'supervisor.py'
$legacyScript = Join-Path $PSScriptRoot 'server.py'
if ($process -and $process.ExecutablePath -eq $expectedPython -and ($process.CommandLine.Contains($expectedScript) -or $process.CommandLine.Contains($legacyScript))) {
    # A Windows venv launcher owns a child Python process: stop this verified tree.
    & taskkill.exe /PID $serverProcessId /T /F | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop the Altiro process tree.' }
    Write-Output 'Stopped Altiro.'
} elseif ($process) { throw 'PID belongs to a different process; leaving it alone.' }
Remove-Item -LiteralPath $pidFile
