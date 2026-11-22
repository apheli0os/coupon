package com.mall.coupon.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.constants.RedisKey;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.domain.enums.CouponEnums;
import com.mall.coupon.mapper.CouponTemplateMapper;
import com.mall.coupon.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponStockReconcileJob {

    private final CouponTemplateMapper templateMapper;
    private final UserCouponMapper userCouponMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

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
                reconcileSingle(template, now);
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
        if (hasInFlightTokens(templateId)) {
            log.debug("优惠券模板存在在途落库凭证，跳过本轮对账 templateId={}", templateId);
            return;
        }
        long dbCount = userCouponMapper.countByTemplate(templateId);
        int total = template.getTotal() == null ? 0 : template.getTotal();
        long expectedStock = Math.max(0L, total - dbCount);

        String stockKey = RedisKey.couponStock(templateId);
        String currentRaw = stringRedisTemplate.opsForValue().get(stockKey);
        long currentStock = currentRaw == null ? -1L : Long.parseLong(currentRaw);

        if (currentStock == expectedStock) {
            return;
        }

        Duration ttl = RedisKey.activityKeyTtl(now, template.getEndTime());
        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(expectedStock), ttl);

        String claimHashKey = RedisKey.couponClaimHash(templateId);
        stringRedisTemplate.delete(claimHashKey);
        List<Map<String, Object>> grouped = userCouponMapper.countGroupedByUser(templateId);
        if (grouped != null && !grouped.isEmpty()) {
            Map<String, String> rebuilt = new HashMap<>();
            for (Map<String, Object> row : grouped) {
                Object uid = row.get("userId");
                Object cnt = row.get("cnt");
                if (uid != null && cnt != null) {
                    rebuilt.put(String.valueOf(uid), String.valueOf(cnt));
                }
            }
            if (!rebuilt.isEmpty()) {
                stringRedisTemplate.opsForHash().putAll(claimHashKey, rebuilt);
                stringRedisTemplate.expire(claimHashKey, ttl);
            }
        }
        log.warn("[RECONCILE] 修复优惠券 Redis-DB 库存偏差 templateId={} redisStock={} expectedStock={} dbCount={}",
                templateId, currentStock, expectedStock, dbCount);
    }

    boolean hasInFlightTokens(Long templateId) {
        String pattern = "coupon:{" + templateId + "}:token:*";
        try (Cursor<String> cursor = stringRedisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(10).build())) {
            return cursor != null && cursor.hasNext();
        }
    }
}
