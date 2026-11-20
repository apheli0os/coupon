package com.mall.order.service.impl;

import com.mall.common.ResultCode;
import com.mall.common.constants.CommonConstant;
import com.mall.common.constants.RedisKey;
import com.mall.common.exception.BizException;
import com.mall.common.message.OrderQueryResult;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.order.domain.entity.SeckillOrder;
import com.mall.order.mapper.SeckillOrderMapper;
import com.mall.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final SeckillOrderMapper orderMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final LuaScriptExecutor luaExecutor;
    private final TransactionTemplate transactionTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createSeckillOrder(SeckillOrderMessage message) {
        String tokenKey = RedisKey.seckillOrderToken(message.getActivityId(), message.getUserId());
        if (orderMapper.selectById(message.getOrderId()) != null) {
            markTokenConsumed(tokenKey, message.getOrderId());
            log.info("订单已存在，幂等跳过 orderId={}", message.getOrderId());
            return;
        }

        // 严格 CAS 认领凭证：token 缺失（生产端超时已回滚库存）或不匹配时一律拒绝落单，彻底消除超卖
        long[] claimRes = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_TOKEN_CLAIM,
                List.of(tokenKey),
                message.getOrderId(),
                RedisKey.TOKEN_TTL_SECONDS));
        if (claimRes[0] != 1L) {
            log.warn("凭证缺失、已被生产端回滚或属于其他订单，拒绝落单以防超卖 orderId={} activityId={} userId={}",
                    message.getOrderId(), message.getActivityId(), message.getUserId());
            return;
        }

        SeckillOrder order = new SeckillOrder();
        order.setOrderId(message.getOrderId());
        order.setUserId(message.getUserId());
        order.setActivityId(message.getActivityId());
        order.setProductName(message.getProductName());
        order.setSeckillPrice(message.getSeckillPrice());
        order.setQuantity(message.getQuantity() == null ? 1 : message.getQuantity());
        BigDecimal price = message.getSeckillPrice() == null ? BigDecimal.ZERO : message.getSeckillPrice();
        order.setTotalAmount(price.multiply(BigDecimal.valueOf(order.getQuantity())));
        order.setStockBucket(message.getStockBucket() == null ? 0 : message.getStockBucket());
        order.setStatus(0);
        order.setStockRestored(0);
        order.setCreateTime(message.getCreateTime() == null ? LocalDateTime.now() : message.getCreateTime());

        try {
            orderMapper.insert(order);
            scheduleMarkTokenConsumed(tokenKey, message.getOrderId());
            log.info("秒杀订单落库成功 orderId={} userId={}", order.getOrderId(), order.getUserId());
        } catch (DuplicateKeyException e) {
            // 主键 order_id 与活跃唯一索引 (activity_id, user_id, active_flag) 兜底幂等
            scheduleMarkTokenConsumed(tokenKey, message.getOrderId());
            log.info("消息重复消费，幂等忽略 orderId={}", message.getOrderId());
        }
    }

    @Override
    public List<SeckillOrder> findExpiredUnpaid(LocalDateTime threshold, int limit) {
        return orderMapper.findExpiredUnpaid(threshold, limit);
    }

    @Override
    public List<SeckillOrder> findPendingStockRestore(int limit) {
        return orderMapper.findPendingStockRestore(limit);
    }

    @Override
    public SeckillOrder findByActivityAndUser(Long activityId, Long userId) {
        return orderMapper.findByActivityAndUser(activityId, userId);
    }

    @Override
    public SeckillOrder findByOrderId(String orderId) {
        return orderMapper.selectById(orderId);
    }

    @Override
    public SeckillOrder findByOrderIdForUser(String orderId, long userId, boolean isAdmin) {
        SeckillOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!isAdmin && !Long.valueOf(userId).equals(order.getUserId())) {
            throw new BizException(ResultCode.FORBIDDEN, "无权查看他人订单");
        }
        return order;
    }

    @Override
    public OrderQueryResult queryResult(Long activityId, Long userId) {
        return toQueryResult(findByActivityAndUser(activityId, userId));
    }

    @Override
    public OrderQueryResult queryByOrderId(String orderId) {
        return toQueryResult(orderMapper.selectById(orderId));
    }

    @Override
    public boolean exists(String orderId) {
        return orderMapper.selectById(orderId) != null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pay(String orderId, long userId) {
        SeckillOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!Long.valueOf(userId).equals(order.getUserId())) {
            throw new BizException(ResultCode.FORBIDDEN, "无权支付他人订单");
        }
        if (orderMapper.pay(orderId) == 0) {
            throw new BizException(ResultCode.ORDER_STATUS_INVALID);
        }
    }

    @Override
    public void cancel(String orderId, long userId, boolean isAdmin) {
        SeckillOrder cancelled = transactionTemplate.execute(status -> {
            SeckillOrder order = orderMapper.selectById(orderId);
            if (order == null) {
                throw new BizException(ResultCode.ORDER_NOT_FOUND);
            }
            if (!isAdmin && !Long.valueOf(userId).equals(order.getUserId())) {
                throw new BizException(ResultCode.FORBIDDEN, "无权取消他人订单");
            }
            if (orderMapper.cancel(orderId) == 0) {
                throw new BizException(ResultCode.ORDER_STATUS_INVALID);
            }
            return order;
        });
        if (cancelled != null) {
            tryRestoreStockQuietly(cancelled);
        }
    }

    @Override
    public boolean cancelBySystem(String orderId) {
        SeckillOrder cancelled = transactionTemplate.execute(status -> {
            SeckillOrder order = orderMapper.selectById(orderId);
            if (order == null || orderMapper.cancel(orderId) == 0) {
                return null;
            }
            return order;
        });
        if (cancelled == null) {
            return false;
        }
        tryRestoreStockQuietly(cancelled);
        return true;
    }

    @Override
    public void restoreStockForCancelledOrder(SeckillOrder order) {
        int bucket = order.getStockBucket() == null ? 0 : order.getStockBucket();
        luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_SECKILL_CANCEL_ROLLBACK,
                List.of(
                        RedisKey.seckillStockBucket(order.getActivityId(), bucket),
                        RedisKey.seckillUsers(order.getActivityId()),
                        RedisKey.seckillOrderRollbackGuard(order.getActivityId(), order.getOrderId()),
                        RedisKey.seckillOrderToken(order.getActivityId(), order.getUserId())),
                String.valueOf(order.getUserId()),
                order.getOrderId(),
                RedisKey.ROLLBACK_GUARD_TTL_SECONDS);
        orderMapper.markStockRestored(order.getOrderId());
        publishCancelEventQuietly(order);
        log.info("取消订单归还库存成功 orderId={} activityId={} bucket={}",
                order.getOrderId(), order.getActivityId(), bucket);
    }

    private void tryRestoreStockQuietly(SeckillOrder order) {
        try {
            restoreStockForCancelledOrder(order);
        } catch (Exception e) {
            // DB 已落 stock_restored=0，后台定时任务会在下轮扫描幂等补齐归还，避免永久丢库存
            log.error("取消订单归还 Redis 库存失败，待后台补偿重试 orderId={}", order.getOrderId(), e);
        }
    }

    private void publishCancelEventQuietly(SeckillOrder order) {
        try {
            SeckillOrderMessage event = new SeckillOrderMessage();
            event.setOrderId(order.getOrderId());
            event.setUserId(order.getUserId());
            event.setActivityId(order.getActivityId());
            event.setQuantity(order.getQuantity() == null ? 1 : order.getQuantity());
            event.setStockBucket(order.getStockBucket());
            event.setCreateTime(LocalDateTime.now());
            kafkaTemplate.send(CommonConstant.MQ_TOPIC_SECKILL_CANCEL, order.getOrderId(), event);
        } catch (Exception e) {
            log.warn("发送取消扣减销量事件失败（不影响核心库存一致性） orderId={}", order.getOrderId(), e);
        }
    }

    private void scheduleMarkTokenConsumed(String tokenKey, String orderId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    markTokenConsumed(tokenKey, orderId);
                }
            });
        } else {
            markTokenConsumed(tokenKey, orderId);
        }
    }

    private void markTokenConsumed(String tokenKey, String orderId) {
        try {
            stringRedisTemplate.opsForValue().set(tokenKey, "CONSUMED:" + orderId,
                    Duration.ofSeconds(RedisKey.CONSUMED_TOKEN_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("回写已消费 token 失败（不影响 DB 幂等） orderId={}", orderId, e);
        }
    }

    private OrderQueryResult toQueryResult(SeckillOrder order) {
        if (order == null) {
            return null;
        }
        OrderQueryResult result = new OrderQueryResult();
        result.setOrderId(order.getOrderId());
        result.setUserId(order.getUserId());
        result.setStatus(order.getStatus());
        result.setStatusDesc(statusDesc(order.getStatus()));
        result.setProductName(order.getProductName());
        result.setTotalAmount(order.getTotalAmount());
        return result;
    }

    private String statusDesc(Integer status) {
        if (status == null) {
            return "未知状态";
        }
        return switch (status) {
            case 0 -> "待支付";
            case 1 -> "已支付";
            case 2 -> "已取消";
            default -> "未知状态";
        };
    }
}
