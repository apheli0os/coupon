package com.mall.common.idempotent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.ResultCode;
import com.mall.common.constants.CommonConstant;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BizException;
import com.mall.common.metrics.BusinessMetrics;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BindingResult;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NoDuplicateSubmitAspectTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    static class TargetBean {

        @NoDuplicateSubmit(lockSeconds = 3)
        public String lockedWindowMethod(String arg) {
            return "proxied:" + arg;
        }

        @NoDuplicateSubmit(unlockOnFinish = true)
        public String unlockAfterMethod(String arg) {
            return "unlocked:" + arg;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    @Test
    void aopProxy_shouldRejectDuplicateAndRespectUnlockOnFinish() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock();
        when(redis.opsForValue()).thenReturn(valueOps);
        BusinessMetrics metrics = mock(BusinessMetrics.class);
        NoDuplicateSubmitAspect aspect = new NoDuplicateSubmitAspect(
                providerOf(redis), providerOf(metrics), providerOf(new ObjectMapper()));

        AspectJProxyFactory factory = new AspectJProxyFactory(new TargetBean());
        factory.addAspect(aspect);
        TargetBean proxy = factory.getProxy();

        UserContext.set(1001L, "USER");
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true, false);

        assertEquals("proxied:ORD-1", proxy.lockedWindowMethod("ORD-1"));
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());

        BizException dup = assertThrows(BizException.class, () -> proxy.lockedWindowMethod("ORD-1"));
        assertEquals(ResultCode.DUPLICATE_SUBMIT.getCode(), dup.getCode());
        verify(metrics).recordDuplicateSubmitRejected();

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        assertEquals("unlocked:ORD-1", proxy.unlockAfterMethod("ORD-1"));
        verify(redis).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    void around_shouldPassThroughWhenRedisUnavailableAndUseActorFallbacks() throws Throwable {
        NoDuplicateSubmit windowAnno = TargetBean.class
                .getDeclaredMethod("lockedWindowMethod", String.class)
                .getAnnotation(NoDuplicateSubmit.class);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        Signature sig = mock(Signature.class);
        when(sig.toLongString()).thenReturn("public java.lang.String OrderController.cancel(java.lang.String)");
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.proceed()).thenReturn("bypassed");

        NoDuplicateSubmitAspect noRedisAspect = new NoDuplicateSubmitAspect(
                providerOf(null), providerOf(null), providerOf(null));
        assertEquals("bypassed", noRedisAspect.around(pjp, windowAnno));

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock();
        when(redis.opsForValue()).thenReturn(valueOps);
        NoDuplicateSubmitAspect aspect = new NoDuplicateSubmitAspect(
                providerOf(redis), providerOf(null), providerOf(null));

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis timeout"));
        assertEquals("bypassed", aspect.around(pjp, windowAnno));

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        MockHttpServletRequest xffRequest = new MockHttpServletRequest();
        xffRequest.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        xffRequest.setRemoteAddr("10.0.0.5");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(xffRequest));
        BindingResult bindingResult = mock(BindingResult.class);
        when(pjp.getArgs()).thenReturn(new Object[]{xffRequest, bindingResult, LocalDateTime.of(2026, 11, 24, 10, 0)});
        when(pjp.proceed()).thenThrow(new IllegalStateException("boom"));
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenThrow(new RuntimeException("redis down"));

        assertThrows(IllegalStateException.class, () -> aspect.around(pjp, windowAnno));
        verify(valueOps).setIfAbsent(startsWith("idempotent:submit:ip:203.0.113.9:"), anyString(), any(Duration.class));
        verify(redis).execute(any(RedisScript.class), anyList(), any());

        MockHttpServletRequest headerUserRequest = new MockHttpServletRequest();
        headerUserRequest.addHeader(CommonConstant.USER_ID_HEADER, "8888");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(headerUserRequest));
        when(pjp.getArgs()).thenReturn(new Object[0]);
        doReturn("hdr-ok").when(pjp).proceed();
        assertEquals("hdr-ok", aspect.around(pjp, windowAnno));
        verify(valueOps).setIfAbsent(startsWith("idempotent:submit:u:8888:"), anyString(), any(Duration.class));

        RequestContextHolder.resetRequestAttributes();
        doReturn("anon-ok").when(pjp).proceed();
        assertEquals("anon-ok", aspect.around(pjp, windowAnno));
        verify(valueOps, times(2)).setIfAbsent(startsWith("idempotent:submit:anon:"), anyString(), any(Duration.class));
    }
}
