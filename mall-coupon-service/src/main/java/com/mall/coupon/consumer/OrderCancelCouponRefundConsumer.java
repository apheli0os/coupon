package com.mall.coupon.consumer;

import com.mall.common.constants.CommonConstant;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.coupon.service.CouponSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OrderCancelCouponRefundConsumer {

    private final CouponSettlementService settlementService;

    @KafkaListener(topics = CommonConstant.MQ_TOPIC_SECKILL_CANCEL,
            groupId = CommonConstant.MQ_GROUP_COUPON_CANCEL_REFUND)
    public void onOrderCancelled(List<SeckillOrderMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (SeckillOrderMessage msg : messages) {
            if (msg == null || msg.getOrderId() == null || msg.getOrderId().isBlank()) {
                continue;
            }
            settlementService.unlockOrRefundByOrder(msg.getOrderId(), msg.getUserId());
        }
    }
}
