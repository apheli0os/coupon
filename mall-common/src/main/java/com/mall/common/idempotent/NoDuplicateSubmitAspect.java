package com.mall.common.idempotent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.ResultCode;
import com.mall.common.constants.CommonConstant;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BizException;
import com.mall.common.metrics.BusinessMetrics;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Slf4j
@Aspect
@Component
public class NoDuplicateSubmitAspect {

    private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectProvider<BusinessMetrics> metricsProvider;
    private final ObjectProvider<ObjectMapper> objectMapperProvider;

    @Autowired
    public NoDuplicateSubmitAspect(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectProvider<BusinessMetrics> metricsProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.metricsProvider = metricsProvider;
        this.objectMapperProvider = objectMapperProvider;
    }

    @Around("@annotation(noDuplicateSubmit)")
    public Object around(ProceedingJoinPoint joinPoint, NoDuplicateSubmit noDuplicateSubmit) throws Throwable {
        StringRedisTemplate redisTemplate = resolveRedisTemplate();
        if (redisTemplate == null) {
            return joinPoint.proceed();
        }
        String key = buildKey(joinPoint);
        String token = UUID.randomUUID().toString();
        Duration ttl = Duration.ofSeconds(Math.max(1, noDuplicateSubmit.lockSeconds()));
        Boolean acquired;
        try {
            acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        } catch (Exception e) {
            log.warn("防重提交 Redis 不可用，降级放行 key={}", key, e);
            return joinPoint.proceed();
        }
        if (Boolean.FALSE.equals(acquired)) {
            BusinessMetrics metrics = resolveMetrics();
            if (metrics != null) {
                metrics.recordDuplicateSubmitRejected();
            }
            throw new BizException(ResultCode.DUPLICATE_SUBMIT, noDuplicateSubmit.message());
        }
        boolean release = noDuplicateSubmit.unlockOnFinish();
        try {
            return joinPoint.proceed();
        } catch (Throwable ex) {
            release = true;
            throw ex;
        } finally {
            if (release) {
                releaseQuietly(redisTemplate, key, token);
            }
        }
    }

    private StringRedisTemplate resolveRedisTemplate() {
        return redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable();
    }

    private BusinessMetrics resolveMetrics() {
        return metricsProvider == null ? null : metricsProvider.getIfAvailable();
    }

    private ObjectMapper resolveObjectMapper() {
        ObjectMapper bean = objectMapperProvider == null ? null : objectMapperProvider.getIfAvailable();
        return bean == null ? FALLBACK_MAPPER : bean;
    }

    private String buildKey(ProceedingJoinPoint joinPoint) {
        String actor = resolveActor();
        String method = joinPoint.getSignature().toLongString();
        String digest = sha256(serializeArgs(joinPoint.getArgs()));
        return "idempotent:submit:" + actor + ":" + method + ":" + digest;
    }

    private String resolveActor() {
        Long userId = UserContext.get();
        if (userId != null) {
            return "u:" + userId;
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            String userHeader = request.getHeader(CommonConstant.USER_ID_HEADER);
            if (StringUtils.hasText(userHeader)) {
                return "u:" + userHeader.trim();
            }
            String forwarded = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(forwarded)) {
                String firstHop = forwarded.split(",")[0].trim();
                if (!firstHop.isEmpty()) {
                    return "ip:" + firstHop;
                }
            }
            if (StringUtils.hasText(request.getRemoteAddr())) {
                return "ip:" + request.getRemoteAddr().trim();
            }
        }
        return "anon";
    }

    private String serializeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        List<Object> filtered = new ArrayList<>(args.length);
        for (Object arg : args) {
            if (arg instanceof ServletRequest || arg instanceof ServletResponse || arg instanceof BindingResult) {
                continue;
            }
            filtered.add(arg);
        }
        if (filtered.isEmpty()) {
            return "";
        }
        try {
            return resolveObjectMapper().writeValueAsString(filtered);
        } catch (JsonProcessingException e) {
            return Arrays.deepToString(filtered.toArray());
        }
    }

    private String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    private void releaseQuietly(StringRedisTemplate redisTemplate, String key, String token) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(key), token);
        } catch (Exception e) {
            log.warn("释放防重提交锁失败 key={}", key, e);
        }
    }
}
