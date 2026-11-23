package com.mall.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 统一鉴权：
 * 1) 拦截外部直接访问内部 Feign 接口（/internal/）；
 * 2) 网关校验 JWT 后使用 headers.set 覆盖写入 X-User-Id / X-User-Role，防止客户端伪造请求头越权或绕过限流。
 */
@Component
public final class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLE_HEADER = "X-User-Role";
    private static final String INTERNAL_AUTH_HEADER = "X-Internal-Key";
    private static final String DEFAULT_ROLE = "USER";
    private static final List<String> WHITE_LIST = List.of("/api/auth/token", "/actuator/health");

    private final SecretKey key;

    public AuthGlobalFilter(@Value("${mall.jwt.secret:mall-coupon-platform-default-secret-key-2026}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (isInternalPath(exchange)) {
            return writeError(exchange, HttpStatus.FORBIDDEN, 403, "禁止访问内部接口");
        }
        String path = exchange.getRequest().getPath().value();
        if (WHITE_LIST.stream().anyMatch(path::startsWith)) {
            ServerWebExchange cleaned = exchange.mutate()
                    .request(builder -> builder.headers(headers -> {
                        headers.remove(USER_ID_HEADER);
                        headers.remove(USER_ROLE_HEADER);
                        headers.remove(INTERNAL_AUTH_HEADER);
                    }))
                    .build();
            return chain.filter(cleaned);
        }

        String token = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        Claims claims = parseClaims(token);
        Long userId = extractUserId(claims);
        if (userId == null) {
            return writeError(exchange, HttpStatus.UNAUTHORIZED, 401, "未登录或登录已失效");
        }
        String role = extractRole(claims);
        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder.headers(headers -> {
                    headers.set(USER_ID_HEADER, String.valueOf(userId));
                    headers.set(USER_ROLE_HEADER, role);
                    headers.remove(INTERNAL_AUTH_HEADER);
                }))
                .build();
        return chain.filter(mutated);
    }

    private boolean isInternalPath(ServerWebExchange exchange) {
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        for (PathContainer.Element element : path.elements()) {
            if (element instanceof PathContainer.PathSegment segment) {
                if (isInternalSegment(segment.valueToMatch()) || isInternalSegment(segment.value())) {
                    return true;
                }
            }
        }
        for (String rawSegment : path.value().split("/")) {
            if (isInternalSegment(rawSegment)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInternalSegment(String segment) {
        if (!StringUtils.hasText(segment)) {
            return false;
        }
        if ("internal".equalsIgnoreCase(stripMatrix(segment))) {
            return true;
        }
        try {
            String decoded = UriUtils.decode(segment, StandardCharsets.UTF_8);
            return "internal".equalsIgnoreCase(stripMatrix(decoded));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String stripMatrix(String segment) {
        int semi = segment.indexOf(';');
        return semi >= 0 ? segment.substring(0, semi) : segment;
    }

    private Claims parseClaims(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            String raw = token.startsWith("Bearer ") ? token.substring(7) : token;
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(raw).getPayload();
        } catch (Exception e) {
            return null;
        }
    }

    private Long extractUserId(Claims claims) {
        if (claims == null) {
            return null;
        }
        Object userId = claims.get("userId");
        return userId == null ? null : Long.valueOf(userId.toString());
    }

    private String extractRole(Claims claims) {
        Object role = claims.get("role");
        return role == null || role.toString().isBlank() ? DEFAULT_ROLE : role.toString();
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, int code, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + code + ",\"message\":\"" + message + "\"}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
