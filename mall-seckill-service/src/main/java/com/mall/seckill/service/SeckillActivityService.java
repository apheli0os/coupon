package com.mall.seckill.service;

import com.mall.seckill.domain.dto.SeckillActivityCreateDTO;
import com.mall.seckill.domain.entity.SeckillActivity;

import java.util.List;

public interface SeckillActivityService {

    Long create(SeckillActivityCreateDTO dto);

    /** 发布活动：分布式锁内切换状态并把库存预热到 Redis */
    void publish(Long activityId);

    SeckillActivity getDetail(Long activityId);

    List<SeckillActivity> listAll();
}
