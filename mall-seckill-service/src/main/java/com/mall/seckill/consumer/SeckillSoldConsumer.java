package com.mall.seckill.consumer;

import com.mall.common.constants.CommonConstant;
import com.mall.common.constants.RedisKey;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.seckill.mapper.SeckillActivityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 秒杀销量计数异步消费者（批量聚合 + orderId 幂等去重 + 取消扣减）。
 *
 * <p>1) 使用 soldBatchListenerFactory 按批拉取，按 activityId 内存聚合后一次 UPDATE DB，消除逐单行锁竞争；
 * 2) 通过 Redis SETNX 按 orderId 去重，避免 Kafka 重投导致 sold_count 虚增或重复扣减；
 * 3) 额外监听 seckill-cancel-topic，在取消/超时关单后同步扣减展示销量。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillSoldConsumer {

    private final SeckillActivityMapper activityMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @KafkaListener(topics = CommonConstant.MQ_TOPIC_SECKILL,
            groupId = CommonConstant.MQ_GROUP_SECKILL_SOLD,
            containerFactory = "soldBatchListenerFactory")
    public void onSoldBatch(List<SeckillOrderMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Map<Long, Integer> counts = new HashMap<>();
        Duration ttl = Duration.ofSeconds(RedisKey.SOLD_DEDUP_TTL_SECONDS);
        for (SeckillOrderMessage msg : messages) {
            if (msg == null || msg.getActivityId() == null || msg.getOrderId() == null) {
                continue;
            }
            String dedupKey = RedisKey.seckillSoldDedup(msg.getActivityId(), msg.getOrderId());
            Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(dedupKey, "1", ttl);
            if (!Boolean.TRUE.equals(first)) {
                continue;
            }
            int qty = msg.getQuantity() == null ? 1 : msg.getQuantity();
            counts.merge(msg.getActivityId(), qty, Integer::sum);
        }
        for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
            try {
                activityMapper.incrSoldBy(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.warn("批量累加销量失败 activityId={} delta={}", entry.getKey(), entry.getValue(), e);
            }
        }
    }

    @KafkaListener(topics = CommonConstant.MQ_TOPIC_SECKILL_CANCEL,
            groupId = "seckill-cancel-counter-group",
            containerFactory = "soldBatchListenerFactory")
    public void onCancelBatch(List<SeckillOrderMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Map<Long, Integer> counts = new HashMap<>();
        Duration ttl = Duration.ofSeconds(RedisKey.SOLD_DEDUP_TTL_SECONDS);
        for (SeckillOrderMessage msg : messages) {
            if (msg == null || msg.getActivityId() == null || msg.getOrderId() == null) {
                continue;
            }
            String dedupKey = RedisKey.seckillCancelDedup(msg.getActivityId(), msg.getOrderId());
            Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(dedupKey, "1", ttl);
            if (!Boolean.TRUE.equals(first)) {
                continue;
            }
            int qty = msg.getQuantity() == null ? 1 : msg.getQuantity();
            counts.merge(msg.getActivityId(), qty, Integer::sum);
        }
        for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
            try {
                activityMapper.decrSoldBy(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.warn("批量扣减取消销量失败 activityId={} delta={}", entry.getKey(), entry.getValue(), e);
            }
        }
    }
}
