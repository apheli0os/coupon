package com.mall.coupon.consumer;

import com.mall.common.message.SeckillOrderMessage;
import com.mall.coupon.service.CouponSettlementService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OrderCancelCouponRefundConsumerTest {

    @Test
    void onOrderCancelled_shouldRefundCouponsBoundToCancelledOrders() {
        CouponSettlementService settlementService = mock(CouponSettlementService.class);

        SeckillOrderMessage m1 = new SeckillOrderMessage();
        m1.setOrderId("ORD-1");
        m1.setUserId(1001L);
        SeckillOrderMessage m2 = new SeckillOrderMessage();
        m2.setOrderId("ORD-2");
        m2.setUserId(1002L);
        SeckillOrderMessage blank = new SeckillOrderMessage();
        blank.setOrderId(" ");

        OrderCancelCouponRefundConsumer consumer = new OrderCancelCouponRefundConsumer(settlementService);
        consumer.onOrderCancelled(List.of(m1, m2, blank));

        verify(settlementService).unlockOrRefundByOrder("ORD-1", 1001L);
        verify(settlementService).unlockOrRefundByOrder("ORD-2", 1002L);
        verify(settlementService, never()).unlockOrRefundByOrder(" ", null);
    }
}
