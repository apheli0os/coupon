package com.mall.common.trace;

import com.mall.common.constants.CommonConstant;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.BatchInterceptor;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;

@Configuration
public class TraceKafkaConsumerConfig {

    @Bean
    public BeanPostProcessor traceKafkaFactoryPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            @SuppressWarnings("unchecked")
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof ConcurrentKafkaListenerContainerFactory<?, ?> rawFactory) {
                    ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                            (ConcurrentKafkaListenerContainerFactory<Object, Object>) rawFactory;
                    factory.setRecordInterceptor(traceRecordInterceptor());
                    factory.setBatchInterceptor(traceBatchInterceptor());
                }
                return bean;
            }
        };
    }

    public static RecordInterceptor<Object, Object> traceRecordInterceptor() {
        return new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<Object, Object> intercept(
                    ConsumerRecord<Object, Object> record,
                    Consumer<Object, Object> consumer) {
                bindTraceFromRecord(record);
                return record;
            }

            @Override
            public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
                MDC.remove(CommonConstant.MDC_TRACE_ID);
            }

            @Override
            public void failure(
                    ConsumerRecord<Object, Object> record,
                    Exception exception,
                    Consumer<Object, Object> consumer) {
                MDC.remove(CommonConstant.MDC_TRACE_ID);
            }
        };
    }

    public static BatchInterceptor<Object, Object> traceBatchInterceptor() {
        return new BatchInterceptor<>() {
            @Override
            public ConsumerRecords<Object, Object> intercept(
                    ConsumerRecords<Object, Object> records,
                    Consumer<Object, Object> consumer) {
                bindTraceFromRecords(records);
                return records;
            }

            @Override
            public void success(ConsumerRecords<Object, Object> records, Consumer<Object, Object> consumer) {
                MDC.remove(CommonConstant.MDC_TRACE_ID);
            }

            @Override
            public void failure(
                    ConsumerRecords<Object, Object> records,
                    Exception exception,
                    Consumer<Object, Object> consumer) {
                MDC.remove(CommonConstant.MDC_TRACE_ID);
            }
        };
    }

    private static void bindTraceFromRecord(ConsumerRecord<?, ?> record) {
        if (record == null) {
            return;
        }
        Header header = record.headers().lastHeader(CommonConstant.TRACE_ID_HEADER);
        String raw = header != null && header.value() != null
                ? new String(header.value(), StandardCharsets.UTF_8) : null;
        MDC.put(CommonConstant.MDC_TRACE_ID, TraceIdSupport.normalizeOrCreate(raw));
    }

    private static void bindTraceFromRecords(ConsumerRecords<?, ?> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        String sharedTraceId = null;
        for (ConsumerRecord<?, ?> record : records) {
            if (record == null) {
                continue;
            }
            Header header = record.headers().lastHeader(CommonConstant.TRACE_ID_HEADER);
            if (header == null || header.value() == null) {
                MDC.put(CommonConstant.MDC_TRACE_ID, TraceIdSupport.normalizeOrCreate(null));
                return;
            }
            String candidate = new String(header.value(), StandardCharsets.UTF_8).trim();
            if (!TraceIdSupport.isValid(candidate)) {
                MDC.put(CommonConstant.MDC_TRACE_ID, TraceIdSupport.normalizeOrCreate(null));
                return;
            }
            if (sharedTraceId == null) {
                sharedTraceId = candidate;
            } else if (!sharedTraceId.equals(candidate)) {
                MDC.put(CommonConstant.MDC_TRACE_ID, TraceIdSupport.normalizeOrCreate(null));
                return;
            }
        }
        MDC.put(CommonConstant.MDC_TRACE_ID, TraceIdSupport.normalizeOrCreate(sharedTraceId));
    }
}
