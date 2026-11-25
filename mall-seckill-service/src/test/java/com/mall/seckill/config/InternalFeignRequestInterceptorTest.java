package com.mall.seckill.config;

import com.mall.common.constants.CommonConstant;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InternalFeignRequestInterceptorTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void apply_shouldAttachConfiguredInternalKeyAndTraceHeader() {
        MDC.put(CommonConstant.MDC_TRACE_ID, "trace-seckill-3");
        InternalFeignRequestInterceptor interceptor = new InternalFeignRequestInterceptor("secret-3");
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);
        assertEquals(List.of("secret-3"), List.copyOf(template.headers().get(CommonConstant.INTERNAL_AUTH_HEADER)));
        assertEquals(List.of("trace-seckill-3"), List.copyOf(template.headers().get(CommonConstant.TRACE_ID_HEADER)));
    }
}
