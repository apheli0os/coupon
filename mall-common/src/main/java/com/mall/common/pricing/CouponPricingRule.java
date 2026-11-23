package com.mall.common.pricing;

import java.math.BigDecimal;

public record CouponPricingRule(
        int type,
        BigDecimal discountValue,
        BigDecimal maxDiscountAmount,
        BigDecimal threshold,
        int target,
        String productCode) {
}
