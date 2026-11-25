package com.mall.gateway.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GatewayFallbackControllerTest {

    @Test
    void fallback_shouldReturnServiceUnavailablePayload() {
        GatewayFallbackController controller = new GatewayFallbackController();
        ResponseEntity<Map<String, Object>> response = controller.fallback("coupon");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(503, response.getBody().get("code"));
        assertEquals("coupon", response.getBody().get("service"));
    }
}
