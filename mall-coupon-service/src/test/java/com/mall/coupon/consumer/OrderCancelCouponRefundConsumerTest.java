package com.mall.coupon.consumer;

import com.mall.common.message.SeckillOrderMessage;
import com.mall.coupon.mapper.UserCouponMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCancelCouponRefundConsumerTest {

    @Test
    void onOrderCancelled_shouldRefundCouponsBoundToCancelledOrders() {
        UserCouponMapper mapper = mock(UserCouponMapper.class);
        when(mapper.refundByOrderId("ORD-1")).thenReturn(1);
        when(mapper.refundByOrderId("ORD-2")).thenReturn(0);

        SeckillOrderMessage m1 = new SeckillOrderMessage();
        m1.setOrderId("ORD-1");
        SeckillOrderMessage m2 = new SeckillOrderMessage();
        m2.setOrderId("ORD-2");
        SeckillOrderMessage blank = new SeckillOrderMessage();
        blank.setOrderId(" ");

        OrderCancelCouponRefundConsumer consumer = new OrderCancelCouponRefundConsumer(mapper);
        consumer.onOrderCancelled(List.of(m1, m2, blank));

        verify(mapper).refundByOrderId("ORD-1");
        verify(mapper).refundByOrderId("ORD-2");
        verify(mapper, never()).refundByOrderId(" ");
    }
}
