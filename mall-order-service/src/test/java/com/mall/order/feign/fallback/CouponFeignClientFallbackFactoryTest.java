package com.mall.order.feign.fallback;

import com.mall.common.R;
import com.mall.common.ResultCode;
import com.mall.common.message.CouponLockRequest;
import com.mall.common.message.CouponLockResult;
import com.mall.common.message.CouponUnlockRequest;
import com.mall.order.feign.CouponFeignClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CouponFeignClientFallbackFactoryTest {

    @Test
    void fallback_shouldFailClosedOnLockAndUnlock() {
        CouponFeignClientFallbackFactory factory = new CouponFeignClientFallbackFactory();
        CouponFeignClient client = factory.create(new RuntimeException("timeout"));

        R<CouponLockResult> nullLock = client.lockCoupon(null);
        assertFalse(nullLock.isSuccess());
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), nullLock.getCode());

        CouponLockRequest lockReq = new CouponLockRequest();
        lockReq.setOrderId("ORD-1");
        lockReq.setUserCouponId(10L);
        R<CouponLockResult> lockRes = client.lockCoupon(lockReq);
        assertFalse(lockRes.isSuccess());
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), lockRes.getCode());

        R<Void> nullUnlock = client.unlockCoupon(null);
        assertFalse(nullUnlock.isSuccess());
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), nullUnlock.getCode());

        CouponUnlockRequest unlockReq = new CouponUnlockRequest();
        unlockReq.setOrderId("ORD-1");
        unlockReq.setUserCouponId(10L);
        R<Void> unlockRes = client.unlockCoupon(unlockReq);
        assertFalse(unlockRes.isSuccess());
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), unlockRes.getCode());
    }
}
