package com.mall.order.consumer;

import com.mall.common.constants.CommonConstant;
import com.mall.common.message.SeckillOrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 死信队列兜底：Kafka 重试耗尽的秒杀订单消息进入 *.DLQ topic。
 * 只记录详细上下文并告警，由运维/定时任务人工补偿，避免消息静默丢失。
 */
@Slf4j
@Component
public class SeckillOrderDlqConsumer {

    @KafkaListener(topics = CommonConstant.MQ_TOPIC_SECKILL_DLQ,
            groupId = "seckill-order-dlq-monitor-group")
    public void onMessage(SeckillOrderMessage message) {
        log.error("[DLQ-ALERT] 秒杀订单消息进入死信队列，需人工介入 orderId={} userId={} activityId={} bucket={}",
                message.getOrderId(), message.getUserId(), message.getActivityId(), message.getStockBucket());
    }
}
