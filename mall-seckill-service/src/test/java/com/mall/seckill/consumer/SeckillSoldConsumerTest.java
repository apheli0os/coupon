package com.mall.seckill.consumer;

import com.mall.common.constants.CommonConstant;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.seckill.mapper.SeckillActivityMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.kafka.annotation.KafkaListener;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillSoldConsumerTest {

    @Test
    void onSoldBatch_shouldDeduplicateByOrderIdAndAggregatePerActivity() throws Exception {
        SeckillActivityMapper mapper = mock(SeckillActivityMapper.class);
        Map<String, String> stateStore = new ConcurrentHashMap<>();
        SeckillSoldConsumer consumer = newConsumerWithInMemoryStateMachine(mapper, stateStore);

        consumer.onSoldBatch(List.of(msg(2001L, "O1"), msg(2001L, "O2"), msg(2001L, "O2"), msg(2001L, "O3")));

        verify(mapper).incrSoldBy(2001L, 3);
    }

    @Test
    void onCancelBatch_shouldDecrementSoldCountAfterSold() throws Exception {
        SeckillActivityMapper mapper = mock(SeckillActivityMapper.class);
        Map<String, String> stateStore = new ConcurrentHashMap<>();
        SeckillSoldConsumer consumer = newConsumerWithInMemoryStateMachine(mapper, stateStore);

        consumer.onSoldBatch(List.of(msg(2001L, "O1")));
        consumer.onCancelBatch(List.of(msg(2001L, "O1"), msg(2001L, "O1")));

        verify(mapper).incrSoldBy(2001L, 1);
        verify(mapper).decrSoldBy(2001L, 1);
    }

    @Test
    void onSoldBatch_mustListenToOrderCreatedTopicAndNotRawSeckillTopic() throws NoSuchMethodException {
        Method method = SeckillSoldConsumer.class.getMethod("onSoldBatch", List.class);
        KafkaListener listener = method.getAnnotation(KafkaListener.class);
        List<String> topics = List.of(listener.topics());
        assertFalse(topics.contains(CommonConstant.MQ_TOPIC_SECKILL),
                "销量消费者不得直接监听未落单的原始 seckill-topic，否则超时回滚消息会导致 sold_count 永久虚高");
        assertTrue(topics.contains(CommonConstant.MQ_TOPIC_SECKILL_ORDER_CREATED),
                "销量消费者必须监听真实落单后发布的 seckill-order-created-topic");
    }

    @Test
    void cancelArrivingBeforeSold_mustNotLeaveSoldCountInflated() throws Exception {
        SeckillActivityMapper mapper = mock(SeckillActivityMapper.class);
        AtomicInteger soldCount = new AtomicInteger(0);
        when(mapper.incrSoldBy(eq(2001L), anyInt())).thenAnswer(inv -> {
            int delta = inv.getArgument(1);
            soldCount.addAndGet(delta);
            return 1;
        });
        when(mapper.decrSoldBy(eq(2001L), anyInt())).thenAnswer(inv -> {
            int delta = inv.getArgument(1);
            if (soldCount.get() <= 0) {
                return 0;
            }
            soldCount.set(Math.max(0, soldCount.get() - delta));
            return 1;
        });

        Map<String, String> stateStore = new ConcurrentHashMap<>();
        SeckillSoldConsumer consumer = newConsumerWithInMemoryStateMachine(mapper, stateStore);

        consumer.onCancelBatch(List.of(msg(2001L, "O1")));
        consumer.onSoldBatch(List.of(msg(2001L, "O1")));

        assertEquals(0, soldCount.get(),
                "取消事件先到达时，sold_count 最终应保持 0，不得在后续建单事件到达后永久残留 +1");
    }

    private SeckillSoldConsumer newConsumerWithInMemoryStateMachine(
            SeckillActivityMapper mapper, Map<String, String> stateStore) throws Exception {
        LuaScriptExecutor luaExecutor = mock(LuaScriptExecutor.class);
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_SECKILL_SOLD_STATE), anyList(), any(), any()))
                .thenAnswer(inv -> {
                    List<String> keys = inv.getArgument(1);
                    String key = keys.get(0);
                    String event = String.valueOf((Object) inv.getArgument(2));
                    String cur = stateStore.get(key);
                    if ("SOLD".equals(event)) {
                        if (cur == null) {
                            stateStore.put(key, "SOLD");
                            return List.of(1L, 0L);
                        }
                        return List.of(0L, 0L);
                    }
                    if ("CANCEL".equals(event)) {
                        if ("SOLD".equals(cur)) {
                            stateStore.put(key, "CANCELLED");
                            return List.of(-1L, 0L);
                        }
                        if (cur == null) {
                            stateStore.put(key, "CANCELLED");
                            return List.of(0L, 0L);
                        }
                        return List.of(0L, 0L);
                    }
                    return List.of(0L, 0L);
                });
        when(luaExecutor.parse(any())).thenAnswer(inv -> {
            List<?> raw = inv.getArgument(0);
            return new long[]{((Number) raw.get(0)).longValue(), ((Number) raw.get(1)).longValue()};
        });

        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        return new SeckillSoldConsumer(mapper, luaExecutor, redisson);
    }

    private SeckillOrderMessage msg(long activityId, String orderId) {
        SeckillOrderMessage m = new SeckillOrderMessage();
        m.setActivityId(activityId);
        m.setOrderId(orderId);
        m.setQuantity(1);
        return m;
    }
}
