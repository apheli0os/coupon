package com.mall.seckill.service.impl;

import com.mall.common.ResultCode;
import com.mall.common.constants.CommonConstant;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BizException;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.common.metrics.BusinessMetrics;
import com.mall.common.seckill.SeckillReservationStore;
import com.mall.common.util.IdGenerator;
import com.mall.seckill.domain.entity.SeckillActivity;
import com.mall.seckill.feign.OrderFeignClient;
import com.mall.seckill.service.SeckillActivityService;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillServiceImplTest {

    private SeckillActivityService activityService;
    private SeckillReservationStore reservations;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private IdGenerator idGenerator;
    private OrderFeignClient orderFeignClient;
    private BusinessMetrics metrics;
    private SeckillServiceImpl seckillService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        activityService = mock(SeckillActivityService.class);
        reservations = mock(SeckillReservationStore.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        idGenerator = mock(IdGenerator.class);
        orderFeignClient = mock(OrderFeignClient.class);
        metrics = mock(BusinessMetrics.class);
        seckillService = new SeckillServiceImpl(
                activityService, reservations, kafkaTemplate,
                idGenerator, orderFeignClient, metrics);
        UserContext.set(1001L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void seckill_shouldResolveUnclaimedReservationWhenKafkaSendFails() {
        when(activityService.getDetail(2001L)).thenReturn(activeActivity(2001L));
        when(reservations.reserve(any(SeckillOrderMessage.class))).thenReturn(new long[]{0L, 4L});
        when(idGenerator.nextIdStr()).thenReturn("ORD-9001");
        when(kafkaTemplate.send(anyString(), eq("ORD-9001"), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("timeout")));

        BizException ex = assertThrows(BizException.class, () -> seckillService.seckill(2001L));
        assertEquals(ResultCode.MQ_SEND_FAILED.getCode(), ex.getCode());
        verify(reservations).confirmClaimOrRelease(2001L, "ORD-9001");
    }

    @Test
    void seckill_shouldReturnOrderIdWhenConsumerAlreadyClaimedReservationOnKafkaTimeout() {
        when(activityService.getDetail(2001L)).thenReturn(activeActivity(2001L));
        when(reservations.reserve(any(SeckillOrderMessage.class))).thenReturn(new long[]{0L, 4L});
        when(idGenerator.nextIdStr()).thenReturn("ORD-9002");
        when(kafkaTemplate.send(anyString(), eq("ORD-9002"), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("ack timeout")));
        when(reservations.confirmClaimOrRelease(2001L, "ORD-9002")).thenReturn(true);

        String orderId = seckillService.seckill(2001L);
        assertEquals("ORD-9002", orderId);
    }

    @Test
    void seckill_shouldPropagateProductCodeInOrderMessage() {
        when(activityService.getDetail(2001L)).thenReturn(activeActivity(2001L));
        when(reservations.reserve(any(SeckillOrderMessage.class))).thenReturn(new long[]{0L, 4L});
        when(idGenerator.nextIdStr()).thenReturn("ORD-9003");
        SendResult<String, Object> sendResult = new SendResult<>(
                null,
                new RecordMetadata(new TopicPartition(CommonConstant.MQ_TOPIC_SECKILL, 0), 0, 0, 0, 0, 0));
        when(kafkaTemplate.send(anyString(), eq("ORD-9003"), any()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        String orderId = seckillService.seckill(2001L);
        assertEquals("ORD-9003", orderId);
        ArgumentCaptor<SeckillOrderMessage> captor = ArgumentCaptor.forClass(SeckillOrderMessage.class);
        verify(kafkaTemplate).send(eq(CommonConstant.MQ_TOPIC_SECKILL), eq("ORD-9003"), captor.capture());
        assertEquals("SKU-2026-001", captor.getValue().getProductCode());
        assertEquals(4, captor.getValue().getStockBucket());
        assertTrue(captor.getValue().getReservationTracked());
    }

    private SeckillActivity activeActivity(long id) {
        SeckillActivity a = new SeckillActivity();
        a.setId(id);
        a.setProductCode("SKU-2026-001");
        a.setProductName("iPhone");
        a.setSeckillPrice(new BigDecimal("5999.00"));
        a.setStartTime(LocalDateTime.now().minusHours(1));
        a.setEndTime(LocalDateTime.now().plusHours(1));
        return a;
    }
}
