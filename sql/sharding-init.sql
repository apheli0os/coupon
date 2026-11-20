-- ============================================================
-- 分库分表初始化脚本（可选，生产 profile 使用）
-- 2 库 × 4 表：user_coupon 按 user_id 分片，seckill_order 按 activity_id 分片
-- 执行：mysql -uroot -p < sql/sharding-init.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS mall_coupon_0 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mall_coupon_1 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mall_order_0 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mall_order_1 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 券模板表数据量小，留在单库 mall_coupon_0，不做分片
CREATE TABLE IF NOT EXISTS mall_coupon_0.coupon_template LIKE mall_coupon.coupon_template;
INSERT IGNORE INTO mall_coupon_0.coupon_template SELECT * FROM mall_coupon.coupon_template;

-- 秒杀活动表数据量小，留在单库 mall_order_0
CREATE TABLE IF NOT EXISTS mall_order_0.seckill_activity LIKE mall_seckill.seckill_activity;
INSERT IGNORE INTO mall_order_0.seckill_activity SELECT * FROM mall_seckill.seckill_activity;

DELIMITER $$

DROP PROCEDURE IF EXISTS mall_create_sharding_tables $$
CREATE PROCEDURE mall_create_sharding_tables()
BEGIN
    DECLARE i INT DEFAULT 0;
    WHILE i < 4 DO
        SET @s1 = CONCAT('CREATE TABLE IF NOT EXISTS mall_coupon_0.user_coupon_', i, ' LIKE mall_coupon.user_coupon');
        SET @s2 = CONCAT('CREATE TABLE IF NOT EXISTS mall_coupon_1.user_coupon_', i, ' LIKE mall_coupon.user_coupon');
        SET @s3 = CONCAT('CREATE TABLE IF NOT EXISTS mall_order_0.seckill_order_', i, ' LIKE mall_order.seckill_order');
        SET @s4 = CONCAT('CREATE TABLE IF NOT EXISTS mall_order_1.seckill_order_', i, ' LIKE mall_order.seckill_order');
        PREPARE st1 FROM @s1; EXECUTE st1; DEALLOCATE PREPARE st1;
        PREPARE st2 FROM @s2; EXECUTE st2; DEALLOCATE PREPARE st2;
        PREPARE st3 FROM @s3; EXECUTE st3; DEALLOCATE PREPARE st3;
        PREPARE st4 FROM @s4; EXECUTE st4; DEALLOCATE PREPARE st4;
        SET i = i + 1;
    END WHILE;
END $$

DELIMITER ;

CALL mall_create_sharding_tables();
DROP PROCEDURE mall_create_sharding_tables;
