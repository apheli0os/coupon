package com.mall.common.util;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.lang.Snowflake;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 雪花算法分布式 ID 生成器。workerId / dataCenterId 需按实例分配，避免时钟回拨与 ID 冲突。
 */
@Component
public class IdGenerator {

    private final Snowflake snowflake;

    public IdGenerator(@Value("${mall.worker-id:1}") long workerId,
                       @Value("${mall.datacenter-id:1}") long datacenterId) {
        this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
    }

    public long nextId() {
        return snowflake.nextId();
    }

    public String nextIdStr() {
        return String.valueOf(snowflake.nextId());
    }
}
