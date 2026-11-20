#!/usr/bin/env bash
# mall-coupon-platform Linux / macOS 一键启停脚本
# 用法：
#   ./scripts/mall.sh start [--profile dev|ha] [--skip-docker] [--build]
#   ./scripts/mall.sh stop  [--profile dev|ha] [--skip-docker] [--clean]
#   ./scripts/mall.sh restart [选项]
#   ./scripts/mall.sh status [--profile dev|ha]
#   ./scripts/mall.sh logs <coupon|seckill|order|gateway>
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
LOG_DIR="$RUN_DIR/logs"

ACTION="${1:-help}"
shift || true

TARGET="gateway"
PROFILE="dev"
SKIP_DOCKER=false
BUILD=false
CLEAN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)     PROFILE="${2:-dev}"; shift 2 ;;
    --skip-docker) SKIP_DOCKER=true; shift ;;
    --build)       BUILD=true; shift ;;
    --clean)       CLEAN=true; shift ;;
    coupon|seckill|order|gateway) TARGET="$1"; shift ;;
    *) echo "[WARN] 忽略未知参数: $1"; shift ;;
  esac
done

COMPOSE_FILE="docker-compose.yml"
[[ "$PROFILE" == "ha" ]] && COMPOSE_FILE="docker-compose-ha.yml"

SERVICES=("coupon:mall-coupon-service:8081:1" "seckill:mall-seckill-service:8082:1" "order:mall-order-service:8083:1" "gateway:mall-gateway:8080:0")

wait_port() {
  local label="$1" port="$2" timeout="${3:-90}"
  echo "[INFO] 等待 $label (localhost:$port) 就绪..."
  local start=$(date +%s)
  while (( $(date +%s) - start < timeout )); do
    if (echo >"/dev/tcp/127.0.0.1/$port") >/dev/null 2>&1; then
      echo "[ OK ] $label (localhost:$port) 已就绪"
      return 0
    fi
    sleep 2
  done
  echo "[WARN] $label (localhost:$port) 在 ${timeout}s 内未响应"
  return 1
}

check_health() {
  local port="$1"
  curl -sf --max-time 2 "http://127.0.0.1:${port}/actuator/health" | grep -q '"status":"UP"'
}

wait_health() {
  local name="$1" module="$2" port="$3" timeout="${4:-90}"
  local start=$(date +%s)
  while (( $(date +%s) - start < timeout )); do
    if check_health "$port"; then
      echo "[ OK ] $module (:$port) UP"
      return 0
    fi
    if [[ -f "$RUN_DIR/${name}.pid" ]]; then
      local pid
      pid="$(cat "$RUN_DIR/${name}.pid")"
      if ! kill -0 "$pid" 2>/dev/null; then
        echo "[FAIL] $module 已退出，查看日志: $LOG_DIR/${name}.log"
        return 1
      fi
    fi
    sleep 2
  done
  echo "[WARN] $module (:$port) 超时未就绪，查看日志: $LOG_DIR/${name}.log"
  return 1
}

