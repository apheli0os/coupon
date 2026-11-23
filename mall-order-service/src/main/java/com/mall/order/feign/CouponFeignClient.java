package com.mall.order.feign;

import com.mall.common.R;
import com.mall.common.message.CouponLockRequest;
import com.mall.common.message.CouponLockResult;
import com.mall.common.message.CouponUnlockRequest;
import com.mall.order.feign.fallback.CouponFeignClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "mall-coupon-service", path = "/api/coupon/internal/settlement",
        fallbackFactory = CouponFeignClientFallbackFactory.class)
public interface CouponFeignClient {

    @PostMapping("/lock")
    R<CouponLockResult> lockCoupon(@RequestBody CouponLockRequest request);

    @PostMapping("/unlock")
    R<Void> unlockCoupon(@RequestBody CouponUnlockRequest request);
}
