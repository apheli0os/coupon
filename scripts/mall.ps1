<#
.SYNOPSIS
    mall-coupon-platform Windows 一键启停脚本（兼容 PowerShell 5.1 / 7+）

.EXAMPLE
    .\scripts\mall.ps1 start                 # 启动 Docker 中间件 + 4 个微服务（dev profile）
    .\scripts\mall.ps1 start -Build          # 先打包再启动
    .\scripts\mall.ps1 start -SkipDocker     # 仅启动 4 个微服务（中间件已在运行）
    .\scripts\mall.ps1 start -Profile ha     # 使用 docker-compose-ha.yml + ha profile 启动
    .\scripts\mall.ps1 status                # 查看全部微服务与中间件状态
    .\scripts\mall.ps1 logs gateway          # 实时查看日志（coupon/seckill/order/gateway）
    .\scripts\mall.ps1 stop                  # 停止 4 个微服务 + Docker 中间件
    .\scripts\mall.ps1 stop -SkipDocker      # 仅停止 4 个微服务，保留中间件
    .\scripts\mall.ps1 stop -Clean           # 停止并清理 Docker 数据卷（重置库表与 Redis）
    .\scripts\mall.ps1 restart               # 一键重启
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet("start", "stop", "restart", "status", "logs", "help")]
    [string]$Action = "help",

    [Parameter(Position = 1)]
    [string]$Target = "gateway",

    [ValidateSet("dev", "ha")]
    [string]$Profile = "dev",

    [switch]$SkipDocker,
    [switch]$Build,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent $PSScriptRoot
$RunDir = Join-Path $RootDir ".run"
$LogDir = Join-Path $RunDir "logs"
$ComposeFile = if ($Profile -eq "ha") { "docker-compose-ha.yml" } else { "docker-compose.yml" }

$Services = @(
    @{ Name = "coupon";  Module = "mall-coupon-service";  Port = 8081; UseProfile = $true  },
    @{ Name = "seckill"; Module = "mall-seckill-service"; Port = 8082; UseProfile = $true  },
    @{ Name = "order";   Module = "mall-order-service";   Port = 8083; UseProfile = $true  },
    @{ Name = "gateway"; Module = "mall-gateway";         Port = 8080; UseProfile = $false }
)

function Write-Info([string]$Msg) { Write-Host "[INFO] $Msg" -ForegroundColor Cyan }
function Write-Ok([string]$Msg)   { Write-Host "[ OK ] $Msg" -ForegroundColor Green }
function Write-Warn([string]$Msg) { Write-Host "[WARN] $Msg" -ForegroundColor Yellow }
function Write-Err([string]$Msg)  { Write-Host "[FAIL] $Msg" -ForegroundColor Red }

function Ensure-Dirs {
    if (-not (Test-Path $RunDir)) { New-Item -ItemType Directory -Path $RunDir -Force | Out-Null }
    if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
}

function Test-Command([string]$Cmd) {
    return $null -ne (Get-Command $Cmd -ErrorAction SilentlyContinue)
}

function Test-TcpPort([string]$TargetHost, [int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect($TargetHost, $Port, $null, $null)
        if ($iar.AsyncWaitHandle.WaitOne(500, $false)) {
            $client.EndConnect($iar)
            return $true
        }
        return $false
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Wait-TcpPort([string]$Label, [int]$Port, [int]$TimeoutSec = 90) {
    Write-Info "等待 $Label (localhost:$Port) 就绪..."
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-TcpPort "127.0.0.1" $Port) {
            Write-Ok "$Label (localhost:$Port) 已就绪"
            return $true
        }
        Start-Sleep -Seconds 2
    }
    Write-Warn "$Label (localhost:$Port) 在 ${TimeoutSec}s 内未响应，继续尝试后续步骤"
    return $false
}

function Test-Health([int]$Port) {
    try {
        $resp = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 2 -ErrorAction Stop
        return ($resp.status -eq "UP")
    } catch {
        return $false
    }
}

function Wait-ServiceHealth($Svc, [int]$TimeoutSec = 90) {
    $port = $Svc.Port
    $name = $Svc.Module
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-Health $port) {
            Write-Ok "$name (:$port) UP"
            return $true
        }
        $pidFile = Join-Path $RunDir "$($Svc.Name).pid"
        if (Test-Path $pidFile) {
            $procId = [int](Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
            if ($procId -gt 0 -and $null -eq (Get-Process -Id $procId -ErrorAction SilentlyContinue)) {
                Write-Err "$name 进程已退出，请查看日志: $(Join-Path $LogDir "$($Svc.Name).out.log") / .err.log"
                return $false
            }
        }
        Start-Sleep -Seconds 2
    }
    Write-Warn "$name (:$port) 在 ${TimeoutSec}s 内未通过健康检查，请查看日志: $(Join-Path $LogDir "$($Svc.Name).out.log")"
    return $false
}

