package com.mall.coupon.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponQuoteItemVO {

    private Long userCouponId;
    private Long templateId;
    private String couponName;
    private Integer type;
    private BigDecimal discountValue;
    private BigDecimal maxDiscountAmount;
    private BigDecimal threshold;
    private Integer target;
    private String productCode;
    private LocalDateTime expireTime;
    private BigDecimal discountAmount;
    private BigDecimal payAmount;
    private boolean available;
    private String unavailableReason;
}
