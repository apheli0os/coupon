package com.mall.common.constants;

/**
 * Redis Key 统一定义。
 * 使用 hash tag {id} 保证同一业务实体的多个 key 落在同一 slot，兼容 Redis Cluster 下的 Lua 脚本。
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

    /** 券模板活动状态 String: 1=进行中 */
    public static String couponStatus(Long templateId) {
        return "coupon:{" + templateId + "}:status";
    }

    /** 券模板详情缓存 Hash */
    public static String couponTemplateCache(Long templateId) {
        return "coupon:{" + templateId + "}:template";
    }

    /* ===== 秒杀 ===== */

    /** 秒杀库存分桶数量：把单点热点打散到 16 个 key，Redis Cluster 下均匀分布到不同 slot */
    public static final int SECKILL_STOCK_BUCKETS = 16;

    /** 秒杀库存桶 String：seckill:{activityId}:stock:{bucket} */
    public static String seckillStockBucket(Long activityId, int bucket) {
        return "seckill:{" + activityId + "}:stock:" + bucket;
    }

    /** 兼容旧接口：读取总库存时用全部桶累加，不再有单一 stock key */
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

    /** 活动状态 String: 1=进行中 */
    public static String seckillStatus(Long activityId) {
        return "seckill:{" + activityId + "}:status";
    }

    /** 活动详情缓存 Hash */
    public static String seckillActivityCache(Long activityId) {
        return "seckill:{" + activityId + "}:activity";
    }

    /** 秒杀下单幂等标记 String */
    public static String seckillOrderToken(Long activityId, Long userId) {
        return "seckill:{" + activityId + "}:token:" + userId;
    }

    /* ===== 分布式锁 ===== */

    public static String couponTemplateLock(Long templateId) {
        return "lock:coupon:template:" + templateId;
    }

    public static String seckillActivityLock(Long activityId) {
        return "lock:seckill:activity:" + activityId;
    }

    /* ===== 常量 ===== */

    public static final String STATUS_ACTIVE = "1";
    public static final long TEMPLATE_CACHE_TTL_SECONDS = 60 * 30;
    public static final long TOKEN_TTL_SECONDS = 60 * 10;
}
