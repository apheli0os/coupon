package com.mall.order.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OrderApplyCouponDTO {

    @NotNull(message = "优惠券 ID 不能为空")
    private Long userCouponId;
}
