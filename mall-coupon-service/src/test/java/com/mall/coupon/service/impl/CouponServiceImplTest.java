package com.mall.coupon.service.impl;

import com.mall.common.R;
import com.mall.common.ResultCode;
import com.mall.common.constants.RedisKey;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BizException;
import com.mall.common.message.OrderQueryResult;
import com.mall.common.metrics.BusinessMetrics;
import com.mall.common.util.IdGenerator;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.coupon.domain.dto.CouponUseDTO;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.domain.entity.UserCoupon;
import com.mall.coupon.domain.enums.CouponEnums;
import com.mall.coupon.feign.OrderFeignClient;
import com.mall.coupon.mapper.UserCouponMapper;
import com.mall.coupon.service.CouponTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private CouponServiceImpl couponService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
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
        couponService = new CouponServiceImpl(
                templateService, userCouponMapper, luaExecutor, stringRedisTemplate,
                idGenerator, orderFeignClient, metrics, kafkaTemplate);
        UserContext.set(1001L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void useCoupon_shouldVerifyRealServerOrderAmountAndRejectThresholdViolation() {
        UserCoupon coupon = sampleCoupon(10L, 1001L, new BigDecimal("100.00"));
        when(userCouponMapper.selectById(10L)).thenReturn(coupon);

        OrderQueryResult order = new OrderQueryResult();
        order.setOrderId("ORD-1");
        order.setUserId(1001L);
        order.setStatus(0);
        // 服务端真实订单金额仅 50，客户端伪造 999 试图绕过 100 门槛
        order.setTotalAmount(new BigDecimal("50.00"));
        when(orderFeignClient.getOrder("ORD-1")).thenReturn(R.ok(order));

        CouponUseDTO dto = new CouponUseDTO();
        dto.setUserCouponId(10L);
        dto.setOrderId("ORD-1");
        dto.setOrderAmount(new BigDecimal("999.00"));

        BizException ex = assertThrows(BizException.class, () -> couponService.useCoupon(dto));
        assertEquals(ResultCode.COUPON_THRESHOLD_NOT_MET.getCode(), ex.getCode());
        verify(userCouponMapper, never()).useCoupon(any(), any(), any());
    }

    @Test
    void useCoupon_shouldRejectOtherUsersOrder() {
        UserCoupon coupon = sampleCoupon(11L, 1001L, BigDecimal.ZERO);
        when(userCouponMapper.selectById(11L)).thenReturn(coupon);

        OrderQueryResult order = new OrderQueryResult();
        order.setOrderId("ORD-2");
        order.setUserId(2002L);
        order.setStatus(0);
        order.setTotalAmount(new BigDecimal("200.00"));
        when(orderFeignClient.getOrder("ORD-2")).thenReturn(R.ok(order));

        CouponUseDTO dto = new CouponUseDTO();
        dto.setUserCouponId(11L);
        dto.setOrderId("ORD-2");

        BizException ex = assertThrows(BizException.class, () -> couponService.useCoupon(dto));
        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void useCoupon_shouldFailClosedWhenOrderServiceUnavailable() {
        UserCoupon coupon = sampleCoupon(12L, 1001L, BigDecimal.ZERO);
        when(userCouponMapper.selectById(12L)).thenReturn(coupon);
        when(orderFeignClient.getOrder("ORD-3")).thenReturn(R.fail(ResultCode.SYSTEM_ERROR));

        CouponUseDTO dto = new CouponUseDTO();
        dto.setUserCouponId(12L);
        dto.setOrderId("ORD-3");

        BizException ex = assertThrows(BizException.class, () -> couponService.useCoupon(dto));
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), ex.getCode());
        verify(userCouponMapper, never()).useCoupon(any(), any(), any());
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
    void useCoupon_shouldRejectAndReleaseWhenOrderCancelledConcurrentlyAfterInitialCheck() {
        UserCoupon coupon = sampleCoupon(15L, 1001L, new BigDecimal("50.00"));
        when(userCouponMapper.selectById(15L)).thenReturn(coupon);

        OrderQueryResult unpaid = new OrderQueryResult();
        unpaid.setOrderId("ORD-RACE");
        unpaid.setUserId(1001L);
        unpaid.setStatus(0);
        unpaid.setTotalAmount(new BigDecimal("100.00"));

        OrderQueryResult cancelled = new OrderQueryResult();
        cancelled.setOrderId("ORD-RACE");
        cancelled.setUserId(1001L);
        cancelled.setStatus(2);
        cancelled.setTotalAmount(new BigDecimal("100.00"));

        when(orderFeignClient.getOrder("ORD-RACE")).thenReturn(R.ok(unpaid), R.ok(cancelled));
        when(userCouponMapper.useCoupon(15L, 1001L, "ORD-RACE")).thenReturn(1);

        CouponUseDTO dto = new CouponUseDTO();
        dto.setUserCouponId(15L);
        dto.setOrderId("ORD-RACE");

        BizException ex = assertThrows(BizException.class, () -> couponService.useCoupon(dto),
                "订单在核销窗口内被并发取消时，不得放行并让已核销券挂在已取消订单上");
        assertEquals(ResultCode.ORDER_STATUS_INVALID.getCode(), ex.getCode());
        verify(userCouponMapper).releaseCoupon(15L, 1001L, "ORD-RACE");
    }

    private UserCoupon sampleCoupon(long id, long userId, BigDecimal threshold) {
        UserCoupon c = new UserCoupon();
        c.setId(id);
        c.setUserId(userId);
        c.setStatus(CouponEnums.COUPON_UNUSED);
        c.setThreshold(threshold);
        c.setExpireTime(LocalDateTime.now().plusDays(1));
        return c;
    }

    private CouponTemplate activeTemplate(long id) {
        CouponTemplate t = new CouponTemplate();
        t.setId(id);
        t.setName("满100减20");
        t.setType(CouponEnums.TYPE_FIXED);
        t.setDiscountValue(new BigDecimal("20.00"));
        t.setThreshold(new BigDecimal("100.00"));
        t.setPerUserLimit(1);
        t.setValidDays(7);
        t.setStartTime(LocalDateTime.now().minusHours(1));
        t.setEndTime(LocalDateTime.now().plusDays(1));
        return t;
    }
}
