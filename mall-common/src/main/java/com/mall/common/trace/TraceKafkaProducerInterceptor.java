package com.mall.common.trace;

import com.mall.common.constants.CommonConstant;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class TraceKafkaProducerInterceptor implements ProducerInterceptor<Object, Object> {

    @Override
    public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
        if (record != null && record.headers().lastHeader(CommonConstant.TRACE_ID_HEADER) == null) {
            String resolved = TraceIdSupport.normalizeOrCreate(MDC.get(CommonConstant.MDC_TRACE_ID));
            record.headers().add(CommonConstant.TRACE_ID_HEADER, resolved.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
    }
}
