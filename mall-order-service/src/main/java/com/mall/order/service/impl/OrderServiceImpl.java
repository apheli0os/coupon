package com.mall.order.service.impl;

import com.mall.common.ResultCode;
import com.mall.common.constants.RedisKey;
import com.mall.common.exception.BizException;
import com.mall.common.message.OrderQueryResult;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.order.domain.entity.SeckillOrder;
import com.mall.order.mapper.SeckillOrderMapper;
import com.mall.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final SeckillOrderMapper orderMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createSeckillOrder(SeckillOrderMessage message) {
        String tokenKey = RedisKey.seckillOrderToken(message.getActivityId(), message.getUserId());
        String token = stringRedisTemplate.opsForValue().get(tokenKey);
        // Token 存在但订单号不一致：说明是旧消息重投，直接丢弃
        if (StringUtils.hasText(token) && !token.equals(message.getOrderId())) {
            log.warn("过期消息丢弃 orderId={} currentToken={}", message.getOrderId(), token);
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
        order.setCreateTime(message.getCreateTime() == null ? LocalDateTime.now() : message.getCreateTime());

        try {
            orderMapper.insert(order);
            stringRedisTemplate.delete(tokenKey);
            log.info("秒杀订单落库成功 orderId={} userId={}", order.getOrderId(), order.getUserId());
        } catch (DuplicateKeyException e) {
            // 唯一索引 (activity_id, user_id) 是幂等的最后一道防线
            log.info("消息重复消费，幂等忽略 orderId={}", message.getOrderId());
        }
    }

    @Override
    public List<SeckillOrder> findExpiredUnpaid(LocalDateTime threshold, int limit) {
        return orderMapper.findExpiredUnpaid(threshold, limit);
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
    public OrderQueryResult queryResult(Long activityId, Long userId) {
        SeckillOrder order = findByActivityAndUser(activityId, userId);
        if (order == null) {
            return null;
        }
        OrderQueryResult result = new OrderQueryResult();
        result.setOrderId(order.getOrderId());
        result.setStatus(order.getStatus());
        result.setStatusDesc(statusDesc(order.getStatus()));
        result.setProductName(order.getProductName());
        result.setTotalAmount(order.getTotalAmount());
        return result;
    }

    @Override
    public boolean exists(String orderId) {
        return orderMapper.selectById(orderId) != null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pay(String orderId) {
        if (orderMapper.selectById(orderId) == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        if (orderMapper.pay(orderId) == 0) {
            throw new BizException(ResultCode.ORDER_STATUS_INVALID);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String orderId) {
        if (orderMapper.selectById(orderId) == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        if (orderMapper.cancel(orderId) == 0) {
            throw new BizException(ResultCode.ORDER_STATUS_INVALID);
        }
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
