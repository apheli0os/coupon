package com.mall.coupon.job;

import com.mall.common.constants.RedisKey;
import com.mall.common.metrics.BusinessMetrics;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.domain.enums.CouponEnums;
import com.mall.coupon.mapper.CouponTemplateMapper;
import com.mall.coupon.mapper.UserCouponMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponStockReconcileJobTest {

    @Test
    @SuppressWarnings("unchecked")
    void reconcileSingle_shouldDebounceFirstRoundAutoFixSmallSecondRoundViaCasAndAlertLargeSecondRound()
            throws Exception {
        CouponTemplateMapper templateMapper = mock(CouponTemplateMapper.class);
        UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        Cursor<String> emptyCursor = mock(Cursor.class);
        when(emptyCursor.hasNext()).thenReturn(false);
        when(redis.scan(any(ScanOptions.class))).thenReturn(emptyCursor);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(RedisKey.couponTemplateLock(100L))).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        LuaScriptExecutor luaExecutor = mock(LuaScriptExecutor.class);
        BusinessMetrics metrics = mock(BusinessMetrics.class);

        CouponTemplate tpl = new CouponTemplate();
        tpl.setId(100L);
        tpl.setTotal(10);
        tpl.setStatus(CouponEnums.TEMPLATE_ACTIVE);
        tpl.setEndTime(LocalDateTime.now().plusDays(1));
        when(templateMapper.selectById(100L)).thenReturn(tpl);

        when(userCouponMapper.countByTemplate(100L)).thenReturn(2L);
        when(valueOps.get(RedisKey.couponStock(100L))).thenReturn("7");
        when(valueOps.get(RedisKey.couponReconcilePending(100L))).thenReturn(null, "UNDER", "UNDER", "UNDER");
        when(userCouponMapper.countGroupedByUser(100L)).thenReturn(List.of(
                Map.of("userId", 1001L, "cnt", 1L),
                Map.of("userId", 1002L, "cnt", 1L)));

        CouponStockReconcileJob job = new CouponStockReconcileJob(
                templateMapper, userCouponMapper, redis, redisson, luaExecutor, metrics, 2L);

        job.reconcileSingle(tpl, LocalDateTime.now());
        verify(valueOps).set(eq(RedisKey.couponReconcilePending(100L)), eq("UNDER"), any(Duration.class));
        verify(luaExecutor, never()).execute(any(), any(), any());

        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_COUPON_RECONCILE_STOCK), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(List.of(1L, 8L));
        when(luaExecutor.parse(List.of(1L, 8L))).thenReturn(new long[]{1L, 8L});

        job.reconcileSingle(tpl, LocalDateTime.now());
        verify(metrics).recordCouponReconcileAutoFix();

        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_COUPON_RECONCILE_STOCK), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(List.of(0L, 0L));
        when(luaExecutor.parse(List.of(0L, 0L))).thenReturn(new long[]{0L, 0L});
        job.reconcileSingle(tpl, LocalDateTime.now());
        verify(metrics, org.mockito.Mockito.times(1)).recordCouponReconcileAutoFix();

        tpl.setTotal(200);
        when(valueOps.get(RedisKey.couponStock(100L))).thenReturn("100");
        job.reconcileSingle(tpl, LocalDateTime.now());
        verify(metrics).recordCouponReconcileAlert();
        verify(redis).delete(RedisKey.couponReconcilePending(100L));

        tpl.setTotal(10);
        when(valueOps.get(RedisKey.couponStock(100L))).thenReturn("8");
        job.reconcileSingle(tpl, LocalDateTime.now());
        verify(redis, org.mockito.Mockito.times(2)).delete(RedisKey.couponReconcilePending(100L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reconcileSingle_shouldSkipInvalidStockInFlightTokensOrContestedLockAndIsolateBatchFailures()
            throws Exception {
        CouponTemplateMapper templateMapper = mock(CouponTemplateMapper.class);
        UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        Cursor<String> busyCursor = mock(Cursor.class);
        when(busyCursor.hasNext()).thenReturn(true);
        when(redis.scan(any(ScanOptions.class))).thenReturn(busyCursor);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(RedisKey.couponTemplateLock(100L))).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true, false, true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        LuaScriptExecutor luaExecutor = mock(LuaScriptExecutor.class);
        BusinessMetrics metrics = mock(BusinessMetrics.class);

        CouponTemplate tpl = new CouponTemplate();
        tpl.setId(100L);
        tpl.setTotal(10);
        tpl.setStatus(CouponEnums.TEMPLATE_ACTIVE);
        tpl.setEndTime(LocalDateTime.now().plusDays(1));
        when(templateMapper.selectById(100L)).thenReturn(tpl);

        CouponStockReconcileJob job = new CouponStockReconcileJob(
                templateMapper, userCouponMapper, redis, redisson, luaExecutor, metrics, 50L);
        job.reconcileSingle(tpl, LocalDateTime.now());
        job.reconcileSingle(tpl, LocalDateTime.now());

        Cursor<String> emptyCursor = mock(Cursor.class);
        when(emptyCursor.hasNext()).thenReturn(false);
        when(redis.scan(any(ScanOptions.class))).thenReturn(emptyCursor);
        when(userCouponMapper.countByTemplate(100L)).thenReturn(1L);
        when(valueOps.get(RedisKey.couponStock(100L))).thenReturn("not-a-number");
        job.reconcileSingle(tpl, LocalDateTime.now());
        verify(redis).delete(RedisKey.couponReconcilePending(100L));
        verify(luaExecutor, never()).execute(any(), any(), any());

        RLock jobLock = mock(RLock.class);
        when(redisson.getLock(RedisKey.couponReconcileJobLock())).thenReturn(jobLock);
        when(jobLock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(jobLock.isHeldByCurrentThread()).thenReturn(true);
        CouponTemplate badTpl = new CouponTemplate();
        badTpl.setId(101L);
        CouponTemplate goodTpl = new CouponTemplate();
        goodTpl.setId(102L);
        when(templateMapper.selectList(any())).thenReturn(List.of(badTpl, goodTpl));
        RLock badLock = mock(RLock.class);
        RLock goodLock = mock(RLock.class);
        when(redisson.getLock(RedisKey.couponTemplateLock(101L))).thenReturn(badLock);
        when(redisson.getLock(RedisKey.couponTemplateLock(102L))).thenReturn(goodLock);
        when(badLock.tryLock(0, TimeUnit.SECONDS)).thenThrow(new RuntimeException("redis err"));
        when(goodLock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        job.reconcileActiveTemplates();
        verify(goodLock).tryLock(0, TimeUnit.SECONDS);
    }
}
