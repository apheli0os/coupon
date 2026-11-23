package com.mall.coupon.feign;

import com.mall.common.R;
import com.mall.common.message.OrderQueryResult;
import com.mall.coupon.feign.fallback.OrderFeignClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 核销优惠券时校验订单归属、状态与真实金额；订单服务不可用时 fail-closed 拒绝核销 */
@FeignClient(name = "mall-order-service", path = "/api/orders",
        fallbackFactory = OrderFeignClientFallbackFactory.class)
public interface OrderFeignClient {

    @GetMapping("/internal/{orderId}/exists")
    R<Boolean> exists(@PathVariable("orderId") String orderId);

    @GetMapping("/internal/{orderId}")
    R<OrderQueryResult> getOrder(@PathVariable("orderId") String orderId);

    @PostMapping("/internal/{orderId}/apply-coupon")
    R<Void> applyCoupon(
            @PathVariable("orderId") String orderId,
            @RequestParam("userId") Long userId,
            @RequestParam("userCouponId") Long userCouponId);
}
