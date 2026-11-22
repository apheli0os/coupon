package com.mall.common.util;

import com.mall.common.constants.CommonConstant;
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
public final class JwtUtil {

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
        return generateToken(userId, username, CommonConstant.ROLE_USER);
    }

    public String generateToken(Long userId, String username, String role) {
        Date now = new Date();
        String safeRole = StringUtils.hasText(role) ? role.toUpperCase() : CommonConstant.ROLE_USER;
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("userId", userId)
                .claim("username", username)
                .claim("role", safeRole)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    /** 解析失败返回 null，由调用方决定是否放行 */
    public Long parseUserId(String token) {
        Claims claims = parseClaims(token);
        if (claims == null) {
            return null;
        }
        Object userId = claims.get("userId");
        return userId == null ? null : Long.valueOf(userId.toString());
    }

    public String parseRole(String token) {
        Claims claims = parseClaims(token);
        if (claims == null) {
            return CommonConstant.ROLE_USER;
        }
        Object role = claims.get("role");
        return role == null || role.toString().isBlank() ? CommonConstant.ROLE_USER : role.toString();
    }

    public Claims parseClaims(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            String raw = token.startsWith(PREFIX) ? token.substring(PREFIX.length()) : token;
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(raw)
                    .getPayload();
        } catch (Exception e) {
            return null;
        }
    }
}
