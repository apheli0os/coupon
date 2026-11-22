package com.mall.coupon.config;

import com.mall.common.constants.CommonConstant;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** 启动时声明领券 topic：副本数按环境可配（单节点默认 1，HA 集群配 3 以满足 min.insync.replicas=2） */
@Configuration
public class KafkaTopicConfig {

    @Value("${mall.kafka.topic.partitions:8}")
    private int partitions;

    @Value("${mall.kafka.topic.replicas:1}")
    private int replicas;

    @Bean
    public NewTopic couponClaimTopic() {
        return TopicBuilder.name(CommonConstant.MQ_TOPIC_COUPON_CLAIM)
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
}
