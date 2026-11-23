package com.mall.common.message;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class CouponLockResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userCouponId;
    private BigDecimal discountAmount;
    private BigDecimal payAmount;
    private Integer type;
    private BigDecimal discountValue;
    private BigDecimal maxDiscountAmount;
    private BigDecimal threshold;
    private Integer target;
    private String productCode;
}
