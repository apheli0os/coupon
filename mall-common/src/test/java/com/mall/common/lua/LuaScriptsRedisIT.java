package com.mall.common.lua;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.constants.RedisKey;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.common.seckill.SeckillReservationStore;
import com.mall.common.util.LuaScriptExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaScriptsRedisIT {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no");

    private static RedissonClient redissonClient;
    private static RedissonConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static LuaScriptExecutor luaExecutor;

    @BeforeAll
    static void startRedis() throws Exception {
        REDIS.start();
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redissonClient = Redisson.create(config);
        connectionFactory = new RedissonConnectionFactory(redissonClient);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        luaExecutor = new LuaScriptExecutor(redisTemplate);
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redissonClient != null) {
            redissonClient.shutdown();
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

        long[] incrPresent = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_COUPON_STOCK_INCR_IF_PRESENT,
                List.of(stockKey),
                5));
        assertEquals(1L, incrPresent[0]);
        assertEquals(7L, incrPresent[1]);
        assertEquals("7", redisTemplate.opsForValue().get(stockKey));

        String absentStockKey = RedisKey.couponStock(9999L);
        long[] incrAbsent = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_COUPON_STOCK_INCR_IF_PRESENT,
                List.of(absentStockKey),
                5));
        assertEquals(0L, incrAbsent[0]);
        assertNull(redisTemplate.opsForValue().get(absentStockKey));
    }

    @Test
    void seckillScripts_shouldEnforceBucketsGuardsAndSoldStateMachine() {
        long actId = 2001L;
        long userId = 6001L;
        int bucket = 0;
        String usersKey = RedisKey.seckillUsers(actId);
        String ownersKey = RedisKey.seckillReservationOwners(actId);
        redisTemplate.opsForValue().set(RedisKey.seckillStockBucket(actId, bucket), "0", Duration.ofMinutes(10));
        redisTemplate.opsForSet().add(usersKey, String.valueOf(userId));

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
                List.of(RedisKey.seckillStockBucket(actId, bucket), usersKey, token1, guard1, ownersKey),
                userId, 3600));
        assertEquals(1L, expireAfterRollback[0]);
        assertEquals("1", redisTemplate.opsForValue().get(RedisKey.seckillStockBucket(actId, bucket)));

        long userId2 = 6002L;
        redisTemplate.opsForValue().decrement(RedisKey.seckillStockBucket(actId, bucket));
        redisTemplate.opsForSet().add(usersKey, String.valueOf(userId2));
        String ord2 = "ORD-IT-2";
        String token2 = RedisKey.seckillOrderToken(actId, ord2);
        redisTemplate.opsForValue().set(token2, ord2, Duration.ofMinutes(5));
        long[] cancelRollback = cancelReservation(actId, userId2, ord2, bucket);
        assertEquals(0L, cancelRollback[0]);
        assertEquals(1L, cancelRollback[1]);
        assertNull(redisTemplate.opsForValue().get(token2));

        long[] cancelRetry = cancelReservation(actId, userId2, ord2, bucket);
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

    @Test
    void seckillReservation_shouldReleaseUnpublishedOrderAndRejectLateMessageAfterRebuy() {
        long activityId = 2201L;
        long userId = 6101L;
        String stockKey = initializeSeckill(activityId);
        String dueKey = RedisKey.seckillReservationsDue(activityId);
        SeckillReservationStore store = reservations();
        SeckillOrderMessage first = reservation("ORD-NO-PUBLISH", activityId, userId);
        SeckillOrderMessage second = reservation("ORD-REBUY", activityId, userId);

        assertEquals(0L, store.reserve(first)[0]);
        assertEquals("0", redisTemplate.opsForValue().get(stockKey));
        assertEquals("PENDING", redisTemplate.opsForHash().get(
                RedisKey.seckillReservation(activityId, first.getOrderId()), "state"));
        assertEquals(-2L, store.reserve(second)[0]);
        redisTemplate.opsForZSet().add(dueKey, first.getOrderId(), 1);
        assertEquals(Set.of(first.getOrderId()), store.dueOrderIds(activityId, 100));
        assertFalse(store.claim(first));
        assertTrue(store.recover(activityId, first.getOrderId()).isEmpty());
        assertEquals("1", redisTemplate.opsForValue().get(stockKey));
        assertFalse(redisTemplate.hasKey(dueKey));

        assertEquals(0L, store.reserve(second)[0]);
        assertFalse(store.claim(first));
        assertFalse(store.confirmClaimOrRelease(activityId, first.getOrderId()));
        String legacyOrder = "ORD-LEGACY";
        long[] legacyResult = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_SECKILL_EXPIRE_COMPENSATE,
                List.of(stockKey, RedisKey.seckillUsers(activityId),
                        RedisKey.seckillOrderToken(activityId, legacyOrder),
                        RedisKey.seckillOrderRollbackGuard(activityId, legacyOrder),
                        RedisKey.seckillReservationOwners(activityId)),
                userId, 3600));
        assertEquals(2L, legacyResult[0]);
        assertEquals("0", redisTemplate.opsForValue().get(stockKey));
        assertEquals(second.getOrderId(), redisTemplate.opsForHash().get(
                RedisKey.seckillReservationOwners(activityId), String.valueOf(userId)));
    }

    @Test
    void seckillReservation_shouldReplayProcessingWithoutReturningStockAndCleanUpAfterCommit() {
        long activityId = 2202L;
        long userId = 6102L;
        String stockKey = initializeSeckill(activityId);
        String dueKey = RedisKey.seckillReservationsDue(activityId);
        SeckillReservationStore store = reservations();
        SeckillOrderMessage original = reservation("ORD-PROCESSING", activityId, userId);
        assertEquals(0L, store.reserve(original)[0]);
        assertTrue(store.claim(original));
        assertTrue(store.confirmClaimOrRelease(activityId, original.getOrderId()));
        redisTemplate.opsForZSet().add(dueKey, original.getOrderId(), 1);

        assertEquals(original, store.recover(activityId, original.getOrderId()).orElseThrow());
        assertTrue(store.recover(activityId, original.getOrderId()).isEmpty());
        assertEquals("0", redisTemplate.opsForValue().get(stockKey));
        assertTrue(store.claim(original));
        store.complete(activityId, original.getOrderId());
        assertTrue(store.confirmClaimOrRelease(activityId, original.getOrderId()));
        assertFalse(store.claim(original));
        assertFalse(redisTemplate.hasKey(dueKey));

        assertEquals(0L, cancelReservation(activityId, userId, original.getOrderId(), 0)[0]);
        SeckillOrderMessage next = reservation("ORD-AFTER-CANCEL", activityId, userId);
        assertEquals(0L, store.reserve(next)[0]);
        redisTemplate.delete(RedisKey.seckillOrderRollbackGuard(activityId, original.getOrderId()));
        assertEquals(1L, cancelReservation(activityId, userId, original.getOrderId(), 0)[0]);
        assertTrue(store.claim(next));
        assertEquals("0", redisTemplate.opsForValue().get(stockKey));
    }

    private long[] cancelReservation(long activityId, long userId, String orderId, int bucket) {
        return luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_SECKILL_CANCEL_ROLLBACK,
                List.of(RedisKey.seckillStockBucket(activityId, bucket),
                        RedisKey.seckillUsers(activityId),
                        RedisKey.seckillOrderRollbackGuard(activityId, orderId),
                        RedisKey.seckillOrderToken(activityId, orderId),
                        RedisKey.seckillReservationOwners(activityId),
                        RedisKey.seckillReservationsDue(activityId),
                        RedisKey.seckillReservation(activityId, orderId)),
                userId, orderId, 3600));
    }

    private String initializeSeckill(long activityId) {
        String stockKey = RedisKey.seckillStockBucket(activityId, 0);
        redisTemplate.opsForValue().set(stockKey, "1", Duration.ofMinutes(10));
        redisTemplate.opsForValue().set(RedisKey.seckillStatus(activityId), RedisKey.STATUS_ACTIVE, Duration.ofMinutes(10));
        return stockKey;
    }

    private SeckillReservationStore reservations() {
        return new SeckillReservationStore(luaExecutor, redisTemplate, new ObjectMapper().findAndRegisterModules());
    }

    private SeckillOrderMessage reservation(String orderId, long activityId, long userId) {
        SeckillOrderMessage message = new SeckillOrderMessage();
        message.setOrderId(orderId);
        message.setActivityId(activityId);
        message.setUserId(userId);
        message.setStockBucket(0);
        message.setProductCode("SKU-RESERVE");
        message.setSeckillPrice(new BigDecimal("19.90"));
        message.setCreateTime(LocalDateTime.of(2026, 11, 25, 8, 0));
        message.setQuantity(1);
        message.setReservationTracked(true);
        return message;
    }
}
