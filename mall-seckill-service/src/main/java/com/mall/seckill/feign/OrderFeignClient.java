package com.mall.seckill.feign;

import com.mall.common.R;
import com.mall.common.message.OrderQueryResult;
import com.mall.seckill.feign.fallback.OrderFeignClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 查询异步下单结果 */
@FeignClient(name = "mall-order-service", path = "/api/orders",
        fallbackFactory = OrderFeignClientFallbackFactory.class)
public interface OrderFeignClient {

    @GetMapping("/internal/by-activity")
    R<OrderQueryResult> findByActivity(@RequestParam("activityId") Long activityId,
                                       @RequestParam("userId") Long userId);
}
