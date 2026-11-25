package com.mall.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TraceIdGlobalFilterTest {

    private final TraceIdGlobalFilter filter = new TraceIdGlobalFilter();

    @Test
    void filter_shouldReplaceForgedTraceIdAndSetResponseHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/coupon/templates")
                .header(TraceIdGlobalFilter.TRACE_ID_HEADER, "forged-trace-id")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            forwarded.set(ex);
            return Mono.empty();
        }).block();

        assertNotNull(forwarded.get());
        String reqTraceId = forwarded.get().getRequest().getHeaders().getFirst(TraceIdGlobalFilter.TRACE_ID_HEADER);
        String respTraceId = exchange.getResponse().getHeaders().getFirst(TraceIdGlobalFilter.TRACE_ID_HEADER);
        assertNotNull(reqTraceId);
        assertFalse(reqTraceId.isBlank());
        assertNotEquals("forged-trace-id", reqTraceId);
        assertEquals(reqTraceId, respTraceId);
        assertEquals(-200, filter.getOrder());
    }
}
