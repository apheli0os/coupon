package com.mall.order.consumer;

import com.mall.common.constants.CommonConstant;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 秒杀订单消费者（Kafka）。
 *
 * <p>集群消费 + at-least-once；异常抛出后由 Kafka 重投，配合
 * Redis Token 校验与 DB 唯一索引 (activity_id, user_id) 实现幂等。
 * 15 分钟关单改由 {@link OrderTimeoutConsumer} 定时扫描，不再依赖 MQ 延迟消息。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderConsumer {

    private final OrderService orderService;

    @KafkaListener(topics = CommonConstant.MQ_TOPIC_SECKILL,
            groupId = CommonConstant.MQ_GROUP_SECKILL_ORDER)
    public void onMessage(SeckillOrderMessage message) {
        try {
            orderService.createSeckillOrder(message);
            log.info("秒杀订单消费完成 orderId={} userId={}", message.getOrderId(), message.getUserId());
        } catch (DuplicateKeyException e) {
            log.info("重复消息已幂等处理 orderId={}", message.getOrderId());
        } catch (Exception e) {
            log.error("秒杀订单消费失败，由 Kafka 重投 orderId={}", message.getOrderId(), e);
            throw e;
        }
    }
}
