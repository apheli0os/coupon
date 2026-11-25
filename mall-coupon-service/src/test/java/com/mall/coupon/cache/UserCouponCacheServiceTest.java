package com.mall.coupon.cache;

import com.mall.common.constants.RedisKey;
import com.mall.coupon.domain.entity.UserCoupon;
import com.mall.coupon.mapper.UserCouponMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserCouponCacheServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void listUnusedCoupons_shouldUseAtomicReadMissPopulateAndHitPaths() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(zSetOps);
        UserCouponMapper mapper = mock(UserCouponMapper.class);
        UserCouponCacheService cacheService = new UserCouponCacheService(redis, mapper);

        LocalDateTime now = LocalDateTime.now();
        String key = RedisKey.userUnusedCouponZSet(1001L);
        String epochKey = RedisKey.userUnusedCouponEpoch(1001L);

        assertTrue(cacheService.listUnusedCoupons(null, now).isEmpty());

        UserCoupon coupon = new UserCoupon();
        coupon.setId(501L);
        coupon.setUserId(1001L);
        coupon.setExpireTime(now.plusDays(3));

        when(redis.execute(ArgumentMatchers.<RedisScript<List>>any(), eq(List.of(key)), any(), any(), any()))
                .thenReturn(List.of("__MISS__"));
        when(valueOps.get(epochKey)).thenReturn("2");
        when(mapper.selectList(any())).thenReturn(List.of(coupon));
        List<UserCoupon> loaded = cacheService.listUnusedCoupons(1001L, now);
        assertEquals(1, loaded.size());
        verify(redis).execute(
                ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of(key, epochKey)),
                eq("2"),
                any(),
                eq("501"),
                any());

        when(redis.execute(ArgumentMatchers.<RedisScript<List>>any(), eq(List.of(key)), any(), any(), any()))
                .thenReturn(List.of("-1", "501"));
        List<UserCoupon> hit = cacheService.listUnusedCoupons(1001L, now);
        assertEquals(1, hit.size());

        when(redis.execute(ArgumentMatchers.<RedisScript<List>>any(), eq(List.of(key)), any(), any(), any()))
                .thenReturn(List.of("-1"));
        assertTrue(cacheService.listUnusedCoupons(1001L, now).isEmpty());
        verify(mapper, times(2)).selectList(any());

        cacheService.removeUnusedCoupon(null, 501L);
        cacheService.removeUnusedCoupon(1001L, 501L);
        verify(zSetOps).remove(key, "501");

        cacheService.evictUnusedCoupons(null);
        cacheService.evictUnusedCoupons(1001L);
        verify(redis).execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of(key, epochKey)), eq("300"));
        verify(redis, never()).delete("wrong");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listUnusedCouponsAndInvalidation_shouldPopulateEmptySentinelFallbackAndSwallowRedisErrors() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(zSetOps);
        UserCouponMapper mapper = mock(UserCouponMapper.class);
        UserCouponCacheService cacheService = new UserCouponCacheService(redis, mapper);

        LocalDateTime now = LocalDateTime.now();
        String key = RedisKey.userUnusedCouponZSet(1002L);
        String epochKey = RedisKey.userUnusedCouponEpoch(1002L);

        when(redis.execute(ArgumentMatchers.<RedisScript<List>>any(), eq(List.of(key)), any(), any(), any()))
                .thenReturn(List.of("__MISS__"));
        when(valueOps.get(epochKey)).thenReturn(null);
        when(mapper.selectList(any())).thenReturn(List.of());
        assertTrue(cacheService.listUnusedCoupons(1002L, now).isEmpty());
        verify(redis).execute(
                ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of(key, epochKey)),
                eq("0"),
                eq("60"),
                eq("-1"),
                any());

        UserCoupon coupon = new UserCoupon();
        coupon.setId(777L);
        when(redis.execute(ArgumentMatchers.<RedisScript<List>>any(), eq(List.of(key)), any(), any(), any()))
                .thenThrow(new RuntimeException("redis read fail"));
        when(mapper.selectList(any())).thenReturn(List.of(coupon));
        assertEquals(1, cacheService.listUnusedCoupons(1002L, now).size());

        when(zSetOps.remove(key, "777")).thenThrow(new RuntimeException("redis write fail"));
        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of(key, epochKey)), eq("300")))
                .thenThrow(new RuntimeException("redis delete fail"));
        cacheService.removeUnusedCoupon(1002L, 777L);
        cacheService.evictUnusedCoupons(1002L);
    }
}
