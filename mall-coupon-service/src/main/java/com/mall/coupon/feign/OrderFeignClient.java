package com.mall.coupon.feign;

import com.mall.common.R;
import com.mall.coupon.feign.fallback.OrderFeignClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** 核销优惠券时校验订单是否存在；订单服务不可用时由降级工厂兜底 */
@FeignClient(name = "mall-order-service", path = "/api/orders",
        fallbackFactory = OrderFeignClientFallbackFactory.class)
public interface OrderFeignClient {

    @GetMapping("/internal/{orderId}/exists")
    R<Boolean> exists(@PathVariable("orderId") String orderId);
}
