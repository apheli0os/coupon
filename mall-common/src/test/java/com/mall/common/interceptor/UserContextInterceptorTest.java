package com.mall.common.interceptor;

import com.mall.common.ResultCode;
import com.mall.common.constants.CommonConstant;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BizException;
import com.mall.common.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
