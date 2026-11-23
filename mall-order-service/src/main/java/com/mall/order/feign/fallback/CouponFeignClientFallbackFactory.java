package com.mall.order.feign.fallback;

import com.mall.common.R;
import com.mall.common.ResultCode;
import com.mall.common.message.CouponLockRequest;
import com.mall.common.message.CouponLockResult;
import com.mall.common.message.CouponUnlockRequest;
import com.mall.order.feign.CouponFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CouponFeignClientFallbackFactory implements FallbackFactory<CouponFeignClient> {

    @Override
    public CouponFeignClient create(Throwable cause) {
        return new CouponFeignClient() {
            @Override
            public R<CouponLockResult> lockCoupon(CouponLockRequest request) {
                log.error("优惠券服务 lockCoupon 调用失败 orderId={} couponId={} cause={}",
                        request == null ? null : request.getOrderId(),
                        request == null ? null : request.getUserCouponId(),
                        cause.getMessage());
                return R.fail(ResultCode.SYSTEM_ERROR);
            }

            @Override
            public R<Void> unlockCoupon(CouponUnlockRequest request) {
                log.error("优惠券服务 unlockCoupon 调用失败 orderId={} couponId={} cause={}",
                        request == null ? null : request.getOrderId(),
                        request == null ? null : request.getUserCouponId(),
                        cause.getMessage());
                return R.fail(ResultCode.SYSTEM_ERROR);
            }
        };
    }
}
