package com.mall.common.pricing;

import java.math.BigDecimal;

public record CouponPricingResult(
        BigDecimal discountAmount,
        BigDecimal payAmount) {
}
