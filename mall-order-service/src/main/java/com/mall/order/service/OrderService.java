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

    /** 带归属人校验的订单查询，防止 IDOR 越权 */
    SeckillOrder findByOrderIdForUser(String orderId, long userId, boolean isAdmin);

    OrderQueryResult queryResult(Long activityId, Long userId);

    OrderQueryResult queryByOrderId(String orderId);

    boolean exists(String orderId);

    void pay(String orderId, long userId);

    void cancel(String orderId, long userId, boolean isAdmin);

    /** 系统定时关单（无 HTTP 用户上下文） */
    boolean cancelBySystem(String orderId);
}
