package com.mall.common.context;

import com.mall.common.exception.BizException;
import com.mall.common.ResultCode;

/**
 * 当前登录用户上下文，基于 ThreadLocal，请求结束后由拦截器清理。
 */
public final class UserContext {

    private UserContext() {
    }

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    public static void set(Long userId) {
        USER_ID.set(userId);
    }

    public static Long get() {
        return USER_ID.get();
    }

    /** 强制要求登录，未登录直接抛 401 */
    public static long getRequired() {
        Long userId = USER_ID.get();
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    public static void clear() {
        USER_ID.remove();
    }
}
