package com.mall.coupon.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("coupon_template_audit_log")
public class CouponTemplateAuditLog {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long templateId;
    private Long operatorId;
    private String action;
    private String detail;
    private String traceId;
    private LocalDateTime createTime;
}
