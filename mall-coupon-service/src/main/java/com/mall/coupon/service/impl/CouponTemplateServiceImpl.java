package com.mall.coupon.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.cache.MultiLevelCache;
import com.mall.common.constants.RedisKey;
import com.mall.common.exception.BizException;
import com.mall.common.util.IdGenerator;
import com.mall.common.ResultCode;
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CouponTemplateCreateDTO dto) {
        if (!dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new BizException(ResultCode.PARAM_ERROR, "结束时间必须晚于开始时间");
        }
        CouponTemplate t = new CouponTemplate();
        t.setId(idGenerator.nextId());
        t.setName(dto.getName());
        t.setType(dto.getType());
        t.setDiscountValue(dto.getDiscountValue());
        t.setThreshold(dto.getThreshold() == null ? java.math.BigDecimal.ZERO : dto.getThreshold());
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long templateId) {
        RLock lock = redissonClient.getLock(RedisKey.couponTemplateLock(templateId));
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAILED);
            }
            CouponTemplate t = templateMapper.selectById(templateId);
            if (t == null) {
                throw new BizException(ResultCode.COUPON_TEMPLATE_NOT_FOUND);
            }
            LocalDateTime now = LocalDateTime.now();
            int status = CouponEnums.TEMPLATE_DRAFT;
            if (now.isAfter(t.getEndTime())) {
                status = CouponEnums.TEMPLATE_ENDED;
            } else if (!now.isBefore(t.getStartTime())) {
                status = CouponEnums.TEMPLATE_ACTIVE;
            }
            t.setStatus(status);
            t.setUpdateTime(now);
            templateMapper.updateById(t);

            // 库存预热：后续所有领券请求只打 Redis，不查 DB 库存
            long remain = Math.max(0, t.getTotal() - t.getReceived());
            stringRedisTemplate.opsForValue().set(RedisKey.couponStock(templateId), String.valueOf(remain));
            stringRedisTemplate.opsForValue().set(RedisKey.couponStatus(templateId),
                    status == CouponEnums.TEMPLATE_ACTIVE ? RedisKey.STATUS_ACTIVE : "0");
            stringRedisTemplate.delete(RedisKey.couponClaimHash(templateId));
            multiLevelCache.invalidate(RedisKey.couponTemplateCache(templateId));
            log.info("优惠券模板发布成功 templateId={} remain={} status={}", templateId, remain, status);
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
}
