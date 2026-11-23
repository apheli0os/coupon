package com.mall.seckill.config;

import com.mall.common.constants.CommonConstant;
import feign.RequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InternalFeignRequestInterceptorTest {

    @Test
    void apply_shouldAttachConfiguredInternalKeyHeader() {
        InternalFeignRequestInterceptor interceptor = new InternalFeignRequestInterceptor("secret-3");
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);
        assertEquals(List.of("secret-3"), List.copyOf(template.headers().get(CommonConstant.INTERNAL_AUTH_HEADER)));
    }
}
