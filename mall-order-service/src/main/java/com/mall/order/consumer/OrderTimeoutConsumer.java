package com.mall.order.consumer;

import com.mall.common.constants.RedisKey;
import com.mall.order.domain.entity.SeckillOrder;
import com.mall.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 15 分钟未支付自动关单 + 取消单库存归还补偿任务。
 *
 * <p>1) 多实例部署下通过 Redisson 分布式锁协调，避免并发争抢同一批订单；
 * 2) 每轮循环排空超时订单（上限 20 批），避免大促后每 30s 仅关 100 单造成积压；
 * 3) 额外扫描 status=2 且 stock_restored=0 的取消单，兜底补偿因 Redis 闪断/进程宕机未归还的库存。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutConsumer {

    private static final long TIMEOUT_MINUTES = 15;
    private static final int BATCH_SIZE = 100;
    private static final int MAX_LOOPS_PER_TICK = 20;

    private final OrderService orderService;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelayString = "${mall.order.timeout-scan-ms:30000}", initialDelay = 30000)
    public void scanTimeoutOrders() {
        RLock lock = redissonClient.getLock(RedisKey.orderTimeoutJobLock());
        boolean locked = false;
        try {
            // 使用 watchdog 自动续期（不指定 leaseTime），避免大批量处理时锁提前过期
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            drainExpiredUnpaid();
            drainPendingStockRestores();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void drainExpiredUnpaid() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);
        int loops = 0;
        List<SeckillOrder> expired;
        do {
            expired = orderService.findExpiredUnpaid(threshold, BATCH_SIZE);
            for (SeckillOrder order : expired) {
                try {
                    orderService.cancelBySystem(order.getOrderId());
                } catch (Exception e) {
                    log.error("超时关单失败 orderId={} 将在下轮重试", order.getOrderId(), e);
                }
            }
            loops++;
        } while (expired.size() == BATCH_SIZE && loops < MAX_LOOPS_PER_TICK);
    }

    private void drainPendingStockRestores() {
        int loops = 0;
        List<SeckillOrder> pending;
        do {
            pending = orderService.findPendingStockRestore(BATCH_SIZE);
            for (SeckillOrder order : pending) {
                try {
                    orderService.restoreStockForCancelledOrder(order);
                } catch (Exception e) {
                    log.error("补偿归还取消单库存失败 orderId={} 将在下轮重试", order.getOrderId(), e);
                }
            }
            loops++;
        } while (pending.size() == BATCH_SIZE && loops < MAX_LOOPS_PER_TICK);
    }
}
