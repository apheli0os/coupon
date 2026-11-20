package com.mall.coupon.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CouponTemplateCreateDTO {

    @NotBlank(message = "券名称不能为空")
    private String name;

    /** 1=满减 2=折扣 */
    @NotNull(message = "券类型不能为空")
    @Min(value = 1, message = "券类型必须为 1(满减) 或 2(折扣)")
    @Max(value = 2, message = "券类型必须为 1(满减) 或 2(折扣)")
    private Integer type;

    @NotNull(message = "优惠值不能为空")
    @DecimalMin(value = "0.01", message = "优惠值必须大于 0")
    private BigDecimal discountValue;

    @DecimalMin(value = "0", message = "使用门槛不能为负")
    private BigDecimal threshold = BigDecimal.ZERO;

    @NotNull(message = "发行总量不能为空")
    @Min(value = 1, message = "发行总量至少为 1")
    private Integer total;

    @NotNull(message = "每人限领不能为空")
    @Min(value = 1, message = "每人限领至少为 1")
    private Integer perUserLimit = 1;

    @NotNull(message = "有效期天数不能为空")
    @Min(value = 1, message = "有效期至少为 1 天")
    private Integer validDays = 30;

    @NotNull(message = "开始时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime endTime;
}
