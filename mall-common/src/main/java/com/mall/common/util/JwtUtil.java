package com.mall.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具。生产环境密钥应从配置中心/KMS 注入，且长度不少于 32 字节。
 */
@Component
public class JwtUtil {

    public static final String HEADER = "Authorization";
    public static final String PREFIX = "Bearer ";

    private final SecretKey key;
    private final long expireMillis;

    public JwtUtil(@Value("${mall.jwt.secret:mall-coupon-platform-default-secret-key-2026}") String secret,
                   @Value("${mall.jwt.expire-seconds:86400}") long expireSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireSeconds * 1000L;
    }

    public String generateToken(Long userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("userId", userId)
                .claim("username", username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    /** 解析失败返回 null，由调用方决定是否放行（演示接口支持 X-User-Id 直通） */
    public Long parseUserId(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            String raw = token.startsWith(PREFIX) ? token.substring(PREFIX.length()) : token;
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(raw)
                    .getPayload();
            Object userId = claims.get("userId");
            return userId == null ? null : Long.valueOf(userId.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
