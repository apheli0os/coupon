param(
    [Parameter(Mandatory = $true)][string]$Branch,
    [Parameter(Mandatory = $true)][string]$Message,
    [string]$PrTitle = "",
    [string]$PrBody = "",
    [switch]$WithContainers
)

$ErrorActionPreference = "Stop"

if ($Branch -notmatch "^ai/.+") {
    throw "Branch must start with 'ai/', got: $Branch"
}
if ($Branch -in @("main", "master")) {
    throw "Refusing to ship directly to protected branch: $Branch"
}
if ([string]::IsNullOrWhiteSpace($Message)) {
    throw "Commit message must not be blank"
}

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    $status = git status --porcelain
    if (-not $status) {
        throw "Working tree is clean; nothing to ship."
    }

    if ($WithContainers) {
        & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "verify-all.ps1") -WithContainers
    } else {
        & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "verify-all.ps1")
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Verification gate failed; aborting ship."
    }

    $currentBranch = (git rev-parse --abbrev-ref HEAD).Trim()
    if ($currentBranch -ne $Branch) {
        git show-ref --verify --quiet "refs/heads/$Branch"
        if ($LASTEXITCODE -eq 0) {
            git checkout $Branch
        } else {
            git checkout -b $Branch
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to switch to branch $Branch"
        }
    }

    $activeBranch = (git rev-parse --abbrev-ref HEAD).Trim()
    if ($activeBranch -in @("main", "master")) {
        throw "Safety check failed: active branch cannot be $activeBranch"
    }

    git add -A
    if ($LASTEXITCODE -ne 0) {
        throw "git add failed"
    }
    git commit -m $Message
    if ($LASTEXITCODE -ne 0) {
        throw "git commit failed"
    }
    git push -u origin $Branch
    if ($LASTEXITCODE -ne 0) {
        throw "git push failed"
    }

    $title = if ([string]::IsNullOrWhiteSpace($PrTitle)) { $Message } else { $PrTitle }
    $body = if ([string]::IsNullOrWhiteSpace($PrBody)) {
        "Automated delivery from branch ``$Branch`` after passing verify-all gates."
    } else {
        $PrBody
    }

    $ghCmd = Get-Command gh -ErrorAction SilentlyContinue
    if ($ghCmd) {
        & gh auth status *> $null
        if ($LASTEXITCODE -eq 0) {
            $prUrl = (& gh pr create --base main --head $Branch --title $title --body $body)
            if ($LASTEXITCODE -ne 0) {
                throw "gh pr create failed"
            }
            & gh pr merge $prUrl --auto --squash
            if ($LASTEXITCODE -ne 0) {
                Write-Warning "PR created ($prUrl), but enabling auto-merge failed; check repository branch protection settings."
            } else {
                Write-Output "PR created with auto-merge enabled: $prUrl"
            }
            return
        }
    }

    $remoteUrl = (git remote get-url origin).Trim()
    $repoHttp = $remoteUrl -replace "\.git$", "" -replace "^git@github\.com:", "https://github.com/"
    Write-Output "Branch pushed: $Branch"
    Write-Output "Open PR manually: $repoHttp/compare/main...$Branch?expand=1"
    Write-Output "Tip: install GitHub CLI once via 'winget install GitHub.cli' and run 'gh auth login' to automate PR creation + auto-merge."
} finally {
    Pop-Location
}
