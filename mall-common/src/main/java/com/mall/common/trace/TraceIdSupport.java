package com.mall.common.trace;

import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.regex.Pattern;

public final class TraceIdSupport {

    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private TraceIdSupport() {
    }

    public static boolean isValid(String candidate) {
        return StringUtils.hasText(candidate) && VALID_TRACE_ID.matcher(candidate.trim()).matches();
    }

    public static String normalizeOrCreate(String candidate) {
        if (isValid(candidate)) {
            return candidate.trim();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
