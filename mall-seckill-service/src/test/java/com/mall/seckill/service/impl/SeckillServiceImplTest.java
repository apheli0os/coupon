package com.mall.seckill.service.impl;

import com.mall.common.ResultCode;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BizException;
import com.mall.common.metrics.BusinessMetrics;
import com.mall.common.util.IdGenerator;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.seckill.domain.entity.SeckillActivity;
import com.mall.seckill.feign.OrderFeignClient;
import com.mall.seckill.service.SeckillActivityService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillServiceImplTest {

    private SeckillActivityService activityService;
    private LuaScriptExecutor luaExecutor;
    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOps;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private IdGenerator idGenerator;
    private OrderFeignClient orderFeignClient;
    private BusinessMetrics metrics;
    private SeckillServiceImpl seckillService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        activityService = mock(SeckillActivityService.class);
        luaExecutor = mock(LuaScriptExecutor.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        kafkaTemplate = mock(KafkaTemplate.class);
        idGenerator = mock(IdGenerator.class);
        orderFeignClient = mock(OrderFeignClient.class);
        metrics = mock(BusinessMetrics.class);
        seckillService = new SeckillServiceImpl(
                activityService, luaExecutor, stringRedisTemplate, kafkaTemplate,
                idGenerator, orderFeignClient, metrics);
        UserContext.set(1001L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void seckill_shouldRollbackWithTokenCasWhenKafkaSendFails() {
        when(activityService.getDetail(2001L)).thenReturn(activeActivity(2001L));
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_SECKILL), anyList(), any(), any(), any()))
                .thenReturn(List.of(0L, 4L));
        when(luaExecutor.parse(List.of(0L, 4L))).thenReturn(new long[]{0L, 4L});
        when(idGenerator.nextIdStr()).thenReturn("ORD-9001");
        when(kafkaTemplate.send(anyString(), eq("ORD-9001"), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("timeout")));
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_SECKILL_ROLLBACK), anyList(), eq("1001"), eq("ORD-9001")))
                .thenReturn(List.of(0L, 10L));
        when(luaExecutor.parse(List.of(0L, 10L))).thenReturn(new long[]{0L, 10L});

        BizException ex = assertThrows(BizException.class, () -> seckillService.seckill(2001L));
        assertEquals(ResultCode.MQ_SEND_FAILED.getCode(), ex.getCode());
        verify(luaExecutor).execute(eq(LuaScriptExecutor.SCRIPT_SECKILL_ROLLBACK), anyList(), eq("1001"), eq("ORD-9001"));
    }

    @Test
    void seckill_shouldReturnOrderIdWhenConsumerAlreadyClaimedTokenOnKafkaTimeout() {
        when(activityService.getDetail(2001L)).thenReturn(activeActivity(2001L));
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_SECKILL), anyList(), any(), any(), any()))
                .thenReturn(List.of(0L, 4L));
        when(luaExecutor.parse(List.of(0L, 4L))).thenReturn(new long[]{0L, 4L});
        when(idGenerator.nextIdStr()).thenReturn("ORD-9002");
        when(kafkaTemplate.send(anyString(), eq("ORD-9002"), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("ack timeout")));
        // code == 2 表示消费端已原子认领该订单凭证，严禁回滚库存
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_SECKILL_ROLLBACK), anyList(), eq("1001"), eq("ORD-9002")))
                .thenReturn(List.of(2L, 9L));
        when(luaExecutor.parse(List.of(2L, 9L))).thenReturn(new long[]{2L, 9L});

        String orderId = seckillService.seckill(2001L);
        assertEquals("ORD-9002", orderId);
    }

    private SeckillActivity activeActivity(long id) {
        SeckillActivity a = new SeckillActivity();
        a.setId(id);
        a.setProductName("iPhone");
        a.setSeckillPrice(new BigDecimal("5999.00"));
        a.setStartTime(LocalDateTime.now().minusHours(1));
        a.setEndTime(LocalDateTime.now().plusHours(1));
        return a;
    }
}
