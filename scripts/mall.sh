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

wait_container_healthy() {
  local label="$1" cname="$2" fallback_port="$3" timeout="${4:-120}"
  echo "[INFO] 等待 $label ($cname) 健康检查就绪..."
  local start=$(date +%s)
  while (( $(date +%s) - start < timeout )); do
    local st
    st="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cname" 2>/dev/null || true)"
    if [[ "$st" == "healthy" ]]; then
      echo "[ OK ] $label ($cname) healthy"
      return 0
    fi
    if [[ "$st" == "exited" || "$st" == "dead" ]]; then
      echo "[FAIL] $label ($cname) 容器异常退出 ($st)"
      return 1
    fi
    sleep 2
  done
  echo "[WARN] $label ($cname) 在 ${timeout}s 内未达到 healthy，回退检测端口 :$fallback_port"
  wait_port "$label" "$fallback_port" 10
}

wait_http_ok() {
  local label="$1" url="$2" timeout="${3:-120}"
  echo "[INFO] 等待 $label ($url) 就绪..."
  local start=$(date +%s)
  while (( $(date +%s) - start < timeout )); do
    if curl --noproxy '*' -sf --max-time 3 "$url" >/dev/null 2>&1; then
      echo "[ OK ] $label 已就绪"
      return 0
    fi
    sleep 2
  done
  echo "[WARN] $label ($url) 在 ${timeout}s 内未就绪"
  return 1
}

port_in_use() {
  local p="$1"
  (echo >"/dev/tcp/127.0.0.1/$p") >/dev/null 2>&1
}

get_container_host_port() {
  local cname="$1" cport="$2"
  local running
  running="$(docker inspect -f '{{.State.Running}}' "$cname" 2>/dev/null || true)"
  if [[ "$running" == "true" ]]; then
    local mapped
    mapped="$(docker port "$cname" "${cport}/tcp" 2>/dev/null | head -n1 || true)"
    if [[ "$mapped" =~ :([0-9]+)$ ]]; then
      echo "${BASH_REMATCH[1]}"
      return 0
    fi
  fi
  return 1
}

find_free_port() {
  for p in "$@"; do
    if ! port_in_use "$p"; then
      echo "$p"
      return 0
    fi
  done
  return 1
}

save_ports_env() {
  local ports_file="$RUN_DIR/ports.env"
  printf 'MYSQL_PORT=%s\nREDIS_PORT=%s\nNACOS_PORT=%s\nNACOS_GRPC_PORT=%s\nNACOS_RAFT_PORT=%s\nNACOS_ADDR=%s\n' \
    "$MYSQL_PORT" "$REDIS_PORT" "$NACOS_PORT" "$NACOS_GRPC_PORT" "$NACOS_RAFT_PORT" "$NACOS_ADDR" >"$ports_file"
}

set_nacos_port_env() {
  local main_port="$1"
  export NACOS_PORT="$main_port"
  export NACOS_GRPC_PORT="$((main_port + 1000))"
  export NACOS_RAFT_PORT="$((main_port + 1001))"
  export NACOS_ADDR="127.0.0.1:${main_port}"
}

resolve_dev_ports() {
  [[ "$PROFILE" != "dev" ]] && return 0
  local ports_file="$RUN_DIR/ports.env"
  if [[ "$SKIP_DOCKER" == "true" && -f "$ports_file" ]]; then
    # shellcheck disable=SC1090
    source "$ports_file"
  fi
  if [[ -z "${MYSQL_PORT:-}" ]]; then
    if existing="$(get_container_host_port mall-mysql 3306 2>/dev/null)"; then
      export MYSQL_PORT="$existing"
    elif [[ "$SKIP_DOCKER" == "false" ]] && port_in_use 3306; then
      MYSQL_PORT="$(find_free_port 13306 13316 23306)"
      export MYSQL_PORT
      echo "[WARN] 检测到本机 :3306 已被占用，自动将 Docker MySQL 端口切换为 127.0.0.1:${MYSQL_PORT}"
    else
      export MYSQL_PORT=3306
    fi
  fi
  if [[ -z "${REDIS_PORT:-}" ]]; then
    if existing="$(get_container_host_port mall-redis 6379 2>/dev/null)"; then
      export REDIS_PORT="$existing"
    elif [[ "$SKIP_DOCKER" == "false" ]] && port_in_use 6379; then
      REDIS_PORT="$(find_free_port 16379 16380 26379)"
      export REDIS_PORT
      echo "[WARN] 检测到本机 :6379 已被占用，自动将 Docker Redis 端口切换为 127.0.0.1:${REDIS_PORT}"
    else
      export REDIS_PORT=6379
    fi
  fi
  if [[ -z "${NACOS_PORT:-}" ]]; then
    if nhttp="$(get_container_host_port mall-nacos 8848 2>/dev/null)" && ngrpc="$(get_container_host_port mall-nacos 9848 2>/dev/null)"; then
      set_nacos_port_env "$nhttp"
    elif [[ "$SKIP_DOCKER" == "false" ]] && { port_in_use 8848 || port_in_use 9848; }; then
      local alt
      alt="$(find_free_port 18848 28848)"
      set_nacos_port_env "$alt"
      echo "[WARN] 检测到本机 :8848/:9848 已被占用，自动将 Docker Nacos 端口切换为 127.0.0.1:${alt} (gRPC :$((alt + 1000)))"
    else
      set_nacos_port_env 8848
    fi
  else
    set_nacos_port_env "$NACOS_PORT"
  fi
  save_ports_env
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
  resolve_dev_ports
  if [[ "$SKIP_DOCKER" == "false" ]]; then
    echo "[INFO] 启动 Docker 中间件 ($COMPOSE_FILE)..."
    (cd "$ROOT_DIR" && docker compose -f "$COMPOSE_FILE" up -d)
    if [[ "$PROFILE" == "ha" ]]; then
      wait_port "MySQL Master (HA)" 13308 120 || true
      wait_port "Redis Sentinel (HA)" 26381 60 || true
      wait_port "Kafka Broker-1 (HA)" 19092 90 || true
    else
      if ! get_container_host_port mall-nacos 8848 >/dev/null 2>&1 || ! get_container_host_port mall-nacos 9848 >/dev/null 2>&1; then
        if [[ "$NACOS_PORT" == "8848" ]]; then
          alt_nacos="$(find_free_port 18848 28848)"
          set_nacos_port_env "$alt_nacos"
          save_ports_env
          echo "[WARN] Nacos 默认端口 :8848/:9848 未能在宿主机映射，自动切换为 127.0.0.1:${alt_nacos}..."
          (cd "$ROOT_DIR" && docker compose -f "$COMPOSE_FILE" up -d nacos)
        fi
      fi
      wait_container_healthy "MySQL" "mall-mysql" "${MYSQL_PORT:-3306}" 120 || true
      wait_container_healthy "Redis" "mall-redis" "${REDIS_PORT:-6379}" 60 || true
      wait_container_healthy "Kafka" "mall-kafka" 9092 120 || true
      wait_container_healthy "Nacos" "mall-nacos" "${NACOS_PORT:-8848}" 120 || true
      wait_http_ok "Nacos (宿主机端口)" "http://127.0.0.1:${NACOS_PORT:-8848}/nacos/v1/console/health/readiness" 30 || true
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
