package com.mall.coupon.consumer;

import com.mall.common.constants.CommonConstant;
import com.mall.common.message.OrderPaidMessage;
import com.mall.coupon.service.CouponSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OrderPaidCouponSettleConsumer {

    private final CouponSettlementService settlementService;

    @KafkaListener(topics = CommonConstant.MQ_TOPIC_ORDER_PAID,
            groupId = CommonConstant.MQ_GROUP_COUPON_ORDER_PAID)
    public void onOrderPaid(List<OrderPaidMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (OrderPaidMessage msg : messages) {
            if (msg == null
                    || msg.getOrderId() == null
                    || msg.getOrderId().isBlank()
                    || msg.getCouponId() == null) {
                continue;
            }
            settlementService.settleByOrder(msg.getOrderId(), msg.getUserId(), msg.getCouponId());
        }
    }
}
