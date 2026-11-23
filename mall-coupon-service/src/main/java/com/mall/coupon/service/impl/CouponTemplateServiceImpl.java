package com.mall.coupon.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.PageResult;
import com.mall.common.ResultCode;
import com.mall.common.cache.MultiLevelCache;
import com.mall.common.constants.RedisKey;
import com.mall.common.exception.BizException;
import com.mall.common.util.IdGenerator;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.coupon.domain.dto.CouponTemplateCreateDTO;
import com.mall.coupon.domain.dto.CouponTemplatePageQueryDTO;
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
import org.springframework.util.StringUtils;

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
    private final LuaScriptExecutor luaExecutor;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CouponTemplateCreateDTO dto) {
        validateCreateDto(dto);
        int target = dto.getTarget() == null ? CouponEnums.TARGET_ALL : dto.getTarget();
        CouponTemplate t = new CouponTemplate();
        t.setId(idGenerator.nextId());
        t.setName(dto.getName());
        t.setType(dto.getType());
        t.setDiscountValue(dto.getDiscountValue());
        t.setMaxDiscountAmount(dto.getMaxDiscountAmount());
        t.setThreshold(dto.getThreshold() == null ? BigDecimal.ZERO : dto.getThreshold());
        t.setTarget(target);
        t.setProductCode(target == CouponEnums.TARGET_PRODUCT ? dto.getProductCode().trim() : null);
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
        if (dto.getType() == CouponEnums.TYPE_DISCOUNT) {
            if (dto.getDiscountValue().compareTo(BigDecimal.ONE) >= 0) {
                throw new BizException(ResultCode.PARAM_ERROR, "折扣券折扣率必须在 (0, 1) 之间");
            }
            if (dto.getMaxDiscountAmount() == null
                    || dto.getMaxDiscountAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException(ResultCode.PARAM_ERROR, "折扣券必须设置大于 0 的封顶优惠金额");
            }
        } else if (dto.getMaxDiscountAmount() != null
                && dto.getMaxDiscountAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "封顶优惠金额必须大于 0");
        }
        if (dto.getThreshold() != null && dto.getThreshold().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "使用门槛不能为负");
        }
        int target = dto.getTarget() == null ? CouponEnums.TARGET_ALL : dto.getTarget();
        if (target != CouponEnums.TARGET_ALL && target != CouponEnums.TARGET_PRODUCT) {
            throw new BizException(ResultCode.PARAM_ERROR, "适用范围必须为 0(全场) 或 1(指定商品)");
        }
        if (target == CouponEnums.TARGET_PRODUCT && !StringUtils.hasText(dto.getProductCode())) {
            throw new BizException(ResultCode.PARAM_ERROR, "指定商品券必须填写商品编码");
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
                if (Integer.valueOf(CouponEnums.TEMPLATE_ENDED).equals(t.getStatus())) {
                    throw new BizException(ResultCode.COUPON_ENDED, "活动已终止，无法重新发布");
                }
                if (now.isAfter(t.getEndTime())) {
                    throw new BizException(ResultCode.PARAM_ERROR, "活动已结束，无法发布");
                }
                int prevStatus = t.getStatus() == null ? CouponEnums.TEMPLATE_DRAFT : t.getStatus();
                int targetStatus = now.isBefore(t.getStartTime())
                        ? CouponEnums.TEMPLATE_DRAFT : CouponEnums.TEMPLATE_ACTIVE;
                if (prevStatus != targetStatus) {
                    templateMapper.updateStatusIfMatch(templateId, prevStatus, targetStatus, now);
                    t.setStatus(targetStatus);
                    t.setUpdateTime(now);
                }
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

    @Override
    public void increaseStock(Long templateId, int delta) {
        if (delta <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "增发数量必须大于 0");
        }
        RLock lock = redissonClient.getLock(RedisKey.couponTemplateLock(templateId));
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAILED);
            }
            LocalDateTime now = LocalDateTime.now();
            transactionTemplate.executeWithoutResult(txStatus -> {
                CouponTemplate t = templateMapper.selectById(templateId);
                if (t == null) {
                    throw new BizException(ResultCode.COUPON_TEMPLATE_NOT_FOUND);
                }
                if (Integer.valueOf(CouponEnums.TEMPLATE_ENDED).equals(t.getStatus())
                        || !t.getEndTime().isAfter(now)) {
                    throw new BizException(ResultCode.COUPON_ENDED, "活动已结束，无法增发库存");
                }
                int updated = templateMapper.increaseTotal(templateId, delta);
                if (updated == 0) {
                    throw new BizException(ResultCode.COUPON_ENDED, "活动已结束，无法增发库存");
                }
            });
            luaExecutor.execute(
                    LuaScriptExecutor.SCRIPT_COUPON_STOCK_INCR_IF_PRESENT,
                    List.of(RedisKey.couponStock(templateId)),
                    delta);
            multiLevelCache.invalidate(RedisKey.couponTemplateCache(templateId));
            log.info("优惠券模板动态增发库存成功 templateId={} delta={}", templateId, delta);
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
    public void terminate(Long templateId) {
        RLock lock = redissonClient.getLock(RedisKey.couponTemplateLock(templateId));
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAILED);
            }
            LocalDateTime now = LocalDateTime.now();
            CouponTemplate template = transactionTemplate.execute(txStatus -> {
                CouponTemplate t = templateMapper.selectById(templateId);
                if (t == null) {
                    throw new BizException(ResultCode.COUPON_TEMPLATE_NOT_FOUND);
                }
                if (!Integer.valueOf(CouponEnums.TEMPLATE_ENDED).equals(t.getStatus())) {
                    templateMapper.terminate(templateId);
                }
                return t;
            });
            if (template != null) {
                Duration ttl = RedisKey.activityKeyTtl(now, template.getEndTime());
                stringRedisTemplate.opsForValue().set(
                        RedisKey.couponStatus(templateId), RedisKey.STATUS_ENDED, ttl);
                multiLevelCache.invalidate(RedisKey.couponTemplateCache(templateId));
                log.info("优惠券模板已提前终止 templateId={}", templateId);
            }
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
    public PageResult<CouponTemplate> pageQuery(CouponTemplatePageQueryDTO query) {
        CouponTemplatePageQueryDTO effective = query == null ? new CouponTemplatePageQueryDTO() : query;
        long pageNum = Math.max(1L, effective.getPageNum());
        long pageSize = Math.min(100L, Math.max(1L, effective.getPageSize()));
        LambdaQueryWrapper<CouponTemplate> wrapper = new LambdaQueryWrapper<CouponTemplate>()
                .like(StringUtils.hasText(effective.getName()), CouponTemplate::getName,
                        effective.getName() == null ? null : effective.getName().trim())
                .eq(effective.getType() != null, CouponTemplate::getType, effective.getType())
                .eq(effective.getStatus() != null, CouponTemplate::getStatus, effective.getStatus())
                .eq(effective.getTarget() != null, CouponTemplate::getTarget, effective.getTarget())
                .eq(StringUtils.hasText(effective.getProductCode()), CouponTemplate::getProductCode,
                        effective.getProductCode() == null ? null : effective.getProductCode().trim())
                .orderByDesc(CouponTemplate::getCreateTime);
        Page<CouponTemplate> page = templateMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        if (page == null) {
            return PageResult.of(pageNum, pageSize, 0L, List.of());
        }
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    private record PublishContext(CouponTemplate template, int prevStatus, int targetStatus) {
    }
}
