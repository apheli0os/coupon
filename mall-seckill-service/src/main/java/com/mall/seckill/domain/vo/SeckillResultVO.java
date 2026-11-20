package com.mall.seckill.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 抢购结果：orderId 为空表示尚未抢到，前端可定时轮询 */
@Data
public class SeckillResultVO {

    private String orderId;
    /** 0=待支付 1=已支付 2=已取消 */
    private Integer orderStatus;
    private String statusDesc;
    private String productName;
    private BigDecimal seckillPrice;
    private BigDecimal totalAmount;
    private String message;
}
