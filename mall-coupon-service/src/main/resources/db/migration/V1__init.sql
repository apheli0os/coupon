-- ============================================================
-- 优惠券服务数据库迁移 V1：初始表结构 + 演示数据
-- 由 Flyway 在应用启动时自动执行，仅执行一次（记录于 flyway_schema_history）
-- 库：mall_coupon（连接串指定，脚本内不写 schema 前缀）
-- ============================================================

CREATE TABLE IF NOT EXISTS coupon_template (
    id              BIGINT          NOT NULL COMMENT '雪花ID',
    name            VARCHAR(128)    NOT NULL COMMENT '券名称',
    type            TINYINT         NOT NULL COMMENT '1=满减 2=折扣',
    discount_value  DECIMAL(12,2)   NOT NULL COMMENT '满减金额/折扣率',
    threshold       DECIMAL(12,2)   NOT NULL DEFAULT 0.00 COMMENT '使用门槛',
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

-- 不建 (user_id, template_id) 唯一索引：per_user_limit 可能 > 1
-- 限领由 Redis Lua 原子计数 + DB 计数兜底
CREATE TABLE IF NOT EXISTS user_coupon (
    id              BIGINT          NOT NULL COMMENT '雪花ID',
    user_id         BIGINT          NOT NULL,
    template_id     BIGINT          NOT NULL,
    coupon_name     VARCHAR(128)    DEFAULT NULL COMMENT '冗余券名',
    type            TINYINT         DEFAULT NULL,
    discount_value  DECIMAL(12,2)   DEFAULT NULL,
    threshold       DECIMAL(12,2)   DEFAULT NULL,
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=未使用 1=已使用 2=已过期',
    order_id        VARCHAR(64)     DEFAULT NULL COMMENT '核销订单号',
    use_time        DATETIME        DEFAULT NULL,
    expire_time     DATETIME        NOT NULL,
    create_time     DATETIME        NOT NULL,
    update_time     DATETIME        NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_status (user_id, status, create_time),
    KEY idx_user_template (user_id, template_id),
    KEY idx_expire (status, expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户优惠券';

-- 演示数据（发布时间设为过去，发布接口可重复预热 Redis）
INSERT IGNORE INTO coupon_template
(id, name, type, discount_value, threshold, total, received, per_user_limit, valid_days,
 start_time, end_time, status, create_time, update_time)
VALUES
(1001, '新人满100减20', 1, 20.00, 100.00, 1000, 0, 2, 30,
 DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 7 DAY), 0, NOW(), NOW()),
(1002, '全场9折券', 2, 0.90, 0.00, 500, 0, 1, 15,
 DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 7 DAY), 0, NOW(), NOW());