start_all() {
  mkdir -p "$LOG_DIR"
  if [[ "$SKIP_DOCKER" == "false" ]]; then
    echo "[INFO] 启动 Docker 中间件 ($COMPOSE_FILE)..."
    (cd "$ROOT_DIR" && docker compose -f "$COMPOSE_FILE" up -d)
    if [[ "$PROFILE" == "ha" ]]; then
      wait_port "MySQL Master (HA)" 13308 120 || true
      wait_port "Redis Sentinel (HA)" 26381 60 || true
      wait_port "Kafka Broker-1 (HA)" 19092 90 || true
    else
      wait_port "MySQL" 3306 120 || true
      wait_port "Redis" 6379 60 || true
      wait_port "Kafka" 9092 90 || true
      wait_port "Nacos" 8848 90 || true
    fi
  fi

  local need_build="$BUILD"
  if [[ "$need_build" == "false" ]]; then
    for entry in "${SERVICES[@]}"; do
      IFS=':' read -r _ module _ _ <<< "$entry"
      [[ ! -f "$ROOT_DIR/$module/target/${module}-1.0.0-SNAPSHOT.jar" ]] && need_build=true
    done
  fi
  if [[ "$need_build" == "true" ]]; then
    echo "[INFO] 编译打包 (mvn -B -DskipTests package)..."
    (cd "$ROOT_DIR" && mvn -B -DskipTests package)
  fi

  for entry in "${SERVICES[@]}"; do
    IFS=':' read -r name module port use_profile <<< "$entry"
    local pid_file="$RUN_DIR/${name}.pid"
    if [[ -f "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
      echo "[WARN] $module 已在运行 (PID=$(cat "$pid_file"))"
      continue
    fi
    local jar="$ROOT_DIR/$module/target/${module}-1.0.0-SNAPSHOT.jar"
    local args=(-Xms128m -Xmx384m -jar "$jar")
    [[ "$use_profile" == "1" ]] && args+=("--spring.profiles.active=$PROFILE")
    echo "[INFO] 启动 $module (:$port, profile=$PROFILE)..."
    nohup java "${args[@]}" >"$LOG_DIR/${name}.log" 2>&1 &
    echo $! >"$pid_file"
  done

  echo "[INFO] 等待 4 个微服务健康检查通过..."
  for entry in "${SERVICES[@]}"; do
    IFS=':' read -r name module port _ <<< "$entry"
    wait_health "$name" "$module" "$port" 90 || true
  done

  cat <<EOF

[ OK ] 启动流程结束，常用入口：
  API 网关        : http://localhost:8080
  Swagger 优惠券  : http://localhost:8081/swagger-ui.html
  Swagger 秒杀    : http://localhost:8082/swagger-ui.html
  Swagger 订单    : http://localhost:8083/swagger-ui.html
  Nacos 控制台    : http://localhost:8848/nacos  (nacos / nacos)
  Sentinel 控制台 : http://localhost:8090
  日志目录        : $LOG_DIR
EOF
}

stop_all() {
  for (( idx=${#SERVICES[@]}-1 ; idx>=0 ; idx-- )); do
    IFS=':' read -r name module _ _ <<< "${SERVICES[$idx]}"
    local pid_file="$RUN_DIR/${name}.pid"
    if [[ -f "$pid_file" ]]; then
      local pid
      pid="$(cat "$pid_file")"
      if kill -0 "$pid" 2>/dev/null; then
        echo "[INFO] 停止 $module (PID=$pid)..."
        kill "$pid" 2>/dev/null || true
      fi
      rm -f "$pid_file"
    fi
  done
  if [[ "$SKIP_DOCKER" == "false" ]]; then
    if [[ "$CLEAN" == "true" ]]; then
      echo "[WARN] 停止并清理 Docker 数据卷 ($COMPOSE_FILE down -v)..."
      (cd "$ROOT_DIR" && docker compose -f "$COMPOSE_FILE" down -v)
    else
      echo "[INFO] 停止 Docker 中间件 ($COMPOSE_FILE stop)..."
      (cd "$ROOT_DIR" && docker compose -f "$COMPOSE_FILE" stop)
    fi
  fi
}

show_status() {
  echo "=== Java 微服务状态 ==="
  for entry in "${SERVICES[@]}"; do
    IFS=':' read -r name module port _ <<< "$entry"
    local pid_str="stopped"
    if [[ -f "$RUN_DIR/${name}.pid" ]] && kill -0 "$(cat "$RUN_DIR/${name}.pid")" 2>/dev/null; then
      pid_str="PID=$(cat "$RUN_DIR/${name}.pid")"
    fi
    local health="DOWN"
    check_health "$port" && health="UP"
    printf "  %-22s :%s  %-12s health=%s\n" "$module" "$port" "$pid_str" "$health"
  done
  echo ""
  echo "=== Docker 中间件状态 ($COMPOSE_FILE) ==="
  (cd "$ROOT_DIR" && docker compose -f "$COMPOSE_FILE" ps)
}

case "$ACTION" in
  start)   start_all ;;
  stop)    stop_all ;;
  restart) stop_all; start_all ;;
  status)  show_status ;;
  logs)    tail -n 80 -f "$LOG_DIR/${TARGET}.log" ;;
  *)
    echo "用法: ./scripts/mall.sh <start|stop|restart|status|logs> [--profile dev|ha] [--skip-docker] [--build] [--clean]"
    ;;
esac
