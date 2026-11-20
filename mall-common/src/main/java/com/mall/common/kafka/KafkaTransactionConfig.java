package com.mall.common.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.transaction.KafkaTransactionManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 事务支持（可选）：kafka.transaction.enabled=true 时生效。
 *
 * 开启后生产者带 transactional.id，可把「多条消息发送 + 消费位移提交」放进一个事务，
 * 实现 read-process-write 原子性。消费端需配套 ack-mode=manual 并使用 KafkaTransactionTemplate。
 *
 * 默认关闭：当前系统用 at-least-once + 幂等消费 + 定时对账，已能兜住重复/丢失，无需事务开销。
 */
@Configuration
@ConditionalOnProperty(name = "kafka.transaction.enabled", havingValue = "true")
public class KafkaTransactionConfig {

    @Value("${kafka.transaction.id-prefix:mall-tx-}")
    private String idPrefix;

    @Value("${spring.application.name:mall-app}")
    private String appName;

    @Bean
    public ProducerFactory<String, Object> transactionalProducerFactory(KafkaProperties properties) {
        Map<String, Object> props = new HashMap<>(properties.buildProducerProperties());
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // transactional.id 必须唯一，同一 consumer group 多实例时用 group 名+实例序号区分
        props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, idPrefix + appName);
        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(props);
        factory.setTransactionIdPrefix(idPrefix + appName + "-");
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> transactionalKafkaTemplate(
            ProducerFactory<String, Object> transactionalProducerFactory) {
        return new KafkaTemplate<>(transactionalProducerFactory);
    }

    @Bean
    public KafkaTransactionManager<String, Object> kafkaTransactionManager(
            ProducerFactory<String, Object> transactionalProducerFactory) {
        return new KafkaTransactionManager<>(transactionalProducerFactory);
    }
}
