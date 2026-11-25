package com.mall.common.interceptor;

import com.mall.common.ResultCode;
import com.mall.common.constants.CommonConstant;
import com.mall.common.constants.RedisKey;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BizException;
import com.mall.common.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserContextInterceptorTest {

    private final JwtUtil jwtUtil = new JwtUtil("mall-coupon-platform-test-secret-key-2026", 3600L);
    private final UserContextInterceptor interceptor =
            new UserContextInterceptor(jwtUtil, CommonConstant.DEFAULT_INTERNAL_KEY);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void preHandle_shouldRejectInternalPathsWithoutValidInternalKey() {
        for (String uri : List.of(
                "/api/coupon/internal/settlement/unlock",
                "/api/coupon/internal;bypass=1/settlement/unlock",
                "/api/orders/%69nternal/ORD-1/apply-coupon")) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
            request.addHeader(CommonConstant.INTERNAL_AUTH_HEADER, "wrong-key");
            MockHttpServletResponse response = new MockHttpServletResponse();

            BizException ex = assertThrows(BizException.class,
                    () -> interceptor.preHandle(request, response, new Object()));
            assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getCode());
        }
    }

    @Test
    void preHandle_shouldAllowInternalPathWithMatchingInternalKey() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/coupon/internal/settlement/lock");
        request.addHeader(CommonConstant.INTERNAL_AUTH_HEADER, CommonConstant.DEFAULT_INTERNAL_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertNull(UserContext.get());
    }

    @Test
    void preHandle_shouldPopulateUserContextOnPublicPath() {
        String token = jwtUtil.generateToken(2002L, "alice", "ADMIN");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/coupon/quote");
        request.addHeader(JwtUtil.HEADER, JwtUtil.PREFIX + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(2002L, UserContext.get());
        assertTrue(UserContext.isAdmin());
    }

    @Test
    void preHandle_shouldEnforceRedisBlacklistAndFailClosedOnRedisError() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ObjectProvider<StringRedisTemplate> provider = mock();
        when(provider.getIfAvailable()).thenReturn(redis);
        UserContextInterceptor guarded = new UserContextInterceptor(
                jwtUtil, CommonConstant.DEFAULT_INTERNAL_KEY, provider);

        String token = jwtUtil.generateToken(3001L, "bob", "USER");
        String jti = jwtUtil.parseJti(token);
        when(redis.hasKey(RedisKey.jwtBlacklist(jti))).thenReturn(true);

        MockHttpServletRequest revokedReq = new MockHttpServletRequest("GET", "/api/coupon/my");
        revokedReq.addHeader(JwtUtil.HEADER, JwtUtil.PREFIX + token);
        BizException revokedEx = assertThrows(BizException.class,
                () -> guarded.preHandle(revokedReq, new MockHttpServletResponse(), new Object()));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), revokedEx.getCode());

        when(redis.hasKey(RedisKey.jwtBlacklist(jti))).thenThrow(new RuntimeException("redis down"));
        BizException redisDownEx = assertThrows(BizException.class,
                () -> guarded.preHandle(revokedReq, new MockHttpServletResponse(), new Object()));
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), redisDownEx.getCode());

        String noJtiToken = Jwts.builder()
                .subject("3002")
                .claim("userId", 3002L)
                .claim("role", "USER")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000L))
                .signWith(Keys.hmacShaKeyFor(
                        "mall-coupon-platform-test-secret-key-2026".getBytes(StandardCharsets.UTF_8)))
                .compact();
        MockHttpServletRequest noJtiReq = new MockHttpServletRequest("GET", "/api/coupon/my");
        noJtiReq.addHeader(JwtUtil.HEADER, JwtUtil.PREFIX + noJtiToken);
        BizException noJtiEx = assertThrows(BizException.class,
                () -> guarded.preHandle(noJtiReq, new MockHttpServletResponse(), new Object()));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), noJtiEx.getCode());
    }
}