function Get-RunningJavaPid($Svc) {
    $pidFile = Join-Path $RunDir "$($Svc.Name).pid"
    if (-not (Test-Path $pidFile)) { return $null }
    $raw = Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $raw) { return $null }
    $procId = 0
    if (-not [int]::TryParse($raw.Trim(), [ref]$procId)) { return $null }
    $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if ($null -ne $proc -and $proc.ProcessName -like "java*") {
        return $procId
    }
    return $null
}

function Start-Middleware {
    if (-not (Test-Command "docker")) {
        throw "未找到 docker 命令，请先安装并启动 Docker Desktop（或加 -SkipDocker 跳过中间件）"
    }
    Write-Info "启动 Docker 中间件 ($ComposeFile)..."
    Push-Location $RootDir
    try {
        & docker compose -f $ComposeFile up -d
        if ($LASTEXITCODE -ne 0) { throw "docker compose up 失败，请确认 Docker Desktop 已启动" }
    } finally {
        Pop-Location
    }

    if ($Profile -eq "ha") {
        Wait-TcpPort "MySQL Master (HA)" 13308 120 | Out-Null
        Wait-TcpPort "Redis Sentinel (HA)" 26381 60 | Out-Null
        Wait-TcpPort "Kafka Broker-1 (HA)" 19092 90 | Out-Null
    } else {
        Wait-TcpPort "MySQL" 3306 120 | Out-Null
        Wait-TcpPort "Redis" 6379 60 | Out-Null
        Wait-TcpPort "Kafka" 9092 90 | Out-Null
        Wait-TcpPort "Nacos" 8848 90 | Out-Null
    }
}

function Stop-Middleware {
    if (-not (Test-Command "docker")) {
        Write-Warn "未找到 docker 命令，跳过停止中间件"
        return
    }
    Push-Location $RootDir
    try {
        if ($Clean) {
            Write-Warn "停止并清理 Docker 数据卷 ($ComposeFile down -v)..."
            & docker compose -f $ComposeFile down -v
        } else {
            Write-Info "停止 Docker 中间件 ($ComposeFile stop)..."
            & docker compose -f $ComposeFile stop
        }
    } finally {
        Pop-Location
    }
}

function Build-IfNeeded {
    $needBuild = $Build.IsPresent
    if (-not $needBuild) {
        foreach ($svc in $Services) {
            $jar = Join-Path $RootDir "$($svc.Module)\target\$($svc.Module)-1.0.0-SNAPSHOT.jar"
            if (-not (Test-Path $jar)) {
                $needBuild = $true
                break
            }
        }
    }
    if ($needBuild) {
        if (-not (Test-Command "mvn")) { throw "缺少 fat JAR 且未找到 mvn 命令，请先安装 Maven" }
        Write-Info "开始编译打包（mvn -B -DskipTests package）..."
        Push-Location $RootDir
        try {
            & mvn -B -DskipTests package
            if ($LASTEXITCODE -ne 0) { throw "Maven 打包失败" }
        } finally {
            Pop-Location
        }
        Write-Ok "打包完成"
    }
}

