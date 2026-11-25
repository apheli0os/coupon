package com.mall.seckill.service.impl;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.mall.common.R;
import com.mall.common.ResultCode;
import com.mall.common.constants.CommonConstant;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BizException;
import com.mall.common.message.OrderQueryResult;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.common.metrics.BusinessMetrics;
import com.mall.common.seckill.SeckillReservationStore;
import com.mall.common.util.IdGenerator;
import com.mall.seckill.domain.entity.SeckillActivity;
import com.mall.seckill.domain.vo.SeckillResultVO;
import com.mall.seckill.feign.OrderFeignClient;
import com.mall.seckill.service.SeckillActivityService;
import com.mall.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    private final SeckillActivityService activityService;
    private final SeckillReservationStore reservations;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final IdGenerator idGenerator;
    private final OrderFeignClient orderFeignClient;
    private final BusinessMetrics metrics;

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

        String orderId = idGenerator.nextIdStr();
        SeckillOrderMessage message = new SeckillOrderMessage();
        message.setOrderId(orderId);
        message.setUserId(userId);
        message.setActivityId(activityId);
        message.setProductCode(activity.getProductCode());
        message.setProductName(activity.getProductName());
        message.setSeckillPrice(activity.getSeckillPrice());
        message.setQuantity(1);
        message.setCreateTime(now);
        message.setReservationTracked(true);

        long[] res = reservations.reserve(message);
        switch ((int) res[0]) {
            case 0 -> message.setStockBucket((int) res[1]);
            case -1 -> throw new BizException(ResultCode.SECKILL_NOT_STARTED);
            case -2 -> { metrics.recordRejected(); throw new BizException(ResultCode.SECKILL_DUPLICATE); }
            case -3 -> { metrics.recordSoldOut(); throw new BizException(ResultCode.SECKILL_SOLD_OUT); }
            default -> throw new BizException(ResultCode.SECKILL_SYSTEM_BUSY);
        }

        try {
            kafkaTemplate.send(CommonConstant.MQ_TOPIC_SECKILL, orderId, message).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            boolean consumerOwns = resolveFailedSend(activityId, orderId);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (!consumerOwns) {
                log.error("秒杀订单消息发送失败 activityId={} userId={} orderId={}",
                        activityId, userId, orderId, e);
                throw new BizException(ResultCode.MQ_SEND_FAILED);
            }
            log.warn("Kafka 发送未确认，消费端已认领预占 activityId={} orderId={}", activityId, orderId);
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

    private boolean resolveFailedSend(Long activityId, String orderId) {
        try {
            return reservations.confirmClaimOrRelease(activityId, orderId);
        } catch (RuntimeException e) {
            log.error("发送失败后的预占处理待巡检 activityId={} orderId={}", activityId, orderId, e);
            return false;
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
