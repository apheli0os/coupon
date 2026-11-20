-- ============================================================
-- 订单服务数据库迁移 V1：初始表结构
-- 库：mall_order（连接串指定）
-- 注意：stock_bucket 字段已包含（RocketMQ→Kafka 迁移后定时关单需要）
-- ============================================================

CREATE TABLE IF NOT EXISTS seckill_order (
    order_id        VARCHAR(64)     NOT NULL COMMENT '订单号（雪花ID字符串）',
    user_id         BIGINT          NOT NULL,
    activity_id     BIGINT          NOT NULL,
    product_name    VARCHAR(128)    DEFAULT NULL,
    seckill_price   DECIMAL(12,2)   NOT NULL,
    quantity        INT             NOT NULL DEFAULT 1,
    total_amount    DECIMAL(12,2)   NOT NULL,
    stock_bucket    INT             NOT NULL DEFAULT 0 COMMENT '预扣命中的库存桶，超时关单按桶归还',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=待支付 1=已支付 2=已取消',
    create_time     DATETIME        NOT NULL,
    pay_time        DATETIME        DEFAULT NULL,
    PRIMARY KEY (order_id),
    -- MQ 重复消费幂等的最后一道防线：一个用户一个活动只能有一单
    UNIQUE KEY uk_activity_user (activity_id, user_id),
    KEY idx_user (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单';
