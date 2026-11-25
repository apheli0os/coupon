package com.mall.order.job;

import com.mall.common.constants.RedisKey;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.common.seckill.SeckillReservationStore;
import com.mall.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SeckillReservationRecoveryJobTest {

    @Test
    void shouldReleaseUnclaimedAndResumeClaimedReservations() {
        long activityId = 2701L;
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        RKeys keys = mock(RKeys.class);
        SeckillReservationStore store = mock(SeckillReservationStore.class);
        OrderService orders = mock(OrderService.class);
        SeckillOrderMessage claimed = new SeckillOrderMessage();
        claimed.setOrderId("CLAIMED");
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.getKeysByPattern(anyString(), eq(100))).thenReturn(List.of(RedisKey.seckillReservationsDue(activityId)));
        when(store.dueOrderIds(activityId, 100)).thenReturn(Set.of("UNCLAIMED", claimed.getOrderId()));
        when(store.recover(activityId, "UNCLAIMED")).thenReturn(Optional.empty());
        when(store.recover(activityId, claimed.getOrderId())).thenReturn(Optional.of(claimed));

        new SeckillReservationRecoveryJob(redisson, store, orders).recoverDueReservations();

        verify(store).recover(activityId, "UNCLAIMED");
        verify(orders).createSeckillOrder(claimed);
        verifyNoMoreInteractions(orders);
        verify(lock).unlock();
    }
}
