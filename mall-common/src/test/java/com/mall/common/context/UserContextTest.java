package com.mall.common.context;

import com.mall.common.ResultCode;
import com.mall.common.constants.CommonConstant;
import com.mall.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserContextTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void requireAdmin_shouldEnforceAuthAndRole() {
        BizException unauth = assertThrows(BizException.class, UserContext::requireAdmin);
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), unauth.getCode());

        UserContext.set(1001L, CommonConstant.ROLE_USER);
        assertFalse(UserContext.isAdmin());
        BizException forbidden = assertThrows(BizException.class, UserContext::requireAdmin);
        assertEquals(ResultCode.FORBIDDEN.getCode(), forbidden.getCode());

        UserContext.set(1L, CommonConstant.ROLE_ADMIN);
        assertTrue(UserContext.isAdmin());
        assertEquals(1L, UserContext.requireAdmin());

        UserContext.clear();
        assertNull(UserContext.get());
        assertFalse(UserContext.isAdmin());
    }
}
