package com.mall.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageResultTest {

    @Test
    void of_shouldComputePagesAndHandleNullOrZeroSize() {
        PageResult<String> normal = PageResult.of(2, 10, 25, List.of("a", "b"));
        assertEquals(2L, normal.getCurrent());
        assertEquals(10L, normal.getSize());
        assertEquals(25L, normal.getTotal());
        assertEquals(3L, normal.getPages());
        assertEquals(2, normal.getRecords().size());

        PageResult<String> empty = PageResult.of(1, 0, 10, null);
        assertEquals(0L, empty.getPages());
        assertTrue(empty.getRecords().isEmpty());
    }
}
