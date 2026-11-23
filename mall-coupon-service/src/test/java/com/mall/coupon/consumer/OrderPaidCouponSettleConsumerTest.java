package com.mall.coupon.consumer;

import com.mall.common.message.OrderPaidMessage;
import com.mall.coupon.service.CouponSettlementService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OrderPaidCouponSettleConsumerTest {

    @Test
    void onOrderPaid_shouldSettleBoundOrdersAndSkipEmptyOrUnbound() {
        CouponSettlementService settlementService = mock(CouponSettlementService.class);
        OrderPaidCouponSettleConsumer consumer = new OrderPaidCouponSettleConsumer(settlementService);

        OrderPaidMessage bound = new OrderPaidMessage();
        bound.setOrderId("ORD-1");
        bound.setUserId(1001L);
        bound.setCouponId(501L);

        OrderPaidMessage unbound = new OrderPaidMessage();
        unbound.setOrderId("ORD-2");
        unbound.setUserId(1001L);

        OrderPaidMessage blank = new OrderPaidMessage();
        blank.setOrderId(" ");
        blank.setUserId(1001L);
        blank.setCouponId(502L);

        consumer.onOrderPaid(List.of(bound, unbound, blank));

        verify(settlementService).settleByOrder("ORD-1", 1001L, 501L);
        verify(settlementService, never()).settleByOrder("ORD-2", 1001L, null);
        verify(settlementService, never()).settleByOrder(" ", 1001L, 502L);
    }
}
