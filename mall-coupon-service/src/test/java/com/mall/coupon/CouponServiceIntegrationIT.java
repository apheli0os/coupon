package com.mall.coupon;

import com.mall.coupon.consumer.CouponClaimConsumer;
import com.mall.coupon.consumer.OrderCancelCouponRefundConsumer;
import com.mall.coupon.domain.entity.UserCoupon;
import com.mall.coupon.domain.enums.CouponEnums;
import com.mall.coupon.mapper.CouponTemplateMapper;
import com.mall.coupon.mapper.UserCouponMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(classes = CouponServiceApplication.class)
class CouponServiceIntegrationIT {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("mall_coupon")
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
    private UserCouponMapper userCouponMapper;

    @Autowired
    private CouponTemplateMapper templateMapper;

    @Autowired
    private CouponClaimConsumer claimConsumer;

    @Autowired
    private OrderCancelCouponRefundConsumer refundConsumer;

    @Test
    void contextAndMapperSql_shouldWorkAgainstRealMysqlWithFlyway() {
        assertNotNull(claimConsumer);
        assertNotNull(refundConsumer);

        LocalDateTime now = LocalDateTime.now();
        UserCoupon active = new UserCoupon();
        active.setId(90001L);
        active.setUserId(10001L);
        active.setTemplateId(1001L);
        active.setCouponName("Test");
        active.setType(CouponEnums.TYPE_FIXED);
        active.setDiscountValue(new BigDecimal("20.00"));
        active.setThreshold(new BigDecimal("100.00"));
        active.setStatus(CouponEnums.COUPON_UNUSED);
        active.setExpireTime(now.plusDays(3));
        active.setCreateTime(now);
        active.setUpdateTime(now);

        assertEquals(1, userCouponMapper.batchInsertIgnore(List.of(active)));
        assertEquals(0, userCouponMapper.batchInsertIgnore(List.of(active)));
        assertEquals(1L, userCouponMapper.countByTemplate(1001L));
        assertEquals(1, userCouponMapper.countGroupedByUser(1001L).size());

        assertEquals(1, userCouponMapper.useCoupon(90001L, 10001L, "ORD-IT-1"));
        assertEquals(1, userCouponMapper.refundByOrderId("ORD-IT-1"));

        UserCoupon refunded = userCouponMapper.selectById(90001L);
        assertEquals(CouponEnums.COUPON_UNUSED, refunded.getStatus());
        assertNull(refunded.getOrderId());

        assertEquals(1, userCouponMapper.useCoupon(90001L, 10001L, "ORD-IT-2"));
        assertEquals(1, userCouponMapper.releaseCoupon(90001L, 10001L, "ORD-IT-2"));
        assertEquals(CouponEnums.COUPON_UNUSED, userCouponMapper.selectById(90001L).getStatus());

        UserCoupon usedExpired = new UserCoupon();
        usedExpired.setId(90002L);
        usedExpired.setUserId(10002L);
        usedExpired.setTemplateId(1001L);
        usedExpired.setCouponName("ExpiredTest");
        usedExpired.setType(CouponEnums.TYPE_FIXED);
        usedExpired.setDiscountValue(new BigDecimal("20.00"));
        usedExpired.setThreshold(new BigDecimal("100.00"));
        usedExpired.setStatus(CouponEnums.COUPON_USED);
        usedExpired.setOrderId("ORD-IT-EXP");
        usedExpired.setExpireTime(now.minusDays(2));
        usedExpired.setCreateTime(now.minusDays(3));
        usedExpired.setUpdateTime(now);
        assertEquals(1, userCouponMapper.insert(usedExpired));

        assertEquals(1, userCouponMapper.refundByOrderId("ORD-IT-EXP"));
        UserCoupon refundedExpired = userCouponMapper.selectById(90002L);
        assertEquals(CouponEnums.COUPON_EXPIRED, refundedExpired.getStatus());
        assertNull(refundedExpired.getOrderId());

        assertEquals(1, templateMapper.incrReceivedBy(1002L, 500));
        assertEquals(0, templateMapper.incrReceivedBy(1002L, 1));
    }
}
