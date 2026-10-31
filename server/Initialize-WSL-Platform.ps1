# Run elevated. Installs WSL and enables virtualization, but never restarts Windows.
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$statusPath = Join-Path $projectRoot '.runtime\wsl-install\platform-status.json'
$platformStatus = @{ phase = 'starting'; restartRequired = $false; error = $null }
function Save-PlatformStatus { $platformStatus | ConvertTo-Json | Set-Content -LiteralPath $statusPath -Encoding utf8 }
try {
    $principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Windows administrator elevation is required.' }
    $installer = Join-Path $projectRoot '.runtime\wsl-install\wsl.3.0.3.0.x64.msi'
    if ((Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'ae977286974255bd26bf925f00f40f5692c6b2084155cbc0563c09aea091f211') { throw 'WSL installer checksum mismatch' }
    $signature = Get-AuthenticodeSignature -LiteralPath $installer
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notlike '*Microsoft Corporation*') { throw 'WSL installer signature mismatch' }
    $platformStatus.phase = 'installing-wsl'
    Save-PlatformStatus
    $installLog = Join-Path $projectRoot '.runtime\wsl-install\msi.log'
    $installerProcess = Start-Process -FilePath (Join-Path $env:WINDIR 'System32\msiexec.exe') -ArgumentList @('/i', ('"' + $installer + '"'), '/qn', '/norestart', '/l*v', ('"' + $installLog + '"')) -WindowStyle Hidden -PassThru -Wait
    if ($installerProcess.ExitCode -notin @(0,3010)) { throw ('WSL MSI failed with code ' + $installerProcess.ExitCode) }
    if ($installerProcess.ExitCode -eq 3010) { $platformStatus.restartRequired = $true }
    $platformStatus.phase = 'enabling-virtual-machine-platform'
    Save-PlatformStatus
    $feature = Enable-WindowsOptionalFeature -Online -FeatureName VirtualMachinePlatform -All -NoRestart
    if ($feature.RestartNeeded) { $platformStatus.restartRequired = $true }
    $platformStatus.phase = 'installed'
    Save-PlatformStatus
} catch {
    $platformStatus.phase = 'failed'
    $platformStatus.error = $_.Exception.Message
    Save-PlatformStatus
    exit 1
}
