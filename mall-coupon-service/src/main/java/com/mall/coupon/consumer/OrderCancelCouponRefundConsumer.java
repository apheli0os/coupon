package com.mall.coupon.consumer;

import com.mall.common.constants.CommonConstant;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.coupon.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCancelCouponRefundConsumer {

    private final UserCouponMapper userCouponMapper;

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
            int refunded = userCouponMapper.refundByOrderId(msg.getOrderId());
            if (refunded > 0) {
                log.info("订单取消自动退回已核销优惠券 orderId={} refunded={}", msg.getOrderId(), refunded);
            }
        }
    }
}
