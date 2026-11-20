package com.mall.coupon.config;

import com.mall.common.constants.CommonConstant;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** 启动时声明领券 topic：8 分区支撑 4 个批量消费线程并行落库 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic couponClaimTopic() {
        return TopicBuilder.name(CommonConstant.MQ_TOPIC_COUPON_CLAIM)
                .partitions(8)
                .replicas(1)
                .build();
    }
}
