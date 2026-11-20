# 高可用部署与故障转移验证

## 两套环境的关系

| 环境 | 编排文件 | 用途 | 是否需要 K8s |
| --- | --- | --- | --- |
| 本地 demo | `docker-compose.yml` | 单节点中间件，跑功能与压测 | 否 |
| 本地 HA 验证 | `docker-compose-ha.yml` | 主从 + 哨兵 + 3 broker，验证故障转移 | 否 |
| 生产多机 | `k8s/*.yaml` + `k8s/deploy.sh` | 多机编排、自愈、弹性、滚动发布 | 是 |

**Docker 是容器运行时，Compose 和 K8s 都是它的编排层。** 本项目本地验证用 Compose 即可，K8s 清单是给真实多机集群准备的可选路径，两者不冲突。

## 一、资源预算（WSL2 memory=8GB）

| 组件 | 实例 | 单实例上限 | 小计 |
| --- | --- | --- | --- |
| MySQL 主 + 从 | 2 | 768m | 1.5 GB |
| Redis 主 + 从 | 2 | 320m | 0.64 GB |
| Redis 哨兵 | 3 | 96m | 0.29 GB |
| Kafka broker | 3 | 700m | 2.1 GB |
| 业务 JVM（3 个） | 3 | -Xmx384m | ~1.5 GB |
| **合计** | | | **~6 GB**，留 2GB 余量 |

## 二、启动 HA 栈

```bash
cd D:/javaRepo/coupon
docker compose -f docker-compose-ha.yml up -d
docker compose -f docker-compose-ha.yml ps    # 确认 11 个容器健康
```

端口（与 demo 完全隔离）：

| 组件 | 宿主机端口 |
| --- | --- |
| MySQL 主 / 从 | 13308 / 13309 |
| Redis 主 / 从 | 16381 / 16382 |
| Redis 哨兵 | 26381 / 26382 / 26383 |
| Kafka broker | 19092 / 19093 / 19094 |

## 三、启动业务服务（HA profile）

```bash
# 三个服务分别执行，激活 ha profile
java -jar mall-order-service/target/mall-order-service-1.0.0-SNAPSHOT.jar --spring.profiles.active=ha
java -jar mall-coupon-service/target/mall-coupon-service-1.0.0-SNAPSHOT.jar --spring.profiles.active=ha
java -jar mall-seckill-service/target/mall-seckill-service-1.0.0-SNAPSHOT.jar --spring.profiles.active=ha
```

`application-ha.yml` 做了三件事：
1. Redis 改用 `spring.data.redis.sentinel`，Redisson 3.34 自动识别（已反编译确认读 `getSentinel().getMaster/getNodes`），分布式锁与缓存同时切换
2. MySQL 指向主库 13308；读分离留给 `-Psharding` 组合，写后读保持强一致
3. Kafka 指向 3 broker，`acks=all` + `enable.idempotence=true` + 服务端 `min.insync.replicas=2`

## 四、故障转移验证步骤

### 4.1 MySQL 主从复制

```bash
docker exec mall-ha-mysql-slave mysql -uroot -proot123456 -e "SHOW REPLICA STATUS\G" | grep -E "Replica_IO_Running|Replica_SQL_Running"
# 两行均为 Yes 即复制正常
```

### 4.2 Redis 主库宕机自动切主

```bash
# 查看当前主节点
docker exec mall-ha-sentinel-1 redis-cli -p 26379 sentinel get-master-addr-by-name mallredis

# 停掉主库
docker stop mall-ha-redis-master

# 等待 5~10 秒后再次查询，应返回从节点地址
docker exec mall-ha-sentinel-1 redis-cli -p 26379 sentinel get-master-addr-by-name mallredis

# 恢复
docker start mall-ha-redis-master
```

预期：哨兵在 `down-after-milliseconds=5000` 后判定主观下线，3 哨兵达成多数派后提升从库。**应用侧无需改配置**，Lettuce 与 Redisson 会自动重连新主库。

### 4.3 Kafka broker 宕机分区仍可用

```bash
# 查看 topic 副本分布（每个分区应 3 副本，ISR 3 个）
docker exec mall-ha-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic seckill-topic

# 停掉一个 broker
docker stop mall-ha-kafka-2

# 生产消费仍应正常（acks=all 下 ISR 还有 2 个，满足 min.insync.replicas=2）
```

预期：Leader 自动切换到存活 broker，`acks=all` 生产不受影响。停掉两个 broker 后 `min.insync.replicas=2` 不满足，生产会失败——这是**用可用性换一致性**的正确行为。

## 五、回到 demo 环境

```bash
docker compose -f docker-compose-ha.yml down
docker compose -f docker-compose.yml up -d
```

## 五点五、HA profile 端到端实测（2026-11-19，真实跑通）

**业务服务容器化进入 HA 网络**（关键）：宿主机 JVM 连不上哨兵返回的主节点容器 IP，因此把业务服务也用 `Dockerfile.local` 打成轻量镜像，`docker run --network coupon_default` 进入同一网络。容器名配置：
- `REDIS_SENTINELS=sentinel-1:26379,sentinel-2:26379,sentinel-3:26379`
- `DB_URL=jdbc:mysql://mysql-master:3306/...`
- `KAFKA_BOOTSTRAP=kafka-1:9092,kafka-2:9092,kafka-3:9092`

