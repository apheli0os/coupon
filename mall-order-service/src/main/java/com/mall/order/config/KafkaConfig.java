package com.mall.order.config;

import com.mall.common.constants.CommonConstant;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
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

/** Kafka 基础设施：启动时声明 topic，消费失败重试耗尽后投递 DLQ */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic seckillTopic() {
        return TopicBuilder.name(CommonConstant.MQ_TOPIC_SECKILL)
                .partitions(8).replicas(1).build();
    }

    @Bean
    public NewTopic seckillDlqTopic() {
        return TopicBuilder.name(CommonConstant.MQ_TOPIC_SECKILL_DLQ)
                .partitions(8).replicas(1).build();
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
}
