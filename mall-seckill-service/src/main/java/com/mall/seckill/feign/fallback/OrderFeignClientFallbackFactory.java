package com.mall.seckill.feign.fallback;

import com.mall.common.R;
import com.mall.seckill.feign.OrderFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OrderFeignClientFallbackFactory implements FallbackFactory<OrderFeignClient> {

    @Override
    public OrderFeignClient create(Throwable cause) {
        return (activityId, userId) -> {
            log.warn("订单查询降级 activityId={} userId={} cause={}", activityId, userId, cause.getMessage());
            return R.ok(null);
        };
    }
}
