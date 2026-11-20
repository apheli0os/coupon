package com.mall.common.lua;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lua 脚本是防超发的核心，任何误改都可能导致超卖。
 * 这里校验脚本的关键指令结构，防止删掉原子扣减/重复购买判断等致命修改。
 */
class LuaScriptIntegrityTest {

    private String load(String name) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("lua/" + name)) {
            assertNotNull(in, "脚本不存在: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void seckillLua_shouldKeepAtomicGuards() throws IOException {
        String script = load("seckill.lua");
        assertTrue(script.contains("redis.call('GET'"), "必须读取活动状态");
        assertTrue(script.contains("SISMEMBER"), "必须有重复购买判断");
        assertTrue(script.contains("DECR"), "必须有原子扣减");
        assertTrue(script.contains("SADD"), "必须记录已购用户");
        assertTrue(script.contains("%"), "必须有分桶轮询取模");
        assertTrue(script.contains("return {-3, -1}"), "必须保留售罄返回码");
    }

    @Test
    void couponClaimLua_shouldKeepAtomicGuards() throws IOException {
        String script = load("coupon_claim.lua");
        assertTrue(script.contains("DECR"), "必须有原子扣减库存");
        assertTrue(script.contains("SADD") || script.contains("HINCRBY"), "必须记录用户领取");
    }

    @Test
    void rollbackScripts_shouldRestoreStockIdempotently() throws IOException {
        for (String name : new String[]{"coupon_rollback.lua", "seckill_rollback.lua", "seckill_cancel_rollback.lua"}) {
            String script = load(name);
            assertTrue(script.contains("INCR") || script.contains("INCRBY"),
                    name + " 必须包含库存归还指令");
        }
        String seckillRb = load("seckill_rollback.lua");
        assertTrue(seckillRb.contains("if removed == 1"), "seckill_rollback 必须仅在 SREM==1 时归还库存");
        String cancelRb = load("seckill_cancel_rollback.lua");
        assertTrue(cancelRb.contains("'NX'"), "seckill_cancel_rollback 必须使用 NX 幂等键防重买误撤");
        String tokenClaim = load("token_claim.lua");
        assertTrue(tokenClaim.contains("PROCESSING:"), "token_claim 必须原子切换至 PROCESSING 态");
    }
}
