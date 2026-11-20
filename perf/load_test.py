#!/usr/bin/env python3
"""
优惠券 / 秒杀系统全链路压测引擎

基于 asyncio + aiohttp，单机即可模拟数千并发长连接，指标与 JMeter 等价。

用法示例：
  python3 perf/load_test.py --scenario coupon  --users 300 --duration 60 --template-id 1
  python3 perf/load_test.py --scenario seckill --users 500 --duration 60 --activity-id 1
  python3 perf/load_test.py --scenario mixed   --users 300 --duration 60 --template-id 1 --activity-id 1
  python3 perf/load_test.py --scenario gateway --users 300 --duration 60 --activity-id 1 --base-url http://localhost:8080

场景说明：
  coupon   领券：POST /api/coupon/claim/{templateId}
  seckill  秒杀：POST /api/seckill/{activityId}/buy
  mixed    混合：50% 领券 + 50% 秒杀
  gateway  经网关秒杀（验证路由 + 鉴权 + 网关限流链路）

业务码约定见 ResultCode：0=成功，1004=券抢光，2004=已抢光，2005=重复参与，
429=限流，2006=系统繁忙。压测把这些当"预期拒绝"单独统计，不算 HTTP 故障。
"""
from __future__ import annotations

import argparse
import asyncio
import aiohttp
import csv
import json
import math
import os
import random
import signal
import statistics
import sys
import time
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

# 业务码 -> 语义（用于报告）
CODE_LABELS = {
    0: "SUCCESS",
    400: "PARAM_ERROR", 401: "UNAUTHORIZED", 403: "FORBIDDEN",
    404: "NOT_FOUND", 429: "RATE_LIMITED", 500: "SYSTEM_ERROR",
    1001: "TEMPLATE_NOT_FOUND", 1002: "COUPON_NOT_STARTED",
    1003: "COUPON_ENDED", 1004: "COUPON_SOLD_OUT",
    1005: "COUPON_LIMIT_EXCEEDED", 2001: "ACTIVITY_NOT_FOUND",
    2002: "SECKILL_NOT_STARTED", 2003: "SECKILL_ENDED",
    2004: "SECKILL_SOLD_OUT", 2005: "SECKILL_DUPLICATE",
    2006: "SECKILL_SYSTEM_BUSY", 3002: "ORDER_CREATE_FAILED",
    9001: "LOCK_ACQUIRE_FAILED", 9002: "MQ_SEND_FAILED",
}
# 这些属于"业务预期拒绝"，不是系统故障
EXPECTED_REJECTS = {1004, 1005, 2004, 2005, 2002, 2003, 1002, 1003, 429}


@dataclass
class Config:
    scenario: str
    base_url: str
    users: int
    duration: float
    ramp: float
    template_id: int | None
    activity_id: int | None
    uid_base: int
    timeout: float
    think_ms: float
    keepalive: bool
    output_dir: Path
    fresh_user: bool = False  # 每请求换新用户，绕开"每人限领"约束以测真实吞吐


@dataclass
class Collector:
    """线程安全的指标收集器（事件循环单线程，无需加锁）。"""
    total: int = 0
    success: int = 0
    expected_reject: int = 0
    http_5xx: int = 0
    network_error: int = 0
    timeout: int = 0
    biz_codes: Counter = field(default_factory=Counter)
    http_codes: Counter = field(default_factory=Counter)
    latencies: list[float] = field(default_factory=list)
    qps_per_second: Counter = field(default_factory=Counter)
    error_samples: list[str] = field(default_factory=list)

    def record(self, http_status: int, code: int, latency_ms: float, error: str = "", kind: str = "") -> None:
        self.total += 1
        self.latencies.append(latency_ms)
        self.http_codes[http_status] += 1
        self.biz_codes[code] += 1
        sec = int(time.monotonic())
        self.qps_per_second[sec] += 1
        if kind == "timeout":
            self.timeout += 1
        elif kind == "network":
            self.network_error += 1
        elif http_status >= 500:
            self.http_5xx += 1
        elif code == 0:
            self.success += 1
        elif code in EXPECTED_REJECTS:
            self.expected_reject += 1
        if error and len(self.error_samples) < 20:
            self.error_samples.append(error[:300])


