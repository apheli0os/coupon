package com.mall.coupon.job;

import com.mall.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时扫描过期优惠券，单批 500 条循环处理，避免长事务 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponExpireJob {

    private final CouponService couponService;

    @Scheduled(cron = "0 0/5 * * * ?", zone = "GMT+8")
    public void expire() {
        int total = 0;
        int rows;
        do {
            rows = couponService.expireBatch();
            total += rows;
        } while (rows > 0);
        if (total > 0) {
            log.info("过期优惠券处理完成 count={}", total);
        }
    }
}
