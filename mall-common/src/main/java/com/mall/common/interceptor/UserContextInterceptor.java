package com.mall.common.interceptor;

import com.mall.common.constants.CommonConstant;
import com.mall.common.context.UserContext;
import com.mall.common.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 从 JWT 或演示用 X-User-Id 请求头解析用户身份，写入 ThreadLocal。
 * 真实项目中网关会完成鉴权，这里保留一层兜底解析。
 */
@Component
@RequiredArgsConstructor
public class UserContextInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userIdHeader = request.getHeader(CommonConstant.USER_ID_HEADER);
        if (StringUtils.hasText(userIdHeader)) {
            try {
                UserContext.set(Long.valueOf(userIdHeader));
                return true;
            } catch (NumberFormatException ignored) {
                // 非法 header 继续尝试 JWT
            }
        }
        Long userId = jwtUtil.parseUserId(request.getHeader(JwtUtil.HEADER));
        if (userId != null) {
            UserContext.set(userId);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
