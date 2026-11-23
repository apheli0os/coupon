package com.mall.common.message;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/** 跨服务订单查询结果，避免 seckill 与 order 模块互相依赖 */
@Data
public class OrderQueryResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String orderId;
    private Long userId;
    /** 0=待支付 1=已支付 2=已取消 */
    private Integer status;
    private String statusDesc;
    private String productCode;
    private String productName;
    private BigDecimal totalAmount;
    private Long couponId;
    private BigDecimal discountAmount;
    private BigDecimal payAmount;
}
