package com.mall.seckill.service;

import com.mall.seckill.domain.vo.SeckillResultVO;

public interface SeckillService {

    /** 抢购：Redis Lua 预占资格 + MQ 异步下单，返回订单号 */
    String seckill(Long activityId);

    /** 轮询抢购结果 */
    SeckillResultVO result(Long activityId);
}
