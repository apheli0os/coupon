package com.mall.common.constants;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RedisKeyTest {

    @Test
    void couponKeys_shouldShareHashTag() {
        Long templateId = 12345L;
        String tag = "{12345}";
        assertTrue(RedisKey.couponStock(templateId).contains(tag));
        assertTrue(RedisKey.couponClaimHash(templateId).contains(tag));
        assertTrue(RedisKey.couponStatus(templateId).contains(tag));
        assertTrue(RedisKey.couponTemplateCache(templateId).contains(tag));
        assertTrue(RedisKey.couponClaimToken(templateId, 99L).contains(tag));
        assertTrue(RedisKey.couponRollbackGuard(templateId, 99L).contains(tag));
    }

    @Test
    void seckillKeys_shouldShareHashTag() {
        Long activityId = 67890L;
        String tag = "{67890}";
        assertTrue(RedisKey.seckillStockBucket(activityId, 0).contains(tag));
        assertTrue(RedisKey.seckillStock(activityId).contains(tag));
        assertTrue(RedisKey.seckillUsers(activityId).contains(tag));
        assertTrue(RedisKey.seckillStatus(activityId).contains(tag));
        assertTrue(RedisKey.seckillOrderToken(activityId, "ORD-1").contains(tag));
        assertNotEquals(RedisKey.seckillOrderToken(activityId, "ORD-1"),
                RedisKey.seckillOrderToken(activityId, "ORD-2"),
                "秒杀凭证必须按 orderId 隔离，避免同用户重买冲突");
        assertTrue(RedisKey.seckillSoldState(activityId, "ORD-1").contains(tag));
    }

    @Test
    void bucketOf_shouldDistributeAndHandleNegative() {
        Set<Integer> buckets = new HashSet<>();
        for (long userId = 0; userId < 1000; userId++) {
            int b = RedisKey.bucketOf(userId);
            assertTrue(b >= 0 && b < RedisKey.SECKILL_STOCK_BUCKETS, "桶索引越界: " + b);
            buckets.add(b);
        }
        assertEquals(RedisKey.SECKILL_STOCK_BUCKETS, buckets.size(), "16 个桶应全部被覆盖");
        assertTrue(RedisKey.bucketOf(-1L) >= 0, "负数 userId 必须映射到有效桶");
    }

    @Test
    void luaScripts_shouldExistAsResources() {
        for (String name : new String[]{
                "coupon_claim.lua", "coupon_rollback.lua", "coupon_expire_compensate.lua",
                "seckill.lua", "seckill_rollback.lua", "seckill_expire_compensate.lua",
                "seckill_cancel_rollback.lua", "seckill_sold_state.lua", "token_claim.lua"}) {
            assertNotNull(getClass().getClassLoader().getResource("lua/" + name), "缺少 Lua 脚本: " + name);
        }
    }

    @Test
    void businessRedisKeys_mustContainClusterHashTag() throws Exception {
        for (Method method : RedisKey.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    || !Modifier.isPublic(method.getModifiers())
                    || !String.class.equals(method.getReturnType())) {
                continue;
            }
            String name = method.getName();
            if ((!name.startsWith("coupon") && !name.startsWith("seckill")) || name.endsWith("Lock")) {
                continue;
            }
            Class<?>[] paramTypes = method.getParameterTypes();
            Object[] args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                if (Long.class.equals(paramTypes[i]) || long.class.equals(paramTypes[i])) {
                    args[i] = 1L;
                } else if (int.class.equals(paramTypes[i]) || Integer.class.equals(paramTypes[i])) {
                    args[i] = 0;
                } else if (String.class.equals(paramTypes[i])) {
                    args[i] = "1";
                }
            }
            String key = (String) method.invoke(null, args);
            assertTrue(key.contains("{") && key.contains("}"),
                    "RedisKey." + name + " must include cluster hash tag {id}, got: " + key);
        }
    }
}
