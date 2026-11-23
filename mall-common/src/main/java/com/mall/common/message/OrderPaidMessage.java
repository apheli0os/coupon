package com.mall.common.message;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class OrderPaidMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String orderId;
    private Long userId;
    private Long couponId;
    private LocalDateTime payTime;
}
