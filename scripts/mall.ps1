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

function Resolve-JavaExe {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-Path $candidate) { return $candidate }
    }
    try {
        $out = (& java -XshowSettings:properties -version 2>&1 | Out-String)
        if ($out -match 'java\.home\s*=\s*([^\r\n]+)') {
            $candidate = Join-Path $Matches[1].Trim() "bin\java.exe"
            if (Test-Path $candidate) { return $candidate }
        }
    } catch {
        # 回退到 PATH
    }
    return "java"
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

function Wait-ContainerHealthy([string]$Label, [string]$ContainerName, [int]$FallbackPort, [int]$TimeoutSec = 120) {
    Write-Info "等待 $Label ($ContainerName) 健康检查就绪..."
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $status = (& docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" $ContainerName 2>$null)
        if ($status) { $status = $status.Trim() }
        if ($status -eq "healthy") {
            Write-Ok "$Label ($ContainerName) healthy"
            return $true
        }
        if ($status -eq "exited" -or $status -eq "dead") {
            Write-Err "$Label ($ContainerName) 容器异常退出 ($status)"
            return $false
        }
        Start-Sleep -Seconds 2
    }
    Write-Warn "$Label ($ContainerName) 在 ${TimeoutSec}s 内未达到 healthy，回退检测端口 :$FallbackPort"
    return (Test-TcpPort "127.0.0.1" $FallbackPort)
}

function Get-DirectHttpBody([string]$Url, [int]$TimeoutMs = 3000) {
    $req = [System.Net.HttpWebRequest]::Create($Url)
    $req.Method = "GET"
    $req.Proxy = $null
    $req.Timeout = $TimeoutMs
    $req.ReadWriteTimeout = $TimeoutMs
    $resp = $req.GetResponse()
    try {
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
        try {
            return @{ StatusCode = [int]$resp.StatusCode; Body = $reader.ReadToEnd() }
        } finally {
            $reader.Dispose()
        }
    } finally {
        $resp.Dispose()
    }
}

function Wait-HttpOk([string]$Label, [string]$Url, [int]$TimeoutSec = 120) {
    Write-Info "等待 $Label ($Url) 就绪..."
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $res = Get-DirectHttpBody $Url 3000
            if ($res.StatusCode -ge 200 -and $res.StatusCode -lt 300) {
                Write-Ok "$Label 已就绪"
                return $true
            }
        } catch {
            # 尚未就绪，继续轮询
        }
        Start-Sleep -Seconds 2
    }
    Write-Warn "$Label ($Url) 在 ${TimeoutSec}s 内未就绪，继续尝试后续步骤"
    return $false
}

function Test-Health([int]$Port) {
    try {
        $res = Get-DirectHttpBody "http://127.0.0.1:$Port/actuator/health" 2500
        return ($res.Body -match '"status"\s*:\s*"UP"')
    } catch {
        return $false
    }
}

function Get-ContainerHostPort([string]$ContainerName, [int]$ContainerPort) {
    $running = (& docker inspect -f "{{.State.Running}}" $ContainerName 2>$null)
    if ($running -and $running.Trim() -eq "true") {
        $mapped = (& docker port $ContainerName "$ContainerPort/tcp" 2>$null | Select-Object -First 1)
        if ($mapped -and $mapped -match ":(\d+)\s*$") {
            return [int]$Matches[1]
        }
    }
    return $null
}

function Find-FreePort([int[]]$Candidates) {
    foreach ($p in $Candidates) {
        if (-not (Test-TcpPort "127.0.0.1" $p)) { return $p }
    }
    throw "未找到可用端口: $($Candidates -join ', ')"
}

function Load-PortsEnv {
    $portsFile = Join-Path $RunDir "ports.env"
    if (-not (Test-Path $portsFile)) { return }
    foreach ($line in (Get-Content $portsFile -ErrorAction SilentlyContinue)) {
        if ($line -match '^(MYSQL_PORT|REDIS_PORT|NACOS_PORT|NACOS_GRPC_PORT|NACOS_RAFT_PORT|NACOS_ADDR)=(.+)$') {
            $name = $Matches[1]
            $val = $Matches[2].Trim()
            if (-not [Environment]::GetEnvironmentVariable($name, "Process")) {
                [Environment]::SetEnvironmentVariable($name, $val, "Process")
            }
        }
    }
}

function Save-PortsEnv {
    Ensure-Dirs
    $portsFile = Join-Path $RunDir "ports.env"
    Set-Content -Path $portsFile -Value @(
        "MYSQL_PORT=$($env:MYSQL_PORT)"
        "REDIS_PORT=$($env:REDIS_PORT)"
        "NACOS_PORT=$($env:NACOS_PORT)"
        "NACOS_GRPC_PORT=$($env:NACOS_GRPC_PORT)"
        "NACOS_RAFT_PORT=$($env:NACOS_RAFT_PORT)"
        "NACOS_ADDR=$($env:NACOS_ADDR)"
    ) -Encoding ASCII
}

function Set-NacosPortEnv([int]$MainPort) {
    $env:NACOS_PORT = "$MainPort"
    $env:NACOS_GRPC_PORT = "$($MainPort + 1000)"
    $env:NACOS_RAFT_PORT = "$($MainPort + 1001)"
    $env:NACOS_ADDR = "127.0.0.1:$MainPort"
}

