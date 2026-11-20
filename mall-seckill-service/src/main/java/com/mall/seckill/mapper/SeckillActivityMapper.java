package com.mall.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.seckill.domain.entity.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    /** DB 侧兜底，防止 soldCount 超过 totalStock */
    @Update("UPDATE seckill_activity SET sold_count = sold_count + 1 " +
            "WHERE id = #{id} AND sold_count < total_stock")
    int incrSold(@Param("id") Long id);

    /** 批量累加展示销量，带 total_stock 上限兜底 */
    @Update("UPDATE seckill_activity SET sold_count = LEAST(total_stock, sold_count + #{delta}), update_time = NOW() " +
            "WHERE id = #{id} AND sold_count < total_stock")
    int incrSoldBy(@Param("id") Long id, @Param("delta") int delta);

    /** 取消订单时批量扣减展示销量，保底为 0 */
    @Update("UPDATE seckill_activity SET sold_count = GREATEST(0, sold_count - #{delta}), update_time = NOW() " +
            "WHERE id = #{id} AND sold_count > 0")
    int decrSoldBy(@Param("id") Long id, @Param("delta") int delta);
}
