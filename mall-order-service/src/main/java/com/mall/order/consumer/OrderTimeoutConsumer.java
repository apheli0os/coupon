package com.mall.order.consumer;

import com.mall.common.constants.RedisKey;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.order.domain.entity.SeckillOrder;
import com.mall.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 15 分钟未支付自动关单（定时扫描替代 MQ 延迟消息）。
 *
 * <p>Kafka 不提供任意时长延迟队列，改为每 30 秒扫描 status=0 且 create_time 超时的订单，
 * 取消订单并归还「下单时记录的库存桶」，避免 MQ 延迟消息在 Broker 重启后丢失。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutConsumer {

    private static final long TIMEOUT_MINUTES = 15;
    private static final int BATCH_SIZE = 100;

    private final OrderService orderService;
    private final StringRedisTemplate stringRedisTemplate;
    private final LuaScriptExecutor luaExecutor;

    @Scheduled(fixedDelayString = "${mall.order.timeout-scan-ms:30000}", initialDelay = 30000)
    public void scanTimeoutOrders() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);
        List<SeckillOrder> expired = orderService.findExpiredUnpaid(threshold, BATCH_SIZE);
        for (SeckillOrder order : expired) {
            try {
                orderService.cancel(order.getOrderId());
                int bucket = order.getStockBucket() == null ? 0 : order.getStockBucket();
                luaExecutor.execute(LuaScriptExecutor.SCRIPT_SECKILL_ROLLBACK,
                        List.of(RedisKey.seckillStockBucket(order.getActivityId(), bucket),
                                RedisKey.seckillUsers(order.getActivityId())),
                        String.valueOf(order.getUserId()));
                stringRedisTemplate.delete(
                        RedisKey.seckillOrderToken(order.getActivityId(), order.getUserId()));
                log.info("超时关单并归还库存成功 orderId={} bucket={}", order.getOrderId(), bucket);
            } catch (Exception e) {
                log.error("超时关单失败 orderId={} 将在下轮重试", order.getOrderId(), e);
            }
        }
    }
}
