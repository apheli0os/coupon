package com.mall.common.util;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lua 脚本执行器。脚本放在 mall-common 的 classpath，各服务共享同一份定义，
 * 避免「库存判断 + 扣减」出现非原子的竞态导致超卖/超领。
 */
@Component
@RequiredArgsConstructor
public class LuaScriptExecutor {

    public static final String SCRIPT_COUPON_CLAIM = "lua/coupon_claim.lua";
    public static final String SCRIPT_COUPON_ROLLBACK = "lua/coupon_rollback.lua";
    public static final String SCRIPT_SECKILL = "lua/seckill.lua";
    public static final String SCRIPT_SECKILL_ROLLBACK = "lua/seckill_rollback.lua";

    private final StringRedisTemplate stringRedisTemplate;

    /** 脚本约定返回整型数组，统一转成 List<Long> */
    public List<Long> execute(String scriptPath, List<String> keys, Object... args) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(scriptPath)));
        script.setResultType(List.class);
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

    /** 从脚本结果中取 code 与剩余库存，缺省为系统错误 */
    public long[] parse(List<Long> result) {
        if (result == null || result.size() < 2) {
            return new long[]{-99L, 0L};
        }
        return new long[]{result.get(0), result.get(1)};
    }
}
