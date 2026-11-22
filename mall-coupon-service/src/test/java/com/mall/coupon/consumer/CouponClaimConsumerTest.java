package com.mall.coupon.consumer;

import com.mall.common.constants.RedisKey;
import com.mall.common.message.CouponClaimMessage;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.coupon.domain.entity.UserCoupon;
import com.mall.coupon.mapper.CouponTemplateMapper;
import com.mall.coupon.mapper.UserCouponMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponClaimConsumerTest {

    @Test
    @SuppressWarnings("unchecked")
    void onMessage_shouldSkipRolledBackTokensAndOnlyPersistValidMessages() {
        UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
        CouponTemplateMapper templateMapper = mock(CouponTemplateMapper.class);
        LuaScriptExecutor luaExecutor = mock(LuaScriptExecutor.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        CouponClaimMessage valid = sampleMsg(1L, 100L, 1001L);
        CouponClaimMessage rolledBack = sampleMsg(2L, 100L, 1002L);

        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_TOKEN_CLAIM),
                eq(List.of(RedisKey.couponClaimToken(100L, 1L))), eq("1"), any()))
                .thenReturn(List.of(1L, 0L));
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_TOKEN_CLAIM),
                eq(List.of(RedisKey.couponClaimToken(100L, 2L))), eq("2"), any()))
                .thenReturn(List.of(0L, 0L));
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_COUPON_EXPIRE_COMPENSATE),
                anyList(), eq("1002"), eq(RedisKey.ROLLBACK_GUARD_TTL_SECONDS)))
                .thenReturn(List.of(1L, 9L));
        when(luaExecutor.parse(List.of(1L, 0L))).thenReturn(new long[]{1L, 0L});
        when(luaExecutor.parse(List.of(0L, 0L))).thenReturn(new long[]{0L, 0L});
        when(luaExecutor.parse(List.of(1L, 9L))).thenReturn(new long[]{1L, 9L});
        when(userCouponMapper.batchInsertIgnore(any())).thenReturn(1);
        when(templateMapper.incrReceivedBy(100L, 1)).thenReturn(1);

        CouponClaimConsumer consumer = new CouponClaimConsumer(userCouponMapper, templateMapper, luaExecutor, redis);
        consumer.onMessage(List.of(valid, rolledBack));

        verify(userCouponMapper).batchInsertIgnore(argThat(list -> list.size() == 1 && list.get(0).getId().equals(1L)));
        verify(templateMapper).incrReceivedBy(100L, 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onMessage_whenTokenExpiredAndNotInDb_shouldCompensateStockInsteadOfSilentlyDropping() {
        UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
        CouponTemplateMapper templateMapper = mock(CouponTemplateMapper.class);
        LuaScriptExecutor luaExecutor = mock(LuaScriptExecutor.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        CouponClaimMessage expiredMsg = sampleMsg(99L, 100L, 1001L);
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_TOKEN_CLAIM),
                eq(List.of(RedisKey.couponClaimToken(100L, 99L))), eq("99"), any()))
                .thenReturn(List.of(0L, 0L));
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_COUPON_EXPIRE_COMPENSATE),
                anyList(), eq("1001"), eq(RedisKey.ROLLBACK_GUARD_TTL_SECONDS)))
                .thenReturn(List.of(0L, 10L));
        when(luaExecutor.parse(List.of(0L, 0L))).thenReturn(new long[]{0L, 0L});
        when(luaExecutor.parse(List.of(0L, 10L))).thenReturn(new long[]{0L, 10L});
        when(userCouponMapper.selectById(99L)).thenReturn(null);

        CouponClaimConsumer consumer = new CouponClaimConsumer(userCouponMapper, templateMapper, luaExecutor, redis);
        consumer.onMessage(List.of(expiredMsg));

        verify(luaExecutor).execute(eq(LuaScriptExecutor.SCRIPT_COUPON_EXPIRE_COMPENSATE),
                eq(List.of(
                        RedisKey.couponStock(100L),
                        RedisKey.couponClaimHash(100L),
                        RedisKey.couponClaimToken(100L, 99L),
                        RedisKey.couponRollbackGuard(100L, 99L))),
                eq("1001"), eq(RedisKey.ROLLBACK_GUARD_TTL_SECONDS));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onMessage_whenTokenMissingButAlreadyPersistedInDb_shouldNotCompensateStock() {
        UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
        CouponTemplateMapper templateMapper = mock(CouponTemplateMapper.class);
        LuaScriptExecutor luaExecutor = mock(LuaScriptExecutor.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        CouponClaimMessage redelivered = sampleMsg(88L, 100L, 1001L);
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_TOKEN_CLAIM),
                eq(List.of(RedisKey.couponClaimToken(100L, 88L))), eq("88"), any()))
                .thenReturn(List.of(0L, 0L));
        when(luaExecutor.parse(List.of(0L, 0L))).thenReturn(new long[]{0L, 0L});
        UserCoupon existing = new UserCoupon();
        existing.setId(88L);
        when(userCouponMapper.selectById(88L)).thenReturn(existing);

        CouponClaimConsumer consumer = new CouponClaimConsumer(userCouponMapper, templateMapper, luaExecutor, redis);
        consumer.onMessage(List.of(redelivered));

        verify(luaExecutor, never()).execute(eq(LuaScriptExecutor.SCRIPT_COUPON_EXPIRE_COMPENSATE),
                anyList(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onMessage_shouldThrowAndRollbackWhenIncrReceivedReturnsZero() {
        UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
        CouponTemplateMapper templateMapper = mock(CouponTemplateMapper.class);
        LuaScriptExecutor luaExecutor = mock(LuaScriptExecutor.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        CouponClaimMessage valid = sampleMsg(10L, 100L, 1001L);
        when(luaExecutor.execute(eq(LuaScriptExecutor.SCRIPT_TOKEN_CLAIM),
                eq(List.of(RedisKey.couponClaimToken(100L, 10L))), eq("10"), any()))
                .thenReturn(List.of(1L, 0L));
        when(luaExecutor.parse(List.of(1L, 0L))).thenReturn(new long[]{1L, 0L});
        when(userCouponMapper.batchInsertIgnore(any())).thenReturn(1);
        when(templateMapper.incrReceivedBy(100L, 1)).thenReturn(0);

        CouponClaimConsumer consumer = new CouponClaimConsumer(userCouponMapper, templateMapper, luaExecutor, redis);
        assertThrows(IllegalStateException.class, () -> consumer.onMessage(List.of(valid)),
                "incrReceivedBy 返回 0 时必须抛出异常回滚事务，避免已插入 user_coupon 造成静默超发");
    }

    private CouponClaimMessage sampleMsg(long id, long templateId, long userId) {
        CouponClaimMessage m = new CouponClaimMessage();
        m.setId(id);
        m.setTemplateId(templateId);
        m.setUserId(userId);
        m.setCouponName("券");
        m.setType(1);
        m.setDiscountValue(BigDecimal.TEN);
        m.setThreshold(BigDecimal.ZERO);
        m.setStatus(0);
        m.setExpireTime(LocalDateTime.now().plusDays(1));
        m.setCreateTime(LocalDateTime.now());
        m.setUpdateTime(LocalDateTime.now());
        return m;
    }
}
