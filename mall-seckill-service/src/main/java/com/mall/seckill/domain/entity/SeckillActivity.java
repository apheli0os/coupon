package com.mall.seckill.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 秒杀活动配置 */
@Data
@TableName("seckill_activity")
public class SeckillActivity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private String productCode;
    private String productName;
    private BigDecimal seckillPrice;
    private BigDecimal originalPrice;
    private Integer totalStock;
    private Integer soldCount;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 0=草稿 1=进行中 2=已结束 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
