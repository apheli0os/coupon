package com.mall.common.trace;

import com.mall.common.constants.CommonConstant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.BatchInterceptor;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceKafkaInterceptorTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void producerAndConsumerInterceptors_shouldPropagateAndClearTraceId() {
        MDC.put(CommonConstant.MDC_TRACE_ID, "trace-kafka-99");
        TraceKafkaProducerInterceptor producer = new TraceKafkaProducerInterceptor();
        ProducerRecord<Object, Object> sent = producer.onSend(new ProducerRecord<>("topic-a", "k1", "v1"));
        assertNotNull(sent.headers().lastHeader(CommonConstant.TRACE_ID_HEADER));
        assertEquals("trace-kafka-99",
                new String(sent.headers().lastHeader(CommonConstant.TRACE_ID_HEADER).value(), StandardCharsets.UTF_8));
        MDC.clear();

        RecordInterceptor<Object, Object> recordInterceptor = TraceKafkaConsumerConfig.traceRecordInterceptor();
        ConsumerRecord<Object, Object> cr = new ConsumerRecord<>("topic-a", 0, 1L, "k1", "v1");
        cr.headers().add(CommonConstant.TRACE_ID_HEADER, "trace-kafka-99".getBytes(StandardCharsets.UTF_8));
        recordInterceptor.intercept(cr, null);
        assertEquals("trace-kafka-99", MDC.get(CommonConstant.MDC_TRACE_ID));
        recordInterceptor.success(cr, null);
        assertNull(MDC.get(CommonConstant.MDC_TRACE_ID));

        BatchInterceptor<Object, Object> batchInterceptor = TraceKafkaConsumerConfig.traceBatchInterceptor();
        ConsumerRecords<Object, Object> crs = new ConsumerRecords<>(
                Map.of(new TopicPartition("topic-a", 0), List.of(cr)));
        batchInterceptor.intercept(crs, null);
        assertEquals("trace-kafka-99", MDC.get(CommonConstant.MDC_TRACE_ID));
        batchInterceptor.failure(crs, new RuntimeException("boom"), null);
        assertNull(MDC.get(CommonConstant.MDC_TRACE_ID));

        ConsumerRecord<Object, Object> cr2 = new ConsumerRecord<>("topic-a", 0, 2L, "k2", "v2");
        cr2.headers().add(CommonConstant.TRACE_ID_HEADER, "trace-other-88".getBytes(StandardCharsets.UTF_8));
        ConsumerRecords<Object, Object> mixed = new ConsumerRecords<>(
                Map.of(new TopicPartition("topic-a", 0), List.of(cr, cr2)));
        batchInterceptor.intercept(mixed, null);
        String mixedTrace = MDC.get(CommonConstant.MDC_TRACE_ID);
        assertNotNull(mixedTrace);
        assertNotEquals("trace-kafka-99", mixedTrace);
        assertNotEquals("trace-other-88", mixedTrace);
        batchInterceptor.success(mixed, null);

        TraceKafkaConsumerConfig config = new TraceKafkaConsumerConfig();
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        assertNotNull(config.traceKafkaFactoryPostProcessor().postProcessAfterInitialization(factory, "kf"));
    }
}
