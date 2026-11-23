package com.mall.coupon.job;

import com.mall.common.constants.RedisKey;
import com.mall.coupon.service.CouponSettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponSettlementSweepJob {

    private final CouponSettlementService settlementService;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelayString = "${mall.coupon.settlement-sweep-ms:60000}", initialDelay = 60000)
    public void sweepExpiredLocks() {
        RLock lock = redissonClient.getLock(RedisKey.couponSettlementSweepJobLock());
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            int handled = settlementService.sweepExpiredLocks();
            if (handled > 0) {
                log.info("巡检处理超时锁券结算单 handled={}", handled);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
