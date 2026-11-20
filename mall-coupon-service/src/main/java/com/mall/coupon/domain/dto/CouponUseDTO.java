package com.mall.coupon.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CouponUseDTO {

    @NotNull(message = "优惠券 ID 不能为空")
    private Long userCouponId;

    @NotBlank(message = "订单号不能为空")
    private String orderId;

    /** 兼容旧入参；服务端核销时以订单服务返回的真实订单金额为准 */
    @DecimalMin(value = "0.01", message = "订单金额必须大于 0")
    private BigDecimal orderAmount;
}
