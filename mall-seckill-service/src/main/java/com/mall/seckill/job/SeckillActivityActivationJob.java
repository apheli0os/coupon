package com.mall.seckill.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.cache.MultiLevelCache;
import com.mall.common.constants.RedisKey;
import com.mall.seckill.domain.entity.SeckillActivity;
import com.mall.seckill.mapper.SeckillActivityMapper;
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
 * 定时激活已预发布的未来秒杀活动，并自动结束已到期活动。
 * 仅激活 Redis 状态为 "0"（已预热）的活动，避免把未发布草稿误上线。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillActivityActivationJob {

    private final SeckillActivityMapper activityMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final MultiLevelCache multiLevelCache;
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelayString = "${mall.seckill.activate-scan-ms:10000}", initialDelay = 10000)
    public void syncActivityStatus() {
        RLock lock = redissonClient.getLock(RedisKey.seckillActivateJobLock());
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
        List<SeckillActivity> candidates = activityMapper.selectList(new LambdaQueryWrapper<SeckillActivity>()
                .eq(SeckillActivity::getStatus, 0)
                .le(SeckillActivity::getStartTime, now)
                .gt(SeckillActivity::getEndTime, now));
        for (SeckillActivity activity : candidates) {
            String statusKey = RedisKey.seckillStatus(activity.getId());
            if (!RedisKey.STATUS_PREHEATED.equals(stringRedisTemplate.opsForValue().get(statusKey))) {
                continue;
            }
            activity.setStatus(1);
            activity.setUpdateTime(now);
            activityMapper.updateById(activity);
            Duration ttl = RedisKey.activityKeyTtl(now, activity.getEndTime());
            stringRedisTemplate.opsForValue().set(statusKey, RedisKey.STATUS_ACTIVE, ttl);
            multiLevelCache.invalidate(RedisKey.seckillActivityCache(activity.getId()));
            log.info("预发布秒杀活动到点自动激活 activityId={}", activity.getId());
        }
    }

    private void endExpired(LocalDateTime now) {
        List<SeckillActivity> expired = activityMapper.selectList(new LambdaQueryWrapper<SeckillActivity>()
                .in(SeckillActivity::getStatus, List.of(0, 1))
                .le(SeckillActivity::getEndTime, now));
        for (SeckillActivity activity : expired) {
            String statusKey = RedisKey.seckillStatus(activity.getId());
            if (Integer.valueOf(0).equals(activity.getStatus())
                    && !RedisKey.STATUS_PREHEATED.equals(stringRedisTemplate.opsForValue().get(statusKey))) {
                continue;
            }
            activity.setStatus(2);
            activity.setUpdateTime(now);
            activityMapper.updateById(activity);
            Duration ttl = RedisKey.activityKeyTtl(now, activity.getEndTime());
            stringRedisTemplate.opsForValue().set(statusKey, RedisKey.STATUS_ENDED, ttl);
            multiLevelCache.invalidate(RedisKey.seckillActivityCache(activity.getId()));
            log.info("秒杀活动到期自动结束 activityId={}", activity.getId());
        }
    }
}
