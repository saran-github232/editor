$ErrorActionPreference = 'Stop'
$webRoot = Split-Path $PSScriptRoot -Parent
$pythonPath = Join-Path $webRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $pythonPath)) {
    py -3 -m venv (Join-Path $webRoot '.venv')
    if ($LASTEXITCODE -ne 0) { throw 'Install Python 3, then retry.' }
}
& $pythonPath -m pip install -r (Join-Path $webRoot 'server\requirements-speech.txt') nvidia-riva-client
if ($LASTEXITCODE -ne 0) { throw 'Speech dependencies could not be installed.' }
