package com.mall.coupon.domain.enums;

/** 券类型 / 模板状态 / 用户券状态 */
public final class CouponEnums {

    private CouponEnums() {
    }

    public static final int TYPE_FIXED = 1;
    public static final int TYPE_DISCOUNT = 2;

    public static final int TEMPLATE_DRAFT = 0;
    public static final int TEMPLATE_ACTIVE = 1;
    public static final int TEMPLATE_ENDED = 2;

    public static final int COUPON_UNUSED = 0;
    public static final int COUPON_USED = 1;
    public static final int COUPON_EXPIRED = 2;
}
