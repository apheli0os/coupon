package com.mall.order.job;

import com.mall.common.seckill.SeckillReservationStore;
import com.mall.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillReservationRecoveryJob {

    private static final String DUE_KEYS = "seckill:{*}:reservations:due";
    private static final Pattern ACTIVITY_DUE_KEY = Pattern.compile("seckill:\\{(\\d+)\\}:reservations:due");
    private static final int BATCH_SIZE = 100;
    private static final String RECOVERY_LOCK = "lock:job:order:seckill-reservation";

    private final RedissonClient redissonClient;
    private final SeckillReservationStore reservations;
    private final OrderService orderService;

    @Scheduled(fixedDelayString = "${mall.order.reservation-scan-ms:10000}", initialDelay = 10000)
    public void recoverDueReservations() {
        RLock lock = redissonClient.getLock(RECOVERY_LOCK);
        if (!lock.tryLock()) {
            return;
        }
        try {
            for (String dueKey : redissonClient.getKeys().getKeysByPattern(DUE_KEYS, BATCH_SIZE)) {
                Matcher key = ACTIVITY_DUE_KEY.matcher(dueKey);
                if (key.matches()) {
                    recoverActivity(Long.valueOf(key.group(1)));
                }
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void recoverActivity(Long activityId) {
        for (String orderId : reservations.dueOrderIds(activityId, BATCH_SIZE)) {
            try {
                reservations.recover(activityId, orderId).ifPresent(orderService::createSeckillOrder);
            } catch (RuntimeException e) {
                log.warn("恢复秒杀预占失败 activityId={} orderId={}", activityId, orderId, e);
            }
        }
    }
}
