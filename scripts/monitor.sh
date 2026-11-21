#!/usr/bin/env bash
# 可观测性栈启停脚本（Prometheus + Alertmanager + Grafana + exporters）
# 用法:
#   ./scripts/monitor.sh start    启动监控栈
#   ./scripts/monitor.sh stop     停止（保留数据）
#   ./scripts/monitor.sh restart  重启
#   ./scripts/monitor.sh status   查看容器状态
#   ./scripts/monitor.sh logs     查看日志（可加服务名：logs grafana）
#   ./scripts/monitor.sh urls     打印访问地址

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="docker-compose-monitoring.yml"

print_urls() {
  echo "=== 监控栈访问地址 ==="
  echo "  Grafana      : http://localhost:3000  (admin/admin)"
  echo "  Prometheus   : http://localhost:9090"
  echo "  Alertmanager : http://localhost:9093"
  echo "  Redis exp.   : http://localhost:9121/metrics"
  echo "  Kafka exp.   : http://localhost:9308/metrics"
  echo "  提示：业务服务需先启动 (./scripts/mall.sh start)"
}

sync_monitor_env() {
  local ports_file="$ROOT_DIR/.run/ports.env"
  if [[ -z "${REDIS_EXPORTER_ADDR:-}" && -f "$ports_file" ]]; then
    # shellcheck disable=SC1090
    source "$ports_file"
    if [[ -n "${REDIS_PORT:-}" ]]; then
      export REDIS_EXPORTER_ADDR="host.docker.internal:${REDIS_PORT}"
      echo "[INFO] 检测到 Docker Redis 映射端口 :${REDIS_PORT}，redis-exporter 将连接 ${REDIS_EXPORTER_ADDR}"
    fi
  fi
}

start_stack() {
  sync_monitor_env
  echo "[INFO] 启动监控栈 ($COMPOSE_FILE)..."
  (cd "$ROOT_DIR" && docker compose -f "$COMPOSE_FILE" up -d)
  echo "[INFO] 等待就绪..."
  sleep 5
  print_urls
}

stop_stack() {
  echo "[INFO] 停止监控栈（保留数据卷）..."
  (cd "$ROOT_DIR" && docker compose -f "$COMPOSE_FILE" down)
}

case "${1:-}" in
  start)   start_stack ;;
  stop)    stop_stack ;;
  restart) stop_stack; start_stack ;;
  status)  (cd "$ROOT_DIR" && docker compose -f "$COMPOSE_FILE" ps) ;;
  logs)    (cd "$ROOT_DIR" && docker compose -f "$COMPOSE_FILE" logs -f "${2:-}") ;;
  urls)    print_urls ;;
  *)
    echo "用法: $0 <start|stop|restart|status|logs [service]|urls>"
    exit 1 ;;
esac
