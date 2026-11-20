package com.mall.common.message;

import lombok.Data;

import java.io.Serializable;

/** 延迟消息：15 分钟未支付自动关单并归还库存 */
@Data
public class OrderTimeoutMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String orderId;
    private Long activityId;
    private Long userId;
    /** 预扣库存时命中的桶索引，归还时必须放回同一个桶 */
    private Integer stockBucket;
}
