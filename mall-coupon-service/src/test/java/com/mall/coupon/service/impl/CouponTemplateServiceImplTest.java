package com.mall.coupon.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.PageResult;
import com.mall.common.ResultCode;
import com.mall.common.cache.MultiLevelCache;
import com.mall.common.constants.RedisKey;
import com.mall.common.exception.BizException;
import com.mall.common.util.IdGenerator;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.coupon.cache.CouponTemplateBloomFilter;
import com.mall.coupon.domain.dto.CouponTemplateCreateDTO;
import com.mall.coupon.domain.dto.CouponTemplatePageQueryDTO;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.domain.entity.CouponTemplateAuditLog;
import com.mall.coupon.domain.enums.CouponEnums;
import com.mall.coupon.mapper.CouponTemplateMapper;
import com.mall.coupon.service.CouponTemplateAuditService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
    private LuaScriptExecutor luaExecutor;
    private CouponTemplateBloomFilter bloomFilter;
    private CouponTemplateAuditService auditService;
    private CouponTemplateServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), CouponTemplate.class);
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
        doAnswer(inv -> {
            Consumer<TransactionStatus> cb = inv.getArgument(0);
            cb.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        luaExecutor = mock(LuaScriptExecutor.class);
        bloomFilter = mock(CouponTemplateBloomFilter.class);
        when(bloomFilter.mightContain(anyLong())).thenReturn(true);
        auditService = mock(CouponTemplateAuditService.class);
        service = new CouponTemplateServiceImpl(
                templateMapper, stringRedisTemplate, redissonClient,
                idGenerator, multiLevelCache, transactionTemplate, luaExecutor,
                bloomFilter, auditService);
    }

    @Test
    void create_shouldPersistCapAndProductScopeForValidTemplates() {
        when(idGenerator.nextId()).thenReturn(2001L);
        CouponTemplateCreateDTO dto = new CouponTemplateCreateDTO();
        dto.setName("指定商品85折券");
        dto.setType(CouponEnums.TYPE_DISCOUNT);
        dto.setDiscountValue(new BigDecimal("0.85"));
        dto.setMaxDiscountAmount(new BigDecimal("200.00"));
        dto.setThreshold(new BigDecimal("100.00"));
        dto.setTarget(CouponEnums.TARGET_PRODUCT);
        dto.setProductCode(" SKU-2026-001 ");
        dto.setTotal(500);
        dto.setPerUserLimit(1);
        dto.setValidDays(7);
        dto.setStartTime(LocalDateTime.now().plusHours(1));
        dto.setEndTime(LocalDateTime.now().plusDays(1));

        Long id = service.create(dto);
        assertEquals(2001L, id);

        ArgumentCaptor<CouponTemplate> captor = ArgumentCaptor.forClass(CouponTemplate.class);
        verify(templateMapper).insert(captor.capture());
        assertEquals(new BigDecimal("200.00"), captor.getValue().getMaxDiscountAmount());
        assertEquals(CouponEnums.TARGET_PRODUCT, captor.getValue().getTarget());
        assertEquals("SKU-2026-001", captor.getValue().getProductCode());

        CouponTemplateCreateDTO fixedDto = new CouponTemplateCreateDTO();
        fixedDto.setName("满减券");
        fixedDto.setType(CouponEnums.TYPE_FIXED);
        fixedDto.setDiscountValue(new BigDecimal("20.00"));
        fixedDto.setTarget(CouponEnums.TARGET_ALL);
        fixedDto.setProductCode("IGNORED");
        fixedDto.setTotal(100);
        fixedDto.setStartTime(LocalDateTime.now().plusHours(1));
        fixedDto.setEndTime(LocalDateTime.now().plusDays(1));
        service.create(fixedDto);
        verify(templateMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertNull(captor.getValue().getProductCode());
    }

    @Test
    void create_shouldRejectInvalidDiscountRateMissingCapAndMissingProductCode() {
        CouponTemplateCreateDTO badRate = new CouponTemplateCreateDTO();
        badRate.setName("非法折扣券");
        badRate.setType(CouponEnums.TYPE_DISCOUNT);
        badRate.setDiscountValue(new BigDecimal("1.50"));
        badRate.setMaxDiscountAmount(new BigDecimal("50.00"));
        badRate.setTotal(100);
        badRate.setPerUserLimit(1);
        badRate.setValidDays(7);
        badRate.setStartTime(LocalDateTime.now().plusHours(1));
        badRate.setEndTime(LocalDateTime.now().plusDays(1));

        BizException exRate = assertThrows(BizException.class, () -> service.create(badRate));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), exRate.getCode());

        CouponTemplateCreateDTO missingCap = new CouponTemplateCreateDTO();
        missingCap.setName("无封顶折扣券");
        missingCap.setType(CouponEnums.TYPE_DISCOUNT);
        missingCap.setDiscountValue(new BigDecimal("0.80"));
        missingCap.setTotal(100);
        missingCap.setStartTime(LocalDateTime.now().plusHours(1));
        missingCap.setEndTime(LocalDateTime.now().plusDays(1));

        BizException exCap = assertThrows(BizException.class, () -> service.create(missingCap));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), exCap.getCode());

        CouponTemplateCreateDTO missingProduct = new CouponTemplateCreateDTO();
        missingProduct.setName("缺少商品编码");
        missingProduct.setType(CouponEnums.TYPE_FIXED);
        missingProduct.setDiscountValue(new BigDecimal("20.00"));
        missingProduct.setTarget(CouponEnums.TARGET_PRODUCT);
        missingProduct.setTotal(100);
        missingProduct.setStartTime(LocalDateTime.now().plusHours(1));
        missingProduct.setEndTime(LocalDateTime.now().plusDays(1));

        BizException exProduct = assertThrows(BizException.class, () -> service.create(missingProduct));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), exProduct.getCode());
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

    @Test
    void publish_shouldRejectTerminatedTemplate() {
        CouponTemplate t = new CouponTemplate();
        t.setId(1001L);
        t.setStatus(CouponEnums.TEMPLATE_ENDED);
        t.setStartTime(LocalDateTime.now().minusHours(1));
        t.setEndTime(LocalDateTime.now().plusDays(1));
        when(templateMapper.selectById(1001L)).thenReturn(t);

        BizException ex = assertThrows(BizException.class, () -> service.publish(1001L));
        assertEquals(ResultCode.COUPON_ENDED.getCode(), ex.getCode());
    }

    @Test
    void increaseStock_shouldUpdateDbIncrementPresentRedisAndInvalidateCache() {
        CouponTemplate active = new CouponTemplate();
        active.setId(1001L);
        active.setStatus(CouponEnums.TEMPLATE_ACTIVE);
        active.setEndTime(LocalDateTime.now().plusDays(1));
        when(templateMapper.selectById(1001L)).thenReturn(active);
        when(templateMapper.increaseTotal(1001L, 50)).thenReturn(1);

        service.increaseStock(1001L, 50);

        verify(templateMapper).increaseTotal(1001L, 50);
        verify(luaExecutor).execute(
                eq(LuaScriptExecutor.SCRIPT_COUPON_STOCK_INCR_IF_PRESENT),
                eq(List.of(RedisKey.couponStock(1001L))),
                eq(50));
        verify(multiLevelCache).invalidate(RedisKey.couponTemplateCache(1001L));
    }

    @Test
    void increaseStock_shouldRejectInvalidDeltaAndEndedTemplate() {
        BizException badDelta = assertThrows(BizException.class, () -> service.increaseStock(1001L, 0));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), badDelta.getCode());

        CouponTemplate ended = new CouponTemplate();
        ended.setId(1002L);
        ended.setStatus(CouponEnums.TEMPLATE_ENDED);
        ended.setEndTime(LocalDateTime.now().plusDays(1));
        when(templateMapper.selectById(1002L)).thenReturn(ended);

        BizException endedEx = assertThrows(BizException.class, () -> service.increaseStock(1002L, 10));
        assertEquals(ResultCode.COUPON_ENDED.getCode(), endedEx.getCode());
        verify(luaExecutor, never()).execute(anyString(), anyList(), any());
    }

    @Test
    void terminate_shouldEndTemplateSetRedisStatusAndInvalidateCache() {
        CouponTemplate active = new CouponTemplate();
        active.setId(1003L);
        active.setStatus(CouponEnums.TEMPLATE_ACTIVE);
        active.setEndTime(LocalDateTime.now().plusDays(1));
        when(templateMapper.selectById(1003L)).thenReturn(active);

        service.terminate(1003L);

        verify(templateMapper).terminate(1003L);
        verify(valueOps).set(eq(RedisKey.couponStatus(1003L)), eq(RedisKey.STATUS_ENDED), any(Duration.class));
        verify(multiLevelCache).invalidate(RedisKey.couponTemplateCache(1003L));

        CouponTemplate alreadyEnded = new CouponTemplate();
        alreadyEnded.setId(1004L);
        alreadyEnded.setStatus(CouponEnums.TEMPLATE_ENDED);
        alreadyEnded.setEndTime(LocalDateTime.now().plusDays(1));
        when(templateMapper.selectById(1004L)).thenReturn(alreadyEnded);
        service.terminate(1004L);
        verify(templateMapper, never()).terminate(1004L);
    }

    @Test
    void pageQuery_shouldReturnPagedResultAndClampBounds() {
        CouponTemplate tpl = new CouponTemplate();
        tpl.setId(1001L);
        tpl.setName("新人满100减20");
        Page<CouponTemplate> mpPage = new Page<>(1, 100);
        mpPage.setTotal(1);
        mpPage.setRecords(List.of(tpl));
        when(templateMapper.selectPage(ArgumentMatchers.<Page<CouponTemplate>>any(), any())).thenReturn(mpPage);

        CouponTemplatePageQueryDTO query = new CouponTemplatePageQueryDTO();
        query.setPageNum(-1);
        query.setPageSize(999);
        query.setName("新人");
        query.setType(CouponEnums.TYPE_FIXED);

        PageResult<CouponTemplate> result = service.pageQuery(query);
        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals(1001L, result.getRecords().get(0).getId());
    }

    @Test
    void getDetailAndListAudits_shouldRespectBloomFilterAndDelegateAuditService() {
        when(bloomFilter.mightContain(404L)).thenReturn(false);
        assertNull(service.getDetail(404L));
        verify(multiLevelCache, never()).get(anyString(), any(), any(), any());

        CouponTemplateAuditLog logEntry = new CouponTemplateAuditLog();
        logEntry.setId(9001L);
        when(auditService.listByTemplate(1001L)).thenReturn(List.of(logEntry));
        assertEquals(1, service.listAudits(1001L).size());
    }
}
