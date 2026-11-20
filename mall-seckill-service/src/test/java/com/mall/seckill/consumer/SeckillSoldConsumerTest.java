package com.mall.seckill.consumer;

import com.mall.common.constants.RedisKey;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.seckill.mapper.SeckillActivityMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillSoldConsumerTest {

    @Test
    @SuppressWarnings("unchecked")
    void onSoldBatch_shouldDeduplicateByOrderIdAndAggregatePerActivity() {
        SeckillActivityMapper mapper = mock(SeckillActivityMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        when(valueOps.setIfAbsent(eq(RedisKey.seckillSoldDedup(2001L, "O1")), eq("1"), any(Duration.class)))
                .thenReturn(true);
        when(valueOps.setIfAbsent(eq(RedisKey.seckillSoldDedup(2001L, "O2")), eq("1"), any(Duration.class)))
                .thenReturn(false);
        when(valueOps.setIfAbsent(eq(RedisKey.seckillSoldDedup(2001L, "O3")), eq("1"), any(Duration.class)))
                .thenReturn(true);

        SeckillSoldConsumer consumer = new SeckillSoldConsumer(mapper, redis);
        consumer.onSoldBatch(List.of(msg(2001L, "O1"), msg(2001L, "O2"), msg(2001L, "O3")));

        verify(mapper).incrSoldBy(2001L, 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onCancelBatch_shouldDeduplicateAndDecrementSoldCount() {
        SeckillActivityMapper mapper = mock(SeckillActivityMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        when(valueOps.setIfAbsent(eq(RedisKey.seckillCancelDedup(2001L, "O1")), eq("1"), any(Duration.class)))
                .thenReturn(true);

        SeckillSoldConsumer consumer = new SeckillSoldConsumer(mapper, redis);
        consumer.onCancelBatch(List.of(msg(2001L, "O1")));

        verify(mapper).decrSoldBy(2001L, 1);
    }

    private SeckillOrderMessage msg(long activityId, String orderId) {
        SeckillOrderMessage m = new SeckillOrderMessage();
        m.setActivityId(activityId);
        m.setOrderId(orderId);
        m.setQuantity(1);
        return m;
    }
}
