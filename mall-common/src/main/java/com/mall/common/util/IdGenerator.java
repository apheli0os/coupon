package com.mall.common.util;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * 雪花算法分布式 ID 生成器。
 *
 * <p>支持两种节点分配方式：
 * 1) 显式数字配置（mall.worker-id=1..31, mall.datacenter-id=0..31），适合单机/本地联调；
 * 2) 自动分配（mall.worker-id=auto 或 K8s Pod 名称字符串）：优先通过 Redis INCR mod 1024
 *    分配全局唯一的 (workerId, datacenterId) 槽位，Redis 不可用时降级为节点名 CRC32 哈希，
 *    既避免 K8s 传 Pod 名称引发 NumberFormatException 启动崩溃，又杜绝多副本雪花 ID 撞车。</p>
 */
@Slf4j
@Component
public final class IdGenerator {

    private static final int MAX_SLOT = 1024;
    private static final int BIT_RANGE = 32;

    @Getter
    private final long workerId;
    @Getter
    private final long datacenterId;
    private final Snowflake snowflake;

    public IdGenerator(long workerId, long datacenterId) {
        this.workerId = Math.floorMod(workerId, BIT_RANGE);
        this.datacenterId = Math.floorMod(datacenterId, BIT_RANGE);
        this.snowflake = IdUtil.getSnowflake(this.workerId, this.datacenterId);
    }

    @Autowired
    public IdGenerator(@Value("${mall.worker-id:1}") String rawWorkerId,
                       @Value("${mall.datacenter-id:1}") String rawDatacenterId,
                       @Value("${spring.application.name:mall}") String appName,
                       ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        long[] resolved = resolveIds(rawWorkerId, rawDatacenterId, appName,
                redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable());
        this.workerId = resolved[0];
        this.datacenterId = resolved[1];
        this.snowflake = IdUtil.getSnowflake(this.workerId, this.datacenterId);
        log.info("Snowflake IdGenerator initialized app={} workerId={} datacenterId={}",
                appName, this.workerId, this.datacenterId);
    }

    static long[] resolveIds(String rawWorkerId, String rawDatacenterId,
                             String appName, StringRedisTemplate redisTemplate) {
        if (isNumeric(rawWorkerId)) {
            long w = Math.floorMod(Long.parseLong(rawWorkerId.trim()), BIT_RANGE);
            long d = isNumeric(rawDatacenterId)
                    ? Math.floorMod(Long.parseLong(rawDatacenterId.trim()), BIT_RANGE) : 1L;
            return new long[]{w, d};
        }
        if (redisTemplate != null) {
            try {
                Long seq = redisTemplate.opsForValue().increment("mall:snowflake:worker:" + appName);
                if (seq != null) {
                    int slot = Math.floorMod(seq, MAX_SLOT);
                    return new long[]{slot % BIT_RANGE, (slot / BIT_RANGE) % BIT_RANGE};
                }
            } catch (Exception e) {
                log.warn("Redis 分配 Snowflake workerId 失败，降级为节点名哈希 app={} cause={}",
                        appName, e.getMessage());
            }
        }
        String seed = appName + ":" + (StringUtils.hasText(rawWorkerId) && !"auto".equalsIgnoreCase(rawWorkerId.trim())
                ? rawWorkerId.trim() : UUID.randomUUID());
        CRC32 crc32 = new CRC32();
        crc32.update(seed.getBytes(StandardCharsets.UTF_8));
        int slot = Math.floorMod(crc32.getValue(), MAX_SLOT);
        return new long[]{slot % BIT_RANGE, (slot / BIT_RANGE) % BIT_RANGE};
    }

    private static boolean isNumeric(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String trimmed = value.trim();
        int start = trimmed.charAt(0) == '-' ? 1 : 0;
        if (start == trimmed.length()) {
            return false;
        }
        for (int i = start; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public long nextId() {
        return snowflake.nextId();
    }

    public String nextIdStr() {
        return String.valueOf(snowflake.nextId());
    }
}
