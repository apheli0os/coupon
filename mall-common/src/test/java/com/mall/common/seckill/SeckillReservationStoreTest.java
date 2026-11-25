package com.mall.common.seckill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mall.common.constants.RedisKey;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.common.util.LuaScriptExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillReservationStoreTest {

    private static final long ACTIVITY = 2101L;
    private static final long USER_ID = 6001L;
    private static final String ORDER = "ORD-RESERVATION";
    private static final long RETRY_MS = Duration.ofSeconds(30).toMillis();
    private static final String DUE_KEY = RedisKey.seckillReservationsDue(ACTIVITY);
    private static final String RECORD_KEY = RedisKey.seckillReservation(ACTIVITY, ORDER);

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final LuaScriptExecutor lua = mock(LuaScriptExecutor.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SeckillReservationStore store = new SeckillReservationStore(lua, redis, mapper);

    @BeforeEach
    void useProductionScriptResultParsing() {
        when(lua.parse(anyList())).thenCallRealMethod();
    }

    @Test
    void shouldWriteStockAndRecoverableMessageInOneLuaCall() throws Exception {
        SeckillOrderMessage message = message();
        List<String> keys = new ArrayList<>(IntStream.range(0, RedisKey.SECKILL_STOCK_BUCKETS)
                .mapToObj(bucket -> RedisKey.seckillStockBucket(ACTIVITY, bucket)).toList());
        keys.addAll(List.of(RedisKey.seckillUsers(ACTIVITY), RedisKey.seckillStatus(ACTIVITY),
                RedisKey.seckillReservationOwners(ACTIVITY), RECORD_KEY, DUE_KEY));
        long pendingMs = Duration.ofSeconds(RedisKey.TOKEN_TTL_SECONDS).toMillis();
        when(lua.execute(eq(LuaScriptExecutor.SCRIPT_SECKILL), eq(keys), eq(USER_ID), eq(RedisKey.bucketOf(USER_ID)),
                eq(RedisKey.SECKILL_STOCK_BUCKETS), eq(ORDER), eq(pendingMs), anyString())).thenReturn(List.of(0L, 4L));

        assertArrayEquals(new long[]{0, 4}, store.reserve(message));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(lua).execute(eq(LuaScriptExecutor.SCRIPT_SECKILL), eq(keys), eq(USER_ID), eq(RedisKey.bucketOf(USER_ID)),
                eq(RedisKey.SECKILL_STOCK_BUCKETS), eq(ORDER), eq(pendingMs), payload.capture());
        assertEquals(message, mapper.readValue(payload.getValue(), SeckillOrderMessage.class));
    }

    @Test
    void shouldClaimWithExactOwnerAndCompleteAfterCommit() {
        List<String> keys = List.of(RECORD_KEY, DUE_KEY);
        when(lua.execute(LuaScriptExecutor.SCRIPT_SECKILL_RESERVATION_CLAIM, keys, ORDER, USER_ID, 4, RETRY_MS))
                .thenReturn(List.of(1L, 0L)).thenReturn(List.of(0L, 0L));

        assertTrue(store.claim(message()));
        assertFalse(store.claim(message()));
        store.complete(ACTIVITY, ORDER);

        verify(lua).execute(LuaScriptExecutor.SCRIPT_SECKILL_RESERVATION_COMPLETE,
                keys, ORDER, RedisKey.CONSUMED_TOKEN_TTL_SECONDS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldListDueReservationsAndReleaseUnclaimedOrders() {
        ZSetOperations<String, String> dueOrders = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(dueOrders);
        when(dueOrders.rangeByScore(eq(DUE_KEY), eq(0.0), anyDouble(), eq(0L), eq(100L)))
                .thenReturn(Set.of(ORDER)).thenReturn(null);
        whenResolutionReturns(RETRY_MS, 0, 4);
        whenResolutionReturns(0, 0, 4);

        assertEquals(Set.of(ORDER), store.dueOrderIds(ACTIVITY, 100));
        assertTrue(store.dueOrderIds(ACTIVITY, 100).isEmpty());
        assertTrue(store.recover(ACTIVITY, ORDER).isEmpty());
        assertFalse(store.confirmClaimOrRelease(ACTIVITY, ORDER));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPreserveClaimedStockAndReplayTheOriginalMessage() throws Exception {
        HashOperations<String, String, String> record = mock(HashOperations.class);
        when(redis.<String, String>opsForHash()).thenReturn(record);
        whenResolutionReturns(RETRY_MS, 3, 4);
        whenResolutionReturns(0, 2, 0);
        SeckillOrderMessage original = message();
        original.setStockBucket(null);
        when(record.get(RECORD_KEY, "message")).thenReturn(mapper.writeValueAsString(original), null, "{");

        assertTrue(store.confirmClaimOrRelease(ACTIVITY, ORDER));
        assertEquals(message(), store.recover(ACTIVITY, ORDER).orElseThrow());
        assertTrue(store.recover(ACTIVITY, ORDER).isEmpty());
        assertThrows(IllegalStateException.class, () -> store.recover(ACTIVITY, ORDER));
    }

    private void whenResolutionReturns(long retryMs, long code, long bucket) {
        when(lua.execute(eq(LuaScriptExecutor.SCRIPT_SECKILL_RESERVATION_RESOLVE), anyList(),
                eq(ORDER), eq(RedisKey.SECKILL_STOCK_BUCKETS), eq(retryMs))).thenReturn(List.of(code, bucket));
    }

    private SeckillOrderMessage message() {
        SeckillOrderMessage message = new SeckillOrderMessage();
        message.setActivityId(ACTIVITY);
        message.setUserId(USER_ID);
        message.setOrderId(ORDER);
        message.setStockBucket(4);
        message.setProductCode("SKU-TEST");
        message.setQuantity(1);
        message.setSeckillPrice(new BigDecimal("19.90"));
        message.setCreateTime(LocalDateTime.of(2026, 11, 25, 10, 0));
        message.setReservationTracked(true);
        return message;
    }
}
