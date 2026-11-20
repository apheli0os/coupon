package com.mall.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class LuaScriptExecutorTest {

    @Test
    void getOrLoadScript_shouldCacheScriptInstancePerPath() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        LuaScriptExecutor executor = new LuaScriptExecutor(redis);

        @SuppressWarnings("rawtypes")
        DefaultRedisScript<List> first = executor.getOrLoadScript(LuaScriptExecutor.SCRIPT_SECKILL);
        @SuppressWarnings("rawtypes")
        DefaultRedisScript<List> second = executor.getOrLoadScript(LuaScriptExecutor.SCRIPT_SECKILL);

        assertSame(first, second, "同一路径的 Lua 脚本对象应被缓存复用，避免每次重读资源和重算 SHA1");
    }
}
