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
