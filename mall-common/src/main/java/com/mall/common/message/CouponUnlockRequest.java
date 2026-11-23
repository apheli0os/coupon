package com.mall.common.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
public class CouponUnlockRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull
    private Long userId;
    @NotBlank
    private String orderId;
    @NotNull
    private Long userCouponId;
}
