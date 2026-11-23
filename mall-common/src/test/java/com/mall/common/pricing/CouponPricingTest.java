package com.mall.common.pricing;

import com.mall.common.ResultCode;
import com.mall.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CouponPricingTest {

    @Test
    void calculate_fixedCoupon_shouldSubtractDiscountAndCapAtOrderAmount() {
        CouponPricingRule rule = new CouponPricingRule(
                CouponPricing.TYPE_FIXED,
                new BigDecimal("20.00"),
                null,
                new BigDecimal("100.00"),
                CouponPricing.TARGET_ALL,
                null);
        CouponPricingResult res = CouponPricing.calculate(rule, new BigDecimal("150.00"), null);
        assertEquals(new BigDecimal("20.00"), res.discountAmount());
        assertEquals(new BigDecimal("130.00"), res.payAmount());

        CouponPricingRule uncappedZeroThreshold = new CouponPricingRule(
                CouponPricing.TYPE_FIXED,
                new BigDecimal("30.00"),
                null,
                BigDecimal.ZERO,
                CouponPricing.TARGET_ALL,
                null);
        CouponPricingResult floorRes = CouponPricing.calculate(uncappedZeroThreshold, new BigDecimal("18.50"), null);
        assertEquals(new BigDecimal("18.50"), floorRes.discountAmount());
        assertEquals(new BigDecimal("0.00"), floorRes.payAmount());

        CouponPricingRule cappedFixed = new CouponPricingRule(
                CouponPricing.TYPE_FIXED,
                new BigDecimal("50.00"),
                new BigDecimal("25.00"),
                BigDecimal.ZERO,
                CouponPricing.TARGET_ALL,
                null);
        CouponPricingResult cappedRes = CouponPricing.calculate(cappedFixed, new BigDecimal("100.00"), null);
        assertEquals(new BigDecimal("25.00"), cappedRes.discountAmount());
        assertEquals(new BigDecimal("75.00"), cappedRes.payAmount());
    }

    @Test
    void calculate_discountCoupon_shouldKeepRepoSemanticAndRespectCap() {
        CouponPricingRule rule = new CouponPricingRule(
                CouponPricing.TYPE_DISCOUNT,
                new BigDecimal("0.90"),
                new BigDecimal("200.00"),
                BigDecimal.ZERO,
                CouponPricing.TARGET_ALL,
                null);

        CouponPricingResult uncapped = CouponPricing.calculate(rule, new BigDecimal("1000.00"), null);
        assertEquals(new BigDecimal("100.00"), uncapped.discountAmount());
        assertEquals(new BigDecimal("900.00"), uncapped.payAmount());

        CouponPricingResult capped = CouponPricing.calculate(rule, new BigDecimal("5999.00"), null);
        assertEquals(new BigDecimal("200.00"), capped.discountAmount());
        assertEquals(new BigDecimal("5799.00"), capped.payAmount());
    }

    @Test
    void calculate_productTarget_shouldMatchProductCode() {
        CouponPricingRule productRule = new CouponPricingRule(
                CouponPricing.TYPE_DISCOUNT,
                new BigDecimal("0.85"),
                new BigDecimal("300.00"),
                new BigDecimal("100.00"),
                CouponPricing.TARGET_PRODUCT,
                "SKU-2026-001");

        CouponPricingResult matched = CouponPricing.calculate(
                productRule, new BigDecimal("1000.00"), "SKU-2026-001");
        assertEquals(new BigDecimal("150.00"), matched.discountAmount());
        assertEquals(new BigDecimal("850.00"), matched.payAmount());

        BizException mismatch = assertThrows(BizException.class,
                () -> CouponPricing.calculate(productRule, new BigDecimal("1000.00"), "SKU-OTHER"));
        assertEquals(ResultCode.COUPON_PRODUCT_NOT_MATCHED.getCode(), mismatch.getCode());

        CouponPricingRule blankProductRule = new CouponPricingRule(
                CouponPricing.TYPE_FIXED,
                BigDecimal.TEN,
                null,
                BigDecimal.ZERO,
                CouponPricing.TARGET_PRODUCT,
                " ");
        BizException badCfg = assertThrows(BizException.class,
                () -> CouponPricing.calculate(blankProductRule, BigDecimal.TEN, "SKU-1"));
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), badCfg.getCode());
    }

    @Test
    void calculate_shouldRejectInvalidInputsAndRuleConfigs() {
        CouponPricingRule valid = new CouponPricingRule(
                CouponPricing.TYPE_FIXED,
                new BigDecimal("20.00"),
                null,
                new BigDecimal("100.00"),
                CouponPricing.TARGET_ALL,
                null);

        BizException zeroOrder = assertThrows(BizException.class,
                () -> CouponPricing.calculate(valid, BigDecimal.ZERO, null));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), zeroOrder.getCode());

        BizException nullRule = assertThrows(BizException.class,
                () -> CouponPricing.calculate(null, new BigDecimal("50.00"), null));
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), nullRule.getCode());

        BizException underThreshold = assertThrows(BizException.class,
                () -> CouponPricing.calculate(valid, new BigDecimal("99.99"), null));
        assertEquals(ResultCode.COUPON_THRESHOLD_NOT_MET.getCode(), underThreshold.getCode());

        CouponPricingRule badDiscountRate = new CouponPricingRule(
                CouponPricing.TYPE_DISCOUNT,
                BigDecimal.ONE,
                null,
                BigDecimal.ZERO,
                CouponPricing.TARGET_ALL,
                null);
        BizException badRateEx = assertThrows(BizException.class,
                () -> CouponPricing.calculate(badDiscountRate, new BigDecimal("50.00"), null));
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), badRateEx.getCode());

        CouponPricingRule badFixed = new CouponPricingRule(
                CouponPricing.TYPE_FIXED,
                BigDecimal.ZERO,
                null,
                BigDecimal.ZERO,
                CouponPricing.TARGET_ALL,
                null);
        BizException badFixedEx = assertThrows(BizException.class,
                () -> CouponPricing.calculate(badFixed, new BigDecimal("50.00"), null));
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), badFixedEx.getCode());

        CouponPricingRule badType = new CouponPricingRule(
                9,
                BigDecimal.TEN,
                null,
                BigDecimal.ZERO,
                CouponPricing.TARGET_ALL,
                null);
        BizException badTypeEx = assertThrows(BizException.class,
                () -> CouponPricing.calculate(badType, new BigDecimal("50.00"), null));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), badTypeEx.getCode());
    }
}
