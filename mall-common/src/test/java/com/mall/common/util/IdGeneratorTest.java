package com.mall.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdGeneratorTest {

    private final IdGenerator generator = new IdGenerator(1, 1);

    @Test
    void nextId_shouldBeMonotonicAndUnique() {
        Set<Long> ids = new HashSet<>();
        long prev = 0;
        for (int i = 0; i < 1000; i++) {
            long id = generator.nextId();
            assertTrue(id > 0, "雪花 ID 必须为正数");
            assertTrue(id > prev, "雪花 ID 必须递增");
            assertTrue(ids.add(id), "雪花 ID 不得重复");
            prev = id;
        }
        assertEquals(1000, ids.size());
    }

    @Test
    void nextIdStr_shouldMatchNumericValueShape() {
        String id = generator.nextIdStr();
        assertTrue(id.matches("\\d{15,}"), "ID 字符串应为长数字，实际=" + id);
    }

    @Test
    void resolveIds_shouldHandlePodNameWithoutNumberFormatException() {
        long[] ids = IdGenerator.resolveIds("coupon-service-7d9b8c-abcde", "1", "mall-coupon-service", null);
        assertTrue(ids[0] >= 0 && ids[0] < 32);
        assertTrue(ids[1] >= 0 && ids[1] < 32);
    }

    @Test
    void resolveIds_shouldUseRedisAtomicSequenceWhenAuto() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("mall:snowflake:worker:mall-seckill-service")).thenReturn(35L);

        long[] ids = IdGenerator.resolveIds("auto", "1", "mall-seckill-service", redis);
        assertEquals(3L, ids[0], "35 % 32 == 3");
        assertEquals(1L, ids[1], "(35 / 32) % 32 == 1");
    }
}
