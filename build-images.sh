#!/usr/bin/env bash
# 构建四个业务镜像（含网关），供 docker compose 或 K8s 使用
set -euo pipefail
cd "$(dirname "$0")"

TAG="${TAG:-1.0.0}"
REGISTRY="${REGISTRY:-mall}"

declare -A IMAGES=(
  [mall-gateway]="gateway"
  [mall-coupon-service]="coupon-service"
  [mall-seckill-service]="seckill-service"
  [mall-order-service]="order-service"
)

for MODULE in "${!IMAGES[@]}"; do
  NAME="${IMAGES[$MODULE]}"
  echo "==> building ${REGISTRY}/${NAME}:${TAG} (module=${MODULE})"
  docker build --build-arg MODULE="${MODULE}" \
    -t "${REGISTRY}/${NAME}:${TAG}" .
done

echo "==> 构建完成，镜像清单："
docker images | grep -E "^${REGISTRY}/|^REPOSITORY" | head -10
