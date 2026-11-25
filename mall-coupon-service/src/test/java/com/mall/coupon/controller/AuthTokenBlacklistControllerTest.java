package com.mall.coupon.controller;

import com.mall.common.ResultCode;
import com.mall.common.constants.CommonConstant;
import com.mall.common.constants.RedisKey;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BizException;
import com.mall.common.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthTokenBlacklistControllerTest {

    private final JwtUtil jwtUtil = new JwtUtil("mall-coupon-platform-test-secret-key-2026", 3600L);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void logout_shouldBlacklistJtiWithRemainingTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock();
        when(redis.opsForValue()).thenReturn(valueOps);
        AuthTokenBlacklistController controller = new AuthTokenBlacklistController(jwtUtil, redis);

        String token = jwtUtil.generateToken(1001L, "alice", CommonConstant.ROLE_USER);
        String jti = jwtUtil.parseJti(token);
        assertNotNull(jti);

        controller.logout(JwtUtil.PREFIX + token);
        verify(valueOps).set(eq(RedisKey.jwtBlacklist(jti)), eq("1"), any(Duration.class));

        controller.logout("invalid");
        verify(valueOps, never()).set(eq(RedisKey.jwtBlacklist("invalid")), eq("1"), any(Duration.class));
    }

    @Test
    void banToken_shouldRequireAdminAndStoreBlacklist() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock();
        when(redis.opsForValue()).thenReturn(valueOps);
        AuthTokenBlacklistController controller = new AuthTokenBlacklistController(jwtUtil, redis);

        UserContext.set(1001L, CommonConstant.ROLE_USER);
        BizException forbidden = assertThrows(BizException.class, () -> controller.banToken("jti-abc", 600L));
        assertEquals(ResultCode.FORBIDDEN.getCode(), forbidden.getCode());

        UserContext.set(1001L, CommonConstant.ROLE_ADMIN);
        controller.banToken("jti-abc", 600L);
        verify(valueOps).set(RedisKey.jwtBlacklist("jti-abc"), "1", Duration.ofSeconds(600L));
    }
}
