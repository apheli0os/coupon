package com.mall.coupon.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 用户持有的优惠券，核销时通过条件更新保证幂等 */
@Data
@TableName("user_coupon")
public class UserCoupon {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long templateId;
    private String couponName;
    /** 1=满减 2=折扣 */
    private Integer type;
    private BigDecimal discountValue;
    private BigDecimal maxDiscountAmount;
    private BigDecimal threshold;
    private Integer target;
    private String productCode;
    /** 0=未使用 1=已使用 2=已过期 */
    private Integer status;
    private String orderId;
    private LocalDateTime useTime;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
