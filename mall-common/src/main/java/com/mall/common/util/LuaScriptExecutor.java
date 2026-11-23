package com.mall.common.util;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lua 脚本执行器。按 scriptPath 缓存 DefaultRedisScript 单例，复用脚本内容与 SHA1 摘要，
 * 避免热路径每次请求重复读资源流与重算 SHA1。
 */
@Component
@RequiredArgsConstructor
public class LuaScriptExecutor {

    public static final String SCRIPT_COUPON_CLAIM = "lua/coupon_claim.lua";
    public static final String SCRIPT_COUPON_ROLLBACK = "lua/coupon_rollback.lua";
    public static final String SCRIPT_COUPON_EXPIRE_COMPENSATE = "lua/coupon_expire_compensate.lua";
    public static final String SCRIPT_COUPON_STOCK_INCR_IF_PRESENT = "lua/coupon_stock_incr_if_present.lua";
    public static final String SCRIPT_SECKILL = "lua/seckill.lua";
    public static final String SCRIPT_SECKILL_ROLLBACK = "lua/seckill_rollback.lua";
    public static final String SCRIPT_SECKILL_EXPIRE_COMPENSATE = "lua/seckill_expire_compensate.lua";
    public static final String SCRIPT_SECKILL_CANCEL_ROLLBACK = "lua/seckill_cancel_rollback.lua";
    public static final String SCRIPT_SECKILL_SOLD_STATE = "lua/seckill_sold_state.lua";
    public static final String SCRIPT_TOKEN_CLAIM = "lua/token_claim.lua";

    private final StringRedisTemplate stringRedisTemplate;
    @SuppressWarnings("rawtypes")
    private final Map<String, DefaultRedisScript<List>> scriptCache = new ConcurrentHashMap<>();

    /** 脚本约定返回整型数组，统一转成 List<Long> */
    @SuppressWarnings("rawtypes")
    public List<Long> execute(String scriptPath, List<String> keys, Object... args) {
        DefaultRedisScript<List> script = getOrLoadScript(scriptPath);
        // StringRedisTemplate 的 ARGV 序列化器只接受 String，统一转换避免运行时 ClassCastException
        Object[] stringArgs = Arrays.stream(args).map(String::valueOf).toArray();
        List<?> raw = stringRedisTemplate.execute(script, keys, stringArgs);
        List<Long> result = new ArrayList<>();
        if (raw != null) {
            for (Object item : raw) {
                result.add(item instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(item)));
            }
        }
        return result;
    }

    @SuppressWarnings("rawtypes")
    DefaultRedisScript<List> getOrLoadScript(String scriptPath) {
        return scriptCache.computeIfAbsent(scriptPath, path -> {
            DefaultRedisScript<List> script = new DefaultRedisScript<>();
            script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
            script.setResultType(List.class);
            return script;
        });
    }

    /** 从脚本结果中取 code 与附加值，缺省为系统错误 */
    public long[] parse(List<Long> result) {
        if (result == null || result.size() < 2) {
            return new long[]{-99L, 0L};
        }
        return new long[]{result.get(0), result.get(1)};
    }
}
