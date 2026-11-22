package com.mall.seckill.consumer;

import com.mall.common.constants.CommonConstant;
import com.mall.common.constants.RedisKey;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.seckill.mapper.SeckillActivityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillSoldConsumer {

    private static final String EVENT_SOLD = "SOLD";
    private static final String EVENT_CANCEL = "CANCEL";

    private final SeckillActivityMapper activityMapper;
    private final LuaScriptExecutor luaExecutor;
    private final RedissonClient redissonClient;

    @KafkaListener(topics = CommonConstant.MQ_TOPIC_SECKILL_ORDER_CREATED,
            groupId = CommonConstant.MQ_GROUP_SECKILL_SOLD,
            containerFactory = "soldBatchListenerFactory")
    public void onSoldBatch(List<SeckillOrderMessage> messages) {
        applyBatch(messages, EVENT_SOLD);
    }

    @KafkaListener(topics = CommonConstant.MQ_TOPIC_SECKILL_CANCEL,
            groupId = "seckill-cancel-counter-group",
            containerFactory = "soldBatchListenerFactory")
    public void onCancelBatch(List<SeckillOrderMessage> messages) {
        applyBatch(messages, EVENT_CANCEL);
    }

    private void applyBatch(List<SeckillOrderMessage> messages, String event) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Map<Long, List<SeckillOrderMessage>> grouped = new LinkedHashMap<>();
        for (SeckillOrderMessage msg : messages) {
            if (msg == null || msg.getActivityId() == null || msg.getOrderId() == null) {
                continue;
            }
            grouped.computeIfAbsent(msg.getActivityId(), k -> new ArrayList<>()).add(msg);
        }

        for (Map.Entry<Long, List<SeckillOrderMessage>> entry : grouped.entrySet()) {
            Long activityId = entry.getKey();
            RLock lock = redissonClient.getLock(RedisKey.seckillSoldLock(activityId));
            boolean locked = false;
            try {
                locked = lock.tryLock(5, TimeUnit.SECONDS);
                if (!locked) {
                    throw new IllegalStateException("获取秒杀销量聚合锁超时 activityId=" + activityId);
                }
                int delta = 0;
                for (SeckillOrderMessage msg : entry.getValue()) {
                    String stateKey = RedisKey.seckillSoldState(activityId, msg.getOrderId());
                    long[] res = luaExecutor.parse(luaExecutor.execute(
                            LuaScriptExecutor.SCRIPT_SECKILL_SOLD_STATE,
                            List.of(stateKey),
                            event,
                            RedisKey.ROLLBACK_GUARD_TTL_SECONDS));
                    int qty = msg.getQuantity() == null ? 1 : msg.getQuantity();
                    delta += (int) res[0] * qty;
                }
                if (delta > 0) {
                    activityMapper.incrSoldBy(activityId, delta);
                } else if (delta < 0) {
                    activityMapper.decrSoldBy(activityId, -delta);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待秒杀销量锁被中断 activityId=" + activityId, e);
            } finally {
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }
}
