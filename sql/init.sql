-- ============================================================
-- 优惠券 + 秒杀平台 初始化脚本
-- MySQL 8.0+ / InnoDB / utf8mb4
-- 每个服务独立 schema，符合微服务「数据私有权」原则
-- ============================================================

CREATE DATABASE IF NOT EXISTS mall_coupon DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mall_seckill DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mall_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ----------------------------
-- 优惠券模板
-- ----------------------------
CREATE TABLE IF NOT EXISTS mall_coupon.coupon_template (
    id              BIGINT          NOT NULL COMMENT '雪花ID',
    name            VARCHAR(128)    NOT NULL COMMENT '券名称',
    type            TINYINT         NOT NULL COMMENT '1=满减 2=折扣',
    discount_value  DECIMAL(12,2)   NOT NULL COMMENT '满减金额/折扣率',
    max_discount_amount DECIMAL(12,2) DEFAULT NULL,
    threshold       DECIMAL(12,2)   NOT NULL DEFAULT 0.00 COMMENT '使用门槛',
    target          TINYINT         NOT NULL DEFAULT 0,
    product_code    VARCHAR(64)     DEFAULT NULL,
    total           INT             NOT NULL COMMENT '发行总量',
    received        INT             NOT NULL DEFAULT 0 COMMENT '已领取数量',
    per_user_limit  INT             NOT NULL DEFAULT 1 COMMENT '每人限领',
    valid_days      INT             NOT NULL DEFAULT 30 COMMENT '领取后有效天数',
    start_time      DATETIME        NOT NULL COMMENT '活动开始',
    end_time        DATETIME        NOT NULL COMMENT '活动结束',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=草稿 1=进行中 2=已结束',
    create_time     DATETIME        NOT NULL,
    update_time     DATETIME        NOT NULL,
    PRIMARY KEY (id),
    KEY idx_status_time (status, start_time, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券模板';

-- ----------------------------
-- 用户优惠券
-- 不建 (user_id, template_id) 唯一索引：因为 per_user_limit 可能 > 1
-- 限领由 Redis Lua 原子计数 + DB 计数兜底共同保证
-- ----------------------------
CREATE TABLE IF NOT EXISTS mall_coupon.user_coupon (
    id              BIGINT          NOT NULL COMMENT '雪花ID',
    user_id         BIGINT          NOT NULL,
    template_id     BIGINT          NOT NULL,
    coupon_name     VARCHAR(128)    DEFAULT NULL COMMENT '冗余券名，避免跨表查询',
    type            TINYINT         DEFAULT NULL,
    discount_value  DECIMAL(12,2)   DEFAULT NULL,
    max_discount_amount DECIMAL(12,2) DEFAULT NULL,
    threshold       DECIMAL(12,2)   DEFAULT NULL,
    target          TINYINT         NOT NULL DEFAULT 0,
    product_code    VARCHAR(64)     DEFAULT NULL,
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=未使用 1=已使用 2=已过期',
    order_id        VARCHAR(64)     DEFAULT NULL COMMENT '核销订单号',
    use_time        DATETIME        DEFAULT NULL,
    expire_time     DATETIME        NOT NULL,
    create_time     DATETIME        NOT NULL,
    update_time     DATETIME        NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_status (user_id, status, create_time),
    KEY idx_user_template (user_id, template_id),
    KEY idx_expire (status, expire_time),
    KEY idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户优惠券';

CREATE TABLE IF NOT EXISTS mall_coupon.coupon_settlement (
    id              BIGINT          NOT NULL,
    user_id         BIGINT          NOT NULL,
    order_id        VARCHAR(64)     NOT NULL,
    user_coupon_id  BIGINT          NOT NULL,
    template_id     BIGINT          NOT NULL,
    status          TINYINT         NOT NULL DEFAULT 0,
    order_amount    DECIMAL(12,2)   NOT NULL,
    discount_amount DECIMAL(12,2)   NOT NULL,
    pay_amount      DECIMAL(12,2)   NOT NULL,
    lock_time       DATETIME        NOT NULL,
    settle_time     DATETIME        DEFAULT NULL,
    create_time     DATETIME        NOT NULL,
    update_time     DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_coupon (order_id, user_coupon_id),
    KEY idx_user_order (user_id, order_id),
    KEY idx_status_lock_time (status, lock_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mall_coupon.coupon_template_audit_log (
    id          BIGINT       NOT NULL,
    template_id BIGINT       NOT NULL,
    operator_id BIGINT       DEFAULT NULL,
    action      VARCHAR(32)  NOT NULL,
    detail      VARCHAR(512) DEFAULT NULL,
    trace_id    VARCHAR(64)  DEFAULT NULL,
    create_time DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_template_time (template_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- 秒杀活动
-- ----------------------------
CREATE TABLE IF NOT EXISTS mall_seckill.seckill_activity (
    id              BIGINT          NOT NULL COMMENT '雪花ID',
    name            VARCHAR(128)    NOT NULL COMMENT '活动名称',
    product_code    VARCHAR(64)     NOT NULL COMMENT '商品编码',
    product_name    VARCHAR(128)    NOT NULL,
    seckill_price   DECIMAL(12,2)   NOT NULL COMMENT '秒杀价',
    original_price  DECIMAL(12,2)   NOT NULL COMMENT '原价',
    total_stock     INT             NOT NULL COMMENT '秒杀库存',
    sold_count      INT             NOT NULL DEFAULT 0 COMMENT '已售计数（展示用，最终以订单为准）',
    start_time      DATETIME        NOT NULL,
    end_time        DATETIME        NOT NULL,
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=草稿 1=进行中 2=已结束',
    create_time     DATETIME        NOT NULL,
    update_time     DATETIME        NOT NULL,
    PRIMARY KEY (id),
    KEY idx_status_time (status, start_time, end_time),
    KEY idx_product (product_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动';

-- ----------------------------
-- 秒杀订单
-- uk_activity_user_active 仅对有效订单（status=0/1）生效，取消单 active_flag=NULL，
-- 既保证 MQ 重复消费幂等，又允许取消/超时关单后用户重新抢购而不触发唯一键死锁。
-- ----------------------------
CREATE TABLE IF NOT EXISTS mall_order.seckill_order (
    order_id        VARCHAR(64)     NOT NULL COMMENT '订单号（雪花ID字符串）',
    user_id         BIGINT          NOT NULL,
    activity_id     BIGINT          NOT NULL,
    product_code    VARCHAR(64)     DEFAULT NULL,
    product_name    VARCHAR(128)    DEFAULT NULL,
    seckill_price   DECIMAL(12,2)   NOT NULL,
    quantity        INT             NOT NULL DEFAULT 1,
    total_amount    DECIMAL(12,2)   NOT NULL,
    coupon_id       BIGINT          DEFAULT NULL,
    discount_amount DECIMAL(12,2)   NOT NULL DEFAULT 0.00,
    pay_amount      DECIMAL(12,2)   DEFAULT NULL,
    stock_bucket    INT             NOT NULL DEFAULT 0 COMMENT '预扣命中的库存桶，超时关单按桶归还',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=待支付 1=已支付 2=已取消',
    stock_restored  TINYINT         NOT NULL DEFAULT 0 COMMENT '取消后Redis库存是否已归还: 0=未归还 1=已归还',
    active_flag     TINYINT         GENERATED ALWAYS AS (CASE WHEN status IN (0, 1) THEN 1 ELSE NULL END) STORED COMMENT '有效订单标志',
    create_time     DATETIME        NOT NULL,
    pay_time        DATETIME        DEFAULT NULL,
    PRIMARY KEY (order_id),
    UNIQUE KEY uk_activity_user_active (activity_id, user_id, active_flag),
    KEY idx_user (user_id, create_time),
    KEY idx_status_create_time (status, create_time),
    KEY idx_cancel_restore (status, stock_restored)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单';

-- ----------------------------
-- 演示数据（发布时间设为过去，发布接口可重复预热 Redis）
-- ----------------------------
INSERT IGNORE INTO mall_coupon.coupon_template
(id, name, type, discount_value, max_discount_amount, threshold, target, product_code, total, received, per_user_limit, valid_days,
 start_time, end_time, status, create_time, update_time)
VALUES
(1001, '新人满100减20', 1, 20.00, NULL, 100.00, 0, NULL, 1000, 0, 2, 30,
 DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 7 DAY), 0, NOW(), NOW()),
(1002, '全场9折券', 2, 0.90, 200.00, 0.00, 0, NULL, 500, 0, 1, 15,
 DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 7 DAY), 0, NOW(), NOW());

INSERT IGNORE INTO mall_seckill.seckill_activity
(id, name, product_code, product_name, seckill_price, original_price, total_stock, sold_count,
 start_time, end_time, status, create_time, update_time)
VALUES
(2001, 'iPhone 限量秒杀', 'SKU-2026-001', 'iPhone 17 Pro 256G', 5999.00, 8999.00, 100, 0,
 DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 7 DAY), 0, NOW(), NOW());
