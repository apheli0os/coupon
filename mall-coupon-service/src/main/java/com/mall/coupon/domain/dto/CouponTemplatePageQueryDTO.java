package com.mall.coupon.domain.dto;

import lombok.Data;

@Data
public class CouponTemplatePageQueryDTO {

    private int pageNum = 1;
    private int pageSize = 20;
    private String name;
    private Integer type;
    private Integer status;
    private Integer target;
    private String productCode;
}
