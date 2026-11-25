package com.mall.coupon.cache;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mall.common.constants.RedisKey;
import com.mall.common.metrics.BusinessMetrics;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.mapper.CouponTemplateMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponTemplateBloomFilterTest {

    @Test
    @SuppressWarnings("unchecked")
    void warmupAddAndMightContain_shouldFailOpenBeforeWarmupRejectMissingAndFailOpenOnEvictionOrError() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), CouponTemplate.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RBloomFilter<Long> bloom = mock(RBloomFilter.class);
        when(redisson.<Long>getBloomFilter(RedisKey.couponTemplateBloomFilter())).thenReturn(bloom);
        CouponTemplateMapper mapper = mock(CouponTemplateMapper.class);
        BusinessMetrics metrics = mock(BusinessMetrics.class);

        CouponTemplate t1 = new CouponTemplate();
        t1.setId(1001L);
        when(mapper.selectList(any())).thenReturn(List.of(t1));
        when(bloom.isExists()).thenReturn(true);
        when(bloom.contains(1001L)).thenReturn(true);
        when(bloom.contains(9999L)).thenReturn(false);

        CouponTemplateBloomFilter filter = new CouponTemplateBloomFilter(redisson, mapper, metrics);
        assertTrue(filter.mightContain(9999L));
        assertFalse(filter.mightContain(null));
        filter.add(1001L);
        verify(bloom, never()).add(1001L);

        filter.warmup();
        verify(bloom).tryInit(100_000L, 0.001D);
        verify(bloom).add(1001L);
        assertTrue(filter.mightContain(1001L));
        assertFalse(filter.mightContain(9999L));
        verify(metrics).recordCouponBloomRejected();

        filter.add(null);
        filter.add(1002L);
        verify(bloom).add(1002L);
        verify(bloom, never()).add((Long) null);

        when(bloom.isExists()).thenReturn(false);
        filter.add(1003L);
        verify(bloom, never()).add(1003L);
        assertTrue(filter.mightContain(9999L));

        when(bloom.isExists()).thenReturn(true);
        filter.warmup();
        doThrow(new RuntimeException("redis error")).when(bloom).add(1004L);
        filter.add(1004L);
        assertTrue(filter.mightContain(9999L));
    }

    @Test
    void warmup_shouldFailOpenWhenRedisThrows() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), CouponTemplate.class);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<Long>getBloomFilter(RedisKey.couponTemplateBloomFilter()))
                .thenThrow(new RuntimeException("redis unavailable"));
        CouponTemplateMapper mapper = mock(CouponTemplateMapper.class);
        BusinessMetrics metrics = mock(BusinessMetrics.class);

        CouponTemplateBloomFilter filter = new CouponTemplateBloomFilter(redisson, mapper, metrics);
        filter.warmup();
        assertTrue(filter.mightContain(1001L));
    }
}
