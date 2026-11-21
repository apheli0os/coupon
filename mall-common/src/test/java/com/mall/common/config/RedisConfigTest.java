package com.mall.common.config;

import com.mall.common.cache.MultiLevelCache;
import com.mall.common.message.OrderQueryResult;
import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class RedisConfigTest {

    private final RedissonAutoConfigurationCustomizer customizer =
            new RedisConfig().redissonEmptyPasswordSanitizer();

    @Test
    @SuppressWarnings("unchecked")
    void redisTemplate_shouldAllowMallTypesAndRejectArbitraryUntrustedTypes() {
        RedisConfig config = new RedisConfig();
        RedisTemplate<String, Object> template = config.redisTemplate(mock(RedisConnectionFactory.class));
        RedisSerializer<Object> serializer = (RedisSerializer<Object>) template.getValueSerializer();

        OrderQueryResult sample = new OrderQueryResult();
        sample.setOrderId("ORD-100");
        sample.setUserId(1001L);
        sample.setTotalAmount(new BigDecimal("99.50"));

        byte[] bytes = serializer.serialize(sample);
        Object restored = serializer.deserialize(bytes);
        assertInstanceOf(OrderQueryResult.class, restored);
        assertEquals("ORD-100", ((OrderQueryResult) restored).getOrderId());

        byte[] nullMarkerBytes = serializer.serialize(new MultiLevelCache.NullValue());
        assertInstanceOf(MultiLevelCache.NullValue.class, serializer.deserialize(nullMarkerBytes));

        // 模拟不可信任意类反序列化 payload，应被 BasicPolymorphicTypeValidator 拒绝
        byte[] malicious = "{\"@class\":\"java.lang.ProcessBuilder\",\"command\":[\"calc\"]}".getBytes();
        assertThrows(Exception.class, () -> serializer.deserialize(malicious));
    }

    @Test
    void singleServerBlankPasswordIsNormalizedToNull() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379").setPassword("");
        customizer.customize(config);
        assertNull(config.useSingleServer().getPassword());
    }

    @Test
    void singleServerNonBlankPasswordIsPreserved() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379").setPassword("mallRedis2026");
        customizer.customize(config);
        assertEquals("mallRedis2026", config.useSingleServer().getPassword());
    }

    @Test
    void sentinelBlankPasswordIsNormalizedToNull() {
        Config config = new Config();
        config.useSentinelServers()
                .setMasterName("mallredis")
                .addSentinelAddress("redis://127.0.0.1:26381")
                .setPassword("   ");
        customizer.customize(config);
        assertNull(config.useSentinelServers().getPassword());
    }

    @Test
    void clusterBlankPasswordIsNormalizedToNull() {
        Config config = new Config();
        config.useClusterServers()
                .addNodeAddress("redis://127.0.0.1:7001")
                .setPassword("");
        customizer.customize(config);
        assertNull(config.useClusterServers().getPassword());
    }
}
