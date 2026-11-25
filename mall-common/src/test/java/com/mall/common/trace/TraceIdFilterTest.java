package com.mall.common.trace;

import com.mall.common.constants.CommonConstant;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void doFilter_shouldPropagateValidTraceIdAndClearMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/1");
        request.addHeader(CommonConstant.TRACE_ID_HEADER, "trace-abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> insideMdc = new AtomicReference<>();
        FilterChain chain = (req, res) -> insideMdc.set(MDC.get(CommonConstant.MDC_TRACE_ID));

        filter.doFilter(request, response, chain);
        assertEquals("trace-abc-123", insideMdc.get());
        assertEquals("trace-abc-123", response.getHeader(CommonConstant.TRACE_ID_HEADER));
        assertNull(MDC.get(CommonConstant.MDC_TRACE_ID));
    }

    @Test
    void doFilter_shouldGenerateTraceIdWhenMissingOrInvalid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/1");
        request.addHeader(CommonConstant.TRACE_ID_HEADER, "invalid\r\nheader");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> insideMdc = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> insideMdc.set(MDC.get(CommonConstant.MDC_TRACE_ID)));

        assertNotNull(insideMdc.get());
        assertNotEquals("invalid\r\nheader", insideMdc.get());
        assertEquals(insideMdc.get(), response.getHeader(CommonConstant.TRACE_ID_HEADER));
        assertNull(MDC.get(CommonConstant.MDC_TRACE_ID));
    }
}
