ALTER TABLE seckill_order
    ADD COLUMN product_code VARCHAR(64) DEFAULT NULL AFTER activity_id,
    ADD COLUMN coupon_id BIGINT DEFAULT NULL AFTER total_amount,
    ADD COLUMN discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER coupon_id,
    ADD COLUMN pay_amount DECIMAL(12,2) DEFAULT NULL AFTER discount_amount;

UPDATE seckill_order
SET pay_amount = total_amount
WHERE pay_amount IS NULL;
