package com.mall.order.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 秒杀订单：activity_id + user_id 上有唯一索引，保证消息重复消费幂等 */
@Data
@TableName("seckill_order")
public class SeckillOrder {

    @TableId(type = IdType.INPUT)
    private String orderId;
    private Long userId;
    private Long activityId;
    private String productCode;
    private String productName;
    private BigDecimal seckillPrice;
    private Integer quantity;
    private BigDecimal totalAmount;
    private Long couponId;
    private BigDecimal discountAmount;
    private BigDecimal payAmount;
    /** 预扣命中的库存桶，超时关单时按桶归还（Kafka 无延迟消息，需落库保存） */
    private Integer stockBucket;
    /** 0=待支付 1=已支付 2=已取消 */
    private Integer status;
    /** 取消后 Redis 库存是否已归还：0=未归还 1=已归还 */
    private Integer stockRestored;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
}
