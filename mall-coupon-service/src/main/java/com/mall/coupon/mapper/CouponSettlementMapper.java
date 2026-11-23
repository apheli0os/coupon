package com.mall.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.coupon.domain.entity.CouponSettlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CouponSettlementMapper extends BaseMapper<CouponSettlement> {

    @Select("SELECT * FROM coupon_settlement WHERE user_id = #{userId} AND order_id = #{orderId} " +
            "AND user_coupon_id = #{userCouponId} LIMIT 1")
    CouponSettlement selectByOrderAndCoupon(
            @Param("userId") Long userId,
            @Param("orderId") String orderId,
            @Param("userCouponId") Long userCouponId);

    @Select("SELECT * FROM coupon_settlement WHERE order_id = #{orderId} AND user_coupon_id = #{userCouponId} LIMIT 1")
    CouponSettlement selectByOrderAndCouponWithoutUser(
            @Param("orderId") String orderId,
            @Param("userCouponId") Long userCouponId);

    @Select("SELECT * FROM coupon_settlement WHERE user_id = #{userId} AND order_id = #{orderId} " +
            "AND status = 0 ORDER BY lock_time DESC")
    List<CouponSettlement> selectLockedByOrderAndUser(@Param("userId") Long userId, @Param("orderId") String orderId);

    @Select("SELECT * FROM coupon_settlement WHERE order_id = #{orderId} AND status = 0 ORDER BY lock_time DESC")
    List<CouponSettlement> selectLockedByOrderId(@Param("orderId") String orderId);

    @Update("UPDATE coupon_settlement SET status = 1, settle_time = NOW(), update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 0")
    int advanceToSettled(@Param("id") Long id, @Param("userId") Long userId);

    @Update("UPDATE coupon_settlement SET status = 2, update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 0")
    int advanceToCancelled(@Param("id") Long id, @Param("userId") Long userId);

    @Select("SELECT * FROM coupon_settlement WHERE status = 0 AND lock_time <= #{deadline} " +
            "ORDER BY lock_time ASC LIMIT #{limit}")
    List<CouponSettlement> findExpiredLocked(@Param("deadline") LocalDateTime deadline, @Param("limit") int limit);
}
