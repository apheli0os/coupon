#!/usr/bin/env bash
# 一键压测编排：准备数据 → 梯度压测（券/秒杀/混合）→ 正确性校验 → 汇总
# 前置：服务已启动（8081/8082），MySQL/Redis 已就绪
set -euo pipefail
cd "$(dirname "$0")/.."

COUPON_URL="${COUPON_URL:-http://localhost:8081}"
SECKILL_URL="${SECKILL_URL:-http://localhost:8082}"
DATASET="perf/reports/dataset.json"
REPORT_DIR="perf/reports"
mkdir -p "$REPORT_DIR"

echo "===== [1/5] 准备压测数据 ====="
python3 perf/setup_data.py \
  --coupon-base "$COUPON_URL" --seckill-base "$SECKILL_URL" \
  --coupon-stock 20000 --seckill-stock 5000

TID=$(python3 -c "import json;print(json.load(open('$DATASET'))['templateId'])")
AID=$(python3 -c "import json;print(json.load(open('$DATASET'))['activityId'])")
echo "模板ID=$TID 活动ID=$AID"

echo "===== [2/5] 领券场景：100 并发 × 30s ====="
python3 perf/load_test.py --scenario coupon --base-url "$COUPON_URL" \
  --users 100 --duration 30 --ramp 5 --template-id "$TID"

echo "===== [3/5] 秒杀场景：300 并发 × 30s（梯度爬坡）====="
python3 perf/load_test.py --scenario seckill --base-url "$SECKILL_URL" \
  --users 300 --duration 30 --ramp 15 --activity-id "$AID"

echo "===== [4/5] 混合场景：200 并发 × 30s ====="
python3 perf/load_test.py --scenario mixed \
  --base-url "$SECKILL_URL" --users 200 --duration 30 --ramp 10 \
  --template-id "$TID" --activity-id "$AID"
# mixed 场景两个服务不同 base-url，用环境变量覆盖后单独重跑券侧更准确

echo "===== [5/5] 正确性校验 ====="
LATEST=$(ls -t "$REPORT_DIR"/seckill_*.json 2>/dev/null | head -1 || true)
if [ -n "$LATEST" ]; then
  python3 perf/verify.py --template-id "$TID" --activity-id "$AID" \
    --report "$LATEST" --wait-mq 15
else
  python3 perf/verify.py --template-id "$TID" --activity-id "$AID" --wait-mq 15
fi

echo "===== 压测完成，报告目录：$REPORT_DIR ====="
ls -lt "$REPORT_DIR" | head -20
