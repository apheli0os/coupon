package com.mall.coupon.feign.fallback;

import com.mall.common.R;
import com.mall.common.ResultCode;
import com.mall.common.message.OrderQueryResult;
import com.mall.coupon.feign.OrderFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 降级策略（fail-closed）：订单服务不可用时拒绝核销并提示重试，
 * 避免在无法校验订单归属、状态与真实金额时错误放行。
 */
@Slf4j
@Component
public class OrderFeignClientFallbackFactory implements FallbackFactory<OrderFeignClient> {

    @Override
    public OrderFeignClient create(Throwable cause) {
        return new OrderFeignClient() {
            @Override
            public R<Boolean> exists(String orderId) {
                log.error("订单服务 exists 调用失败，拒绝放行 orderId={} cause={}", orderId, cause.getMessage());
                return R.fail(ResultCode.SYSTEM_ERROR);
            }

            @Override
            public R<OrderQueryResult> getOrder(String orderId) {
                log.error("订单服务 getOrder 调用失败，拒绝放行 orderId={} cause={}", orderId, cause.getMessage());
                return R.fail(ResultCode.SYSTEM_ERROR);
            }
        };
    }
}
