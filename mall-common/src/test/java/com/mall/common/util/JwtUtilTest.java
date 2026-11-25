package com.mall.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private final JwtUtil jwt = new JwtUtil(
            "test-secret-key-must-be-at-least-32-bytes-long-2026", 3600);

    @Test
    void generateAndParse_shouldReturnSameUserIdAndUniqueJti() {
        String token1 = jwt.generateToken(12345L, "alice");
        String token2 = jwt.generateToken(12345L, "alice");
        assertEquals(12345L, jwt.parseUserId(token1));
        assertNotNull(jwt.parseJti(token1));
        assertNotEquals(jwt.parseJti(token1), jwt.parseJti(token2));
        assertNull(jwt.parseJti("invalid"));
    }

    @Test
    void parseWithBearerPrefix_shouldStripPrefix() {
        String token = jwt.generateToken(999L, "bob");
        assertEquals(999L, jwt.parseUserId(JwtUtil.PREFIX + token));
    }

    @Test
    void parseInvalidOrBlank_shouldReturnNull() {
        assertNull(jwt.parseUserId(null));
        assertNull(jwt.parseUserId(""));
        assertNull(jwt.parseUserId("not.a.valid.jwt"));
        assertNull(jwt.parseUserId("Bearer garbage"));
    }
}
