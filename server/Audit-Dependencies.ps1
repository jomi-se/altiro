$ErrorActionPreference = 'Stop'
$env:UV_CACHE_DIR = Join-Path $PSScriptRoot '.cache\uv'
$env:UV_TOOL_DIR = Join-Path $PSScriptRoot '.runtime\tools'
Set-Location -LiteralPath $PSScriptRoot
& .\.runtime\uv\uv.exe tool run --python .venv\Scripts\python.exe --from pip-audit==2.10.1 pip-audit --requirement requirements.lock --disable-pip --no-deps --format json --output security-audit.local.json
if ($LASTEXITCODE -ne 0) { throw 'Dependency audit reported findings or failed. Read security-audit.local.json.' }
Write-Output 'No known Python-package vulnerabilities reported. This does not establish absence of vulnerabilities or malicious code.'
