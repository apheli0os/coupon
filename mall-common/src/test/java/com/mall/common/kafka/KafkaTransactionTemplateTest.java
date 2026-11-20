package com.mall.common.kafka;

import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaTransactionTemplateTest {

    @Test
    @SuppressWarnings("unchecked")
    void processAndProceed_shouldSendOutputAndCommitOffsetsInTransaction() {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        KafkaOperations<String, Object> txOps = mock(KafkaOperations.class);
        when(kafkaTemplate.executeInTransaction(any())).thenAnswer(inv -> {
            KafkaOperations.OperationsCallback<String, Object, Object> cb = inv.getArgument(0);
            return cb.doInOperations(txOps);
        });

        ProducerRecord<String, Object> outRecord = new ProducerRecord<>("out-topic", "k1", "v1");
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("out-topic", 0), 0, 1, 0, 0, 0);
        when(txOps.send(outRecord)).thenReturn(CompletableFuture.completedFuture(new SendResult<>(outRecord, metadata)));

        KafkaTransactionTemplate template = new KafkaTransactionTemplate(kafkaTemplate);
        ConsumerRecord<String, String> inRecord = new ConsumerRecord<>("in-topic", 2, 41L, "k1", "payload");

        template.processAndProceed(inRecord, "test-group", r -> outRecord);

        verify(txOps).send(outRecord);
        ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> offsetCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<ConsumerGroupMetadata> groupCaptor = ArgumentCaptor.forClass(ConsumerGroupMetadata.class);
        verify(txOps).sendOffsetsToTransaction(offsetCaptor.capture(), groupCaptor.capture());

        OffsetAndMetadata committed = offsetCaptor.getValue().get(new TopicPartition("in-topic", 2));
        assertEquals(42L, committed.offset(), "必须提交 record.offset() + 1");
        assertEquals("test-group", groupCaptor.getValue().groupId());
    }
}
