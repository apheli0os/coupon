package com.mall.coupon.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.cache.MultiLevelCache;
import com.mall.common.constants.RedisKey;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.domain.enums.CouponEnums;
import com.mall.coupon.mapper.CouponTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 定时激活已预发布的未来券模板，并自动结束已过期模板。
 * 仅激活已在 Redis 中预热过（status == "0"）的模板，避免把未发布的草稿误上线。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponTemplateActivationJob {

    private final CouponTemplateMapper templateMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final MultiLevelCache multiLevelCache;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelayString = "${mall.coupon.activate-scan-ms:10000}", initialDelay = 10000)
    public void syncTemplateStatus() {
        RLock lock = redissonClient.getLock(RedisKey.couponActivateJobLock());
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            activatePreheated(now);
            endExpired(now);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void activatePreheated(LocalDateTime now) {
        List<CouponTemplate> candidates = templateMapper.selectList(new LambdaQueryWrapper<CouponTemplate>()
                .eq(CouponTemplate::getStatus, CouponEnums.TEMPLATE_DRAFT)
                .le(CouponTemplate::getStartTime, now)
                .gt(CouponTemplate::getEndTime, now));
        for (CouponTemplate t : candidates) {
            String statusKey = RedisKey.couponStatus(t.getId());
            if (!RedisKey.STATUS_PREHEATED.equals(stringRedisTemplate.opsForValue().get(statusKey))) {
                continue;
            }
            t.setStatus(CouponEnums.TEMPLATE_ACTIVE);
            t.setUpdateTime(now);
            templateMapper.updateById(t);
            Duration ttl = RedisKey.activityKeyTtl(now, t.getEndTime());
            stringRedisTemplate.opsForValue().set(statusKey, RedisKey.STATUS_ACTIVE, ttl);
            multiLevelCache.invalidate(RedisKey.couponTemplateCache(t.getId()));
            log.info("预发布优惠券模板到点自动激活 templateId={}", t.getId());
        }
    }

    private void endExpired(LocalDateTime now) {
        List<CouponTemplate> expired = templateMapper.selectList(new LambdaQueryWrapper<CouponTemplate>()
                .in(CouponTemplate::getStatus, List.of(CouponEnums.TEMPLATE_DRAFT, CouponEnums.TEMPLATE_ACTIVE))
                .le(CouponTemplate::getEndTime, now));
        for (CouponTemplate t : expired) {
            String statusKey = RedisKey.couponStatus(t.getId());
            if (Integer.valueOf(CouponEnums.TEMPLATE_DRAFT).equals(t.getStatus())
                    && !RedisKey.STATUS_PREHEATED.equals(stringRedisTemplate.opsForValue().get(statusKey))) {
                continue;
            }
            t.setStatus(CouponEnums.TEMPLATE_ENDED);
            t.setUpdateTime(now);
            templateMapper.updateById(t);
            Duration ttl = RedisKey.activityKeyTtl(now, t.getEndTime());
            stringRedisTemplate.opsForValue().set(statusKey, RedisKey.STATUS_ENDED, ttl);
            multiLevelCache.invalidate(RedisKey.couponTemplateCache(t.getId()));
            log.info("优惠券模板到期自动结束 templateId={}", t.getId());
        }
    }
}
