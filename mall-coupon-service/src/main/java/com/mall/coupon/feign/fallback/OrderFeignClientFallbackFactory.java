package com.mall.coupon.feign.fallback;

import com.mall.common.R;
import com.mall.coupon.feign.OrderFeignClient;
import org.springframework.cloud.openfeign.FallbackFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 降级策略：订单服务暂不可用时放行核销（订单最终一致性由订单侧保证），
 * 并记录告警。生产环境应根据业务改为拒绝并重试。
 */
@Slf4j
@Component
public class OrderFeignClientFallbackFactory implements FallbackFactory<OrderFeignClient> {

    @Override
    public OrderFeignClient create(Throwable cause) {
        return orderId -> {
            log.warn("订单服务调用失败，走降级 orderId={} cause={}", orderId, cause.getMessage());
            return R.ok(true);
        };
    }
}
