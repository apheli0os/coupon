package com.mall.gateway;

import com.mall.gateway.controller.GatewayFallbackController;
import com.mall.gateway.filter.AuthGlobalFilter;
import com.mall.gateway.filter.TraceIdGlobalFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayContextIT {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no");

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.enabled", () -> "false");
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.cloud.nacos.config.enabled", () -> "false");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AuthGlobalFilter authGlobalFilter;

    @Autowired
    private TraceIdGlobalFilter traceIdGlobalFilter;

    @Autowired
    private GatewayFallbackController gatewayFallbackController;

    @Autowired
    private KeyResolver userKeyResolver;

    @Test
    void gatewayContext_shouldLoadSecurityFilterAndRateLimiterResolver() {
        assertNotNull(context);
        assertNotNull(authGlobalFilter);
        assertNotNull(traceIdGlobalFilter);
        assertNotNull(gatewayFallbackController);
        assertNotNull(userKeyResolver);
    }
}
