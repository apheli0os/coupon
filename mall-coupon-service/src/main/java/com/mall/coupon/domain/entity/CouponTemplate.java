package com.mall.coupon.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 优惠券模板：运营配置的活动规则 */
@Data
@TableName("coupon_template")
public class CouponTemplate {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    /** 1=满减 2=折扣 */
    private Integer type;
    /** 满减金额 / 折扣率（0.85 表示 85 折） */
    private BigDecimal discountValue;
    private BigDecimal maxDiscountAmount;
    /** 使用门槛，0 表示无门槛 */
    private BigDecimal threshold;
    private Integer target;
    private String productCode;
    private Integer total;
    private Integer received;
    private Integer perUserLimit;
    /** 领取后有效天数 */
    private Integer validDays;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 0=草稿 1=进行中 2=已结束 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
