package com.mall.common.interceptor;

import com.mall.common.constants.CommonConstant;
import com.mall.common.context.UserContext;
import com.mall.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 从 JWT 或网关透传的 X-User-Id / X-User-Role 请求头解析用户身份，写入 ThreadLocal。
 * 优先解析可信签名 JWT，避免直连下游服务时通过伪造 X-User-Id 越权。
 */
@Component
@RequiredArgsConstructor
public class UserContextInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Claims claims = jwtUtil.parseClaims(request.getHeader(JwtUtil.HEADER));
        if (claims != null && claims.get("userId") != null) {
            Long userId = Long.valueOf(claims.get("userId").toString());
            Object roleClaim = claims.get("role");
            String role = roleClaim == null ? CommonConstant.ROLE_USER : roleClaim.toString();
            UserContext.set(userId, role);
            return true;
        }
        String userIdHeader = request.getHeader(CommonConstant.USER_ID_HEADER);
        if (StringUtils.hasText(userIdHeader)) {
            try {
                String roleHeader = request.getHeader(CommonConstant.USER_ROLE_HEADER);
                UserContext.set(Long.valueOf(userIdHeader), roleHeader);
                return true;
            } catch (NumberFormatException ignored) {
                // 非法 header 忽略，按未登录处理
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
