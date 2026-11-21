<#
.SYNOPSIS
    mall-coupon-platform 可观测性监控栈 Windows 一键启停脚本（Prometheus + Alertmanager + Grafana + Exporters）

.EXAMPLE
    .\scripts\monitor.ps1 start
    .\scripts\monitor.ps1 status
    .\scripts\monitor.ps1 logs grafana
    .\scripts\monitor.ps1 stop
    .\scripts\monitor.ps1 stop -Clean
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet("start", "stop", "restart", "status", "logs", "urls", "help")]
    [string]$Action = "help",

    [Parameter(Position = 1)]
    [string]$Service = "",

    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $PSScriptRoot
$ComposeFile = "docker-compose-monitoring.yml"

function Sync-MonitorTargetEnv {
    $portsFile = Join-Path $RootDir ".run\ports.env"
    if (-not $env:REDIS_EXPORTER_ADDR -and (Test-Path $portsFile)) {
        foreach ($line in (Get-Content $portsFile -ErrorAction SilentlyContinue)) {
            if ($line -match '^REDIS_PORT=(\d+)$') {
                $env:REDIS_EXPORTER_ADDR = "host.docker.internal:$($Matches[1])"
                Write-Host "[INFO] 检测到 Docker Redis 映射端口 :$($Matches[1])，redis-exporter 将连接 $($env:REDIS_EXPORTER_ADDR)" -ForegroundColor Cyan
            }
        }
    }
}

function Show-Urls {
    Write-Host "=== 监控栈访问地址 ===" -ForegroundColor Cyan
    Write-Host "  Grafana      : http://localhost:3000  (admin / admin)"
    Write-Host "  Mall 大盘    : http://localhost:3000/d/mall-overview"
    Write-Host "  Prometheus   : http://localhost:9090"
    Write-Host "  Alertmanager : http://localhost:9093"
    Write-Host "  Redis exp.   : http://localhost:9121/metrics"
    Write-Host "  Kafka exp.   : http://localhost:9308/metrics"
    Write-Host "  提示：业务服务需先启动 (.\scripts\mall.ps1 start)" -ForegroundColor Yellow
}

function Invoke-Compose([string[]]$ComposeArgs) {
    Push-Location $RootDir
    try {
        & docker compose -f $ComposeFile @ComposeArgs
    } finally {
        Pop-Location
    }
}

switch ($Action) {
    "start" {
        Sync-MonitorTargetEnv
        Write-Host "[INFO] 启动监控栈 ($ComposeFile)..." -ForegroundColor Cyan
        Invoke-Compose @("up", "-d")
        Start-Sleep -Seconds 3
        Show-Urls
    }
    "stop" {
        if ($Clean) {
            Write-Host "[WARN] 停止监控栈并清空数据卷..." -ForegroundColor Yellow
            Invoke-Compose @("down", "-v")
        } else {
            Write-Host "[INFO] 停止监控栈（保留数据卷）..." -ForegroundColor Cyan
            Invoke-Compose @("down")
        }
    }
    "restart" {
        Invoke-Compose @("down")
        Invoke-Compose @("up", "-d")
        Show-Urls
    }
    "status" {
        Invoke-Compose @("ps")
    }
    "logs" {
        if ($Service) { Invoke-Compose @("logs", "-f", "--tail", "80", $Service) }
        else { Invoke-Compose @("logs", "-f", "--tail", "80") }
    }
    "urls" {
        Show-Urls
    }
    default {
        Write-Host "用法: .\scripts\monitor.ps1 <start|stop|restart|status|logs [service]|urls> [-Clean]" -ForegroundColor Cyan
    }
}
