param([switch]$Deploy)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
& (Join-Path $PSScriptRoot 'setup.ps1') -ControlOnly
if ($LASTEXITCODE -ne 0) { throw 'Control runtime installation failed' }
$installDirectory = Join-Path $PSScriptRoot '.runtime\wsl-install'
New-Item -ItemType Directory -Force -Path $installDirectory | Out-Null
function Get-VerifiedArtifact([string]$Uri, [string]$FileName, [string]$ExpectedHash) {
    $target = Join-Path $installDirectory $FileName
    if (-not (Test-Path -LiteralPath $target)) { Invoke-WebRequest -Uri $Uri -OutFile $target }
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ExpectedHash) { throw ('Artifact checksum mismatch: ' + $FileName) }
    return $target
}
$installer = Get-VerifiedArtifact 'https://github.com/microsoft/WSL/releases/download/3.0.3/wsl.3.0.3.0.x64.msi' 'wsl.3.0.3.0.x64.msi' 'ae977286974255bd26bf925f00f40f5692c6b2084155cbc0563c09aea091f211'
$signature = Get-AuthenticodeSignature -LiteralPath $installer
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notlike '*Microsoft Corporation*') { throw 'Invalid WSL installer signature' }
$imageUrl = 'https://releases.ubuntu.com/24.04.5/ubuntu-24.04.5-wsl-amd64.wsl'
$imageHash = 'bb415d824822c4b878125729af451a5d18fb13d1cf5cbed9a7393ad64ac6039e'
$image = Get-VerifiedArtifact $imageUrl 'ubuntu-24.04-amd64.wsl' $imageHash
@{ name='Ubuntu-24.04'; url=$imageUrl; sha256=$imageHash; file=$image } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $installDirectory 'ubuntu-image.json') -Encoding utf8
$python = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
& $python (Join-Path $PSScriptRoot 'download_model.py')
if ($LASTEXITCODE -ne 0) { throw 'Model preparation failed' }
& $python -c 'from smoke_test import prepare_sample; prepare_sample()'
if ($LASTEXITCODE -ne 0) { throw 'Public test-sample preparation failed' }
if ($Deploy) {
    & $python -u wsl\manage.py deploy
    if ($LASTEXITCODE -ne 0) { throw 'WSL deployment failed; check whether platform installation/restart is required' }
    & (Join-Path $PSScriptRoot 'Start-Server.ps1')
    & $python -u wsl\manage.py verify
    if ($LASTEXITCODE -ne 0) { throw 'Isolation verification failed' }
} else {
    Write-Output 'Verified installers, model and control tools are prepared. See server/README.md for the one-time administrator step and deployment.'
}
