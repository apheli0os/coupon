package com.mall.coupon.service.impl;

import com.mall.common.ResultCode;
import com.mall.common.cache.MultiLevelCache;
import com.mall.common.constants.RedisKey;
import com.mall.common.exception.BizException;
import com.mall.common.util.IdGenerator;
import com.mall.coupon.domain.dto.CouponTemplateCreateDTO;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.domain.enums.CouponEnums;
import com.mall.coupon.mapper.CouponTemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponTemplateServiceImplTest {

    private CouponTemplateMapper templateMapper;
    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOps;
    private RedissonClient redissonClient;
    private RLock rLock;
    private IdGenerator idGenerator;
    private MultiLevelCache multiLevelCache;
    private TransactionTemplate transactionTemplate;
    private CouponTemplateServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        templateMapper = mock(CouponTemplateMapper.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        redissonClient = mock(RedissonClient.class);
        rLock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        idGenerator = mock(IdGenerator.class);
        multiLevelCache = mock(MultiLevelCache.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
        service = new CouponTemplateServiceImpl(
                templateMapper, stringRedisTemplate, redissonClient,
                idGenerator, multiLevelCache, transactionTemplate);
    }

    @Test
    void create_shouldRejectDiscountRateGreaterThanOne() {
        CouponTemplateCreateDTO dto = new CouponTemplateCreateDTO();
        dto.setName("非法折扣券");
        dto.setType(CouponEnums.TYPE_DISCOUNT);
        dto.setDiscountValue(new BigDecimal("1.50"));
        dto.setTotal(100);
        dto.setPerUserLimit(1);
        dto.setValidDays(7);
        dto.setStartTime(LocalDateTime.now().plusHours(1));
        dto.setEndTime(LocalDateTime.now().plusDays(1));

        BizException ex = assertThrows(BizException.class, () -> service.create(dto));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void publish_shouldNotResetStockOrDeleteClaimHashWhenAlreadyActiveAndPreheated() {
        CouponTemplate t = new CouponTemplate();
        t.setId(1001L);
        t.setTotal(1000);
        t.setReceived(50);
        t.setStatus(CouponEnums.TEMPLATE_ACTIVE);
        t.setStartTime(LocalDateTime.now().minusHours(1));
        t.setEndTime(LocalDateTime.now().plusDays(1));
        when(templateMapper.selectById(1001L)).thenReturn(t);
        when(stringRedisTemplate.hasKey(RedisKey.couponStock(1001L))).thenReturn(true);

        service.publish(1001L);

        verify(valueOps, never()).set(eq(RedisKey.couponStock(1001L)), anyString(), any(Duration.class));
        verify(stringRedisTemplate, never()).delete(RedisKey.couponClaimHash(1001L));
        verify(rLock).unlock();
    }
}
