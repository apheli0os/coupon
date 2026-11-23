package com.mall.gateway.filter;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthGlobalFilterTest {

    private static final String SECRET = "test-gateway-secret-key-at-least-32-bytes-long-2026";
    private final AuthGlobalFilter filter = new AuthGlobalFilter(SECRET);

    private String issueToken(Long userId, String role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("userId", userId)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 60_000))
                .signWith(key)
                .compact();
    }

    @Test
    void shouldOverwriteForgedUserIdAndRoleHeadersInsteadOfAppending() {
        String token = issueToken(1001L, "USER");
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/seckill/2001")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", "9999")
                .header("X-User-Role", "ADMIN")
                .header("X-Internal-Key", "forged-internal-key")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            forwarded.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertNotNull(forwarded.get());
        HttpHeaders headers = forwarded.get().getRequest().getHeaders();
        assertEquals(List.of("1001"), headers.get("X-User-Id"), "必须覆盖而非追加 X-User-Id");
        assertEquals(List.of("USER"), headers.get("X-User-Role"), "必须覆盖而非追加 X-User-Role");
        assertNull(headers.getFirst("X-Internal-Key"));
    }

    @Test
    void shouldBlockInternalEndpointsWithForbidden() {
        String token = issueToken(1001L, "ADMIN");
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders/internal/by-activity")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<Boolean> called = new AtomicReference<>(false);
        GatewayFilterChain chain = ex -> {
            called.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertFalse(called.get(), "内部接口禁止放行");
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void shouldBlockInternalEndpointsWithMatrixParamsOrEncodedSegment() {
        String token = issueToken(1001L, "USER");
        for (String bypassPath : List.of(
                "/api/coupon/internal;bypass=1/settlement/unlock",
                "/api/orders/internal;x=1/ORD-1/apply-coupon",
                "/api/coupon/%69nternal/settlement/unlock")) {
            MockServerHttpRequest request = MockServerHttpRequest.post(bypassPath)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header("X-Internal-Key", "mall-coupon-platform-default-internal-key-2026")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            AtomicReference<Boolean> called = new AtomicReference<>(false);
            filter.filter(exchange, ex -> {
                called.set(true);
                return Mono.empty();
            }).block();

            assertFalse(called.get());
            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        }
    }

    @Test
    void shouldRejectMissingOrInvalidJwtWithUnauthorized() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders/12345")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.token.here")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void shouldStripForgedHeadersOnWhitelistPath() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/auth/token")
                .header("X-User-Id", "9999")
                .header("X-User-Role", "ADMIN")
                .header("X-Internal-Key", "forged-internal-key")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            forwarded.set(ex);
            return Mono.empty();
        }).block();

        assertNotNull(forwarded.get());
        assertNull(forwarded.get().getRequest().getHeaders().getFirst("X-User-Id"));
        assertNull(forwarded.get().getRequest().getHeaders().getFirst("X-User-Role"));
        assertNull(forwarded.get().getRequest().getHeaders().getFirst("X-Internal-Key"));
    }
}
