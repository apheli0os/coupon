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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long activityId) {
        RLock lock = redissonClient.getLock(RedisKey.seckillActivityLock(activityId));
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAILED);
            }
            SeckillActivity activity = activityMapper.selectById(activityId);
            if (activity == null) {
                throw new BizException(ResultCode.SECKILL_ACTIVITY_NOT_FOUND);
            }
            LocalDateTime now = LocalDateTime.now();
            if (now.isAfter(activity.getEndTime())) {
                throw new BizException(ResultCode.PARAM_ERROR, "活动已结束，无法发布");
            }
            int status = now.isBefore(activity.getStartTime()) ? 0 : 1;
            activity.setStatus(status);
            activity.setUpdateTime(now);
            activityMapper.updateById(activity);

            long remain = Math.max(0, activity.getTotalStock() - activity.getSoldCount());
            // 库存均分到 N 个桶，余数分给前几个桶，总和严格等于 remain
            int buckets = RedisKey.SECKILL_STOCK_BUCKETS;
            long base = remain / buckets;
            int extra = (int) (remain % buckets);
            for (int i = 0; i < buckets; i++) {
                long bucketStock = base + (i < extra ? 1 : 0);
                stringRedisTemplate.opsForValue().set(
                        RedisKey.seckillStockBucket(activityId, i), String.valueOf(bucketStock));
            }
            // 兼容旧的总库存 key，供监控/查询使用
            stringRedisTemplate.opsForValue().set(RedisKey.seckillStock(activityId), String.valueOf(remain));
            stringRedisTemplate.opsForValue().set(RedisKey.seckillStatus(activityId),
                    status == 1 ? RedisKey.STATUS_ACTIVE : "0");
            stringRedisTemplate.delete(RedisKey.seckillUsers(activityId));
            multiLevelCache.invalidate(RedisKey.seckillActivityCache(activityId));
            log.info("秒杀活动发布成功 activityId={} remain={} status={}", activityId, remain, status);
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
}
