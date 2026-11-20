package com.mall.coupon.job;

import com.mall.common.constants.RedisKey;
import com.mall.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** 定时扫描过期优惠券，多实例通过 Redisson 分布式锁互斥，单批 500 条循环处理避免长事务 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponExpireJob {

    private final CouponService couponService;
    private final RedissonClient redissonClient;

    @Scheduled(cron = "0 0/5 * * * ?", zone = "GMT+8")
    public void expire() {
        RLock lock = redissonClient.getLock(RedisKey.couponExpireJobLock());
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            int total = 0;
            int rows;
            do {
                rows = couponService.expireBatch();
                total += rows;
            } while (rows > 0);
            if (total > 0) {
                log.info("过期优惠券处理完成 count={}", total);
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