def parse_biz_code(text: str) -> int:
    """从 JSON 响应提取业务 code；解析失败返回 -1。"""
    try:
        obj = json.loads(text)
        if isinstance(obj, dict):
            return int(obj.get("code", -1))
    except (ValueError, TypeError):
        pass
    return -1


def build_urls(cfg: Config) -> list[str]:
    base = cfg.base_url.rstrip("/")
    if cfg.scenario == "coupon":
        assert cfg.template_id, "coupon 场景需要 --template-id"
        return [f"{base}/api/coupon/claim/{cfg.template_id}"]
    if cfg.scenario == "seckill":
        assert cfg.activity_id, "seckill 场景需要 --activity-id"
        return [f"{base}/api/seckill/{cfg.activity_id}/buy"]
    if cfg.scenario == "mixed":
        assert cfg.template_id and cfg.activity_id, "mixed 场景需要 --template-id 和 --activity-id"
        return [
            f"{cfg.base_url.rstrip('/')}/api/coupon/claim/{cfg.template_id}",
            f"{cfg.base_url.rstrip('/')}/api/seckill/{cfg.activity_id}/buy",
        ]
    if cfg.scenario == "gateway":
        assert cfg.activity_id, "gateway 场景需要 --activity-id"
        return [f"{base}/api/seckill/{cfg.activity_id}/buy"]
    raise ValueError(f"未知场景: {cfg.scenario}")


async def worker(cfg: Config, urls: list[str], collector: Collector,
                 wid: int, start_at: float, deadline: float, stop: asyncio.Event,
                 connector: aiohttp.TCPConnector, limiter: asyncio.Semaphore | None) -> None:
    """单个虚拟用户：在 ramp 期内错峰启动，然后持续压到 deadline。"""
    delay = start_at + (wid / max(1, cfg.users - 1)) * cfg.ramp - time.monotonic()
    if delay > 0:
        try:
            await asyncio.wait_for(stop.wait(), timeout=delay)
            return
        except asyncio.TimeoutError:
            pass

    uid = cfg.uid_base + wid
    headers = {"X-User-Id": str(uid), "User-Agent": f"loadgen-{cfg.scenario}"}
    timeout = aiohttp.ClientTimeout(total=cfg.timeout, connect=cfg.timeout)
    session = aiohttp.ClientSession(connector=connector, timeout=timeout,
                                    headers=headers, raise_for_status=False)

    idx = 0
    # fresh_user 模式：每个 worker 用独立的号段，保证并发请求之间 uid 不重复
    fresh_counter = [cfg.uid_base + wid * 10_000_000]
    try:
        while not stop.is_set() and time.monotonic() < deadline:
            url = urls[idx % len(urls)]
            idx += 1
            if cfg.fresh_user:
                fresh_counter[0] += 1
                session.headers["X-User-Id"] = str(fresh_counter[0])
            t0 = time.perf_counter()
            try:
                if limiter:
                    await limiter.acquire()
                async with session.post(url) as resp:
                    text = await resp.text()
                    cost = (time.perf_counter() - t0) * 1000
                    collector.record(resp.status, parse_biz_code(text), cost)
            except asyncio.TimeoutError:
                collector.record(0, -1, (time.perf_counter() - t0) * 1000,
                                 f"timeout uid={uid} url={url}", kind="timeout")
            except aiohttp.ClientError as e:
                collector.record(0, -1, (time.perf_counter() - t0) * 1000,
                                 f"{type(e).__name__}: {e} uid={uid}", kind="network")
            except Exception as e:  # 不让单个 worker 崩掉整场压测
                collector.record(0, -1, (time.perf_counter() - t0) * 1000,
                                 f"UNEXPECTED {type(e).__name__}: {e}", kind="network")
            finally:
                if limiter:
                    limiter.release()
            if cfg.think_ms > 0:
                await asyncio.sleep(cfg.think_ms / 1000.0)
    finally:
        await session.close()


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    k = (len(s) - 1) * p
    lo, hi = math.floor(k), math.ceil(k)
    if lo == hi:
        return s[lo]
    return s[lo] * (hi - k) + s[hi] * (k - lo)


