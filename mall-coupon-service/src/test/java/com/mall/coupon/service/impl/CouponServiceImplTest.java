package com.mall.coupon.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.PageResult;
import com.mall.common.R;
import com.mall.common.ResultCode;
import com.mall.common.constants.RedisKey;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BizException;
import com.mall.common.message.CouponClaimMessage;
import com.mall.common.metrics.BusinessMetrics;
import com.mall.common.util.IdGenerator;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.coupon.cache.UserCouponCacheService;
import com.mall.coupon.domain.dto.CouponQuoteRequestDTO;
import com.mall.coupon.domain.dto.CouponQuoteResponseDTO;
import com.mall.coupon.domain.dto.CouponUseDTO;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.domain.entity.UserCoupon;
import com.mall.coupon.domain.enums.CouponEnums;
import com.mall.coupon.feign.OrderFeignClient;
import com.mall.coupon.mapper.UserCouponMapper;
import com.mall.coupon.service.CouponTemplateService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponServiceImplTest {

    private CouponTemplateService templateService;
    private UserCouponMapper userCouponMapper;
    private LuaScriptExecutor luaExecutor;
    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOps;
    private IdGenerator idGenerator;
    private OrderFeignClient orderFeignClient;
    private BusinessMetrics metrics;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private UserCouponCacheService userCouponCacheService;
    private CouponServiceImpl couponService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserCoupon.class);
        templateService = mock(CouponTemplateService.class);
        userCouponMapper = mock(UserCouponMapper.class);
        luaExecutor = mock(LuaScriptExecutor.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        idGenerator = mock(IdGenerator.class);
        orderFeignClient = mock(OrderFeignClient.class);
        metrics = mock(BusinessMetrics.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        userCouponCacheService = mock(UserCouponCacheService.class);
        when(userCouponCacheService.listUnusedCoupons(anyLong(), any()))
                .thenAnswer(inv -> userCouponMapper.selectList(null));
        couponService = new CouponServiceImpl(
                templateService, userCouponMapper, luaExecutor, stringRedisTemplate,
                idGenerator, orderFeignClient, metrics, kafkaTemplate, userCouponCacheService);
        UserContext.set(1001L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void claim_shouldPropagateMaxDiscountCapAndProductScopeInMessage() {
        CouponTemplate tpl = activeTemplate(101L);
        tpl.setType(CouponEnums.TYPE_DISCOUNT);
        tpl.setDiscountValue(new BigDecimal("0.85"));
        tpl.setMaxDiscountAmount(new BigDecimal("200.00"));
        tpl.setTarget(CouponEnums.TARGET_PRODUCT);
        tpl.setProductCode("SKU-2026-001");
        when(templateService.getDetail(101L)).thenReturn(tpl);
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_COUPON_CLAIM), anyList(), any(), any()))
                .thenReturn(List.of(0L, 19L));
        when(luaExecutor.parse(List.of(0L, 19L))).thenReturn(new long[]{0L, 19L});
        when(idGenerator.nextId()).thenReturn(9001L);
        SendResult<String, Object> sendResult = new SendResult<>(
                null,
                new RecordMetadata(new TopicPartition("coupon-claim-topic", 0), 0, 0, 0, 0, 0));
        when(kafkaTemplate.send(anyString(), eq("9001"), any()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        couponService.claim(101L);

        ArgumentCaptor<CouponClaimMessage> msgCaptor = ArgumentCaptor.forClass(CouponClaimMessage.class);
        verify(kafkaTemplate).send(anyString(), eq("9001"), msgCaptor.capture());
        assertEquals(new BigDecimal("200.00"), msgCaptor.getValue().getMaxDiscountAmount());
        assertEquals(CouponEnums.TARGET_PRODUCT, msgCaptor.getValue().getTarget());
        assertEquals("SKU-2026-001", msgCaptor.getValue().getProductCode());
    }

    @Test
    void claim_shouldWriteTokenAndRollbackOnKafkaFailure() {
        CouponTemplate tpl = activeTemplate(100L);
        when(templateService.getDetail(100L)).thenReturn(tpl);
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_COUPON_CLAIM), anyList(), any(), any()))
                .thenReturn(List.of(0L, 99L));
        when(luaExecutor.parse(List.of(0L, 99L))).thenReturn(new long[]{0L, 99L});
        when(idGenerator.nextId()).thenReturn(8888L);
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_COUPON_ROLLBACK), anyList(),
                eq("1001"), eq("8888"), eq(RedisKey.ROLLBACK_GUARD_TTL_SECONDS)))
                .thenReturn(List.of(0L, 100L));
        when(luaExecutor.parse(List.of(0L, 100L))).thenReturn(new long[]{0L, 100L});

        BizException ex = assertThrows(BizException.class, () -> couponService.claim(100L));
        assertEquals(ResultCode.MQ_SEND_FAILED.getCode(), ex.getCode());
        verify(luaExecutor).execute(eq(LuaScriptExecutor.SCRIPT_COUPON_ROLLBACK),
                eq(List.of(
                        RedisKey.couponStock(100L),
                        RedisKey.couponClaimHash(100L),
                        RedisKey.couponClaimToken(100L, 8888L),
                        RedisKey.couponRollbackGuard(100L, 8888L))),
                eq("1001"), eq("8888"), eq(RedisKey.ROLLBACK_GUARD_TTL_SECONDS));
    }

    @Test
    void quoteCoupons_shouldSortAvailableByDiscountDescAndExpireAscWithUnavailableReasons() {
        LocalDateTime now = LocalDateTime.now();
        UserCoupon fixedEarlier = fixedCoupon(1L, new BigDecimal("20.00"), new BigDecimal("100.00"), now.plusDays(1));
        UserCoupon fixedLater = fixedCoupon(2L, new BigDecimal("20.00"), new BigDecimal("100.00"), now.plusDays(5));
        UserCoupon cappedDiscount = new UserCoupon();
        cappedDiscount.setId(3L);
        cappedDiscount.setUserId(1001L);
        cappedDiscount.setTemplateId(102L);
        cappedDiscount.setCouponName("8折封顶150");
        cappedDiscount.setType(CouponEnums.TYPE_DISCOUNT);
        cappedDiscount.setDiscountValue(new BigDecimal("0.80"));
        cappedDiscount.setMaxDiscountAmount(new BigDecimal("150.00"));
        cappedDiscount.setThreshold(BigDecimal.ZERO);
        cappedDiscount.setTarget(CouponEnums.TARGET_ALL);
        cappedDiscount.setStatus(CouponEnums.COUPON_UNUSED);
        cappedDiscount.setExpireTime(now.plusDays(3));

        UserCoupon wrongProduct = fixedCoupon(4L, new BigDecimal("50.00"), BigDecimal.ZERO, now.plusDays(2));
        wrongProduct.setTarget(CouponEnums.TARGET_PRODUCT);
        wrongProduct.setProductCode("SKU-OTHER");

        UserCoupon highThreshold = fixedCoupon(5L, new BigDecimal("100.00"), new BigDecimal("5000.00"), now.plusDays(4));

        when(userCouponMapper.selectList(any())).thenReturn(
                List.of(fixedLater, cappedDiscount, fixedEarlier, wrongProduct, highThreshold));

        CouponQuoteRequestDTO req = new CouponQuoteRequestDTO();
        req.setOrderAmount(new BigDecimal("1000.00"));
        req.setProductCode("SKU-2026-001");

        CouponQuoteResponseDTO resp = couponService.quoteCoupons(req);
        assertEquals(3, resp.getAvailableCoupons().size());
        assertEquals(2, resp.getUnavailableCoupons().size());

        assertEquals(3L, resp.getAvailableCoupons().get(0).getUserCouponId());
        assertEquals(new BigDecimal("150.00"), resp.getAvailableCoupons().get(0).getDiscountAmount());
        assertEquals(new BigDecimal("850.00"), resp.getAvailableCoupons().get(0).getPayAmount());
        assertTrue(resp.getAvailableCoupons().get(0).isAvailable());

        assertEquals(1L, resp.getAvailableCoupons().get(1).getUserCouponId());
        assertEquals(2L, resp.getAvailableCoupons().get(2).getUserCouponId());

        assertEquals(4L, resp.getUnavailableCoupons().get(0).getUserCouponId());
        assertFalse(resp.getUnavailableCoupons().get(0).isAvailable());
        assertEquals(ResultCode.COUPON_PRODUCT_NOT_MATCHED.getMessage(),
                resp.getUnavailableCoupons().get(0).getUnavailableReason());
    }

    @Test
    void useCoupon_shouldForwardToOrderApplyCouponAndPropagateErrors() {
        when(orderFeignClient.applyCoupon("ORD-1", 1001L, 10L)).thenReturn(R.ok());

        CouponUseDTO dto = new CouponUseDTO();
        dto.setOrderId("ORD-1");
        dto.setUserCouponId(10L);
        couponService.useCoupon(dto);
        verify(orderFeignClient).applyCoupon("ORD-1", 1001L, 10L);

        when(orderFeignClient.applyCoupon("ORD-2", 1001L, 11L))
                .thenReturn(R.fail(ResultCode.ORDER_COUPON_ALREADY_APPLIED.getCode(), "订单已绑定优惠券"));
        CouponUseDTO conflictDto = new CouponUseDTO();
        conflictDto.setOrderId("ORD-2");
        conflictDto.setUserCouponId(11L);
        BizException bizEx = assertThrows(BizException.class, () -> couponService.useCoupon(conflictDto));
        assertEquals(ResultCode.ORDER_COUPON_ALREADY_APPLIED.getCode(), bizEx.getCode());

        when(orderFeignClient.applyCoupon("ORD-3", 1001L, 12L))
                .thenThrow(new RuntimeException("order service down"));
        CouponUseDTO downDto = new CouponUseDTO();
        downDto.setOrderId("ORD-3");
        downDto.setUserCouponId(12L);
        BizException downEx = assertThrows(BizException.class, () -> couponService.useCoupon(downDto));
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), downEx.getCode());
    }

    @Test
    void pageMyCouponsAndCachedUnusedList_shouldReturnExpectedResults() {
        UserCoupon c1 = fixedCoupon(1L, new BigDecimal("20.00"), new BigDecimal("100.00"),
                LocalDateTime.now().plusDays(1));
        c1.setCreateTime(LocalDateTime.now().minusMinutes(5));
        Page<UserCoupon> mpPage = new Page<>(1, 10);
        mpPage.setTotal(1);
        mpPage.setRecords(List.of(c1));
        when(userCouponMapper.selectPage(ArgumentMatchers.<Page<UserCoupon>>any(), any())).thenReturn(mpPage);

        PageResult<UserCoupon> paged = couponService.pageMyCoupons(0L, 200L, CouponEnums.COUPON_UNUSED);
        assertEquals(1L, paged.getTotal());
        assertEquals(1, paged.getRecords().size());

        when(userCouponCacheService.listUnusedCoupons(eq(1001L), any(LocalDateTime.class))).thenReturn(List.of(c1));
        assertEquals(1, couponService.listMyCoupons(CouponEnums.COUPON_UNUSED).size());
        verify(userCouponCacheService).listUnusedCoupons(eq(1001L), any(LocalDateTime.class));
    }

    private UserCoupon fixedCoupon(long id, BigDecimal discount, BigDecimal threshold, LocalDateTime expire) {
        UserCoupon c = new UserCoupon();
        c.setId(id);
        c.setUserId(1001L);
        c.setTemplateId(100L);
        c.setCouponName("满减券");
        c.setType(CouponEnums.TYPE_FIXED);
        c.setDiscountValue(discount);
        c.setThreshold(threshold);
        c.setTarget(CouponEnums.TARGET_ALL);
        c.setStatus(CouponEnums.COUPON_UNUSED);
        c.setExpireTime(expire);
        return c;
    }

    private CouponTemplate activeTemplate(long id) {
        CouponTemplate t = new CouponTemplate();
        t.setId(id);
        t.setName("满100减20");
        t.setType(CouponEnums.TYPE_FIXED);
        t.setDiscountValue(new BigDecimal("20.00"));
        t.setThreshold(new BigDecimal("100.00"));
        t.setTarget(CouponEnums.TARGET_ALL);
        t.setPerUserLimit(1);
        t.setValidDays(7);
        t.setStartTime(LocalDateTime.now().minusHours(1));
        t.setEndTime(LocalDateTime.now().plusDays(1));
        return t;
    }
}
