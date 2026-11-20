package com.mall.seckill.service.impl;

import com.mall.common.cache.MultiLevelCache;
import com.mall.common.constants.RedisKey;
import com.mall.common.util.IdGenerator;
import com.mall.seckill.domain.entity.SeckillActivity;
import com.mall.seckill.mapper.SeckillActivityMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillActivityServiceImplTest {

    @Test
    @SuppressWarnings("unchecked")
    void publish_shouldBeIdempotentAndPreserveBucketsAndUsersWhenAlreadyActive() throws Exception {
        SeckillActivityMapper mapper = mock(SeckillActivityMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        MultiLevelCache cache = mock(MultiLevelCache.class);
        TransactionTemplate txTemplate = mock(TransactionTemplate.class);
        when(txTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });

        SeckillActivity act = new SeckillActivity();
        act.setId(2001L);
        act.setTotalStock(100);
        act.setSoldCount(20);
        act.setStatus(1);
        act.setStartTime(LocalDateTime.now().minusHours(1));
        act.setEndTime(LocalDateTime.now().plusHours(2));
        when(mapper.selectById(2001L)).thenReturn(act);
        when(redis.hasKey(RedisKey.seckillStockBucket(2001L, 0))).thenReturn(true);

        SeckillActivityServiceImpl service = new SeckillActivityServiceImpl(
                mapper, redis, redisson, mock(IdGenerator.class), cache, txTemplate);
        service.publish(2001L);

        verify(valueOps, never()).set(eq(RedisKey.seckillStockBucket(2001L, 0)), anyString(), any(Duration.class));
        verify(redis, never()).delete(RedisKey.seckillUsers(2001L));
        verify(lock).unlock();
    }
}
