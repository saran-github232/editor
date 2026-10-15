param([string]$Python = "python")
$ErrorActionPreference = "Stop"
$webRoot = Join-Path $PSScriptRoot "../web"
Push-Location $webRoot
try {
    & $Python -m venv .venv
    if ($LASTEXITCODE -ne 0) { throw "Unable to create the speech environment. Use Python 3.10-3.12." }
    & .venv/Scripts/python.exe -m pip install -r server/requirements-speech.txt
    if ($LASTEXITCODE -ne 0) { throw "Speech dependencies could not be installed." }
    Write-Host "Speech runtime ready. Download a model from Clipper's Batch clips workspace."
} finally { Pop-Location }
