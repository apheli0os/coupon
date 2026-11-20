package com.mall.common.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Kafka 事务模板：把「处理消息 + 发送新消息 + 提交消费位移」包在一个事务里，
 * 实现 read-process-write 的原子性（Exactly-Once 语义）。
 *
 * 仅在 kafka.transaction.enabled=true 时装配（见 KafkaTransactionConfig）。
 * 默认仍用 at-least-once + 幂等消费 + 定时对账，不引入事务开销。
 *
 * 用法（消费端）：
 *   kafkaTxTemplate.processAndProceed(record, r -> {
 *       orderService.create(r.value());          // 处理
 *       return new ProducerRecord<>("out-topic", key, value); // 产出
 *   });
 * 事务成功时，消费位移与产出消息一起提交；失败则全部回滚，消息会被重新消费。
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
     * 在事务中处理一条消息：执行业务处理函数产出消息，随消费位移一起提交。
     * 注意：使用此方法时，对应监听器必须设为手动提交（ack-mode=manual），
     * 由本模板通过 KafkaTemplate.sendOffsetsToTransaction 提交位移。
     */
    public <K, V> void processAndProceed(ConsumerRecord<K, V> record,
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
            return null;
        });
    }

    /** 仅发送事务消息（不涉及消费位移），适合生产端需要原子写多条消息的场景 */
    public void sendInTransaction(ProducerRecord<String, Object>... records) {
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
