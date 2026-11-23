package com.mall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.order.domain.entity.SeckillOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SeckillOrderMapper extends BaseMapper<SeckillOrder> {

    /** 优先返回有效订单（status=0/1），若全部取消则返回最新一条取消单 */
    @Select("SELECT * FROM seckill_order WHERE activity_id = #{activityId} AND user_id = #{userId} " +
            "ORDER BY status ASC, create_time DESC LIMIT 1")
    SeckillOrder findByActivityAndUser(@Param("activityId") Long activityId, @Param("userId") Long userId);

    /** 扫描超时未支付订单（命中 idx_status_create_time 复合索引） */
    @Select("SELECT * FROM seckill_order WHERE status = 0 AND create_time <= #{threshold} " +
            "ORDER BY create_time ASC LIMIT #{limit}")
    List<SeckillOrder> findExpiredUnpaid(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit);

    /** 扫描已取消但 Redis 库存尚未归还的订单，用于异常后补偿重试 */
    @Select("SELECT * FROM seckill_order WHERE status = 2 AND stock_restored = 0 " +
            "ORDER BY create_time ASC LIMIT #{limit}")
    List<SeckillOrder> findPendingStockRestore(@Param("limit") int limit);

    @Update("UPDATE seckill_order SET status = 1, pay_time = NOW() WHERE order_id = #{orderId} AND status = 0")
    int pay(@Param("orderId") String orderId);

    @Update("UPDATE seckill_order SET status = 2, stock_restored = 0 WHERE order_id = #{orderId} AND status = 0")
    int cancel(@Param("orderId") String orderId);

    @Update("UPDATE seckill_order SET stock_restored = 1 WHERE order_id = #{orderId} AND status = 2 AND stock_restored = 0")
    int markStockRestored(@Param("orderId") String orderId);

    @Update("UPDATE seckill_order " +
            "SET coupon_id = #{couponId}, discount_amount = #{discountAmount}, pay_amount = #{payAmount} " +
            "WHERE order_id = #{orderId} AND user_id = #{userId} AND status = 0 AND coupon_id IS NULL")
    int applyCoupon(
            @Param("orderId") String orderId,
            @Param("userId") Long userId,
            @Param("couponId") Long couponId,
            @Param("discountAmount") BigDecimal discountAmount,
            @Param("payAmount") BigDecimal payAmount);
}
