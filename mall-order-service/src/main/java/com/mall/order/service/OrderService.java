package com.mall.order.service;

import com.mall.common.message.OrderQueryResult;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.order.domain.entity.SeckillOrder;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderService {

    /** 消费 MQ 消息创建订单，依赖唯一索引实现幂等 */
    void createSeckillOrder(SeckillOrderMessage message);

    /** 定时扫描超时未支付订单 */
    List<SeckillOrder> findExpiredUnpaid(LocalDateTime threshold, int limit);

    SeckillOrder findByActivityAndUser(Long activityId, Long userId);

    SeckillOrder findByOrderId(String orderId);

    OrderQueryResult queryResult(Long activityId, Long userId);

    boolean exists(String orderId);

    void pay(String orderId);

    void cancel(String orderId);
}
