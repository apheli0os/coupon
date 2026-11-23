package com.mall.common.message;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 领券异步落库消息。
 * Redis Lua 原子预扣成功后立即投递，用户券的 DB insert 与模板 received 累加
 * 由消费端批量完成，避免请求线程同步写 MySQL 成为瓶颈。
 */
@Data
public class CouponClaimMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户券雪花 ID，同时作为 Kafka 分区键与消费幂等主键 */
    private Long id;
    private Long userId;
    private Long templateId;
    private String couponName;
    private Integer type;
    private BigDecimal discountValue;
    private BigDecimal maxDiscountAmount;
    private BigDecimal threshold;
    private Integer target;
    private String productCode;
    private Integer status;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
