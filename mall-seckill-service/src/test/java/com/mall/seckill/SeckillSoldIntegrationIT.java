package com.mall.seckill;

import com.mall.common.message.SeckillOrderMessage;
import com.mall.seckill.consumer.SeckillSoldConsumer;
import com.mall.seckill.mapper.SeckillActivityMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = SeckillServiceApplication.class)
class SeckillSoldIntegrationIT {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("mall_seckill")
                    .withTmpFs(Map.of("/var/lib/mysql", "rw,nosuid,size=256m,mode=1777"))
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
        registry.add("spring.cloud.sentinel.enabled", () -> "false");
        registry.add("spring.cloud.sentinel.eager", () -> "false");
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
    private SeckillSoldConsumer soldConsumer;

    @Autowired
    private SeckillActivityMapper activityMapper;

    @Test
    void soldConsumer_shouldHandleOutOfOrderCancelAndSoldInRealRedisAndMysql() {
        assertNotNull(soldConsumer);
        assertEquals(0, activityMapper.selectById(2001L).getSoldCount());

        soldConsumer.onCancelBatch(List.of(msg(2001L, "ORD-OOO-IT")));
        soldConsumer.onSoldBatch(List.of(msg(2001L, "ORD-OOO-IT")));
        assertEquals(0, activityMapper.selectById(2001L).getSoldCount());

        soldConsumer.onSoldBatch(List.of(msg(2001L, "ORD-NORM-IT"), msg(2001L, "ORD-NORM-IT")));
        assertEquals(1, activityMapper.selectById(2001L).getSoldCount());

        soldConsumer.onCancelBatch(List.of(msg(2001L, "ORD-NORM-IT")));
        assertEquals(0, activityMapper.selectById(2001L).getSoldCount());
    }

    private SeckillOrderMessage msg(long activityId, String orderId) {
        SeckillOrderMessage m = new SeckillOrderMessage();
        m.setActivityId(activityId);
        m.setOrderId(orderId);
        m.setQuantity(1);
        return m;
    }
}
