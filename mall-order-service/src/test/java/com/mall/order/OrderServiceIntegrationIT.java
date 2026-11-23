package com.mall.order;

import com.mall.common.constants.RedisKey;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.order.domain.entity.SeckillOrder;
import com.mall.order.mapper.SeckillOrderMapper;
import com.mall.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = OrderServiceApplication.class)
class OrderServiceIntegrationIT {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("mall_order")
                    .withTmpFs(Map.of("/var/lib/mysql", "rw,nosuid,size=512m,mode=1777"))
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_unicode_ci",
                            "--default-time-zone=+08:00",
                            "--skip-log-bin",
                            "--innodb-flush-log-at-trx-commit=0",
                            "--innodb-doublewrite=0",
                            "--innodb-buffer-pool-size=128M");

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no");

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.2"))
                    .withTmpFs(Map.of("/tmp/kraft-combined-logs", "rw,nosuid,size=128m,mode=1777"))
                    .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m");

    static {
        MYSQL.start();
        REDIS.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.cloud.discovery.enabled", () -> "false");
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.cloud.nacos.config.enabled", () -> "false");
        registry.add("spring.task.scheduling.enabled", () -> "false");
        registry.add("mall.kafka.topic.partitions", () -> "1");
        registry.add("spring.kafka.listener.concurrency", () -> "1");
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private SeckillOrderMapper orderMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void createCancelAndRebuy_shouldKeepPerOrderTokenIsolatedInRealMysqlAndRedis() {
        long actId = 2001L;
        long userId = 30001L;

        redisTemplate.opsForValue().set(RedisKey.seckillStockBucket(actId, 0), "1", Duration.ofMinutes(10));
        redisTemplate.opsForSet().add(RedisKey.seckillUsers(actId), String.valueOf(userId));
        redisTemplate.opsForValue().set(RedisKey.seckillOrderToken(actId, "ORD-IT-1"), "ORD-IT-1", Duration.ofMinutes(10));

        SeckillOrderMessage m1 = msg("ORD-IT-1", actId, userId);
        orderService.createSeckillOrder(m1);
        SeckillOrder created1 = orderMapper.selectById("ORD-IT-1");
        assertNotNull(created1);
        assertEquals(0, created1.getStatus());

        orderService.cancel("ORD-IT-1", userId, false);
        SeckillOrder cancelled1 = orderMapper.selectById("ORD-IT-1");
        assertEquals(2, cancelled1.getStatus());
        assertEquals(1, cancelled1.getStockRestored());

        redisTemplate.opsForValue().set(RedisKey.seckillOrderToken(actId, "ORD-IT-2"), "ORD-IT-2", Duration.ofMinutes(10));
        orderService.createSeckillOrder(m1);
        orderService.createSeckillOrder(msg("ORD-IT-2", actId, userId));

        SeckillOrder created2 = orderMapper.selectById("ORD-IT-2");
        assertNotNull(created2);
        assertEquals(0, created2.getStatus());
        assertEquals(2, orderMapper.selectById("ORD-IT-1").getStatus());
    }

    private SeckillOrderMessage msg(String orderId, long activityId, long userId) {
        SeckillOrderMessage m = new SeckillOrderMessage();
        m.setOrderId(orderId);
        m.setActivityId(activityId);
        m.setUserId(userId);
        m.setProductName("Phone");
        m.setSeckillPrice(new BigDecimal("5999.00"));
        m.setQuantity(1);
        m.setStockBucket(0);
        m.setCreateTime(LocalDateTime.now());
        return m;
    }
}
