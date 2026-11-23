package com.mall.common.message;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class CouponLockRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull
    private Long userId;
    @NotBlank
    private String orderId;
    @NotNull
    private Long userCouponId;
    @NotNull
    @DecimalMin("0.01")
    private BigDecimal orderAmount;
    private String productCode;
}
