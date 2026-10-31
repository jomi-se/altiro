$ErrorActionPreference = 'Stop'
$wslPath = Join-Path $env:WINDIR 'System32\wsl.exe'
& $wslPath --distribution Altiro-Isolated --user root --cd / --exec systemctl stop altiro-proxy.socket altiro-proxy.service altiro.service
if ($LASTEXITCODE -ne 0) { throw 'Could not stop isolated services' }
$pidPath = Join-Path $PSScriptRoot 'wsl-test.pid'
if (Test-Path -LiteralPath $pidPath) {
    $keepAliveId = [int](Get-Content -LiteralPath $pidPath)
    $existing = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $keepAliveId)
    if ($existing -and $existing.ExecutablePath -eq $wslPath -and $existing.CommandLine.Contains('Altiro-Isolated') -and $existing.CommandLine.Contains('sleep infinity')) { Stop-Process -Id $keepAliveId }
    elseif ($existing) { throw 'Keepalive PID belongs to another process; leaving it alone.' }
    Remove-Item -LiteralPath $pidPath
}
Write-Output 'Isolated WSL services stopped.'
