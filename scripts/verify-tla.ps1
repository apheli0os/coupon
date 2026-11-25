$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$modelsDir = Join-Path $repoRoot "formal\models"
$cacheDir = Join-Path $repoRoot ".cache"
$cachedJar = Join-Path $cacheDir "tla2tools.jar"
$localPluginJar = Join-Path $repoRoot ".agents\plugins\pstack\.tools\tla2tools.jar"
$downloadUrl = "https://github.com/tlaplus/tlaplus/releases/download/v1.8.0/tla2tools.jar"

$tlaJar = $null
if ($env:TLA2TOOLS -and (Test-Path $env:TLA2TOOLS)) {
    $tlaJar = $env:TLA2TOOLS
} elseif (Test-Path $localPluginJar) {
    $tlaJar = $localPluginJar
} elseif (Test-Path $cachedJar) {
    $tlaJar = $cachedJar
} else {
    New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
    Invoke-WebRequest -Uri $downloadUrl -OutFile $cachedJar -UseBasicParsing
    $tlaJar = $cachedJar
}

$javaCmd = if ($env:JAVA -and (Test-Path $env:JAVA)) {
    $env:JAVA
} elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    Join-Path $env:JAVA_HOME "bin\java.exe"
} else {
    "java"
}

$models = @("SeckillProtocol", "CouponLifecycle", "ActivityTemplateLifecycle", "StockReconcileDebounce")

Push-Location $modelsDir
try {
    foreach ($model in $models) {
        $metaDir = Join-Path ([System.IO.Path]::GetTempPath()) ("tlc-meta-" + $model + "-" + [System.Guid]::NewGuid().ToString("N"))
        New-Item -ItemType Directory -Force -Path $metaDir | Out-Null
        try {
            Write-Output "Verifying TLA+ model: $model"
            & $javaCmd -XX:+UseParallelGC -cp $tlaJar tlc2.TLC -workers auto -cleanup -metadir $metaDir -config "$model.cfg" "$model.tla"
            if ($LASTEXITCODE -ne 0) {
                throw "TLC failed on model $model with exit code $LASTEXITCODE"
            }
        } finally {
            if (Test-Path $metaDir) {
                Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $metaDir
            }
        }
    }
} finally {
    Pop-Location
}
