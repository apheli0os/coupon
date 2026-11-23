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
        transitionCandidates(candidates, now, false);
    }

    private void endExpired(LocalDateTime now) {
        List<CouponTemplate> expired = templateMapper.selectList(new LambdaQueryWrapper<CouponTemplate>()
                .in(CouponTemplate::getStatus, List.of(CouponEnums.TEMPLATE_DRAFT, CouponEnums.TEMPLATE_ACTIVE))
                .le(CouponTemplate::getEndTime, now));
        transitionCandidates(expired, now, true);
    }

    private void transitionCandidates(List<CouponTemplate> candidates, LocalDateTime now, boolean expire) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (CouponTemplate candidate : candidates) {
            if (!transitionSingleTemplate(candidate.getId(), now, expire)) {
                return;
            }
        }
    }

    private boolean transitionSingleTemplate(Long templateId, LocalDateTime now, boolean expire) {
        RLock templateLock = redissonClient.getLock(RedisKey.couponTemplateLock(templateId));
        boolean locked = false;
        try {
            locked = templateLock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                return true;
            }
            CouponTemplate latest = templateMapper.selectById(templateId);
            if (latest == null || latest.getStatus() == null) {
                return true;
            }
            int currentStatus = latest.getStatus();
            if (expire ? currentStatus == CouponEnums.TEMPLATE_ENDED
                    : currentStatus != CouponEnums.TEMPLATE_DRAFT) {
                return true;
            }
            String statusKey = RedisKey.couponStatus(latest.getId());
            if (currentStatus == CouponEnums.TEMPLATE_DRAFT
                    && !RedisKey.STATUS_PREHEATED.equals(stringRedisTemplate.opsForValue().get(statusKey))) {
                return true;
            }
            int targetStatus = expire ? CouponEnums.TEMPLATE_ENDED : CouponEnums.TEMPLATE_ACTIVE;
            String redisStatus = expire ? RedisKey.STATUS_ENDED : RedisKey.STATUS_ACTIVE;
            if (templateMapper.updateStatusIfMatch(latest.getId(), currentStatus, targetStatus, now) > 0) {
                Duration ttl = RedisKey.activityKeyTtl(now, latest.getEndTime());
                stringRedisTemplate.opsForValue().set(statusKey, redisStatus, ttl);
                multiLevelCache.invalidate(RedisKey.couponTemplateCache(latest.getId()));
                log.info(expire ? "优惠券模板到期自动结束 templateId={}" : "预发布优惠券模板到点自动激活 templateId={}",
                        latest.getId());
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (locked && templateLock.isHeldByCurrentThread()) {
                templateLock.unlock();
            }
        }
    }
}
