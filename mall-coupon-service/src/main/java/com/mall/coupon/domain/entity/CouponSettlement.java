package com.mall.coupon.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("coupon_settlement")
public class CouponSettlement {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    private String orderId;
    private Long userCouponId;
    private Long templateId;
    private Integer status;
    private BigDecimal orderAmount;
    private BigDecimal discountAmount;
    private BigDecimal payAmount;
    private LocalDateTime lockTime;
    private LocalDateTime settleTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
