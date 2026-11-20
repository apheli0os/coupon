package com.mall.order.service.impl;

import com.mall.common.ResultCode;
import com.mall.common.constants.CommonConstant;
import com.mall.common.exception.BizException;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.order.domain.entity.SeckillOrder;
import com.mall.order.mapper.SeckillOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        orderService = new OrderServiceImpl(
                orderMapper, stringRedisTemplate, luaExecutor, transactionTemplate, kafkaTemplate);
    }

    @Test
    void createSeckillOrder_shouldRejectWhenTokenMissingOrRolledBack() {
        SeckillOrderMessage msg = sampleMessage("ORD-1", 2001L, 1001L);
        when(orderMapper.selectById("ORD-1")).thenReturn(null);
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_TOKEN_CLAIM), anyList(), any(), any()))
                .thenReturn(List.of(0L, 0L));
        when(luaExecutor.parse(List.of(0L, 0L))).thenReturn(new long[]{0L, 0L});

        orderService.createSeckillOrder(msg);

        verify(orderMapper, never()).insert(any(SeckillOrder.class));
    }

    @Test
    void createSeckillOrder_shouldInsertAndMarkConsumedWhenTokenClaimed() {
        SeckillOrderMessage msg = sampleMessage("ORD-2", 2001L, 1001L);
        when(orderMapper.selectById("ORD-2")).thenReturn(null);
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_TOKEN_CLAIM), anyList(), any(), any()))
                .thenReturn(List.of(1L, 0L));
        when(luaExecutor.parse(List.of(1L, 0L))).thenReturn(new long[]{1L, 0L});

        orderService.createSeckillOrder(msg);

        verify(orderMapper).insert(any(SeckillOrder.class));
        verify(valueOps).set(eq("seckill:{2001}:token:1001"), eq("CONSUMED:ORD-2"), any(Duration.class));
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

    private SeckillOrderMessage sampleMessage(String orderId, long activityId, long userId) {
        SeckillOrderMessage m = new SeckillOrderMessage();
        m.setOrderId(orderId);
        m.setActivityId(activityId);
        m.setUserId(userId);
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
        o.setStatus(0);
        o.setStockBucket(3);
        o.setQuantity(1);
        o.setTotalAmount(new BigDecimal("5999.00"));
        return o;
    }
}
