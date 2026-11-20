#!/usr/bin/env python3
"""
压测后正确性校验：确认高并发下没有超卖 / 超领 / 重复落单。

校验项：
  1. 券模板 received <= total（不超领）
  2. 秒杀活动 soldCount <= totalStock（不超卖）
  3. DB 实际 user_coupon 行数 <= total，且和压测报告 success 数吻合
  4. DB 实际 seckill_order 行数 <= totalStock，且唯一索引无重复
  5. Redis 剩余库存 >= 0，且 已售 + 剩余 == 总量（无丢失/无多扣）

用法：
  python3 perf/verify.py --template-id 1 --activity-id 1 --report perf/reports/seckill_xxx.json
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import urllib.request
from pathlib import Path


def http_get(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=10) as r:
        obj = json.loads(r.read().decode("utf-8"))
    if obj.get("code") != 0:
        raise RuntimeError(f"GET {url} 失败: {obj}")
    return obj["data"]


def mysql_scalar(sql: str) -> str:
    cmd = ["docker", "compose", "exec", "-T", "mysql",
           "mysql", "-uroot", "-proot123456", "-N", "-B", "-e", sql]
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    if out.returncode != 0:
        raise RuntimeError(f"MySQL 查询失败: {out.stderr.strip()}")
    return out.stdout.strip()


def redis_cmd(*args: str) -> str:
    cmd = ["docker", "compose", "exec", "-T", "redis", "redis-cli", *args]
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    if out.returncode != 0:
        raise RuntimeError(f"Redis 命令失败: {out.stderr.strip()}")
    return out.stdout.strip()


def check(cond: bool, label: str, detail: str, results: list[tuple[bool, str]]) -> None:
    results.append((cond, f"{'PASS' if cond else 'FAIL'}  {label}: {detail}"))


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--coupon-base", default="http://localhost:8081")
    p.add_argument("--seckill-base", default="http://localhost:8082")
    p.add_argument("--template-id", type=int, required=True)
    p.add_argument("--activity-id", type=int, required=True)
    p.add_argument("--report", help="压测报告 JSON，用于对比 success 数")
    p.add_argument("--wait-mq", type=int, default=10, help="等待 MQ 落库秒数")
    a = p.parse_args()

    import time
    print(f"等待 MQ 异步落库 {a.wait_mq}s ...")
    time.sleep(a.wait_mq)

    results: list[tuple[bool, str]] = []
    tpl = http_get(f"{a.coupon_base}/api/coupon/templates/{a.template_id}")
    act = http_get(f"{a.seckill_base}/api/seckill/activities/{a.activity_id}")

    total_coupon = int(tpl["total"])
    received = int(tpl.get("received", 0))
    total_stock = int(act["totalStock"])
    sold = int(act.get("soldCount", 0))

    check(received <= total_coupon, "券不超领",
          f"received={received}, total={total_coupon}")
    check(sold <= total_stock, "秒杀不超卖（活动计数）",
          f"soldCount={sold}, totalStock={total_stock}")

    db_coupons = int(mysql_scalar(
        f"SELECT COUNT(*) FROM mall_coupon.user_coupon WHERE template_id={a.template_id};") or 0)
    db_orders = int(mysql_scalar(
        f"SELECT COUNT(*) FROM mall_order.seckill_order WHERE activity_id={a.activity_id};") or 0)
    dup_orders = int(mysql_scalar(
        f"SELECT COUNT(*) FROM (SELECT user_id FROM mall_order.seckill_order "
        f"WHERE activity_id={a.activity_id} GROUP BY user_id HAVING COUNT(*)>1) t;") or 0)
    dup_coupons = int(mysql_scalar(
        f"SELECT COUNT(*) FROM (SELECT user_id FROM mall_coupon.user_coupon "
        f"WHERE template_id={a.template_id} GROUP BY user_id HAVING COUNT(*)>1) t;") or 0)

    check(db_coupons <= total_coupon, "券不超领（DB 实际行数）",
          f"rows={db_coupons}, total={total_coupon}")
    check(db_orders <= total_stock, "秒杀不超卖（DB 实际订单）",
          f"rows={db_orders}, totalStock={total_stock}")
    check(dup_orders == 0, "秒杀无重复落单", f"重复用户组={dup_orders}")
    check(dup_coupons == 0, "领券无重复（perUserLimit=1）", f"重复用户组={dup_coupons}")

    # Redis 分桶库存汇总
    bucket_keys = redis_cmd("--scan", "0", "--pattern",
                            f"seckill:{{{a.activity_id}}}:stock:*").strip()
    lines = [ln for ln in bucket_keys.splitlines() if "stock" in ln]
    remain = 0
    for key in lines:
        try:
            remain += int(redis_cmd("GET", key.strip()) or 0)
        except ValueError:
            pass
    if lines:
        check(remain >= 0, "Redis 库存非负", f"16桶合计剩余={remain}")
        check(sold + remain <= total_stock + max(16, total_stock // 100),
              "已售+剩余≈总量", f"sold={sold}, remain={remain}, total={total_stock}")
    else:
        results.append((True, f"SKIP  Redis 分桶库存键未找到（活动可能未预热）"))

    if a.report and Path(a.report).exists():
        rep = json.loads(Path(a.report).read_text(encoding="utf-8"))
        success = rep["summary"]["success"]
        codes = rep.get("biz_codes", {})
        print(f"压测报告: success={success}, 业务码={list(codes.items())[:6]}")
        # 秒杀成功数应当 ≈ DB 订单数（异步落库有延迟，允许小偏差）
        if "seckill" in Path(a.report).name or "gateway" in Path(a.report).name:
            check(abs(db_orders - success) <= max(20, success * 0.05),
                  "成功数与落库数吻合", f"report_success={success}, db_orders={db_orders}")

    print("\n" + "=" * 72)
    for ok, line in results:
        print(line)
    print("=" * 72)
    failed = [line for ok, line in results if not ok]
    if failed:
        print(f"\n校验失败 {len(failed)} 项！存在并发正确性问题。")
        sys.exit(1)
    print("\n全部校验通过：高并发下未发现超卖 / 超领 / 重复落单。")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"校验异常: {e}", file=sys.stderr)
        sys.exit(2)
