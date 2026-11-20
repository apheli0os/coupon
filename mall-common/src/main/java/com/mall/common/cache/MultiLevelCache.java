package com.mall.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * 多级缓存：Caffeine 本地缓存（10s） + Redis 分布式缓存。
 * - 读：本地 → Redis → DB，逐层回填
 * - 删：删 Redis 后发布 Pub/Sub，所有实例清本地缓存，避免多实例脏读
 * - 空值用哨兵对象短 TTL 缓存，防缓存穿透
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiLevelCache {

    public static final String INVALIDATE_CHANNEL = "mall:cache:invalidate";
    private static final Duration LOCAL_TTL = Duration.ofSeconds(10);
    private static final Duration NULL_TTL = Duration.ofSeconds(30);
    private static final long MAX_LOCAL_SIZE = 5000;
    private static final NullValue NULL_MARKER = new NullValue();

    /** 非 final，保证 GenericJackson2JsonRedisSerializer 写入类型信息后可反序列化 */
    public static class NullValue implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisConnectionFactory connectionFactory;

    private final Cache<String, Object> localCache = Caffeine.newBuilder()
            .expireAfterWrite(LOCAL_TTL)
            .maximumSize(MAX_LOCAL_SIZE)
            .build();

    @PostConstruct
    public void initListener() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String key = new String(message.getBody(), StandardCharsets.UTF_8);
            localCache.invalidate(key);
            log.debug("本地缓存失效 key={}", key);
        }, new ChannelTopic(INVALIDATE_CHANNEL));
        container.afterPropertiesSet();
        container.start();
    }

    /** 两级缓存读取，loader 只在两层都未命中时调用 */
    public <T> T get(String key, Class<T> type, Supplier<T> loader, Duration ttl) {
        Object local = localCache.getIfPresent(key);
        if (local != null) {
            return unwrap(local, type);
        }
        Object remote = redisTemplate.opsForValue().get(key);
        if (remote != null) {
            localCache.put(key, remote);
            return unwrap(remote, type);
        }
        T loaded = loader.get();
        redisTemplate.opsForValue().set(key, loaded == null ? NULL_MARKER : loaded,
                loaded == null ? NULL_TTL : ttl);
        if (loaded != null) {
            localCache.put(key, loaded);
        }
        return loaded;
    }

    /** 失效：删 Redis + 广播所有实例清本地 */
    public void invalidate(String key) {
        redisTemplate.delete(key);
        localCache.invalidate(key);
        try {
            stringRedisTemplate.convertAndSend(INVALIDATE_CHANNEL, key);
        } catch (Exception e) {
            log.warn("缓存失效广播失败 key={}，本地已清理，其他实例最多脏读 10s", key, e);
        }
    }

    private <T> T unwrap(Object value, Class<T> type) {
        return value instanceof NullValue ? null : type.cast(value);
    }
}
