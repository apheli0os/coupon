package com.mall.order.controller;

import com.mall.common.R;
import com.mall.common.context.UserContext;
import com.mall.common.message.OrderQueryResult;
import com.mall.order.domain.dto.OrderApplyCouponDTO;
import com.mall.order.domain.entity.SeckillOrder;
import com.mall.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "秒杀订单")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "订单详情")
    @GetMapping("/{orderId}")
    public R<SeckillOrder> detail(@PathVariable String orderId) {
        long userId = UserContext.getRequired();
        return R.ok(orderService.findByOrderIdForUser(orderId, userId, UserContext.isAdmin()));
    }

    @Operation(summary = "订单是否存在（Feign 内部调用）")
    @GetMapping("/internal/{orderId}/exists")
    public R<Boolean> exists(@PathVariable String orderId) {
        return R.ok(orderService.exists(orderId));
    }

    @Operation(summary = "按订单号查询校验信息（Feign 内部调用）")
    @GetMapping("/internal/{orderId}")
    public R<OrderQueryResult> internalDetail(@PathVariable String orderId) {
        return R.ok(orderService.queryByOrderId(orderId));
    }

    @Operation(summary = "按活动+用户查询（Feign 内部调用）")
    @GetMapping("/internal/by-activity")
    public R<OrderQueryResult> byActivity(@RequestParam Long activityId, @RequestParam Long userId) {
        return R.ok(orderService.queryResult(activityId, userId));
    }

    @Operation(summary = "支付订单")
    @PostMapping("/{orderId}/pay")
    public R<Void> pay(@PathVariable String orderId) {
        long userId = UserContext.getRequired();
        orderService.pay(orderId, userId);
        return R.ok();
    }

    @PostMapping("/{orderId}/apply-coupon")
    public R<SeckillOrder> applyCoupon(
            @PathVariable String orderId,
            @Valid @RequestBody OrderApplyCouponDTO dto) {
        long userId = UserContext.getRequired();
        return R.ok(orderService.applyCoupon(orderId, userId, dto.getUserCouponId()));
    }

    @PostMapping("/internal/{orderId}/apply-coupon")
    public R<Void> internalApplyCoupon(
            @PathVariable String orderId,
            @RequestParam Long userId,
            @RequestParam Long userCouponId) {
        orderService.applyCoupon(orderId, userId, userCouponId);
        return R.ok();
    }

    @Operation(summary = "取消订单")
    @PostMapping("/{orderId}/cancel")
    public R<Void> cancel(@PathVariable String orderId) {
        long userId = UserContext.getRequired();
        orderService.cancel(orderId, userId, UserContext.isAdmin());
        return R.ok();
    }
}
