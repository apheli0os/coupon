package com.mall.coupon.config;

import com.mall.common.constants.CommonConstant;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class InternalFeignRequestInterceptor implements RequestInterceptor {

    private final String internalKey;

    public InternalFeignRequestInterceptor(
            @Value("${mall.internal.key:" + CommonConstant.DEFAULT_INTERNAL_KEY + "}") String internalKey) {
        this.internalKey = internalKey == null ? CommonConstant.DEFAULT_INTERNAL_KEY : internalKey;
    }

    @Override
    public void apply(RequestTemplate template) {
        template.header(CommonConstant.INTERNAL_AUTH_HEADER, internalKey);
        String traceId = MDC.get(CommonConstant.MDC_TRACE_ID);
        if (StringUtils.hasText(traceId)) {
            template.header(CommonConstant.TRACE_ID_HEADER, traceId.trim());
        }
    }
}
