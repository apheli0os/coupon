package com.mall.order.config;

import com.mall.common.constants.CommonConstant;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Kafka 基础设施：启动时按环境副本数声明 topic，业务消费失败重试耗尽后投递 DLQ，DLQ 使用独立无递归工厂 */
@Slf4j
@Configuration
public class KafkaConfig {

    @Value("${mall.kafka.topic.partitions:8}")
    private int partitions;

    @Value("${mall.kafka.topic.replicas:1}")
    private int replicas;

    @Bean
    public NewTopic seckillTopic() {
        return TopicBuilder.name(CommonConstant.MQ_TOPIC_SECKILL)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }

    @Bean
    public NewTopic seckillDlqTopic() {
        return TopicBuilder.name(CommonConstant.MQ_TOPIC_SECKILL_DLQ)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }

    @Bean
    public NewTopic seckillCancelTopic() {
        return TopicBuilder.name(CommonConstant.MQ_TOPIC_SECKILL_CANCEL)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }

    /**
     * 5 次重试后转入 seckill-topic.DLQ，由 DLQ 监听器告警，避免坏消息阻塞分区。
     * 用 Spring Boot 的 Configurer 保留自动配置的 ack-mode/deserializer 等属性。
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            KafkaTemplate<String, Object> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLQ", record.partition()));
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 5)));
        return factory;
    }

    /**
     * DLQ 专用容器工厂：不挂载 DeadLetterPublishingRecoverer，仅记录告警并跳过坏消息，
     * 彻底避免 DLQ 消费异常时递归投递到 *.DLQ.DLQ。
     */
    @Bean("dlqKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> dlqKafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, ex) -> log.error("[DLQ-SKIP] DLQ 消息处理/反序列化异常，已跳过避免递归死信 topic={} partition={} offset={}",
                        record.topic(), record.partition(), record.offset(), ex),
                new FixedBackOff(0L, 0L));
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
