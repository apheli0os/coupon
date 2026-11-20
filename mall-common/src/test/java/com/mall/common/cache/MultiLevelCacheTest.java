package com.mall.common.cache;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiLevelCacheTest {

    @Test
    @SuppressWarnings("unchecked")
    void get_shouldPreventCacheBreakdownWithSingleflightLock() throws Exception {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("hot:key")).thenReturn(null);

        MultiLevelCache cache = new MultiLevelCache(
                redisTemplate, mock(StringRedisTemplate.class), mock(RedisConnectionFactory.class));

        AtomicInteger dbLoads = new AtomicInteger(0);
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        String value = cache.get("hot:key", String.class, () -> {
                            dbLoads.incrementAndGet();
                            try {
                                Thread.sleep(40);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                            return "loaded-value";
                        }, Duration.ofMinutes(5));
                        assertEquals("loaded-value", value);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await();
            start.countDown();
            done.await();
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, dbLoads.get(), "高并发同 Key 缓存击穿时只应回源 DB 一次");
        verify(valueOps, times(1)).set(eq("hot:key"), eq("loaded-value"), any(Duration.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void get_shouldCacheNullMarkerToPreventPenetration() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("missing:key")).thenReturn(null);

        MultiLevelCache cache = new MultiLevelCache(
                redisTemplate, mock(StringRedisTemplate.class), mock(RedisConnectionFactory.class));
        AtomicInteger loads = new AtomicInteger(0);

        assertNull(cache.get("missing:key", String.class, () -> {
            loads.incrementAndGet();
            return null;
        }, Duration.ofMinutes(5)));
        assertNull(cache.get("missing:key", String.class, () -> {
            loads.incrementAndGet();
            return null;
        }, Duration.ofMinutes(5)));

        assertEquals(1, loads.get(), "空值哨兵应被缓存，第二次命中本地缓存不再回源 DB");
    }
}
