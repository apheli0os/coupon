package com.mall.coupon.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.constants.RedisKey;
import com.mall.common.metrics.BusinessMetrics;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.domain.enums.CouponEnums;
import com.mall.coupon.mapper.CouponTemplateMapper;
import com.mall.coupon.mapper.UserCouponMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class CouponStockReconcileJob {

    private static final Duration PENDING_TTL = Duration.ofMinutes(15);
    private static final String MISSING_MARKER = "__MISSING__";

    private final CouponTemplateMapper templateMapper;
    private final UserCouponMapper userCouponMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final LuaScriptExecutor luaExecutor;
    private final BusinessMetrics businessMetrics;
    private final long maxAutoFixDiff;

    @Autowired
    public CouponStockReconcileJob(
            CouponTemplateMapper templateMapper,
            UserCouponMapper userCouponMapper,
            StringRedisTemplate stringRedisTemplate,
            RedissonClient redissonClient,
            LuaScriptExecutor luaExecutor,
            BusinessMetrics businessMetrics,
            @Value("${mall.coupon.reconcile-max-autofix-diff:50}") long maxAutoFixDiff) {
        this.templateMapper = templateMapper;
        this.userCouponMapper = userCouponMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.luaExecutor = luaExecutor;
        this.businessMetrics = businessMetrics;
        this.maxAutoFixDiff = Math.max(0L, maxAutoFixDiff);
    }

    @Scheduled(fixedDelayString = "${mall.coupon.reconcile-scan-ms:300000}", initialDelay = 60000)
    public void reconcileActiveTemplates() {
        RLock lock = redissonClient.getLock(RedisKey.couponReconcileJobLock());
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            List<CouponTemplate> activeTemplates = templateMapper.selectList(
                    new LambdaQueryWrapper<CouponTemplate>()
                            .eq(CouponTemplate::getStatus, CouponEnums.TEMPLATE_ACTIVE));
            for (CouponTemplate template : activeTemplates) {
                try {
                    reconcileSingle(template, now);
                } catch (Exception e) {
                    log.error("优惠券模板对账异常，跳过本模板 templateId={}", template.getId(), e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    void reconcileSingle(CouponTemplate template, LocalDateTime now) {
        Long templateId = template.getId();
        RLock templateLock = redissonClient.getLock(RedisKey.couponTemplateLock(templateId));
        boolean locked = false;
        try {
            locked = templateLock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            CouponTemplate latest = templateMapper.selectById(templateId);
            if (latest == null || !Integer.valueOf(CouponEnums.TEMPLATE_ACTIVE).equals(latest.getStatus())) {
                return;
            }
            if (hasInFlightTokens(templateId)) {
                log.debug("优惠券模板存在在途落库凭证，跳过本轮对账 templateId={}", templateId);
                return;
            }
            long dbCount = userCouponMapper.countByTemplate(templateId);
            int total = latest.getTotal() == null ? 0 : latest.getTotal();
            long expectedStock = Math.max(0L, total - dbCount);

            String stockKey = RedisKey.couponStock(templateId);
            String currentRaw = stringRedisTemplate.opsForValue().get(stockKey);
            String pendingKey = RedisKey.couponReconcilePending(templateId);
            Long parsedStock = parseObservedStock(currentRaw);
            if (currentRaw != null && parsedStock == null) {
                stringRedisTemplate.delete(pendingKey);
                log.warn("优惠券 Redis 库存值非法，跳过本轮对账 templateId={} raw={}", templateId, currentRaw);
                return;
            }
            String observedMarker = currentRaw == null ? MISSING_MARKER : currentRaw.trim();
            long currentStock = parsedStock == null ? -1L : parsedStock;

            if (currentStock == expectedStock) {
                stringRedisTemplate.delete(pendingKey);
                return;
            }

            long diff = expectedStock - currentStock;
            String direction = diff > 0 ? "UNDER" : "OVER";
            String pending = stringRedisTemplate.opsForValue().get(pendingKey);
            if (!direction.equals(pending)) {
                stringRedisTemplate.opsForValue().set(pendingKey, direction, PENDING_TTL);
                log.info("优惠券库存首轮发现偏差，进入防抖观察 templateId={} redisStock={} expectedStock={} dir={}",
                        templateId, currentStock, expectedStock, direction);
                return;
            }

            if (Math.abs(diff) > maxAutoFixDiff) {
                stringRedisTemplate.delete(pendingKey);
                businessMetrics.recordCouponReconcileAlert();
                log.error("[RECONCILE-ALERT] 优惠券库存偏差超过自动修复阈值，拒绝覆盖库存 "
                                + "templateId={} redisStock={} expectedStock={} diff={} threshold={}",
                        templateId, currentStock, expectedStock, diff, maxAutoFixDiff);
                return;
            }

            if (hasInFlightTokens(templateId)) {
                log.debug("优惠券模板提交前检测到在途落库凭证，放弃本轮覆盖 templateId={}", templateId);
                return;
            }

            Duration ttl = RedisKey.activityKeyTtl(now, latest.getEndTime());
            long ttlSeconds = Math.max(1L, ttl.toSeconds());
            List<String> argv = buildReconcileArgs(
                    observedMarker, expectedStock, ttlSeconds, userCouponMapper.countGroupedByUser(templateId));
            long[] res = luaExecutor.parse(luaExecutor.execute(
                    LuaScriptExecutor.SCRIPT_COUPON_RECONCILE_STOCK,
                    List.of(stockKey, RedisKey.couponClaimHash(templateId), pendingKey),
                    argv.toArray()));
            if (res[0] == 1L) {
                businessMetrics.recordCouponReconcileAutoFix();
                log.warn("[RECONCILE] 修复优惠券 Redis-DB 库存偏差 templateId={} redisStock={} expectedStock={} dbCount={}",
                        templateId, currentStock, expectedStock, dbCount);
            } else {
                log.info("优惠券库存 CAS 跳过（并发领券已改动库存） templateId={} observed={}", templateId, observedMarker);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked && templateLock.isHeldByCurrentThread()) {
                templateLock.unlock();
            }
        }
    }

    private Long parseObservedStock(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<String> buildReconcileArgs(
            String observedMarker,
            long expectedStock,
            long ttlSeconds,
            List<Map<String, Object>> grouped) {
        List<String> argv = new ArrayList<>();
        argv.add(observedMarker);
        argv.add(String.valueOf(expectedStock));
        argv.add(String.valueOf(ttlSeconds));
        if (grouped != null) {
            for (Map<String, Object> row : grouped) {
                Object uid = row.get("userId");
                Object cnt = row.get("cnt");
                if (uid != null && cnt != null) {
                    argv.add(String.valueOf(uid));
                    argv.add(String.valueOf(cnt));
                }
            }
        }
        return argv;
    }

    boolean hasInFlightTokens(Long templateId) {
        String pattern = "coupon:{" + templateId + "}:token:*";
        try (Cursor<String> cursor = stringRedisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(10).build())) {
            return cursor != null && cursor.hasNext();
        }
    }
}
