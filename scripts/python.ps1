param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Args
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$uvCacheDir = Join-Path $repoRoot ".uv-cache"
$pythonExe = "C:\Users\mac\AppData\Local\Programs\Python\Python314\python.exe"

if (-not (Test-Path $pythonExe)) {
    throw "Expected Python interpreter not found at $pythonExe"
}

New-Item -ItemType Directory -Force -Path $uvCacheDir | Out-Null

$env:UV_CACHE_DIR = $uvCacheDir

& uv run --python $pythonExe python @Args
exit $LASTEXITCODE
