package com.mall.order.service.impl;

import com.mall.common.R;
import com.mall.common.ResultCode;
import com.mall.common.constants.CommonConstant;
import com.mall.common.exception.BizException;
import com.mall.common.message.CouponLockRequest;
import com.mall.common.message.CouponLockResult;
import com.mall.common.message.CouponUnlockRequest;
import com.mall.common.message.OrderPaidMessage;
import com.mall.common.message.OrderQueryResult;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.common.pricing.CouponPricing;
import com.mall.common.seckill.SeckillReservationStore;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.order.domain.entity.SeckillOrder;
import com.mall.order.feign.CouponFeignClient;
import com.mall.order.mapper.SeckillOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceImplTest {

    private SeckillOrderMapper orderMapper;
    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOps;
    private LuaScriptExecutor luaExecutor;
    private TransactionTemplate transactionTemplate;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private CouponFeignClient couponFeignClient;
    private SeckillReservationStore reservations;
    private OrderServiceImpl orderService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orderMapper = mock(SeckillOrderMapper.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        luaExecutor = mock(LuaScriptExecutor.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
        kafkaTemplate = mock(KafkaTemplate.class);
        couponFeignClient = mock(CouponFeignClient.class);
        reservations = mock(SeckillReservationStore.class);
        orderService = new OrderServiceImpl(
                orderMapper, stringRedisTemplate, luaExecutor, transactionTemplate,
                kafkaTemplate, couponFeignClient, reservations);
    }

    @Test
    void createSeckillOrder_shouldRejectAndTriggerExpireCompensationWhenTokenMissing() {
        SeckillOrderMessage msg = sampleMessage("ORD-1", 2001L, 1001L);
        when(orderMapper.selectById("ORD-1")).thenReturn(null);
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_TOKEN_CLAIM), anyList(), any(), any()))
                .thenReturn(List.of(0L, 0L));
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_SECKILL_EXPIRE_COMPENSATE), anyList(), any(), any()))
                .thenReturn(List.of(0L, 10L));
        when(luaExecutor.parse(List.of(0L, 0L))).thenReturn(new long[]{0L, 0L});
        when(luaExecutor.parse(List.of(0L, 10L))).thenReturn(new long[]{0L, 10L});

        orderService.createSeckillOrder(msg);

        verify(orderMapper, never()).insert(any(SeckillOrder.class));
        verify(kafkaTemplate, never()).send(eq(CommonConstant.MQ_TOPIC_SECKILL_ORDER_CREATED), any(), any());
        verify(luaExecutor).execute(eq(LuaScriptExecutor.SCRIPT_SECKILL_EXPIRE_COMPENSATE),
                anyList(), eq("1001"), any());
    }

    @Test
    void createSeckillOrder_shouldInsertMarkConsumedAndPublishOrderCreatedWhenTokenClaimed() {
        SeckillOrderMessage msg = sampleMessage("ORD-2", 2001L, 1001L);
        when(orderMapper.selectById("ORD-2")).thenReturn(null);
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_TOKEN_CLAIM), anyList(), any(), any()))
                .thenReturn(List.of(1L, 0L));
        when(luaExecutor.parse(List.of(1L, 0L))).thenReturn(new long[]{1L, 0L});

        orderService.createSeckillOrder(msg);

        ArgumentCaptor<SeckillOrder> orderCaptor = ArgumentCaptor.forClass(SeckillOrder.class);
        verify(orderMapper).insert(orderCaptor.capture());
        assertEquals("SKU-2026-001", orderCaptor.getValue().getProductCode());
        assertEquals(BigDecimal.ZERO, orderCaptor.getValue().getDiscountAmount());
        assertEquals(new BigDecimal("5999.00"), orderCaptor.getValue().getPayAmount());
        verify(valueOps).set(eq("seckill:{2001}:token:ORD-2"), eq("CONSUMED:ORD-2"), any(Duration.class));
        verify(kafkaTemplate).send(eq(CommonConstant.MQ_TOPIC_SECKILL_ORDER_CREATED), eq("ORD-2"), any());
    }

    @Test
    void createSeckillOrder_shouldRejectReleasedReservationWithoutLegacyCompensation() {
        SeckillOrderMessage message = sampleMessage("ORD-RELEASED", 2001L, 1001L);
        message.setReservationTracked(true);

        orderService.createSeckillOrder(message);

        verify(reservations).claim(message);
        verify(orderMapper, never()).insert(any(SeckillOrder.class));
        verify(luaExecutor, never()).execute(eq(LuaScriptExecutor.SCRIPT_SECKILL_EXPIRE_COMPENSATE), anyList(), any(), any());
    }

    @Test
    void createSeckillOrder_shouldCompleteReservationWhenRetryFindsCommittedOrder() {
        SeckillOrderMessage message = sampleMessage("ORD-RECOVER", 2001L, 1001L);
        message.setReservationTracked(true);
        when(orderMapper.selectById("ORD-RECOVER")).thenReturn(sampleOrder("ORD-RECOVER", 2001L, 1001L));

        orderService.createSeckillOrder(message);

        verify(reservations, never()).claim(message);
        verify(reservations).complete(2001L, "ORD-RECOVER");
        verify(orderMapper, never()).insert(any(SeckillOrder.class));
    }

    @Test
    void findByOrderIdForUser_shouldRejectOtherUserWithForbidden() {
        SeckillOrder order = sampleOrder("ORD-3", 2001L, 1001L);
        when(orderMapper.selectById("ORD-3")).thenReturn(order);

        BizException ex = assertThrows(BizException.class,
                () -> orderService.findByOrderIdForUser("ORD-3", 2002L, false));
        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void pay_shouldRejectOtherUserWithForbidden() {
        SeckillOrder order = sampleOrder("ORD-4", 2001L, 1001L);
        when(orderMapper.selectById("ORD-4")).thenReturn(order);

        BizException ex = assertThrows(BizException.class, () -> orderService.pay("ORD-4", 9999L));
        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getCode());
        verify(orderMapper, never()).pay("ORD-4");
    }

    @Test
    void cancel_shouldRollbackRedisMarkRestoredAndPublishCancelEvent() {
        SeckillOrder order = sampleOrder("ORD-5", 2001L, 1001L);
        when(orderMapper.selectById("ORD-5")).thenReturn(order);
        when(orderMapper.cancel("ORD-5")).thenReturn(1);
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_SECKILL_CANCEL_ROLLBACK), anyList(), any(), any(), any()))
                .thenReturn(List.of(0L, 10L));

        orderService.cancel("ORD-5", 1001L, false);

        verify(orderMapper).cancel("ORD-5");
        verify(luaExecutor).execute(eq(LuaScriptExecutor.SCRIPT_SECKILL_CANCEL_ROLLBACK), anyList(),
                eq("1001"), eq("ORD-5"), any());
        verify(orderMapper).markStockRestored("ORD-5");
        verify(kafkaTemplate).send(eq(CommonConstant.MQ_TOPIC_SECKILL_CANCEL), eq("ORD-5"), any());
    }

    @Test
    void cancel_shouldLeaveStockPendingRestoreWhenRedisFails() {
        SeckillOrder order = sampleOrder("ORD-6", 2001L, 1001L);
        when(orderMapper.selectById("ORD-6")).thenReturn(order);
        when(orderMapper.cancel("ORD-6")).thenReturn(1);
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_SECKILL_CANCEL_ROLLBACK), anyList(), any(), any(), any()))
                .thenThrow(new RuntimeException("Redis down"));

        orderService.cancel("ORD-6", 1001L, false);

        verify(orderMapper).cancel("ORD-6");
        verify(orderMapper, never()).markStockRestored("ORD-6");
    }

    @Test
    void createSeckillOrder_redeliveryOfCancelledOrderMustNotOverwriteNewOrdersToken() {
        Map<String, String> redisStore = new HashMap<>();
        Map<String, SeckillOrder> dbOrders = new HashMap<>();

        SeckillOrder cancelledOrd1 = sampleOrder("ORD-1", 2001L, 1001L);
        cancelledOrd1.setStatus(2);
        cancelledOrd1.setStockRestored(1);
        dbOrders.put("ORD-1", cancelledOrd1);

        String ord2TokenKey = com.mall.common.constants.RedisKey.seckillOrderToken(2001L, "ORD-2");
        redisStore.put(ord2TokenKey, "ORD-2");

        when(orderMapper.selectById(any())).thenAnswer(inv -> dbOrders.get(inv.getArgument(0)));
        when(orderMapper.insert(any(SeckillOrder.class))).thenAnswer(inv -> {
            SeckillOrder o = inv.getArgument(0);
            dbOrders.put(o.getOrderId(), o);
            return 1;
        });
        org.mockito.Mockito.doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(any(), any(), any(Duration.class));
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_TOKEN_CLAIM), anyList(), any(), any()))
                .thenAnswer(inv -> {
                    List<String> keys = inv.getArgument(1);
                    String expected = String.valueOf((Object) inv.getArgument(2));
                    String cur = redisStore.get(keys.get(0));
                    if (expected.equals(cur) || ("PROCESSING:" + expected).equals(cur)) {
                        redisStore.put(keys.get(0), "PROCESSING:" + expected);
                        return List.of(1L, 0L);
                    }
                    return List.of(0L, 0L);
                });
        when(luaExecutor.parse(any())).thenAnswer(inv -> {
            List<?> raw = inv.getArgument(0);
            return new long[]{((Number) raw.get(0)).longValue(), ((Number) raw.get(1)).longValue()};
        });

        orderService.createSeckillOrder(sampleMessage("ORD-1", 2001L, 1001L));
        orderService.createSeckillOrder(sampleMessage("ORD-2", 2001L, 1001L));

        assertTrue(dbOrders.containsKey("ORD-2"),
                "已取消订单 ORD-1 的延迟重投不得覆盖同用户新订单 ORD-2 的凭证，导致 ORD-2 被拒单并永久泄漏库存");
    }

    @Test
    void pay_shouldPublishOrderPaidEventWithCouponId() {
        SeckillOrder order = sampleOrder("ORD-PAY", 2001L, 1001L);
        order.setCouponId(9001L);
        when(orderMapper.selectById("ORD-PAY")).thenReturn(order);
        when(orderMapper.pay("ORD-PAY")).thenReturn(1);

        orderService.pay("ORD-PAY", 1001L);

        ArgumentCaptor<OrderPaidMessage> paidCaptor = ArgumentCaptor.forClass(OrderPaidMessage.class);
        verify(kafkaTemplate).send(eq(CommonConstant.MQ_TOPIC_ORDER_PAID), eq("ORD-PAY"), paidCaptor.capture());
        assertEquals("ORD-PAY", paidCaptor.getValue().getOrderId());
        assertEquals(1001L, paidCaptor.getValue().getUserId());
        assertEquals(9001L, paidCaptor.getValue().getCouponId());
    }

    @Test
    void applyCoupon_shouldLockCrossCheckAndBindOrder() {
        SeckillOrder initial = sampleOrder("ORD-10", 2001L, 1001L);
        SeckillOrder bound = sampleOrder("ORD-10", 2001L, 1001L);
        bound.setCouponId(501L);
        bound.setDiscountAmount(new BigDecimal("200.00"));
        bound.setPayAmount(new BigDecimal("5799.00"));
        when(orderMapper.selectById("ORD-10")).thenReturn(initial, bound);

        CouponLockResult lockResult = discountLockResult(501L, new BigDecimal("200.00"), new BigDecimal("5799.00"));
        when(couponFeignClient.lockCoupon(any(CouponLockRequest.class))).thenReturn(R.ok(lockResult));
        when(orderMapper.applyCoupon(
                "ORD-10", 1001L, 501L, new BigDecimal("200.00"), new BigDecimal("5799.00")))
                .thenReturn(1);

        SeckillOrder result = orderService.applyCoupon("ORD-10", 1001L, 501L);
        assertEquals(501L, result.getCouponId());
        assertEquals(new BigDecimal("200.00"), result.getDiscountAmount());
        assertEquals(new BigDecimal("5799.00"), result.getPayAmount());
    }

    @Test
    void applyCoupon_shouldReplayIdempotentlyOrRejectDifferentCoupon() {
        SeckillOrder already = sampleOrder("ORD-11", 2001L, 1001L);
        already.setCouponId(501L);
        when(orderMapper.selectById("ORD-11")).thenReturn(already);

        SeckillOrder replayed = orderService.applyCoupon("ORD-11", 1001L, 501L);
        assertEquals(501L, replayed.getCouponId());
        verify(couponFeignClient, never()).lockCoupon(any());

        BizException conflict = assertThrows(BizException.class,
                () -> orderService.applyCoupon("ORD-11", 1001L, 502L));
        assertEquals(ResultCode.ORDER_COUPON_ALREADY_APPLIED.getCode(), conflict.getCode());
    }

    @Test
    void applyCoupon_shouldCompensateUnlockOnPricingMismatchOrCasConflict() {
        SeckillOrder initial = sampleOrder("ORD-12", 2001L, 1001L);
        SeckillOrder raced = sampleOrder("ORD-12", 2001L, 1001L);
        raced.setCouponId(999L);
        when(orderMapper.selectById("ORD-12")).thenReturn(initial, initial, raced);

        CouponLockResult mismatched = discountLockResult(501L, new BigDecimal("500.00"), new BigDecimal("5499.00"));
        when(couponFeignClient.lockCoupon(any(CouponLockRequest.class))).thenReturn(R.ok(mismatched));

        BizException mismatchEx = assertThrows(BizException.class,
                () -> orderService.applyCoupon("ORD-12", 1001L, 501L));
        assertEquals(ResultCode.ORDER_AMOUNT_MISMATCH.getCode(), mismatchEx.getCode());
        verify(couponFeignClient).unlockCoupon(any(CouponUnlockRequest.class));

        CouponLockResult valid = discountLockResult(501L, new BigDecimal("200.00"), new BigDecimal("5799.00"));
        when(couponFeignClient.lockCoupon(any(CouponLockRequest.class))).thenReturn(R.ok(valid));
        when(orderMapper.applyCoupon(any(), any(), any(), any(), any())).thenReturn(0);

        BizException raceEx = assertThrows(BizException.class,
                () -> orderService.applyCoupon("ORD-12", 1001L, 501L));
        assertEquals(ResultCode.ORDER_COUPON_ALREADY_APPLIED.getCode(), raceEx.getCode());
    }

    @Test
    void applyCoupon_shouldPropagateCouponErrorAndFailClosedOnUnavailable() {
        SeckillOrder order = sampleOrder("ORD-13", 2001L, 1001L);
        when(orderMapper.selectById("ORD-13")).thenReturn(order);
        when(couponFeignClient.lockCoupon(any()))
                .thenReturn(R.fail(ResultCode.COUPON_THRESHOLD_NOT_MET.getCode(), "未满足使用门槛"));

        BizException bizEx = assertThrows(BizException.class,
                () -> orderService.applyCoupon("ORD-13", 1001L, 501L));
        assertEquals(ResultCode.COUPON_THRESHOLD_NOT_MET.getCode(), bizEx.getCode());

        when(couponFeignClient.lockCoupon(any())).thenThrow(new RuntimeException("connection refused"));
        BizException sysEx = assertThrows(BizException.class,
                () -> orderService.applyCoupon("ORD-13", 1001L, 501L));
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), sysEx.getCode());
    }

    @Test
    void queryByOrderId_shouldExposeProductAndCouponFields() {
        SeckillOrder order = sampleOrder("ORD-Q", 2001L, 1001L);
        order.setCouponId(701L);
        order.setDiscountAmount(new BigDecimal("200.00"));
        order.setPayAmount(new BigDecimal("5799.00"));
        when(orderMapper.selectById("ORD-Q")).thenReturn(order);

        OrderQueryResult res = orderService.queryByOrderId("ORD-Q");
        assertEquals("SKU-2026-001", res.getProductCode());
        assertEquals(701L, res.getCouponId());
        assertEquals(new BigDecimal("200.00"), res.getDiscountAmount());
        assertEquals(new BigDecimal("5799.00"), res.getPayAmount());
    }

    private CouponLockResult discountLockResult(long couponId, BigDecimal discount, BigDecimal pay) {
        CouponLockResult res = new CouponLockResult();
        res.setUserCouponId(couponId);
        res.setDiscountAmount(discount);
        res.setPayAmount(pay);
        res.setType(CouponPricing.TYPE_DISCOUNT);
        res.setDiscountValue(new BigDecimal("0.90"));
        res.setMaxDiscountAmount(new BigDecimal("200.00"));
        res.setThreshold(BigDecimal.ZERO);
        res.setTarget(CouponPricing.TARGET_ALL);
        return res;
    }

    private SeckillOrderMessage sampleMessage(String orderId, long activityId, long userId) {
        SeckillOrderMessage m = new SeckillOrderMessage();
        m.setOrderId(orderId);
        m.setActivityId(activityId);
        m.setUserId(userId);
        m.setProductCode("SKU-2026-001");
        m.setProductName("iPhone");
        m.setSeckillPrice(new BigDecimal("5999.00"));
        m.setQuantity(1);
        m.setStockBucket(3);
        m.setCreateTime(LocalDateTime.now());
        return m;
    }

    private SeckillOrder sampleOrder(String orderId, long activityId, long userId) {
        SeckillOrder o = new SeckillOrder();
        o.setOrderId(orderId);
        o.setActivityId(activityId);
        o.setUserId(userId);
        o.setProductCode("SKU-2026-001");
        o.setStatus(0);
        o.setStockBucket(3);
        o.setQuantity(1);
        o.setTotalAmount(new BigDecimal("5999.00"));
        o.setDiscountAmount(BigDecimal.ZERO);
        o.setPayAmount(new BigDecimal("5999.00"));
        return o;
    }
}
