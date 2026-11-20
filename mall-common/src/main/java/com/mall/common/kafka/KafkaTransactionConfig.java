package com.mall.common.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.transaction.KafkaTransactionManager;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka 事务支持（可选）：kafka.transaction.enabled=true 时生效。
 *
 * <p>同时保留 @Primary 非事务 ProducerFactory / KafkaTemplate，确保开启事务后原有普通发送链路不受影响；
 * 事务工厂使用「idPrefix + appName + instanceId」作为事务 ID 前缀，避免多实例冲突引发 ProducerFencedException。</p>
 */
@Configuration
@ConditionalOnProperty(name = "kafka.transaction.enabled", havingValue = "true")
public class KafkaTransactionConfig {

    @Value("${kafka.transaction.id-prefix:mall-tx-}")
    private String idPrefix;

    @Value("${spring.application.name:mall-app}")
    private String appName;

    @Value("${mall.instance-id:${POD_NAME:${HOSTNAME:}}}")
    private String instanceId;

    /** 显式保留默认非事务 ProducerFactory，防止自定义事务工厂顶掉 Boot 自动装配 */
    @Bean("kafkaProducerFactory")
    @Primary
    public ProducerFactory<String, Object> kafkaProducerFactory(KafkaProperties properties) {
        return new DefaultKafkaProducerFactory<>(new HashMap<>(properties.buildProducerProperties(null)));
    }

    @Bean("kafkaTemplate")
    @Primary
    public KafkaTemplate<String, Object> kafkaTemplate(
            @Qualifier("kafkaProducerFactory") ProducerFactory<String, Object> kafkaProducerFactory) {
        return new KafkaTemplate<>(kafkaProducerFactory);
    }

    @Bean("transactionalProducerFactory")
    public ProducerFactory<String, Object> transactionalProducerFactory(KafkaProperties properties) {
        Map<String, Object> props = new HashMap<>(properties.buildProducerProperties(null));
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // 由 DefaultKafkaProducerFactory.setTransactionIdPrefix 统一分配唯一 transactional.id
        props.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
        String suffix = StringUtils.hasText(instanceId)
                ? instanceId.trim() : UUID.randomUUID().toString().substring(0, 8);
        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(props);
        factory.setTransactionIdPrefix(idPrefix + appName + "-" + suffix + "-");
        return factory;
    }

    @Bean("transactionalKafkaTemplate")
    public KafkaTemplate<String, Object> transactionalKafkaTemplate(
            @Qualifier("transactionalProducerFactory") ProducerFactory<String, Object> transactionalProducerFactory) {
        return new KafkaTemplate<>(transactionalProducerFactory);
    }

    @Bean
    public KafkaTransactionManager<String, Object> kafkaTransactionManager(
            @Qualifier("transactionalProducerFactory") ProducerFactory<String, Object> transactionalProducerFactory) {
        return new KafkaTransactionManager<>(transactionalProducerFactory);
    }
}
