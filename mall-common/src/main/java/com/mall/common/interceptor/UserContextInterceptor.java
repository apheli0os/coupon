package com.mall.common.interceptor;

import com.mall.common.ResultCode;
import com.mall.common.constants.CommonConstant;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BizException;
import com.mall.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class UserContextInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final String internalKey;

    public UserContextInterceptor(JwtUtil jwtUtil) {
        this(jwtUtil, CommonConstant.DEFAULT_INTERNAL_KEY);
    }

    @Autowired
    public UserContextInterceptor(
            JwtUtil jwtUtil,
            @Value("${mall.internal.key:" + CommonConstant.DEFAULT_INTERNAL_KEY + "}") String internalKey) {
        this.jwtUtil = jwtUtil;
        this.internalKey = internalKey == null ? CommonConstant.DEFAULT_INTERNAL_KEY : internalKey;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isInternalUri(request.getRequestURI())) {
            String supplied = request.getHeader(CommonConstant.INTERNAL_AUTH_HEADER);
            if (!matchesInternalKey(supplied)) {
                throw new BizException(ResultCode.FORBIDDEN, "禁止访问内部接口");
            }
        }
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
                UserContext.clear();
            }
        }
        return true;
    }

    private boolean matchesInternalKey(String supplied) {
        if (!StringUtils.hasText(supplied) || !StringUtils.hasText(internalKey)) {
            return false;
        }
        return MessageDigest.isEqual(
                internalKey.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isInternalUri(String uri) {
        if (!StringUtils.hasText(uri)) {
            return false;
        }
        PathContainer path = PathContainer.parsePath(uri);
        for (PathContainer.Element element : path.elements()) {
            if (element instanceof PathContainer.PathSegment segment) {
                if (isInternalSegment(segment.valueToMatch()) || isInternalSegment(segment.value())) {
                    return true;
                }
            }
        }
        for (String rawSegment : uri.split("/")) {
            if (isInternalSegment(rawSegment)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInternalSegment(String segment) {
        if (!StringUtils.hasText(segment)) {
            return false;
        }
        if ("internal".equalsIgnoreCase(stripMatrix(segment))) {
            return true;
        }
        try {
            String decoded = UriUtils.decode(segment, StandardCharsets.UTF_8);
            return "internal".equalsIgnoreCase(stripMatrix(decoded));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String stripMatrix(String segment) {
        int semi = segment.indexOf(';');
        return semi >= 0 ? segment.substring(0, semi) : segment;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
