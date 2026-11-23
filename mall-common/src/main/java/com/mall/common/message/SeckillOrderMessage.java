package com.mall.common.message;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀抢购成功后投递到 MQ 的消息。
 * Redis 只做「资格预占」，真正的订单落库由订单服务异步完成，实现削峰填谷。
 */
@Data
public class SeckillOrderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String orderId;
    private Long userId;
    private Long activityId;
    private String productCode;
    private String productName;
    private BigDecimal seckillPrice;
    private Integer quantity;
    /** 预扣库存命中的桶索引，关单归还库存时需要 */
    private Integer stockBucket;
    private LocalDateTime createTime;
}
