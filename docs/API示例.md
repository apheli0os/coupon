# API 示例与验证

前提：中间件已启动、三个服务已运行。下文统一使用 `X-User-Id` 请求头模拟登录（也可用 JWT）。

## 1. 公共：签发 Token

```bash
curl "http://localhost:8081/api/auth/token?userId=1001"
# {"code":0,"message":"success","data":"eyJhbGciOiJIUzI1NiJ9..."}
```

## 2. 优惠券

### 2.1 创建模板

```bash
curl -X POST http://localhost:8081/api/coupon/templates \
  -H 'Content-Type: application/json' \
  -d '{
    "name":"双11满200减50",
    "type":1,
    "discountValue":50.00,
    "threshold":200.00,
    "total":1000,
    "perUserLimit":2,
    "validDays":7,
    "startTime":"2026-01-01 00:00:00",
    "endTime":"2026-12-31 23:59:59"
  }'
```

### 2.2 发布（必须！否则 Redis 无库存）

```bash
curl -X POST http://localhost:8081/api/coupon/templates/{templateId}/publish \
  -H 'X-User-Id: 1001'
```

### 2.3 领取 / 查询 / 核销

```bash
curl -X POST -H 'X-User-Id: 1001' http://localhost:8081/api/coupon/claim/{templateId}
# 注意：领券为 Kafka 异步落库，返回成功后券记录由消费者批量写入，
# 紧接着查 /my 可能有秒级延迟，请稍等片刻再查。
curl -H 'X-User-Id: 1001' 'http://localhost:8081/api/coupon/my?status=0'
curl -X POST -H 'X-User-Id: 1001' http://localhost:8081/api/coupon/use \
  -H 'Content-Type: application/json' \
  -d '{"userCouponId":123,"orderId":"TEST-001","orderAmount":299.00}'
```

**限领验证**：模板 `perUserLimit=2`，同一用户领 3 次，第 3 次应返回 `code=1005`「超出每人限领数量」。

**并发超领验证**：`total=10`、`perUserLimit=1`，10 个不同用户并发领取，Redis 与 DB 最终都应为 10，`received` 不应超过 `total`。

## 3. 秒杀

### 3.1 创建 / 发布

```bash
curl -X POST http://localhost:8082/api/seckill/activities \
  -H 'Content-Type: application/json' \
  -d '{
    "name":"限量秒杀",
    "productCode":"SKU-2026-002",
    "productName":"测试商品",
    "seckillPrice":9.90,
    "originalPrice":99.00,
    "totalStock":100,
    "startTime":"2026-01-01 00:00:00",
    "endTime":"2026-12-31 23:59:59"
  }'

curl -X POST -H 'X-User-Id: 1001' http://localhost:8082/api/seckill/activities/{activityId}/publish
```

### 3.2 抢购 / 查询结果

```bash
curl -X POST -H 'X-User-Id: 1001' http://localhost:8082/api/seckill/{activityId}/buy
# 返回 orderId

curl -H 'X-User-Id: 1001' "http://localhost:8082/api/seckill/result?activityId={activityId}"
# MQ 消费完成后返回订单状态
```

**重复抢购验证**：同一用户第二次抢同一活动应返回 `code=2005`「您已参与过该秒杀活动」。

**超卖验证**：库存 100，用 500 个不同用户并发抢购，成功数应恰好 100，订单表 `seckill_order` 也是 100 行。

### 3.3 简单并发脚本

```bash
for i in $(seq 1 200); do
  curl -s -o /dev/null -X POST \
    -H "X-User-Id: $i" \
    "http://localhost:8082/api/seckill/2001/buy" &
done
wait
redis-cli GET 'seckill:{2001}:stock'
mysql -h127.0.0.1 -uroot -proot123456 -e \
  'SELECT COUNT(*) FROM mall_order.seckill_order WHERE activity_id=2001;'
```

## 4. 订单

```bash
curl -H 'X-User-Id: 1001' http://localhost:8083/api/orders/{orderId}
curl -X POST -H 'X-User-Id: 1001' http://localhost:8083/api/orders/{orderId}/pay
curl -X POST -H 'X-User-Id: 1001' http://localhost:8083/api/orders/{orderId}/cancel
```

## 5. 控制台

| 组件 | 地址 | 账号 |
| --- | --- | --- |
| Swagger UI（coupon） | <http://localhost:8081/swagger-ui.html> | - |
| Swagger UI（seckill） | <http://localhost:8082/swagger-ui.html> | - |
| Swagger UI（order） | <http://localhost:8083/swagger-ui.html> | - |
| Nacos | <http://localhost:8848/nacos> | nacos/nacos |
| Sentinel Dashboard | <http://localhost:8090> | - |
| Kafka（bootstrap） | localhost:9092 | mall-kafka，topic 由服务启动时自动声明 |
