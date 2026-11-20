package com.mall.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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
}
