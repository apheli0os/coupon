package com.mall.common.constants;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Redis Key 统一定义。
 * 使用 hash tag {id} 保证同一业务实体的多个 key 落在同一 slot，兼容 Redis Cluster 下的多 Key Lua 脚本。
 */
public final class RedisKey {

    private RedisKey() {
    }

    /* ===== 优惠券 ===== */

    /** 券模板剩余库存 String */
    public static String couponStock(Long templateId) {
        return "coupon:{" + templateId + "}:stock";
    }

    /** 用户已领数量 Hash: field=userId, value=count */
    public static String couponClaimHash(Long templateId) {
        return "coupon:{" + templateId + "}:claims";
    }

    /** 券模板活动状态 String: 0=已预热未开始 1=进行中 2=已结束 */
    public static String couponStatus(Long templateId) {
        return "coupon:{" + templateId + "}:status";
    }

    /** 券模板详情缓存 */
    public static String couponTemplateCache(Long templateId) {
        return "coupon:{" + templateId + "}:template";
    }

    /** 领券异步落库凭证 String（防 Kafka 发送超时回滚后延迟消息仍落库导致超发） */
    public static String couponClaimToken(Long templateId, Long userCouponId) {
        return "coupon:{" + templateId + "}:token:" + userCouponId;
    }

    public static String couponRollbackGuard(Long templateId, Long userCouponId) {
        return "coupon:{" + templateId + "}:rollback:" + userCouponId;
    }

    /* ===== 秒杀 ===== */

    /**
     * 秒杀库存分桶数量：将单一库存 key 拆分为 16 个逻辑桶。
     * 注意：因 seckill.lua 需原子操作库存桶 + users Set + status，所有 key 统一使用 {activityId} hash tag
     * 以保证 Redis Cluster 下同 slot 不报 CROSSSLOT；若需跨节点打散，须将 users 校验与单桶扣减拆分为独立调用。
     */
    public static final int SECKILL_STOCK_BUCKETS = 16;

    /** 秒杀库存桶 String：seckill:{activityId}:stock:{bucket} */
    public static String seckillStockBucket(Long activityId, int bucket) {
        return "seckill:{" + activityId + "}:stock:" + bucket;
    }

    /** 兼容旧接口：记录预热初始总量，实际库存以 16 个分桶累加为准 */
    public static String seckillStock(Long activityId) {
        return "seckill:{" + activityId + "}:stock";
    }

    /** 按 userId 选起始桶，Lua 脚本从该桶开始轮询所有桶 */
    public static int bucketOf(Long userId) {
        return (int) Math.floorMod(userId, SECKILL_STOCK_BUCKETS);
    }

    /** 已参与用户 Set: member=userId（一人一单） */
    public static String seckillUsers(Long activityId) {
        return "seckill:{" + activityId + "}:users";
    }

    /** 活动状态 String: 0=已预热未开始 1=进行中 2=已结束 */
    public static String seckillStatus(Long activityId) {
        return "seckill:{" + activityId + "}:status";
    }

    /** 活动详情缓存 */
    public static String seckillActivityCache(Long activityId) {
        return "seckill:{" + activityId + "}:activity";
    }

    public static String seckillOrderToken(Long activityId, String orderId) {
        return "seckill:{" + activityId + "}:token:" + orderId;
    }

    /** 关单归还库存幂等键（按 orderId 防止宕机重试时误撤用户重新下单的新订单） */
    public static String seckillOrderRollbackGuard(Long activityId, String orderId) {
        return "seckill:{" + activityId + "}:rollback:" + orderId;
    }

    public static String seckillSoldState(Long activityId, String orderId) {
        return "seckill:{" + activityId + "}:soldstate:" + orderId;
    }

    /** 秒杀销量累加去重键 */
    public static String seckillSoldDedup(Long activityId, String orderId) {
        return "seckill:{" + activityId + "}:sold:" + orderId;
    }

    /** 秒杀取消扣减销量去重键 */
    public static String seckillCancelDedup(Long activityId, String orderId) {
        return "seckill:{" + activityId + "}:cancel:" + orderId;
    }

    /* ===== 分布式锁 ===== */

    public static String couponTemplateLock(Long templateId) {
        return "lock:coupon:template:" + templateId;
    }

    public static String seckillActivityLock(Long activityId) {
        return "lock:seckill:activity:" + activityId;
    }

    public static String seckillSoldLock(Long activityId) {
        return "lock:seckill:sold:" + activityId;
    }

    public static String couponExpireJobLock() {
        return "lock:job:coupon:expire";
    }

    public static String couponActivateJobLock() {
        return "lock:job:coupon:activate";
    }

    public static String couponReconcileJobLock() {
        return "lock:job:coupon:reconcile";
    }

    public static String seckillActivateJobLock() {
        return "lock:job:seckill:activate";
    }

    public static String orderTimeoutJobLock() {
        return "lock:job:order:timeout";
    }

    public static String couponSettlementSweepJobLock() {
        return "lock:job:coupon:settlement-sweep";
    }

    /* ===== 常量与 TTL 计算 ===== */

    public static final String STATUS_PREHEATED = "0";
    public static final String STATUS_ACTIVE = "1";
    public static final String STATUS_ENDED = "2";
    public static final long TEMPLATE_CACHE_TTL_SECONDS = 60 * 30;
    public static final long TOKEN_TTL_SECONDS = 60 * 30;
    public static final long CONSUMED_TOKEN_TTL_SECONDS = 60 * 5;
    public static final long ROLLBACK_GUARD_TTL_SECONDS = 60L * 60 * 24 * 7;
    public static final long SOLD_DEDUP_TTL_SECONDS = 60L * 60 * 24;
    private static final Duration MIN_ACTIVITY_TTL = Duration.ofDays(1);
    private static final Duration POST_END_GRACE = Duration.ofDays(7);

    /** 活动库存/状态/防重 Key 的统一 TTL：活动结束后保留 7 天，最少 1 天，避免永久驻留耗尽内存 */
    public static Duration activityKeyTtl(LocalDateTime now, LocalDateTime endTime) {
        if (endTime == null) {
            return POST_END_GRACE;
        }
        Duration remaining = Duration.between(now, endTime).plus(POST_END_GRACE);
        return remaining.compareTo(MIN_ACTIVITY_TTL) < 0 ? MIN_ACTIVITY_TTL : remaining;
    }
}