function Start-All {
    if (-not (Test-Command "java")) { throw "未找到 java 命令，请确认 JDK 17+ 已加入 PATH" }
    Ensure-Dirs
    if (-not $SkipDocker) { Start-Middleware }
    Build-IfNeeded

    foreach ($svc in $Services) {
        $existingPid = Get-RunningJavaPid $svc
        if ($existingPid) {
            Write-Warn "$($svc.Module) 已在运行 (PID=$existingPid)，跳过重复启动"
            continue
        }
        $jar = Join-Path $RootDir "$($svc.Module)\target\$($svc.Module)-1.0.0-SNAPSHOT.jar"
        $outLog = Join-Path $LogDir "$($svc.Name).out.log"
        $errLog = Join-Path $LogDir "$($svc.Name).err.log"
        $pidFile = Join-Path $RunDir "$($svc.Name).pid"

        $javaArgs = @("-Xms128m", "-Xmx384m", "-jar", $jar)
        if ($svc.UseProfile) {
            $javaArgs += "--spring.profiles.active=$Profile"
        }

        Write-Info "启动 $($svc.Module) (:$($svc.Port), profile=$Profile)..."
        $proc = Start-Process -FilePath "java" -ArgumentList $javaArgs `
            -WorkingDirectory $RootDir `
            -RedirectStandardOutput $outLog `
            -RedirectStandardError $errLog `
            -WindowStyle Hidden `
            -PassThru
        Set-Content -Path $pidFile -Value $proc.Id -Encoding ASCII
    }

    Write-Info "等待 4 个微服务健康检查通过..."
    $allOk = $true
    foreach ($svc in $Services) {
        if (-not (Wait-ServiceHealth $svc 90)) { $allOk = $false }
    }

    Write-Host ""
    if ($allOk) {
        Write-Ok "全部服务启动完成！常用入口："
    } else {
        Write-Warn "部分服务尚未就绪，可执行 .\scripts\mall.ps1 logs <coupon|seckill|order|gateway> 排查"
    }
    Write-Host "  API 网关        : http://localhost:8080"
    Write-Host "  Swagger 优惠券  : http://localhost:8081/swagger-ui.html"
    Write-Host "  Swagger 秒杀    : http://localhost:8082/swagger-ui.html"
    Write-Host "  Swagger 订单    : http://localhost:8083/swagger-ui.html"
    Write-Host "  Nacos 控制台    : http://localhost:8848/nacos  (nacos / nacos)"
    Write-Host "  Sentinel 控制台 : http://localhost:8090"
    Write-Host "  日志目录        : $LogDir"
}

function Stop-All {
    Ensure-Dirs
    # 逆序停止：先停网关，再停业务服务
    $reversed = @($Services)
    [Array]::Reverse($reversed)
    foreach ($svc in $reversed) {
        $pidFile = Join-Path $RunDir "$($svc.Name).pid"
        $procId = Get-RunningJavaPid $svc
        if ($procId) {
            Write-Info "停止 $($svc.Module) (PID=$procId)..."
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            Write-Ok "$($svc.Module) 已停止"
        } else {
            Write-Host "[ -- ] $($svc.Module) 未运行"
        }
        if (Test-Path $pidFile) { Remove-Item $pidFile -Force -ErrorAction SilentlyContinue }
    }
    if (-not $SkipDocker) { Stop-Middleware }
}

function Show-Status {
    Write-Host "=== Java 微服务状态 ===" -ForegroundColor Cyan
    foreach ($svc in $Services) {
        $procId = Get-RunningJavaPid $svc
        $health = if (Test-Health $svc.Port) { "UP" } else { "DOWN" }
        $pidStr = if ($procId) { "PID=$procId" } else { "stopped" }
        $color = if ($health -eq "UP") { "Green" } else { "Yellow" }
        Write-Host ("  {0,-22} :{1}  {2,-12} health={3}" -f $svc.Module, $svc.Port, $pidStr, $health) -ForegroundColor $color
    }
    if (Test-Command "docker") {
        Write-Host ""
        Write-Host "=== Docker 中间件状态 ($ComposeFile) ===" -ForegroundColor Cyan
        Push-Location $RootDir
        try {
            & docker compose -f $ComposeFile ps
        } finally {
            Pop-Location
        }
    }
}

function Show-Logs {
    $outLog = Join-Path $LogDir "$Target.out.log"
    if (-not (Test-Path $outLog)) {
        Write-Err "日志文件不存在: $outLog（可选目标：coupon / seckill / order / gateway）"
        return
    }
    Write-Info "跟踪日志 $outLog（Ctrl+C 退出）..."
    Get-Content -Path $outLog -Tail 80 -Wait
}

function Show-Help {
    Write-Host "用法: .\scripts\mall.ps1 <start|stop|restart|status|logs> [选项]" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "命令:"
    Write-Host "  start               一键启动 Docker 中间件 + 4 个微服务（默认 -Profile dev）"
    Write-Host "  stop                一键停止 4 个微服务 + Docker 中间件"
    Write-Host "  restart             一键重启"
    Write-Host "  status              查看微服务与中间件健康状态"
    Write-Host "  logs <service>      跟踪日志（coupon | seckill | order | gateway）"
    Write-Host ""
    Write-Host "选项:"
    Write-Host "  -Profile <dev|ha>   切换单机 dev 或高可用 ha（默认 dev）"
    Write-Host "  -SkipDocker         仅启停 Java 微服务，不动 Docker 容器"
    Write-Host "  -Build              启动前强制执行 mvn -B -DskipTests package"
    Write-Host "  -Clean              配合 stop 使用，执行 docker compose down -v 清空数据卷"
}

switch ($Action) {
    "start"   { Start-All }
    "stop"    { Stop-All }
    "restart" { Stop-All; Start-All }
    "status"  { Show-Status }
    "logs"    { Show-Logs }
    default   { Show-Help }
}
