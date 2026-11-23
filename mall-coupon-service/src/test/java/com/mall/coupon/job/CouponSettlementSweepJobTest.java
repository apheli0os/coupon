package com.mall.coupon.job;

import com.mall.common.constants.RedisKey;
import com.mall.coupon.service.CouponSettlementService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponSettlementSweepJobTest {

    @Test
    void sweepExpiredLocks_shouldRunWhenLockAcquiredAndSkipWhenContested() throws Exception {
        CouponSettlementService settlementService = mock(CouponSettlementService.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(RedisKey.couponSettlementSweepJobLock())).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true, false);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(settlementService.sweepExpiredLocks()).thenReturn(2);

        CouponSettlementSweepJob job = new CouponSettlementSweepJob(settlementService, redissonClient);
        job.sweepExpiredLocks();
        verify(settlementService).sweepExpiredLocks();
        verify(lock).unlock();

        job.sweepExpiredLocks();
        verify(settlementService).sweepExpiredLocks();
        verify(lock, never()).wait();
    }
}
