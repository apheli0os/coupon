package com.mall.order.config;

import com.mall.common.constants.CommonConstant;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
    }
}
