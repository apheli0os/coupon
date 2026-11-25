package com.mall.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public final class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);
        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder.headers(headers -> headers.set(TRACE_ID_HEADER, traceId)))
                .build();
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
