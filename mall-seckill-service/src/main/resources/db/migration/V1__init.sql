-- ============================================================
-- 秒杀服务数据库迁移 V1：初始表结构 + 演示数据
-- 库：mall_seckill（连接串指定）
-- ============================================================

CREATE TABLE IF NOT EXISTS seckill_activity (
    id              BIGINT          NOT NULL COMMENT '雪花ID',
    name            VARCHAR(128)    NOT NULL COMMENT '活动名称',
    product_code    VARCHAR(64)     NOT NULL COMMENT '商品编码',
    product_name    VARCHAR(128)    NOT NULL,
    seckill_price   DECIMAL(12,2)   NOT NULL COMMENT '秒杀价',
    original_price  DECIMAL(12,2)   NOT NULL COMMENT '原价',
    total_stock     INT             NOT NULL COMMENT '秒杀库存',
    sold_count      INT             NOT NULL DEFAULT 0 COMMENT '已售计数（展示用）',
    start_time      DATETIME        NOT NULL,
    end_time        DATETIME        NOT NULL,
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=草稿 1=进行中 2=已结束',
    create_time     DATETIME        NOT NULL,
    update_time     DATETIME        NOT NULL,
    PRIMARY KEY (id),
    KEY idx_status_time (status, start_time, end_time),
    KEY idx_product (product_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动';

-- 演示数据
INSERT IGNORE INTO seckill_activity
(id, name, product_code, product_name, seckill_price, original_price, total_stock, sold_count,
 start_time, end_time, status, create_time, update_time)
VALUES
(2001, 'iPhone 限量秒杀', 'SKU-2026-001', 'iPhone 17 Pro 256G', 5999.00, 8999.00, 100, 0,
 DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 7 DAY), 0, NOW(), NOW());
