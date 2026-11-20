package com.mall.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.coupon.domain.entity.CouponTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CouponTemplateMapper extends BaseMapper<CouponTemplate> {

    /** 条件自增已领数量，防止 DB 层超发（保留供同步场景使用） */
    @Update("UPDATE coupon_template SET received = received + 1 " +
            "WHERE id = #{id} AND received < total")
    int incrReceived(@Param("id") Long id);

    /** 消费端按实际新增行数批量累加，带 total 上限兜底防止超发 */
    @Update("UPDATE coupon_template SET received = received + #{delta}, update_time = NOW() " +
            "WHERE id = #{id} AND received + #{delta} <= total")
    int incrReceivedBy(@Param("id") Long id, @Param("delta") int delta);
}
