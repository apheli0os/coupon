-- ============================================================
-- RocketMQ -> Kafka 链路切换增量迁移（幂等，可重复执行前先检查）
-- 1) seckill_order 增加 stock_bucket：Kafka 无任意延迟消息，
--    15 分钟关单改为定时扫描，必须把下单时命中的库存桶落到 DB。
-- 2) user_coupon 幂等依赖雪花主键 id（已存在），无需新增唯一索引；
--    消费端用 INSERT IGNORE + 实际新增行数累加 received。
-- ============================================================

ALTER TABLE mall_order.seckill_order
    ADD COLUMN stock_bucket INT NOT NULL DEFAULT 0 COMMENT '预扣库存桶，超时关单按桶归还' AFTER total_amount;
