package com.mall.coupon.domain.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CouponTemplateIncreaseStockDTO {

    @NotNull(message = "增发数量不能为空")
    @Min(value = 1, message = "增发数量至少为 1")
    private Integer delta;
}
