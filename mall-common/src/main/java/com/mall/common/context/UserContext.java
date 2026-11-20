package com.mall.common.context;

import com.mall.common.ResultCode;
import com.mall.common.constants.CommonConstant;
import com.mall.common.exception.BizException;

/**
 * 当前登录用户上下文，基于 ThreadLocal，请求结束后由拦截器清理。
 */
public final class UserContext {

    private UserContext() {
    }

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ROLE = new ThreadLocal<>();

    public static void set(Long userId) {
        USER_ID.set(userId);
    }

    public static void set(Long userId, String role) {
        USER_ID.set(userId);
        USER_ROLE.set(role == null || role.isBlank() ? CommonConstant.ROLE_USER : role);
    }

    public static Long get() {
        return USER_ID.get();
    }

    public static String getRole() {
        String role = USER_ROLE.get();
        return role == null ? CommonConstant.ROLE_USER : role;
    }

    public static boolean isAdmin() {
        return CommonConstant.ROLE_ADMIN.equalsIgnoreCase(getRole());
    }

    /** 强制要求登录，未登录直接抛 401 */
    public static long getRequired() {
        Long userId = USER_ID.get();
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    /** 强制要求管理员权限，未登录抛 401，非管理员抛 403 */
    public static long requireAdmin() {
        long userId = getRequired();
        if (!isAdmin()) {
            throw new BizException(ResultCode.FORBIDDEN, "需要管理员权限");
        }
        return userId;
    }

    public static void clear() {
        USER_ID.remove();
        USER_ROLE.remove();
    }
}
