package com.mall.coupon.job;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mall.common.cache.MultiLevelCache;
import com.mall.common.constants.RedisKey;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.domain.enums.CouponEnums;
import com.mall.coupon.mapper.CouponTemplateMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponTemplateActivationJobTest {

    @Test
    @SuppressWarnings("unchecked")
    void syncTemplateStatus_shouldEndPreheatedTemplateThatMissedActiveWindowWithoutTouchingDraft() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CouponTemplate.class);
        CouponTemplateMapper mapper = mock(CouponTemplateMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        MultiLevelCache cache = mock(MultiLevelCache.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        CouponTemplate preheatedActive = new CouponTemplate();
        preheatedActive.setId(99L);
        preheatedActive.setStatus(CouponEnums.TEMPLATE_DRAFT);
        preheatedActive.setStartTime(LocalDateTime.now().minusMinutes(5));
        preheatedActive.setEndTime(LocalDateTime.now().plusHours(2));

        CouponTemplate preheatedExpired = new CouponTemplate();
        preheatedExpired.setId(100L);
        preheatedExpired.setStatus(CouponEnums.TEMPLATE_DRAFT);
        preheatedExpired.setStartTime(LocalDateTime.now().minusHours(2));
        preheatedExpired.setEndTime(LocalDateTime.now().minusMinutes(5));

        CouponTemplate draftExpired = new CouponTemplate();
        draftExpired.setId(101L);
        draftExpired.setStatus(CouponEnums.TEMPLATE_DRAFT);
        draftExpired.setStartTime(LocalDateTime.now().minusHours(2));
        draftExpired.setEndTime(LocalDateTime.now().minusMinutes(5));

        when(mapper.selectById(99L)).thenReturn(preheatedActive);
        when(mapper.selectById(100L)).thenReturn(preheatedExpired);
        when(mapper.selectById(101L)).thenReturn(draftExpired);
        when(valueOps.get(RedisKey.couponStatus(99L))).thenReturn(RedisKey.STATUS_PREHEATED);
        when(valueOps.get(RedisKey.couponStatus(100L))).thenReturn(RedisKey.STATUS_PREHEATED);
        when(valueOps.get(RedisKey.couponStatus(101L))).thenReturn(null);
        when(mapper.updateStatusIfMatch(anyLong(), anyInt(), anyInt(), any())).thenReturn(1);

        List<CouponTemplate> expiredRows = List.of(preheatedExpired, draftExpired);
        AtomicInteger queryCount = new AtomicInteger(0);
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(inv -> {
            int call = queryCount.incrementAndGet();
            if (call == 1) {
                return List.of(preheatedActive);
            }
            LambdaQueryWrapper<?> wrapper = inv.getArgument(0);
            wrapper.getSqlSegment();
            Set<Object> params = wrapper.getParamNameValuePairs().values().stream()
                    .flatMap(v -> v instanceof Collection<?> c ? c.stream() : Stream.of(v))
                    .collect(Collectors.toSet());
            return expiredRows.stream()
                    .filter(t -> params.contains(t.getStatus()))
                    .toList();
        });

        CouponTemplateActivationJob job = new CouponTemplateActivationJob(mapper, redis, cache, redisson);
        job.syncTemplateStatus();

        verify(mapper).updateStatusIfMatch(
                eq(99L), eq(CouponEnums.TEMPLATE_DRAFT), eq(CouponEnums.TEMPLATE_ACTIVE), any());
        verify(valueOps).set(eq(RedisKey.couponStatus(99L)), eq(RedisKey.STATUS_ACTIVE), any(Duration.class));
        verify(mapper).updateStatusIfMatch(
                eq(100L), eq(CouponEnums.TEMPLATE_DRAFT), eq(CouponEnums.TEMPLATE_ENDED), any());
        verify(valueOps).set(eq(RedisKey.couponStatus(100L)), eq(RedisKey.STATUS_ENDED), any(Duration.class));
        verify(mapper, never()).updateStatusIfMatch(eq(101L), anyInt(), anyInt(), any());
        verify(valueOps, never()).set(eq(RedisKey.couponStatus(101L)), any(), any(Duration.class));
    }
}
