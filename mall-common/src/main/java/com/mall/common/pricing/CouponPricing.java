package com.mall.common.pricing;

import com.mall.common.ResultCode;
import com.mall.common.exception.BizException;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class CouponPricing {

    public static final int TYPE_FIXED = 1;
    public static final int TYPE_DISCOUNT = 2;
    public static final int TARGET_ALL = 0;
    public static final int TARGET_PRODUCT = 1;

    private CouponPricing() {
    }

    public static CouponPricingResult calculate(
            CouponPricingRule rule,
            BigDecimal orderAmount,
            String orderProductCode) {
        if (orderAmount == null || orderAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "订单金额必须大于 0");
        }
        if (rule == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "优惠券规则缺失");
        }
        validateTarget(rule, orderProductCode);

        BigDecimal threshold = rule.threshold() == null ? BigDecimal.ZERO : rule.threshold();
        if (orderAmount.compareTo(threshold) < 0) {
            throw new BizException(ResultCode.COUPON_THRESHOLD_NOT_MET);
        }

        BigDecimal rawDiscount = computeRawDiscount(rule, orderAmount);
        BigDecimal discount = rawDiscount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal scaledOrder = orderAmount.setScale(2, RoundingMode.HALF_UP);
        if (discount.compareTo(scaledOrder) > 0) {
            discount = scaledOrder;
        }
        if (discount.compareTo(BigDecimal.ZERO) < 0) {
            discount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal payAmount = scaledOrder.subtract(discount).setScale(2, RoundingMode.HALF_UP);
        return new CouponPricingResult(discount, payAmount);
    }

    private static void validateTarget(CouponPricingRule rule, String orderProductCode) {
        if (rule.target() != TARGET_PRODUCT) {
            return;
        }
        if (rule.productCode() == null || rule.productCode().isBlank()) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "商品专属券缺少商品编码配置");
        }
        if (orderProductCode == null || !rule.productCode().equals(orderProductCode)) {
            throw new BizException(ResultCode.COUPON_PRODUCT_NOT_MATCHED);
        }
    }

    private static BigDecimal computeRawDiscount(CouponPricingRule rule, BigDecimal orderAmount) {
        BigDecimal value = rule.discountValue();
        BigDecimal maxCap = rule.maxDiscountAmount();
        BigDecimal discount;
        switch (rule.type()) {
            case TYPE_FIXED -> {
                if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BizException(ResultCode.SYSTEM_ERROR, "满减金额配置异常");
                }
                discount = value;
            }
            case TYPE_DISCOUNT -> {
                if (value == null
                        || value.compareTo(BigDecimal.ZERO) <= 0
                        || value.compareTo(BigDecimal.ONE) >= 0) {
                    throw new BizException(ResultCode.SYSTEM_ERROR, "折扣率必须在 (0, 1) 区间");
                }
                discount = orderAmount.multiply(BigDecimal.ONE.subtract(value));
            }
            default -> throw new BizException(ResultCode.PARAM_ERROR, "不支持的优惠券类型");
        }
        if (maxCap != null && maxCap.compareTo(BigDecimal.ZERO) > 0 && discount.compareTo(maxCap) > 0) {
            discount = maxCap;
        }
        return discount;
    }
}