def make_report(cfg: Config, c: Collector, elapsed: float) -> dict:
    qps_values = list(c.qps_per_second.values())
    active_seconds = max(1, len(qps_values)) if qps_values else max(1, int(elapsed))
    http_ok = sum(v for k, v in c.http_codes.items() if 200 <= k < 300)
    return {
        "meta": {
            "scenario": cfg.scenario,
            "target": cfg.base_url,
            "users": cfg.users,
            "duration_sec": round(elapsed, 2),
            "ramp_sec": cfg.ramp,
            "timestamp": datetime.now().isoformat(timespec="seconds"),
        },
        "summary": {
            "total_requests": c.total,
            "avg_qps": round(c.total / elapsed, 1),
            "peak_qps": max(qps_values) if qps_values else 0,
            "success": c.success,
            "success_rate_pct": round(c.success / c.total * 100, 2) if c.total else 0,
            "expected_rejects": c.expected_reject,
            "http_5xx": c.http_5xx,
            "timeouts": c.timeout,
            "network_errors": c.network_error,
            "http_2xx": http_ok,
        },
        "latency_ms": {
            "min": round(min(c.latencies), 2) if c.latencies else 0,
            "avg": round(statistics.fmean(c.latencies), 2) if c.latencies else 0,
            "p50": round(percentile(c.latencies, 0.50), 2),
            "p90": round(percentile(c.latencies, 0.90), 2),
            "p95": round(percentile(c.latencies, 0.95), 2),
            "p99": round(percentile(c.latencies, 0.99), 2),
            "max": round(max(c.latencies), 2) if c.latencies else 0,
        },
        "biz_codes": {f"{k}:{CODE_LABELS.get(k, 'UNKNOWN')}": v
                      for k, v in sorted(c.biz_codes.items(), key=lambda x: -x[1])},
        "http_codes": {str(k): v for k, v in sorted(c.http_codes.items())},
        "qps_timeline": dict(sorted(c.qps_per_second.items())),
        "error_samples": c.error_samples,
    }


def print_live(cfg: Config, c: Collector, elapsed: float) -> None:
    qps = list(c.qps_per_second.values())
    cur = qps[-1] if qps else 0
    avg = sum(qps) / len(qps) if qps else 0
    sys.stdout.write(
        f"\r[{elapsed:6.1f}s] total={c.total:7d}  QPS(cur/avg)={cur:5d}/{avg:6.1f}  "
        f"OK={c.success:6d}  reject={c.expected_reject:6d}  "
        f"5xx={c.http_5xx:4d}  err={c.network_error + c.timeout:4d}"
    )
    sys.stdout.flush()


def save_report(cfg: Config, report: dict, c: Collector) -> tuple[Path, Path]:
    cfg.output_dir.mkdir(parents=True, exist_ok=True)
    tag = f"{cfg.scenario}_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
    jpath = cfg.output_dir / f"{tag}.json"
    cpath = cfg.output_dir / f"{tag}_qps.csv"
    jpath.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    with cpath.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["second_epoch", "qps"])
        for sec, n in sorted(c.qps_per_second.items()):
            w.writerow([sec, n])
    return jpath, cpath


