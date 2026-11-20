package com.mall.coupon.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.ResultCode;
import com.mall.common.cache.MultiLevelCache;
import com.mall.common.constants.RedisKey;
import com.mall.common.exception.BizException;
import com.mall.common.util.IdGenerator;
import com.mall.coupon.domain.dto.CouponTemplateCreateDTO;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.domain.enums.CouponEnums;
import com.mall.coupon.mapper.CouponTemplateMapper;
import com.mall.coupon.service.CouponTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponTemplateServiceImpl implements CouponTemplateService {

    private final CouponTemplateMapper templateMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final IdGenerator idGenerator;
    private final MultiLevelCache multiLevelCache;
    private final TransactionTemplate transactionTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CouponTemplateCreateDTO dto) {
        validateCreateDto(dto);
        CouponTemplate t = new CouponTemplate();
        t.setId(idGenerator.nextId());
        t.setName(dto.getName());
        t.setType(dto.getType());
        t.setDiscountValue(dto.getDiscountValue());
        t.setThreshold(dto.getThreshold() == null ? BigDecimal.ZERO : dto.getThreshold());
        t.setTotal(dto.getTotal());
        t.setReceived(0);
        t.setPerUserLimit(dto.getPerUserLimit());
        t.setValidDays(dto.getValidDays());
        t.setStartTime(dto.getStartTime());
        t.setEndTime(dto.getEndTime());
        t.setStatus(CouponEnums.TEMPLATE_DRAFT);
        t.setCreateTime(LocalDateTime.now());
        t.setUpdateTime(t.getCreateTime());
        templateMapper.insert(t);
        return t.getId();
    }

    private void validateCreateDto(CouponTemplateCreateDTO dto) {
        if (!dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new BizException(ResultCode.PARAM_ERROR, "结束时间必须晚于开始时间");
        }
        if (dto.getType() != CouponEnums.TYPE_FIXED && dto.getType() != CouponEnums.TYPE_DISCOUNT) {
            throw new BizException(ResultCode.PARAM_ERROR, "券类型必须为 1(满减) 或 2(折扣)");
        }
        if (dto.getDiscountValue() == null || dto.getDiscountValue().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "优惠值必须大于 0");
        }
        if (dto.getType() == CouponEnums.TYPE_DISCOUNT && dto.getDiscountValue().compareTo(BigDecimal.ONE) > 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "折扣券折扣率必须在 (0, 1] 之间");
        }
        if (dto.getThreshold() != null && dto.getThreshold().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "使用门槛不能为负");
        }
    }

    /**
     * 分布式锁包裹事务（锁外层、TransactionTemplate 内层），保证 DB 事务提交后再释放锁；
     * 且对已处于 ACTIVE 且 Redis 已预热的模板执行幂等刷新，严禁清空已领 Hash 或用异步滞后 DB received 覆盖库存。
     */
    @Override
    public void publish(Long templateId) {
        RLock lock = redissonClient.getLock(RedisKey.couponTemplateLock(templateId));
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAILED);
            }
            LocalDateTime now = LocalDateTime.now();
            PublishContext ctx = transactionTemplate.execute(txStatus -> {
                CouponTemplate t = templateMapper.selectById(templateId);
                if (t == null) {
                    throw new BizException(ResultCode.COUPON_TEMPLATE_NOT_FOUND);
                }
                if (now.isAfter(t.getEndTime())) {
                    throw new BizException(ResultCode.PARAM_ERROR, "活动已结束，无法发布");
                }
                int prevStatus = t.getStatus() == null ? CouponEnums.TEMPLATE_DRAFT : t.getStatus();
                int targetStatus = now.isBefore(t.getStartTime())
                        ? CouponEnums.TEMPLATE_DRAFT : CouponEnums.TEMPLATE_ACTIVE;
                t.setStatus(targetStatus);
                t.setUpdateTime(now);
                templateMapper.updateById(t);
                return new PublishContext(t, prevStatus, targetStatus);
            });
            if (ctx == null) {
                return;
            }
            CouponTemplate t = ctx.template();
            Duration ttl = RedisKey.activityKeyTtl(now, t.getEndTime());
            String stockKey = RedisKey.couponStock(templateId);
            String statusKey = RedisKey.couponStatus(templateId);
            String claimHashKey = RedisKey.couponClaimHash(templateId);
            String redisStatus = ctx.targetStatus() == CouponEnums.TEMPLATE_ACTIVE
                    ? RedisKey.STATUS_ACTIVE : RedisKey.STATUS_PREHEATED;

            boolean alreadyPreheated = Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey));
            if (ctx.prevStatus() == CouponEnums.TEMPLATE_ACTIVE && alreadyPreheated) {
                // 进行中活动重复发布：绝不覆盖运行中库存、绝不删除用户限领 Hash，仅刷新状态与 TTL
                stringRedisTemplate.opsForValue().set(statusKey, redisStatus, ttl);
                stringRedisTemplate.expire(stockKey, ttl);
                stringRedisTemplate.expire(claimHashKey, ttl);
                multiLevelCache.invalidate(RedisKey.couponTemplateCache(templateId));
                log.info("优惠券模板已处于活跃态，幂等刷新状态与TTL templateId={} status={}", templateId, ctx.targetStatus());
                return;
            }

            long remain = Math.max(0, t.getTotal() - (t.getReceived() == null ? 0 : t.getReceived()));
            stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(remain), ttl);
            stringRedisTemplate.opsForValue().set(statusKey, redisStatus, ttl);
            if (ctx.prevStatus() == CouponEnums.TEMPLATE_DRAFT && (t.getReceived() == null || t.getReceived() == 0)) {
                stringRedisTemplate.delete(claimHashKey);
            } else {
                stringRedisTemplate.expire(claimHashKey, ttl);
            }
            multiLevelCache.invalidate(RedisKey.couponTemplateCache(templateId));
            log.info("优惠券模板发布成功 templateId={} remain={} status={}", templateId, remain, ctx.targetStatus());
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
    public CouponTemplate getDetail(Long templateId) {
        return multiLevelCache.get(RedisKey.couponTemplateCache(templateId), CouponTemplate.class,
                () -> templateMapper.selectById(templateId),
                Duration.ofSeconds(RedisKey.TEMPLATE_CACHE_TTL_SECONDS));
    }

    @Override
    public List<CouponTemplate> listAll() {
        return templateMapper.selectList(new LambdaQueryWrapper<CouponTemplate>()
                .orderByDesc(CouponTemplate::getCreateTime));
    }

    private record PublishContext(CouponTemplate template, int prevStatus, int targetStatus) {
    }
}
