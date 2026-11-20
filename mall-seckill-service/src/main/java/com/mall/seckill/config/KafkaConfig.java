package com.mall.seckill.config;

import com.mall.common.constants.CommonConstant;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** 秒杀服务启动时声明订单 topic，避免首次发送时 topic 尚未创建的元数据等待 */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic seckillTopic() {
        return TopicBuilder.name(CommonConstant.MQ_TOPIC_SECKILL)
                .partitions(8).replicas(1).build();
    }
}
