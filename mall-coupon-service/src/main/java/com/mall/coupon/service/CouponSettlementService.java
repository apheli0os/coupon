package com.mall.coupon.service;

import com.mall.common.message.CouponLockRequest;
import com.mall.common.message.CouponLockResult;
import com.mall.common.message.CouponUnlockRequest;

public interface CouponSettlementService {

    CouponLockResult lockCoupon(CouponLockRequest request);

    void settleByOrder(String orderId, Long userId, Long couponId);

    void unlockCoupon(CouponUnlockRequest request);

    void unlockOrRefundByOrder(String orderId, Long userId);

    int sweepExpiredLocks();
}
