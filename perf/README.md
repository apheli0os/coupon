# 压测手册

本目录提供两套等价的压测方案：**Python 异步引擎**（无需额外安装，开箱即用）和 **JMeter 测试计划**（团队常用、图形化分析结果）。两者都验证同一个核心命题：**高并发下不超卖、不超领、不重复落单**。

## 一、压测分层（从下往上，逐层定位瓶颈）

| 层次 | 场景 | 目标 | 预期瓶颈 |
| --- | --- | --- | --- |
| L1 单接口 | `coupon` 领券 | 验证 Redis 原子计数 + Lua 防超领 | Redis 单核、网络往返 |
| L2 单接口 | `seckill` 秒杀 | 验证 Lua 原子预扣 + 分桶打散热点 | Lua 脚本执行、Redis 连接数 |
| L3 混合 | `mixed` 领券+秒杀 | 验证共享 Redis 下的资源竞争 | Redis 带宽、连接池 |
| L4 全链路 | `gateway` 经网关 | 验证路由 + JWT 鉴权 + 网关限流 + MQ 异步落单 | 网关 CPU、Netty 线程、MQ 堆积 |
| L5 持久化 | 压测后查 DB | 验证 MQ 消费幂等、无重复落单 | MySQL 写入、唯一索引冲突 |

## 二、快速开始（Python 引擎）

```bash
# 前置：服务已起（8081 券 / 8082 秒杀 / 8080 网关），MySQL+Redis 就绪

# 1. 准备数据（建券模板 + 秒杀活动，各预热到 Redis）
python3 perf/setup_data.py --coupon-base http://localhost:8081 --seckill-base http://localhost:8082

# 2. 领券单接口压测：100 并发，30 秒
python3 perf/load_test.py --scenario coupon --users 100 --duration 30 --ramp 5 --template-id <TID>

# 3. 秒杀单接口压测：300 并发，30 秒（重点看 p99 和 5xx）
python3 perf/load_test.py --scenario seckill --users 300 --duration 30 --ramp 15 --activity-id <AID>

# 4. 混合场景：200 并发
python3 perf/load_test.py --scenario mixed --users 200 --duration 30 --template-id <TID> --activity-id <AID>

# 5. 全链路（经网关）
python3 perf/load_test.py --scenario gateway --base-url http://localhost:8080 \
  --users 200 --duration 30 --activity-id <AID>

# 6. 正确性校验（最重要！）
python3 perf/verify.py --template-id <TID> --activity-id <AID> \
  --report perf/reports/seckill_xxx.json --wait-mq 15

# 或一键跑全流程：
bash perf/run_benchmark.sh
```

**压测机调优**：单机模拟 500 并发没问题；上千并发要调高 `ulimit -n`（Linux/Mac），Windows 上注意 ephemeral port 耗尽（默认约 16000 个，TIME_WAIT 会占满）。遇到 `网络错` 激增先看是不是端口耗尽，不是服务的问题。

## 三、JMeter 方案

打开 `perf/jmeter/seckill-test.jmx`，修改变量 `BASE_URL` / `ACTIVITY_ID` / `UID_BASE` 即可。

- 线程组默认 500 线程、30 秒爬坡、120 秒持续，可按机器性能下调
- `虚拟用户ID计数器` 保证每个线程唯一 uid，避免 2005 重复参与污染结果
- JSON 断言把「售罄/限流/重复」计为业务成功（这些是预期拒绝，不是系统故障）
- 聚合报告输出到 `perf/reports/jmeter-summary.csv`

命令行跑法：
```bash
jmeter -n -t perf/jmeter/seckill-test.jmx -l perf/reports/jmeter-result.jtl -e -o perf/reports/dashboard
```

## 四、关注指标与验收标准

