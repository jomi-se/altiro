param([switch]$ControlOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$env:UV_CACHE_DIR = Join-Path $projectRoot '.cache\uv'
$env:UV_PYTHON_INSTALL_DIR = Join-Path $projectRoot '.runtime\python'
$env:HF_HOME = Join-Path $projectRoot '.cache\huggingface'
$env:HF_HUB_DISABLE_TELEMETRY = '1'
$uvPath = Join-Path $projectRoot '.runtime\uv\uv.exe'
if (-not (Test-Path -LiteralPath $uvPath)) {
    New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot '.runtime\uv') | Out-Null
    $archive = Join-Path $projectRoot '.runtime\uv.zip'
    Invoke-WebRequest -Uri 'https://github.com/astral-sh/uv/releases/download/0.13.6/uv-x86_64-pc-windows-msvc.zip' -OutFile $archive
    $expectedHash = 'a2a8820d5eea32f08ef467ef3b7f66883a9b076d5737a2fc2fbb5f458d1bb50f'
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) { throw 'uv archive checksum mismatch' }
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $projectRoot '.runtime\uv') -Force
}
& $uvPath python install 3.11.18 --no-bin --no-registry
if ($LASTEXITCODE -ne 0) { throw 'Python installation failed' }
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot '.venv\Scripts\python.exe'))) {
    & $uvPath venv --python 3.11.18 .venv
    if ($LASTEXITCODE -ne 0) { throw 'Virtual environment creation failed' }
}
$dependencyInput = if ($ControlOnly) { 'requirements.control.in' } else { 'requirements.in' }
$dependencyLock = if ($ControlOnly) { 'requirements.control.lock' } else { 'requirements.lock' }
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot $dependencyLock))) {
    & $uvPath pip compile $dependencyInput --generate-hashes --output-file $dependencyLock --python .venv\Scripts\python.exe --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Dependency resolution failed' }
}
& $uvPath pip sync $dependencyLock --python .venv\Scripts\python.exe --require-hashes
if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed' }
if ($ControlOnly) { Write-Output 'Windows control tools installed. GPU inference runs inside WSL2.' }
else { Write-Output 'Optional native Windows runtime installed. This profile is not OS-sandboxed.' }
