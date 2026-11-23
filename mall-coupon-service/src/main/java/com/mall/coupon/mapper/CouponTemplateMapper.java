package com.mall.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.coupon.domain.entity.CouponTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

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

    @Update("UPDATE coupon_template SET total = total + #{delta}, update_time = NOW() " +
            "WHERE id = #{id} AND status IN (0, 1) AND end_time > NOW()")
    int increaseTotal(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE coupon_template SET status = 2, update_time = NOW() " +
            "WHERE id = #{id} AND status IN (0, 1)")
    int terminate(@Param("id") Long id);

    @Update("UPDATE coupon_template SET status = #{targetStatus}, update_time = #{now} " +
            "WHERE id = #{id} AND status = #{expectedStatus}")
    int updateStatusIfMatch(
            @Param("id") Long id,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus,
            @Param("now") LocalDateTime now);
}