| 指标 | 关注点 | 参考阈值（单机 Docker demo） |
| --- | --- | --- |
| 峰值 QPS | 系统吞吐上限 | ⚠️ 实测修正：领券**同步落库仅 ~100 QPS**（MySQL 单写瓶颈）；快速拒绝路径可达 3600+ QPS。秒杀若异步落库可达数千 QPS |
| p99 延迟 | 长尾请求 | ⚠️ 实测：真实领券 p99 ≈ 1000ms（同步写库）；快速拒绝 p99 < 30ms |
| 5xx / 超时 | 系统故障 | 必须为 0（业务拒绝码不算） |
| 限流(429) 比例 | Sentinel 是否生效 | 高压下应出现，证明限流生效 |
| 售罄(1004/2004) 比例 | 库存耗尽后的行为 | 应平稳返回，不报错 |
| DB 落单 vs 成功数 | MQ 最终一致性 | 允许 5% 内异步延迟偏差，15s 后应基本吻合 |
| 超卖/超领/重复 | **一票否决** | 必须为 0 |

## 五、报告产物

- `perf/reports/<场景>_<时间戳>.json` — 完整指标（QPS、延迟分位、业务码分布）
- `perf/reports/<场景>_<时间戳>_qps.csv` — 每秒 QPS 时间序列，可画图
- `perf/reports/dataset.json` — 本轮压测的模板/活动 ID 与库存
- `perf/reports/jmeter-summary.csv` — JMeter 汇总（若使用 JMeter）

## 六、本机实测结果（2026-11-18 ~ 11-19，真实跑通）

环境：Windows + Docker Desktop，MySQL 8（13307）/ Redis 7（16380）/ **Kafka 3.7.2（9092，KRaft 单副本）**，Spring Boot 3.2 服务本地 JVM，各 JVM -Xmx512m。

### 6.0 RocketMQ 故障真相（修正此前的错误结论）

此前文档把秒杀事务消息超时归因为「Windows Docker WSL2 对 RocketMQ Netty 网络不兼容」——**这是错的**。实际排查：

```
docker inspect coupon-bench-broker  →  OOMKilled=true, ExitCode=137
```

**根因是 Broker 进程被 OOM Kill**（同时跑 3 个 JVM + MySQL + Redis + Nameserver + Broker 超出本机内存），客户端拿不到 broker 路由，事务半消息自然超时。要么给 Broker 加内存，要么换更轻量的 MQ。本次选择**整体切换到 Kafka**，既解决内存问题，也顺带把领券/秒杀的同步 DB 写入全部异步化。

### 6.1 领券链路 ✅ 完整跑通（Kafka 异步落库后）

| 场景 | 并发 | 平均 QPS | p99 延迟 | 结果 |
| --- | --- | --- | --- | --- |
| 快速拒绝（限流阈值 200） | 100 | 3885 | 49ms | 429 限流，0 个 5xx |
| **真实领券**（fresh-user，限流放开到 5000） | 100 | **3244** | **58ms** | 成功 98585，0 个 5xx |

**对比改造前**：QPS **107 → 3244（约 30 倍）**，p99 **1013ms → 58ms（约 17 倍改善）**。

**改造要点**：`CouponServiceImpl.claim` 请求线程只做「缓存读模板 → Lua 原子预扣 → Kafka 同步发送」，DB 查重与 `insert`/`incrReceived` 全部下移到 `CouponClaimConsumer` 批量消费（按模板分组 `INSERT IGNORE` + 按实际新增行数累加 received，重复消费幂等）。MQ 发送失败立即回滚 Redis 预扣。

**正确性校验**（模板 2123273412964126720，库存 200000）：
- DB 落库 98635 = 模板 received 98635（完全一致，无丢失/无多计）
- Redis 剩余 101365 = 200000 − 98635（库存扣减精确，零超领）
- 报告 success 98585 vs DB 98635 的差异 = 客户端网络超时 54 笔（请求已落库但响应超时，**服务端正确**）

### 6.2 秒杀链路 ✅ 完整跑通（Kafka 替代事务消息）

| 场景 | 并发 | 平均 QPS | p99 延迟 | 结果 |
| --- | --- | --- | --- | --- |
| **真实秒杀**（fresh-user，库存 10000 全部售罄） | 300 | **3269** | **149ms** | 成功 10000，售罄 29635，限流 26809，0 个 5xx |

