package com.mall.common.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;

/**
 * Kafka 事务模板：把「处理消息 + 发送新消息 + 提交消费位移」包在一个事务里，
 * 实现 read-process-write 的原子性（Exactly-Once 语义）。
 *
 * <p>仅在 kafka.transaction.enabled=true 时装配（见 {@link KafkaTransactionConfig}）。
 * 默认仍用 at-least-once + 幂等消费 + 定时对账，不引入事务开销。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kafka.transaction.enabled", havingValue = "true")
public class KafkaTransactionTemplate {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaTransactionTemplate(
            @Qualifier("transactionalKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 在事务中处理一条消息：执行业务处理函数产出消息，并通过 sendOffsetsToTransaction 原子提交消费位移。
     * 注意：对应监听器必须关闭自动提交并设为手动提交（ack-mode=manual），消费端需设 isolation.level=read_committed。
     */
    public <K, V> void processAndProceed(ConsumerRecord<K, V> record,
                                         String consumerGroupId,
                                         Function<ConsumerRecord<K, V>, ProducerRecord<String, Object>> handler) {
        kafkaTemplate.executeInTransaction(tx -> {
            ProducerRecord<String, Object> out = handler.apply(record);
            if (out != null) {
                try {
                    SendResult<String, Object> result = tx.send(out).get();
                    log.debug("事务消息已发送: topic={}, partition={}, offset={}",
                            result.getRecordMetadata().topic(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                } catch (Exception e) {
                    throw new IllegalStateException("事务消息发送失败", e);
                }
            }
            Map<TopicPartition, OffsetAndMetadata> offsets = Map.of(
                    new TopicPartition(record.topic(), record.partition()),
                    new OffsetAndMetadata(record.offset() + 1));
            tx.sendOffsetsToTransaction(offsets, new ConsumerGroupMetadata(consumerGroupId));
            return null;
        });
    }

    /** 仅发送事务消息（不涉及消费位移），适合生产端需要原子写多条消息的场景 */
    @SafeVarargs
    public final void sendInTransaction(ProducerRecord<String, Object>... records) {
        kafkaTemplate.executeInTransaction(tx -> {
            for (ProducerRecord<String, Object> record : records) {
                try {
                    tx.send(record).get();
                } catch (Exception e) {
                    throw new IllegalStateException("事务消息发送失败: " + record.topic(), e);
                }
            }
            return null;
        });
    }
}
