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
}
