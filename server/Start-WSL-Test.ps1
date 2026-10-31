$ErrorActionPreference = 'Stop'
$wslPath = Join-Path $env:WINDIR 'System32\wsl.exe'
& $wslPath --distribution Altiro-Isolated --user root --cd / --exec systemctl start altiro.service altiro-proxy.socket
if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated services' }
$pidPath = Join-Path $PSScriptRoot 'wsl-test.pid'
if (Test-Path -LiteralPath $pidPath) {
    $keepAliveId = [int](Get-Content -LiteralPath $pidPath)
    $existing = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $keepAliveId)
    if ($existing -and $existing.ExecutablePath -eq $wslPath -and $existing.CommandLine.Contains('Altiro-Isolated') -and $existing.CommandLine.Contains('sleep infinity')) { Write-Output 'WSL keepalive is already running.'; exit 0 }
}
$keepAlive = Start-Process -FilePath $wslPath -ArgumentList @('--distribution','Altiro-Isolated','--user','altiro','--cd','/','--exec','/usr/bin/sleep','infinity') -WindowStyle Hidden -PassThru
Set-Content -LiteralPath $pidPath -Value $keepAlive.Id -Encoding ascii
Write-Output 'Isolated service started. Local URL: http://127.0.0.1:8875'
