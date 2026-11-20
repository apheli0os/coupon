package com.mall.seckill.service.impl;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.mall.common.R;
import com.mall.common.ResultCode;
import com.mall.common.constants.CommonConstant;
import com.mall.common.constants.RedisKey;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BizException;
import com.mall.common.message.OrderQueryResult;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.common.metrics.BusinessMetrics;
import com.mall.common.util.IdGenerator;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.seckill.domain.entity.SeckillActivity;
import com.mall.seckill.domain.vo.SeckillResultVO;
import com.mall.seckill.feign.OrderFeignClient;
import com.mall.seckill.service.SeckillActivityService;
import com.mall.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    private final SeckillActivityService activityService;
    private final LuaScriptExecutor luaExecutor;
    private final StringRedisTemplate stringRedisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final IdGenerator idGenerator;
    private final OrderFeignClient orderFeignClient;
    private final BusinessMetrics metrics;

    /**
     * 秒杀：Redis 分桶 Lua 原子预扣 + Kafka 异步落单。
     *
     * <p>用「Redis Token 凭证 + Kafka at-least-once + 消费端幂等 + 定时对账」替代
     * RocketMQ 事务消息，不再依赖 Broker 的 HALF 消息与事务回查：
     * 1) Lua 预扣后写入 token；
     * 2) 同步发送 Kafka 并等待 ack；
     * 3) 发送失败立即回滚库存并删除 token；
     * 4) 消费者只在 token 匹配时落单，靠唯一索引幂等，落单后删除 token。</p>
     */
    @Override
    @SentinelResource(value = "seckill", blockHandler = "seckillBlockHandler")
    public String seckill(Long activityId) {
        long start = System.nanoTime();
        long userId = UserContext.getRequired();
        SeckillActivity activity = activityService.getDetail(activityId);
        if (activity == null) {
            throw new BizException(ResultCode.SECKILL_ACTIVITY_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime())) {
            throw new BizException(ResultCode.SECKILL_NOT_STARTED);
        }
        if (now.isAfter(activity.getEndTime())) {
            throw new BizException(ResultCode.SECKILL_ENDED);
        }

        // KEYS = N 个库存桶 + 已购用户 Set + 活动状态
        List<String> keys = new java.util.ArrayList<>();
        for (int i = 0; i < RedisKey.SECKILL_STOCK_BUCKETS; i++) {
            keys.add(RedisKey.seckillStockBucket(activityId, i));
        }
        keys.add(RedisKey.seckillUsers(activityId));
        keys.add(RedisKey.seckillStatus(activityId));
        int startBucket = RedisKey.bucketOf(userId);
        long[] res = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_SECKILL, keys,
                String.valueOf(userId), startBucket, RedisKey.SECKILL_STOCK_BUCKETS));
        int deductedBucket = (int) res[1];
        switch ((int) res[0]) {
            case 0 -> { /* 拿到抢购资格 */ }
            case -1 -> throw new BizException(ResultCode.SECKILL_NOT_STARTED);
            case -2 -> { metrics.recordRejected(); throw new BizException(ResultCode.SECKILL_DUPLICATE); }
            case -3 -> { metrics.recordSoldOut(); throw new BizException(ResultCode.SECKILL_SOLD_OUT); }
            default -> throw new BizException(ResultCode.SECKILL_SYSTEM_BUSY);
        }

        String orderId = idGenerator.nextIdStr();
        String tokenKey = RedisKey.seckillOrderToken(activityId, userId);
        try {
            // 本地凭证：消费端据此判定消息有效，避免旧消息误落单
            stringRedisTemplate.opsForValue().set(tokenKey, orderId,
                    Duration.ofSeconds(RedisKey.TOKEN_TTL_SECONDS));

            SeckillOrderMessage message = new SeckillOrderMessage();
            message.setOrderId(orderId);
            message.setUserId(userId);
            message.setActivityId(activityId);
            message.setProductName(activity.getProductName());
            message.setSeckillPrice(activity.getSeckillPrice());
            message.setQuantity(1);
            message.setStockBucket(deductedBucket);
            message.setCreateTime(now);

            // orderId 作为分区键：同一订单顺序消费；失败由 Kafka 重投，不依赖事务回查
            SendResult<String, Object> result = kafkaTemplate.send(
                    CommonConstant.MQ_TOPIC_SECKILL, orderId, message).get(3, TimeUnit.SECONDS);
            var metadata = result.getRecordMetadata();
            log.debug("秒杀订单消息已投递 partition={} offset={} orderId={}",
                    metadata.partition(), metadata.offset(), orderId);
        } catch (Exception e) {
            // Kafka 未确认，归还库存并清掉凭证，避免「扣了库存却没订单」
            rollbackSeckill(activityId, userId, deductedBucket);
            stringRedisTemplate.delete(tokenKey);
            log.error("秒杀订单消息发送失败，已回滚库存 activityId={} userId={} orderId={}",
                    activityId, userId, orderId, e);
            throw new BizException(ResultCode.MQ_SEND_FAILED);
        }

        // 销量计数已下移到 SeckillSoldConsumer（Kafka 异步），请求线程不碰 DB
        metrics.recordSuccess();
        metrics.recordLatency(Duration.ofNanos(System.nanoTime() - start));
        log.info("秒杀抢购成功 activityId={} userId={} orderId={}", activityId, userId, orderId);
        return orderId;
    }

    /**
     * Sentinel 限流降级。返回类型必须与业务方法 seckill() 一致（String），
     * 否则 Sentinel 匹配不到 blockHandler，FlowException 会逃逸并被 JDK 动态代理
     * 包装成 UndeclaredThrowableException，最终误报 500 而不是 429。
     */
    public String seckillBlockHandler(Long activityId, BlockException e) {
        metrics.recordRejected();
        throw new BizException(ResultCode.TOO_MANY_REQUESTS);
    }

    private void rollbackSeckill(Long activityId, long userId, int bucket) {
        try {
            luaExecutor.execute(LuaScriptExecutor.SCRIPT_SECKILL_ROLLBACK,
                    List.of(RedisKey.seckillStockBucket(activityId, bucket), RedisKey.seckillUsers(activityId)),
                    String.valueOf(userId));
        } catch (Exception ex) {
            log.error("秒杀补偿失败 activityId={} userId={} 需人工对账", activityId, userId, ex);
        }
    }

    @Override
    public SeckillResultVO result(Long activityId) {
        long userId = UserContext.getRequired();
        SeckillActivity activity = activityService.getDetail(activityId);
        SeckillResultVO vo = new SeckillResultVO();
        if (activity != null) {
            vo.setProductName(activity.getProductName());
            vo.setSeckillPrice(activity.getSeckillPrice());
        }
        R<OrderQueryResult> response = orderFeignClient.findByActivity(activityId, userId);
        if (response == null || !response.isSuccess() || response.getData() == null) {
            vo.setMessage("抢购处理中或未抢到");
            return vo;
        }
        OrderQueryResult order = response.getData();
        vo.setOrderId(order.getOrderId());
        vo.setOrderStatus(order.getStatus());
        vo.setStatusDesc(order.getStatusDesc());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setMessage("抢购成功");
        return vo;
    }
}
