param(
    [switch]$WithContainers
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

Push-Location $repoRoot
try {
    if ($WithContainers) {
        Write-Output "[1/3] Running Maven tests, integration tests, Checkstyle, and SpotBugs..."
        & mvn -B verify checkstyle:check spotbugs:check
    } else {
        Write-Output "[1/3] Running Maven unit tests, IT compile check, Checkstyle, and SpotBugs..."
        & mvn -B test checkstyle:check spotbugs:check
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Maven verification failed with exit code $LASTEXITCODE"
    }

    Write-Output "[2/3] Running formal TLA+ verification..."
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "verify-tla.ps1")
    if ($LASTEXITCODE -ne 0) {
        throw "TLA+ verification failed with exit code $LASTEXITCODE"
    }

    Write-Output "[3/3] Running frontend typecheck and build..."
    $webDir = Join-Path $repoRoot "web"
    Push-Location $webDir
    try {
        if (-not (Test-Path (Join-Path $webDir "node_modules"))) {
            & npm ci
            if ($LASTEXITCODE -ne 0) {
                throw "npm ci failed with exit code $LASTEXITCODE"
            }
        }
        & npm run build
        if ($LASTEXITCODE -ne 0) {
            throw "Frontend build failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }

    Write-Output "All local verification gates passed."
} finally {
    Pop-Location
}
