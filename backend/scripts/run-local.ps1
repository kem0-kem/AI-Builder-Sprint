[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$backendRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $backendRoot ".env"
$envExample = Join-Path $backendRoot ".env.example"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker was not found in PATH. Install and start Docker Desktop, then retry."
}

if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    throw "Python was not found in PATH. Install Python 3.11 or newer, then retry."
}

if (-not (Test-Path -LiteralPath $envFile)) {
    Write-Host "Local configuration is missing: $envFile" -ForegroundColor Yellow
    Write-Host "Copy the template, review it, and run this script again:" -ForegroundColor Yellow
    Write-Host "  Copy-Item -LiteralPath `"$envExample`" -Destination `"$envFile`""
    exit 1
}

Push-Location $backendRoot
try {
    docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Desktop is installed but its engine is not available. Start Docker Desktop."
    }

    docker compose up -d --wait postgres
    if ($LASTEXITCODE -ne 0) {
        throw "PostgreSQL failed to start. Inspect it with 'docker compose logs postgres'."
    }

    python -m alembic upgrade head
    if ($LASTEXITCODE -ne 0) {
        throw "Database migrations failed."
    }

    Write-Host "Starting SlowTalk API at http://localhost:8000/api/v1" -ForegroundColor Green
    python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
    if ($LASTEXITCODE -ne 0) {
        throw "The SlowTalk API process exited with an error."
    }
}
finally {
    Pop-Location
}
