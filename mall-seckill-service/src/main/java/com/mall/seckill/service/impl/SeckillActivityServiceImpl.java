package com.mall.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.ResultCode;
import com.mall.common.cache.MultiLevelCache;
import com.mall.common.constants.RedisKey;
import com.mall.common.exception.BizException;
import com.mall.common.util.IdGenerator;
import com.mall.seckill.domain.dto.SeckillActivityCreateDTO;
import com.mall.seckill.domain.entity.SeckillActivity;
import com.mall.seckill.mapper.SeckillActivityMapper;
import com.mall.seckill.service.SeckillActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillActivityServiceImpl implements SeckillActivityService {

    private final SeckillActivityMapper activityMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final IdGenerator idGenerator;
    private final MultiLevelCache multiLevelCache;
    private final TransactionTemplate transactionTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(SeckillActivityCreateDTO dto) {
        if (!dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new BizException(ResultCode.PARAM_ERROR, "结束时间必须晚于开始时间");
        }
        SeckillActivity activity = new SeckillActivity();
        activity.setId(idGenerator.nextId());
        activity.setName(dto.getName());
        activity.setProductCode(dto.getProductCode());
        activity.setProductName(dto.getProductName());
        activity.setSeckillPrice(dto.getSeckillPrice());
        activity.setOriginalPrice(dto.getOriginalPrice() == null ? dto.getSeckillPrice() : dto.getOriginalPrice());
        activity.setTotalStock(dto.getTotalStock());
        activity.setSoldCount(0);
        activity.setStartTime(dto.getStartTime());
        activity.setEndTime(dto.getEndTime());
        activity.setStatus(0);
        activity.setCreateTime(LocalDateTime.now());
        activity.setUpdateTime(activity.getCreateTime());
        activityMapper.insert(activity);
        return activity.getId();
    }

    /**
     * 分布式锁包裹事务（锁在外、TransactionTemplate 在内），确保 DB 提交后再释放锁；
     * 对已处于进行中且 Redis 库存桶已预热的活动仅做幂等刷新，绝不重置分桶库存或清空已购用户 Set。
     */
    @Override
    public void publish(Long activityId) {
        RLock lock = redissonClient.getLock(RedisKey.seckillActivityLock(activityId));
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAILED);
            }
            LocalDateTime now = LocalDateTime.now();
            PublishContext ctx = transactionTemplate.execute(txStatus -> {
                SeckillActivity activity = activityMapper.selectById(activityId);
                if (activity == null) {
                    throw new BizException(ResultCode.SECKILL_ACTIVITY_NOT_FOUND);
                }
                if (now.isAfter(activity.getEndTime())) {
                    throw new BizException(ResultCode.PARAM_ERROR, "活动已结束，无法发布");
                }
                int prevStatus = activity.getStatus() == null ? 0 : activity.getStatus();
                int targetStatus = now.isBefore(activity.getStartTime()) ? 0 : 1;
                activity.setStatus(targetStatus);
                activity.setUpdateTime(now);
                activityMapper.updateById(activity);
                return new PublishContext(activity, prevStatus, targetStatus);
            });
            if (ctx == null) {
                return;
            }
            SeckillActivity activity = ctx.activity();
            Duration ttl = RedisKey.activityKeyTtl(now, activity.getEndTime());
            String firstBucketKey = RedisKey.seckillStockBucket(activityId, 0);
            String statusKey = RedisKey.seckillStatus(activityId);
            String usersKey = RedisKey.seckillUsers(activityId);
            String redisStatus = ctx.targetStatus() == 1 ? RedisKey.STATUS_ACTIVE : RedisKey.STATUS_PREHEATED;

            boolean alreadyPreheated = Boolean.TRUE.equals(stringRedisTemplate.hasKey(firstBucketKey));
            if (ctx.prevStatus() == 1 && alreadyPreheated) {
                // 进行中活动重复发布：严禁覆盖分桶库存与清空 seckillUsers Set
                stringRedisTemplate.opsForValue().set(statusKey, redisStatus, ttl);
                for (int i = 0; i < RedisKey.SECKILL_STOCK_BUCKETS; i++) {
                    stringRedisTemplate.expire(RedisKey.seckillStockBucket(activityId, i), ttl);
                }
                stringRedisTemplate.expire(RedisKey.seckillStock(activityId), ttl);
                stringRedisTemplate.expire(usersKey, ttl);
                multiLevelCache.invalidate(RedisKey.seckillActivityCache(activityId));
                log.info("秒杀活动已处于活跃态，幂等刷新状态与TTL activityId={} status={}", activityId, ctx.targetStatus());
                return;
            }

            long remain = Math.max(0, activity.getTotalStock() - (activity.getSoldCount() == null ? 0 : activity.getSoldCount()));
            // 库存均分到 N 个桶，余数分给前几个桶，总和严格等于 remain
            int buckets = RedisKey.SECKILL_STOCK_BUCKETS;
            long base = remain / buckets;
            int extra = (int) (remain % buckets);
            for (int i = 0; i < buckets; i++) {
                long bucketStock = base + (i < extra ? 1 : 0);
                stringRedisTemplate.opsForValue().set(
                        RedisKey.seckillStockBucket(activityId, i), String.valueOf(bucketStock), ttl);
            }
            // 兼容旧的总库存 key，供监控/查询使用
            stringRedisTemplate.opsForValue().set(RedisKey.seckillStock(activityId), String.valueOf(remain), ttl);
            stringRedisTemplate.opsForValue().set(statusKey, redisStatus, ttl);
            if (ctx.prevStatus() == 0 && (activity.getSoldCount() == null || activity.getSoldCount() == 0)) {
                stringRedisTemplate.delete(usersKey);
            } else {
                stringRedisTemplate.expire(usersKey, ttl);
            }
            multiLevelCache.invalidate(RedisKey.seckillActivityCache(activityId));
            log.info("秒杀活动发布成功 activityId={} remain={} status={}", activityId, remain, ctx.targetStatus());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.LOCK_ACQUIRE_FAILED);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public SeckillActivity getDetail(Long activityId) {
        return multiLevelCache.get(RedisKey.seckillActivityCache(activityId), SeckillActivity.class,
                () -> activityMapper.selectById(activityId),
                Duration.ofSeconds(RedisKey.TEMPLATE_CACHE_TTL_SECONDS));
    }

    @Override
    public List<SeckillActivity> listAll() {
        return activityMapper.selectList(new LambdaQueryWrapper<SeckillActivity>()
                .orderByDesc(SeckillActivity::getCreateTime));
    }

    private record PublishContext(SeckillActivity activity, int prevStatus, int targetStatus) {
    }
}