**改造要点**：
- 用「Redis Token 凭证 + Kafka at-least-once + 消费端唯一索引幂等 + 定时对账」替代 RocketMQ 事务消息，去掉对 Broker HALF 消息与事务回查的依赖。发送失败回滚库存并删 token。
- 15 分钟关单由 RocketMQ 延迟消息改为 `@Scheduled` 定时扫描（订单表新增 `stock_bucket` 字段记录扣减桶，关单按桶归还），避免延迟消息在 Broker 重启后丢失。
- 消费失败重试 5 次后转 `seckill-topic.DLQ`，由 DLQ 监听器告警。
- **消除请求线程同步 DB 写入**：`incrSold` 下移到独立的 Kafka 消费者（`seckill-sold-counter-group`），请求线程彻底不碰 DB。这一步使 p50 从 3.2s 降至 80ms（此前每个请求同步 `UPDATE seckill_activity`，单线程串行化）。

**正确性校验**（活动 2123275246529290240，库存 10000）：
- DB 订单 10000 = 10000 个不同 orderId = 10000 个不同用户（零重复落单）
- sold_count 最终追平 10000；Redis 16 桶剩余合计 0；已购用户 Set 基数 10000（零超卖）
- 消费端最终一致性存在秒级延迟（单/少量消费线程逐条落库），已给订单与销量消费者配置 `concurrency` 提升追平速度

### 6.3 构建过程发现并修复的真实 Bug

| 问题 | 影响 | 修复 |
| --- | --- | --- |
| `spring-boot-maven-plugin` 未绑定 `repackage` goal | 产出 37K 普通 jar，**无法 java -jar 启动** | 父 POM 绑定 repackage，产出 130M+ fat jar |
| Redisson 空密码触发 AUTH | 服务启动即失败 | 本地 Redis 配置密码并传 `REDIS_PASSWORD` |
| `KafkaTemplate.send().get()` 返回 `SendResult` 非 `RecordMetadata` | 编译失败 | 用 `result.getRecordMetadata()` |
| `SendResult` 泛型 / errorHandler 类型不匹配 | 订单服务启动失败 | 用 Boot 的 `ConcurrentKafkaListenerContainerFactoryConfigurer` + `<Object,Object>` 泛型挂载 DLQ ErrorHandler |
| Sentinel `seckillBlockHandler` 返回 `void` 与业务方法 `String` 签名不符 | 限流时 FlowException 逃逸，误报 500 而非 429 | blockHandler 返回类型改为 `String` |
| `enable.idempotence=true` 与 `acks=1` 冲突 | 秒杀全部 MQ 发送失败 | 去掉幂等 producer 配置，acks=1 + 攒批，可靠性由 token/唯一索引/DLQ 兜底 |
| `setup_data.py` publish 用 GET | 数据准备失败（405→500） | 改用 POST 空 body |
| `load_test.py` seckill 场景默认打 8081 | 秒杀压测全打到券服务 | 按场景自动选择默认端口（seckill=8082） |

## 七、已知限制（如实说明）

1. 压测为本机单机 Docker，QPS 受压测机网卡/文件描述符/JVM 堆限制；报告测的是「本机 X 并发下的表现」，不是服务绝对容量上限。
2. Kafka 为单副本 KRaft，未验证多副本 ISR 选举与分区重平衡下的行为；生产应配 ≥3 副本 + min.insync.replicas=2。
3. 分桶库存（16 桶）在单 Redis 实例上无法体现 Cluster 打散效果，仅验证分桶逻辑正确性；真实热点打散需 Redis Cluster 多主节点。
4. MQ 异步化带来秒级最终一致性延迟：用户领券/秒杀接口返回成功后，DB 落库由消费者异步追赶，存在短暂窗口；已通过 token + 唯一索引 + DLQ + 定时对账保证最终一致与不超卖。
5. 削峰依赖 Kafka 分区数（当前 8）与消费者并发；突发流量超过消费能力会产生 lag，需监控 consumer lag 并动态扩分区/消费者。