**验证结果**：
| 项目 | 结果 |
| --- | --- |
| 三服务启动 | 全部 Started，health 200；Redisson 均通过哨兵发现 master + slave |
| 领券压测（HA profile） | 50 并发 15s，成功 5000（库存全发完），0 个 5xx，p99 158ms，平均 QPS 1560 / 峰值 3214 |
| 秒杀压测（HA profile） | 150 并发 15s，成功 3000（库存全售罄），0 个 5xx，p99 1.2s，平均 QPS 380 |
| 数据对账 | 领券：主库 5000 = received 5000 = 从库 5000，Redis 归零；秒杀：订单 3000 = 3000 个不同用户，从库 3000 |
| **HA 性能代价（已受控排查修正）** | 最初测到 380 QPS 并误归因于 `acks=all`，实为**内存 swap  thrashing**：WSL2 只剩 1.7GB 可用、swap 用掉一半。三次对照实验证明：内存释放后 `acks=all` 就有 2958 QPS；预热后 `acks=1` 为 4094 QPS。**acks=all 真实代价约 28%**（与业界 15-35% 吻合），而非 8.6 倍。详见下方"性能排查"章节 |
| **运行中切主** | 停 Redis 主库后 15s 内哨兵完成切换（172.23.0.13 → 172.23.0.2），三服务 health 全程 200，业务接口正常返回业务码——应用无感知自动重连 |

**本机资源注意**：WSL2 需 `memory=8GB`（原 6GB 装不下 13+ 容器，触发 swap thrashing 把磁盘打满、daemon 假死）。配置见 `C:\Users\.wslconfig`：`memory=8GB` + `swap=1GB` + `autoMemoryReclaim=gradual`。HA 全套 + 3 业务服务实测稳定在 7.1GB。压测前停掉非必需的 demo 容器可再省 ~700MB。

## 五点六、QPS 下降根因排查（2026-11-19 受控实验）

初测 HA 秒杀 380 QPS / p99 1.2s，我最初归咎于 `acks=all`——**这是错的**。做了三组对照实验（同环境、同 300 并发、同 5000 库存，每次新活动 + fresh-user）：

| 实验 | 配置 | 平均 QPS | 峰值 | p50 | p99 | 网络错 |
| --- | --- | --- | --- | --- | --- | --- |
| 初测 | acks=all，内存 1.7GB 可用 + swap 用半 | 380 | 900 | 189ms | 1200ms | 142 |
| A | acks=all，停掉非必需服务释放内存 | 2958 | 5035 | 58ms | 377ms | 146 |
| B | acks=1，容器刚重启（未预热） | 1239 | 2939 | 126ms | 503ms | 292 |
| C | acks=1，预热 50 请求后 | 4094 | 6252 | 48ms | 302ms | 144 |

**根因排序**：
1. **内存 swap thrashing（主因，7.8 倍）**：初测时 WSL2 `MemAvailable=1.7GB`、`SwapFree` 仅剩 500MB/1GB。Redis/Kafka/JVM 的页被换出，p99 从 377ms 飙到 1200ms、max 5.6s。停掉 coupon/order 容器释放到 2.89GB 后，QPS 立刻回升到 2958，`acks` 配置没动。
2. **JVM 冷启动（3.3 倍）**：实验 B 刚重启就压测，JIT 未编译、连接池未建立、Kafka 生产者未攒批；预热后实验 C 从 1239 升到 4094。
3. **acks=all 真实代价（约 28%）**：实验 C(acks=1) 4094 vs 实验 A(acks=all) 2958，与业界调研的 15-35% 区间吻合。

**结论**：`acks=all` 该留——秒杀订单丢了要道歉，28% 的吞吐税值得付。真正的优化方向是：保证内存充足、压测前预热、用 batch/压缩/分区把吞吐补回来。**压测必须在内存充裕 + 服务预热的状态下做，否则数据完全失真。**

## 六、实测踩坑（已修复）

| 问题 | 现象 | 修复 |
| --- | --- | --- |
| 哨兵启动竞态 | `Failed to resolve hostname 'redis-master'`，容器反复 Restarting | `sentinel-entry.sh` 轮询等 DNS + PONG 就绪后再启动；`depends_on` 用 `service_healthy` 条件 |
| 哨兵配置只读挂载 | Redis 7 重写 sentinel.conf 失败 | 去掉 `:ro`，允许哨兵持久化状态 |
| MySQL 初始化 SOURCE 路径错 | `Failed to open file '/docker-entrypoint-initdb.d/init.sql'` | 业务表由 `00-init.sql` 单独挂载，复制账号脚本删掉多余的 SOURCE 行 |
| 宿主机 JVM 连不上哨兵返回的主节点容器 IP | Redisson `checkSentinelsList` 校验失败 / Lettuce 连不上 | 业务服务也容器化进入同一网络（`docker run --network coupon_default`），容器 IP 天然互通；`RedissonHaConfig`（仅 ha profile）关闭 `checkSentinelsList` |
| Sentinel 容器权限 | `FileHandler.publish` NPE，ApplicationContext 启动失败 | Sentinel 1.8.6 启动写 `${user.home}/logs/csp`，非 root 用户目录不可写；镜像改为 root 运行（生产应预建可写日志目录） |

## 七、已知边界

- Nacos 仍是单机（`MODE=standalone`）：8GB 下再起 3 节点 Nacos 会挤爆。生产集群请按 Nacos 官方集群模式部署。
- Redis 用哨兵而非 Cluster：哨兵只解决故障切换、不解决容量扩展。本项目 key 均带 hash tag，切 Cluster 无需改 key；本地受 8GB 限制选哨兵。
- MySQL 主从是异步/半同步混合，故障切换存在秒级数据延迟窗口；金融级场景需 MGR 或 InnoDB Cluster。
- 本地 HA 验证的是"中间件层故障转移"，应用多副本 + K8s 自愈需在真实多机集群验证。
