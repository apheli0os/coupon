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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 统一鉴权：网关校验 JWT 后把 userId 注入请求头，下游服务直接信任网关透传的 X-User-Id。
 * 生产环境应部署网关为唯一入口，下游服务不对外暴露。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final List<String> WHITE_LIST = List.of("/api/auth/token", "/actuator/health");

    private final SecretKey key;

    public AuthGlobalFilter(@Value("${mall.jwt.secret:mall-coupon-platform-default-secret-key-2026}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (WHITE_LIST.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String token = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        Long userId = parseUserId(token);
        if (userId == null) {
            return unauthorized(exchange);
        }
        // 覆盖客户端伪造的 X-User-Id，确保身份只能来自网关
        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder.header(USER_ID_HEADER, String.valueOf(userId)))
                .build();
        return chain.filter(mutated);
    }

    private Long parseUserId(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            String raw = token.startsWith("Bearer ") ? token.substring(7) : token;
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(raw).getPayload();
            Object userId = claims.get("userId");
            return userId == null ? null : Long.valueOf(userId.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":401,\"message\":\"未登录或登录已失效\"}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
