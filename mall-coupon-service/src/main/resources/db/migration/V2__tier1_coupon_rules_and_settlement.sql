ALTER TABLE coupon_template
    ADD COLUMN max_discount_amount DECIMAL(12,2) DEFAULT NULL AFTER discount_value,
    ADD COLUMN target TINYINT NOT NULL DEFAULT 0 AFTER threshold,
    ADD COLUMN product_code VARCHAR(64) DEFAULT NULL AFTER target;

ALTER TABLE user_coupon
    ADD COLUMN max_discount_amount DECIMAL(12,2) DEFAULT NULL AFTER discount_value,
    ADD COLUMN target TINYINT NOT NULL DEFAULT 0 AFTER threshold,
    ADD COLUMN product_code VARCHAR(64) DEFAULT NULL AFTER target,
    ADD KEY idx_order_id (order_id);

UPDATE coupon_template
SET max_discount_amount = 200.00
WHERE id = 1002 AND max_discount_amount IS NULL;

CREATE TABLE IF NOT EXISTS coupon_settlement (
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
