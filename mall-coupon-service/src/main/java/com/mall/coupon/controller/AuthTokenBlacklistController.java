package com.mall.coupon.controller;

import com.mall.common.R;
import com.mall.common.ResultCode;
import com.mall.common.constants.RedisKey;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BizException;
import com.mall.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthTokenBlacklistController {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;

    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader(value = JwtUtil.HEADER, required = false) String authorization) {
        Claims claims = jwtUtil.parseClaims(authorization);
        if (claims != null && StringUtils.hasText(claims.getId()) && claims.getExpiration() != null) {
            long remainingMillis = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (remainingMillis > 0) {
                stringRedisTemplate.opsForValue().set(
                        RedisKey.jwtBlacklist(claims.getId()), "1", Duration.ofMillis(remainingMillis));
            }
        }
        return R.ok();
    }

    @PostMapping("/ban/{jti}")
    public R<Void> banToken(
            @PathVariable String jti,
            @RequestParam(defaultValue = "86400") long ttlSeconds) {
        UserContext.requireAdmin();
        if (!StringUtils.hasText(jti)) {
            throw new BizException(ResultCode.PARAM_ERROR, "jti 不能为空");
        }
        stringRedisTemplate.opsForValue().set(
                RedisKey.jwtBlacklist(jti.trim()), "1", Duration.ofSeconds(Math.max(1L, ttlSeconds)));
        return R.ok();
    }
}
