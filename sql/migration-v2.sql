-- ============================================================
-- 存量库升级脚本：修复取消重买死锁、关单库存补偿标记与超时扫描索引
-- 仅用于已按旧版 init.sql 初始化过的数据库；新环境直接执行 sql/init.sql 即可。
-- ============================================================
ALTER TABLE mall_order.seckill_order
    ADD COLUMN stock_restored TINYINT NOT NULL DEFAULT 0 COMMENT '取消后Redis库存是否已归还: 0=未归还 1=已归还' AFTER status,
    ADD COLUMN active_flag TINYINT GENERATED ALWAYS AS (CASE WHEN status IN (0, 1) THEN 1 ELSE NULL END) STORED COMMENT '有效订单标志' AFTER stock_restored,
    DROP INDEX uk_activity_user,
    ADD UNIQUE KEY uk_activity_user_active (activity_id, user_id, active_flag),
    ADD KEY idx_status_create_time (status, create_time),
    ADD KEY idx_cancel_restore (status, stock_restored);
