package com.mall.common.lua;

import com.mall.common.constants.RedisKey;
import com.mall.common.util.LuaScriptExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LuaScriptsRedisIT {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no");

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static LuaScriptExecutor luaExecutor;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        luaExecutor = new LuaScriptExecutor(redisTemplate);
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void flushKeys() {
        Set<String> keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void couponScripts_shouldEnforceClaimTokenExpireCompensationAndRollbackMutualExclusion() {
        long tplId = 1001L;
        long userId = 5001L;
        long couponId = 80001L;
        String stockKey = RedisKey.couponStock(tplId);
        String claimHash = RedisKey.couponClaimHash(tplId);
        String statusKey = RedisKey.couponStatus(tplId);
        String tokenKey = RedisKey.couponClaimToken(tplId, couponId);
        String guardKey = RedisKey.couponRollbackGuard(tplId, couponId);

        redisTemplate.opsForValue().set(stockKey, "1", Duration.ofMinutes(10));
        redisTemplate.opsForValue().set(statusKey, RedisKey.STATUS_ACTIVE, Duration.ofMinutes(10));

        long[] claimRes = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_COUPON_CLAIM,
                List.of(stockKey, claimHash, statusKey),
                userId, 1));
        assertEquals(0L, claimRes[0]);
        assertEquals(0L, claimRes[1]);

        long[] limitRes = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_COUPON_CLAIM,
                List.of(stockKey, claimHash, statusKey),
                userId, 1));
        assertEquals(-2L, limitRes[0]);

        redisTemplate.opsForValue().set(tokenKey, String.valueOf(couponId), Duration.ofMinutes(5));
        long[] compWhileTokenPresent = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_COUPON_EXPIRE_COMPENSATE,
                List.of(stockKey, claimHash, tokenKey, guardKey),
                userId, 3600));
        assertEquals(2L, compWhileTokenPresent[0]);

        long[] tokenClaim = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_TOKEN_CLAIM,
                List.of(tokenKey),
                couponId, 300));
        assertEquals(1L, tokenClaim[0]);
        assertEquals("PROCESSING:" + couponId, redisTemplate.opsForValue().get(tokenKey));

        long[] rollbackBlocked = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_COUPON_ROLLBACK,
                List.of(stockKey, claimHash, tokenKey, guardKey),
                userId, couponId, 3600));
        assertEquals(2L, rollbackBlocked[0]);

        long couponId2 = 80002L;
        String tokenKey2 = RedisKey.couponClaimToken(tplId, couponId2);
        String guardKey2 = RedisKey.couponRollbackGuard(tplId, couponId2);
        redisTemplate.opsForValue().set(stockKey, "1", Duration.ofMinutes(10));
        redisTemplate.opsForHash().put(claimHash, "5002", "1");
        redisTemplate.opsForValue().set(tokenKey2, String.valueOf(couponId2), Duration.ofMinutes(5));

        long[] producerRollback = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_COUPON_ROLLBACK,
                List.of(stockKey, claimHash, tokenKey2, guardKey2),
                5002L, couponId2, 3600));
        assertEquals(0L, producerRollback[0]);
        assertEquals(2L, producerRollback[1]);
        assertNull(redisTemplate.opsForHash().get(claimHash, "5002"));

        long[] compAfterRollback = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_COUPON_EXPIRE_COMPENSATE,
                List.of(stockKey, claimHash, tokenKey2, guardKey2),
                5002L, 3600));
        assertEquals(1L, compAfterRollback[0]);
        assertEquals(2L, compAfterRollback[1]);
        assertEquals("2", redisTemplate.opsForValue().get(stockKey));

        long couponId3 = 80003L;
        String tokenKey3 = RedisKey.couponClaimToken(tplId, couponId3);
        String guardKey3 = RedisKey.couponRollbackGuard(tplId, couponId3);
        redisTemplate.opsForValue().set(stockKey, "1", Duration.ofMinutes(10));
        redisTemplate.opsForHash().put(claimHash, "5003", "1");

        long[] compExpire = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_COUPON_EXPIRE_COMPENSATE,
                List.of(stockKey, claimHash, tokenKey3, guardKey3),
                5003L, 3600));
        assertEquals(0L, compExpire[0]);
        assertEquals(2L, compExpire[1]);
        assertNull(redisTemplate.opsForHash().get(claimHash, "5003"));

        long[] compRetry = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_COUPON_EXPIRE_COMPENSATE,
                List.of(stockKey, claimHash, tokenKey3, guardKey3),
                5003L, 3600));
        assertEquals(1L, compRetry[0]);
        assertEquals(2L, compRetry[1]);
        assertEquals("2", redisTemplate.opsForValue().get(stockKey));
    }

    @Test
    void seckillScripts_shouldEnforceBucketsGuardsAndSoldStateMachine() {
        long actId = 2001L;
        long userId = 6001L;
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < RedisKey.SECKILL_STOCK_BUCKETS; i++) {
            String bucketKey = RedisKey.seckillStockBucket(actId, i);
            redisTemplate.opsForValue().set(bucketKey, i == 0 ? "1" : "0", Duration.ofMinutes(10));
            keys.add(bucketKey);
        }
        String usersKey = RedisKey.seckillUsers(actId);
        String statusKey = RedisKey.seckillStatus(actId);
        keys.add(usersKey);
        keys.add(statusKey);
        redisTemplate.opsForValue().set(statusKey, RedisKey.STATUS_ACTIVE, Duration.ofMinutes(10));

        long[] deduct = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_SECKILL,
                keys,
                userId, RedisKey.bucketOf(userId), RedisKey.SECKILL_STOCK_BUCKETS));
        assertEquals(0L, deduct[0]);
        int bucket = (int) deduct[1];
        assertEquals(0, bucket);

        long[] dup = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_SECKILL,
                keys,
                userId, RedisKey.bucketOf(userId), RedisKey.SECKILL_STOCK_BUCKETS));
        assertEquals(-2L, dup[0]);

        String ord1 = "ORD-IT-1";
        String token1 = RedisKey.seckillOrderToken(actId, ord1);
        String guard1 = RedisKey.seckillOrderRollbackGuard(actId, ord1);
        redisTemplate.opsForValue().set(token1, ord1, Duration.ofMinutes(5));

        long[] rollback1 = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_SECKILL_ROLLBACK,
                List.of(RedisKey.seckillStockBucket(actId, bucket), usersKey, token1, guard1),
                userId, ord1, 3600));
        assertEquals(0L, rollback1[0]);
        assertEquals(1L, rollback1[1]);

        long[] expireAfterRollback = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_SECKILL_EXPIRE_COMPENSATE,
                List.of(RedisKey.seckillStockBucket(actId, bucket), usersKey, token1, guard1),
                userId, 3600));
        assertEquals(1L, expireAfterRollback[0]);
        assertEquals("1", redisTemplate.opsForValue().get(RedisKey.seckillStockBucket(actId, bucket)));

        long userId2 = 6002L;
        long[] deduct2 = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_SECKILL,
                keys,
                userId2, RedisKey.bucketOf(userId2), RedisKey.SECKILL_STOCK_BUCKETS));
        assertEquals(0L, deduct2[0]);
        int bucket2 = (int) deduct2[1];
        String ord2 = "ORD-IT-2";
        String token2 = RedisKey.seckillOrderToken(actId, ord2);
        String guard2 = RedisKey.seckillOrderRollbackGuard(actId, ord2);
        redisTemplate.opsForValue().set(token2, ord2, Duration.ofMinutes(5));

        long[] cancelRollback = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_SECKILL_CANCEL_ROLLBACK,
                List.of(RedisKey.seckillStockBucket(actId, bucket2), usersKey, guard2, token2),
                userId2, ord2, 3600));
        assertEquals(0L, cancelRollback[0]);
        assertEquals(1L, cancelRollback[1]);
        assertNull(redisTemplate.opsForValue().get(token2));

        long[] cancelRetry = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_SECKILL_CANCEL_ROLLBACK,
                List.of(RedisKey.seckillStockBucket(actId, bucket2), usersKey, guard2, token2),
                userId2, ord2, 3600));
        assertEquals(1L, cancelRetry[0]);
        assertEquals(1L, cancelRetry[1]);

        String stateNormal = RedisKey.seckillSoldState(actId, "ORD-NORM");
        assertEquals(1L, luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_SECKILL_SOLD_STATE, List.of(stateNormal), "SOLD", 3600))[0]);
        assertEquals(0L, luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_SECKILL_SOLD_STATE, List.of(stateNormal), "SOLD", 3600))[0]);
        assertEquals(-1L, luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_SECKILL_SOLD_STATE, List.of(stateNormal), "CANCEL", 3600))[0]);
        assertEquals(0L, luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_SECKILL_SOLD_STATE, List.of(stateNormal), "CANCEL", 3600))[0]);

        String stateOutOfOrder = RedisKey.seckillSoldState(actId, "ORD-OOO");
        assertEquals(0L, luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_SECKILL_SOLD_STATE, List.of(stateOutOfOrder), "CANCEL", 3600))[0]);
        assertEquals(0L, luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_SECKILL_SOLD_STATE, List.of(stateOutOfOrder), "SOLD", 3600))[0]);
    }
}
