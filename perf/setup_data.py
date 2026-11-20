#!/usr/bin/env python3
"""
压测数据准备：创建券模板 / 秒杀活动并发布预热。

用法：
  python3 perf/setup_data.py --base-url http://localhost:8081 --coupon-base http://localhost:8081 \
      --seckill-base http://localhost:8082 --coupon-stock 20000 --seckill-stock 5000

输出 perf/reports/dataset.json，供 load_test.py 与 verify.py 自动读取。
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path

DEFAULT_ADMIN_USER = "9900000"  # 管理接口也走同一套鉴权兜底


def call(base: str, path: str, body: dict | None = None) -> dict:
    url = base.rstrip("/") + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(
        url, data=data, method="POST" if data else "GET",
        headers={"Content-Type": "application/json", "X-User-Id": DEFAULT_ADMIN_USER},
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        result = json.loads(resp.read().decode("utf-8"))
    if result.get("code") != 0:
        raise RuntimeError(f"调用失败 {url}: {result}")
    return result


def fmt(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%d %H:%M:%S")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--base-url", default="http://localhost:8080", help="网关地址（仅探活用）")
    p.add_argument("--coupon-base", default="http://localhost:8081")
    p.add_argument("--seckill-base", default="http://localhost:8082")
    p.add_argument("--coupon-stock", type=int, default=20000)
    p.add_argument("--seckill-stock", type=int, default=5000)
    p.add_argument("--per-user-limit", type=int, default=1)
    p.add_argument("--output", default="perf/reports/dataset.json")
    a = p.parse_args()

    now = datetime.now()
    start, end = fmt(now - timedelta(minutes=10)), fmt(now + timedelta(days=1))
    stamp = now.strftime("%m%d%H%M%S")

    print("创建券模板 ...")
    tid = call(a.coupon_base, "/api/coupon/templates", {
        "name": f"压测满减券-{stamp}", "type": 1, "discountValue": 10,
        "threshold": 100, "total": a.coupon_stock,
        "perUserLimit": a.per_user_limit, "validDays": 30,
        "startTime": start, "endTime": end,
    })["data"]
    call(a.coupon_base, f"/api/coupon/templates/{tid}/publish", {})
    print(f"  模板 ID={tid} 库存={a.coupon_stock} 已发布")

    print("创建秒杀活动 ...")
    aid = call(a.seckill_base, "/api/seckill/activities", {
        "name": f"压测秒杀活动-{stamp}", "productCode": f"BENCH-{stamp}",
        "productName": "压测商品", "seckillPrice": 1,
        "originalPrice": 100, "totalStock": a.seckill_stock,
        "startTime": start, "endTime": end,
    })["data"]
    call(a.seckill_base, f"/api/seckill/activities/{aid}/publish", {})
    print(f"  活动 ID={aid} 库存={a.seckill_stock} 已发布")

    out = Path(a.output).resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    dataset = {
        "templateId": tid, "activityId": aid,
        "couponStock": a.coupon_stock, "seckillStock": a.seckill_stock,
        "createdAt": now.isoformat(timespec="seconds"),
    }
    out.write_text(json.dumps(dataset, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"数据集已保存: {out}")
    print(json.dumps(dataset, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"准备失败: {e}", file=sys.stderr)
        sys.exit(1)
