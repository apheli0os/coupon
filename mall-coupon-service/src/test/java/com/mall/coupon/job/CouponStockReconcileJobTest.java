package com.mall.coupon.job;

import com.mall.common.constants.RedisKey;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.domain.enums.CouponEnums;
import com.mall.coupon.mapper.CouponTemplateMapper;
import com.mall.coupon.mapper.UserCouponMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponStockReconcileJobTest {

    @Test
    @SuppressWarnings("unchecked")
    void reconcileSingle_shouldRepairDriftedRedisStockAndClaimsWhenNoInFlightTokens() {
        CouponTemplateMapper templateMapper = mock(CouponTemplateMapper.class);
        UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForHash()).thenReturn(hashOps);
        Cursor<String> emptyCursor = mock(Cursor.class);
        when(emptyCursor.hasNext()).thenReturn(false);
        when(redis.scan(any(ScanOptions.class))).thenReturn(emptyCursor);
        RedissonClient redisson = mock(RedissonClient.class);

        CouponTemplate tpl = new CouponTemplate();
        tpl.setId(100L);
        tpl.setTotal(10);
        tpl.setStatus(CouponEnums.TEMPLATE_ACTIVE);
        tpl.setEndTime(LocalDateTime.now().plusDays(1));

        when(userCouponMapper.countByTemplate(100L)).thenReturn(2L);
        when(valueOps.get(RedisKey.couponStock(100L))).thenReturn("7");
        when(userCouponMapper.countGroupedByUser(100L)).thenReturn(List.of(
                Map.of("userId", 1001L, "cnt", 1L),
                Map.of("userId", 1002L, "cnt", 1L)));

        CouponStockReconcileJob job = new CouponStockReconcileJob(templateMapper, userCouponMapper, redis, redisson);
        job.reconcileSingle(tpl, LocalDateTime.now());

        verify(valueOps).set(eq(RedisKey.couponStock(100L)), eq("8"), any(Duration.class));
        verify(redis).delete(RedisKey.couponClaimHash(100L));
        verify(hashOps).putAll(eq(RedisKey.couponClaimHash(100L)), eq(Map.of("1001", "1", "1002", "1")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reconcileSingle_shouldSkipWhenInFlightTokensExist() {
        CouponTemplateMapper templateMapper = mock(CouponTemplateMapper.class);
        UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        Cursor<String> busyCursor = mock(Cursor.class);
        when(busyCursor.hasNext()).thenReturn(true);
        when(redis.scan(any(ScanOptions.class))).thenReturn(busyCursor);
        RedissonClient redisson = mock(RedissonClient.class);

        CouponTemplate tpl = new CouponTemplate();
        tpl.setId(100L);
        tpl.setTotal(10);
        tpl.setStatus(CouponEnums.TEMPLATE_ACTIVE);
        tpl.setEndTime(LocalDateTime.now().plusDays(1));

        CouponStockReconcileJob job = new CouponStockReconcileJob(templateMapper, userCouponMapper, redis, redisson);
        job.reconcileSingle(tpl, LocalDateTime.now());

        verify(userCouponMapper, never()).countByTemplate(any());
        verify(valueOps, never()).set(any(), any(), any(Duration.class));
    }
}
