package com.mall.common.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Key 用 String 序列化（可读、便于排查），Value 用 JSON（带类型信息，支持对象直存）。
 * 必须注册 JavaTimeModule，否则缓存中含 LocalDateTime 的对象会序列化失败。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // 收紧多态反序列化白名单：仅允许项目实体与必要 JDK 值类型，避免开放 Object.class 引发反序列化风险
        BasicPolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType("com.mall.")
                .allowIfSubType("com.mall.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .allowIfSubType("java.math.")
                .build();

        ObjectMapper mapper = JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .activateDefaultTyping(
                        typeValidator,
                        ObjectMapper.DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.PROPERTY)
                .build();

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(mapper);
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 当未配置 REDIS_PASSWORD 时，Spring 会把 `${REDIS_PASSWORD:}` 解析为空字符串 ""，
     * Redisson 3.34 会向无密码 Redis 发送 `AUTH ""` 触发启动失败；此处统一将空白密码归一化为 null。
     */
    @Bean
    public RedissonAutoConfigurationCustomizer redissonEmptyPasswordSanitizer() {
        return config -> {
            if (config.isSingleConfig() && isBlank(config.useSingleServer().getPassword())) {
                config.useSingleServer().setPassword(null);
            } else if (config.isSentinelConfig() && isBlank(config.useSentinelServers().getPassword())) {
                config.useSentinelServers().setPassword(null);
            } else if (config.isClusterConfig() && isBlank(config.useClusterServers().getPassword())) {
                config.useClusterServers().setPassword(null);
            }
        };
    }

    private static boolean isBlank(String value) {
        return value != null && value.isBlank();
    }
}
