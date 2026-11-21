package com.mall.seckill.config;

import com.mall.common.constants.CommonConstant;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/** 秒杀服务 Kafka 配置：副本数参数化适配单机与 HA 集群，并为销量计数提供批量消费工厂 */
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
    public NewTopic seckillCancelTopic() {
        return TopicBuilder.name(CommonConstant.MQ_TOPIC_SECKILL_CANCEL)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }

    /** 销量累加专用批量监听工厂：同活动多单内存聚合后一次 UPDATE DB */
    @Bean("soldBatchListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> soldBatchListenerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(true);
        // 批量监听器必须用 BATCH ack 模式；全局 ack-mode=record 与 batch listener 冲突会在启动时抛
        // IllegalStateException: Cannot use AckMode.RECORD with a batch listener
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        return factory;
    }
}
