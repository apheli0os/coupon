package com.mall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.order.domain.entity.SeckillOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SeckillOrderMapper extends BaseMapper<SeckillOrder> {

    @Select("SELECT * FROM seckill_order WHERE activity_id = #{activityId} AND user_id = #{userId} LIMIT 1")
    SeckillOrder findByActivityAndUser(@Param("activityId") Long activityId, @Param("userId") Long userId);

    /** 扫描超时未支付订单（Kafka 定时关单替代 MQ 延迟消息） */
    @Select("SELECT * FROM seckill_order WHERE status = 0 AND create_time <= #{threshold} " +
            "ORDER BY create_time ASC LIMIT #{limit}")
    List<SeckillOrder> findExpiredUnpaid(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit);

    @Update("UPDATE seckill_order SET status = 1, pay_time = NOW() WHERE order_id = #{orderId} AND status = 0")
    int pay(@Param("orderId") String orderId);

    @Update("UPDATE seckill_order SET status = 2 WHERE order_id = #{orderId} AND status = 0")
    int cancel(@Param("orderId") String orderId);
}