async def run(cfg: Config) -> dict:
    urls = build_urls(cfg)
    connector = aiohttp.TCPConnector(
        limit=cfg.users + 50,
        limit_per_host=cfg.users + 50,
        keepalive_timeout=30,
        force_close=not cfg.keepalive,
        ttl_dns_cache=300,
    )
    collector = Collector()
    stop = asyncio.Event()
    limiter = None  # 需要全局 QPS 上限时可在 CLI 暴露

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop.set)
        except NotImplementedError:
            pass  # Windows 不支持部分信号

    start_at = time.monotonic() + 0.3
    deadline = start_at + cfg.duration
    print(f"场景={cfg.scenario}  目标={cfg.base_url}  并发={cfg.users}  "
          f"爬坡={cfg.ramp}s  持续={cfg.duration}s")
    print(f"URL: {urls}")
    print("开始压测（Ctrl+C 可提前结束并出报告）...")

    tasks = [asyncio.create_task(worker(cfg, urls, collector, i, start_at,
                                        deadline, stop, connector, limiter))
             for i in range(cfg.users)]
    t0 = time.monotonic()
    last_print = 0.0
    while not stop.is_set():
        now = time.monotonic()
        if now >= deadline:
            break
        if now - last_print >= 2.0:
            print_live(cfg, collector, now - t0)
            last_print = now
        await asyncio.sleep(0.2)
    stop.set()
    await asyncio.gather(*tasks, return_exceptions=True)
    elapsed = min(time.monotonic() - t0, cfg.duration + cfg.ramp + 2)
    print_live(cfg, collector, elapsed)
    print()
    await connector.close()

    report = make_report(cfg, collector, elapsed)
    jpath, cpath = save_report(cfg, report, collector)
    s = report["summary"]
    lat = report["latency_ms"]
    print("=" * 72)
    print(f"总请求 {s['total_requests']}  平均QPS {s['avg_qps']}  峰值QPS {s['peak_qps']}")
    print(f"成功 {s['success']} ({s['success_rate_pct']}%)  预期拒绝 {s['expected_rejects']}  "
          f"5xx {s['http_5xx']}  超时 {s['timeouts']}  网络错 {s['network_errors']}")
    print(f"延迟(ms) avg={lat['avg']} p50={lat['p50']} p95={lat['p95']} p99={lat['p99']} max={lat['max']}")
    print("业务码分布:")
    for code, n in list(report["biz_codes"].items())[:8]:
        print(f"    {code:28s} {n:7d}")
    print(f"报告: {jpath}")
    print(f"QPS : {cpath}")
    print("=" * 72)
    return report


def main() -> None:
    p = argparse.ArgumentParser(description="优惠券/秒杀全链路压测引擎")
    p.add_argument("--scenario", choices=["coupon", "seckill", "mixed", "gateway"], required=True)
    # 各场景默认打到对应服务：coupon=8081 / seckill=8082 / gateway=8080
    # 显式传 --base-url 或设置 BASE_URL 环境变量可覆盖
    _default_base = os.getenv("BASE_URL")
    if not _default_base:
        _default_base = {"seckill": "http://localhost:8082",
                         "gateway": "http://localhost:8080"}.get(
            next((x for x in sys.argv if x in ("coupon", "seckill", "mixed", "gateway")), ""),
            "http://localhost:8081")
    p.add_argument("--base-url", default=_default_base)
    p.add_argument("--users", type=int, default=100)
    p.add_argument("--duration", type=float, default=30.0, help="稳定压测秒数")
    p.add_argument("--ramp", type=float, default=5.0, help="爬坡秒数")
    p.add_argument("--template-id", type=int)
    p.add_argument("--activity-id", type=int)
    p.add_argument("--uid-base", type=int, default=2000000, help="虚拟用户 ID 起始值")
    p.add_argument("--timeout", type=float, default=5.0)
    p.add_argument("--think-ms", type=float, default=0.0, help="每请求后思考时间")
    p.add_argument("--no-keepalive", action="store_true")
    p.add_argument("--fresh-user", action="store_true", help="每请求换新用户ID，测真实吞吐（绕开限领约束）")
    p.add_argument("--output-dir", default="perf/reports")
    a = p.parse_args()

    cfg = Config(
        scenario=a.scenario, base_url=a.base_url, users=a.users,
        duration=a.duration, ramp=a.ramp, template_id=a.template_id,
        activity_id=a.activity_id, uid_base=a.uid_base, timeout=a.timeout,
        think_ms=a.think_ms, keepalive=not a.no_keepalive,
        output_dir=Path(a.output_dir).resolve(), fresh_user=a.fresh_user,
    )
    try:
        asyncio.run(run(cfg))
    except (ValueError, AssertionError) as e:
        print(f"参数错误: {e}", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
