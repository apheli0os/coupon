#!/usr/bin/env bash
# 生产 K8s 一键部署脚本（本地 8GB 环境无集群，此脚本留给真实多机集群）
set -euo pipefail

NAMESPACE=mall
REGISTRY="${REGISTRY:-registry.example.com/mall}"
TAG="${TAG:-1.0.0}"

echo "==> 1/4 创建命名空间与配置"
kubectl apply -f "$(dirname "$0")/namespace-configmap.yaml"

echo "==> 2/4 替换镜像地址为实际仓库 ${REGISTRY}:${TAG}"
for f in deployments.yaml gateway.yaml; do
  sed -i.bak "s#registry.example.com/mall#${REGISTRY}#g; s#:1\.0\.0#:${TAG}#g" "$(dirname "$0")/$f"
  rm -f "$(dirname "$0")/$f.bak"
done

echo "==> 3/4 部署工作负载、Service、HPA、Ingress"
kubectl apply -f "$(dirname "$0")/deployments.yaml"
kubectl apply -f "$(dirname "$0")/gateway.yaml"
kubectl apply -f "$(dirname "$0")/services-hpa-ingress.yaml"

echo "==> 4/4 部署 HA 增强（PDB / NetworkPolicy / 反亲和）"
kubectl apply -f "$(dirname "$0")/ha-production-addon.yaml"

echo "==> 等待滚动更新完成"
kubectl -n "$NAMESPACE" rollout status deploy/coupon-service --timeout=180s
kubectl -n "$NAMESPACE" rollout status deploy/seckill-service --timeout=180s
kubectl -n "$NAMESPACE" rollout status deploy/order-service --timeout=180s
kubectl -n "$NAMESPACE" rollout status deploy/mall-gateway --timeout=180s

echo "==> 部署完成，当前 Pod："
kubectl -n "$NAMESPACE" get pods -o wide