function Resolve-DevPorts {
    if ($Profile -ne "dev") { return }
    if ($SkipDocker) { Load-PortsEnv }

    if (-not $env:MYSQL_PORT) {
        $existing = if (Test-Command "docker") { Get-ContainerHostPort "mall-mysql" 3306 } else { $null }
        if ($existing) {
            $env:MYSQL_PORT = "$existing"
        } elseif (-not $SkipDocker -and (Test-TcpPort "127.0.0.1" 3306)) {
            $alt = Find-FreePort @(13306, 13316, 23306)
            $env:MYSQL_PORT = "$alt"
            Write-Warn "检测到本机 :3306 已被占用，自动将 Docker MySQL 端口切换为 127.0.0.1:$alt"
        } else {
            $env:MYSQL_PORT = "3306"
        }
    }

    if (-not $env:REDIS_PORT) {
        $existing = if (Test-Command "docker") { Get-ContainerHostPort "mall-redis" 6379 } else { $null }
        if ($existing) {
            $env:REDIS_PORT = "$existing"
        } elseif (-not $SkipDocker -and (Test-TcpPort "127.0.0.1" 6379)) {
            $alt = Find-FreePort @(16379, 16380, 26379)
            $env:REDIS_PORT = "$alt"
            Write-Warn "检测到本机 :6379 已被占用，自动将 Docker Redis 端口切换为 127.0.0.1:$alt"
        } else {
            $env:REDIS_PORT = "6379"
        }
    }

    if (-not $env:NACOS_PORT) {
        $existingHttp = if (Test-Command "docker") { Get-ContainerHostPort "mall-nacos" 8848 } else { $null }
        $existingGrpc = if (Test-Command "docker") { Get-ContainerHostPort "mall-nacos" 9848 } else { $null }
        if ($existingHttp -and $existingGrpc) {
            Set-NacosPortEnv $existingHttp
        } elseif (-not $SkipDocker -and ((Test-TcpPort "127.0.0.1" 8848) -or (Test-TcpPort "127.0.0.1" 9848))) {
            $alt = Find-FreePort @(18848, 28848)
            Set-NacosPortEnv $alt
            Write-Warn "检测到本机 :8848/:9848 已被占用，自动将 Docker Nacos 端口切换为 127.0.0.1:$alt (gRPC :$($alt + 1000))"
        } else {
            Set-NacosPortEnv 8848
        }
    } else {
        Set-NacosPortEnv ([int]$env:NACOS_PORT)
    }

    Save-PortsEnv
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
    Resolve-DevPorts
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
        # 若 Docker Desktop WSL2 暴露默认 8848/9848 发生端口绑定冲突，自动切到 18848/19848/19849 并重建 Nacos 容器
        $boundNacosHttp = Get-ContainerHostPort "mall-nacos" 8848
        $boundNacosGrpc = Get-ContainerHostPort "mall-nacos" 9848
        if ((-not $boundNacosHttp -or -not $boundNacosGrpc) -and $env:NACOS_PORT -eq "8848") {
            $altNacos = Find-FreePort @(18848, 28848)
            Set-NacosPortEnv $altNacos
            Save-PortsEnv
            Write-Warn "Nacos 默认端口 :8848/:9848 未能在宿主机成功映射，自动切换为 127.0.0.1:$altNacos (gRPC :$($altNacos + 1000))..."
            Push-Location $RootDir
            try {
                & docker compose -f $ComposeFile up -d nacos
                if ($LASTEXITCODE -ne 0) { throw "Nacos 端口切换重建失败" }
            } finally {
                Pop-Location
            }
        }
        $mysqlPort = if ($env:MYSQL_PORT) { [int]$env:MYSQL_PORT } else { 3306 }
        $redisPort = if ($env:REDIS_PORT) { [int]$env:REDIS_PORT } else { 6379 }
        $nacosPort = if ($env:NACOS_PORT) { [int]$env:NACOS_PORT } else { 8848 }
        Wait-ContainerHealthy "MySQL" "mall-mysql" $mysqlPort 120 | Out-Null
        Wait-ContainerHealthy "Redis" "mall-redis" $redisPort 60 | Out-Null
        Wait-ContainerHealthy "Kafka" "mall-kafka" 9092 120 | Out-Null
        Wait-ContainerHealthy "Nacos" "mall-nacos" $nacosPort 120 | Out-Null
        Wait-HttpOk "Nacos (宿主机端口)" "http://127.0.0.1:$nacosPort/nacos/v1/console/health/readiness" 30 | Out-Null
    }
}

function Stop-Middleware {
    if (-not (Test-Command "docker")) {
        Write-Warn "未找到 docker 命令，跳过停止中间件"
        return
    }
    Load-PortsEnv
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
    if ($SkipDocker) { Resolve-DevPorts } else { Start-Middleware }
    Build-IfNeeded
    $javaExe = Resolve-JavaExe

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

        $javaArgs = @("-Xms64m", "-Xmx320m", "-jar", $jar)
        if ($svc.UseProfile) {
            $javaArgs += "--spring.profiles.active=$Profile"
        }

        Write-Info "启动 $($svc.Module) (:$($svc.Port), profile=$Profile)..."
        $proc = Start-Process -FilePath $javaExe -ArgumentList $javaArgs `
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
    $shownNacosPort = if ($env:NACOS_PORT) { $env:NACOS_PORT } else { "8848" }
    Write-Host "  Nacos 控制台    : http://localhost:$shownNacosPort/nacos  (nacos / nacos)"
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
            $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$procId" -ErrorAction SilentlyContinue
            foreach ($child in $children) {
                Stop-Process -Id $child.ProcessId -Force -ErrorAction SilentlyContinue
            }
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            Wait-Process -Id $procId -Timeout 5 -ErrorAction SilentlyContinue
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
