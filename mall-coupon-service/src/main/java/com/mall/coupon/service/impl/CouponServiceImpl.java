package com.mall.coupon.service.impl;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.PageResult;
import com.mall.common.R;
import com.mall.common.ResultCode;
import com.mall.common.constants.CommonConstant;
import com.mall.common.constants.RedisKey;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BizException;
import com.mall.common.message.CouponClaimMessage;
import com.mall.common.metrics.BusinessMetrics;
import com.mall.common.pricing.CouponPricing;
import com.mall.common.pricing.CouponPricingResult;
import com.mall.common.pricing.CouponPricingRule;
import com.mall.common.util.IdGenerator;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.coupon.cache.UserCouponCacheService;
import com.mall.coupon.domain.dto.CouponQuoteRequestDTO;
import com.mall.coupon.domain.dto.CouponQuoteResponseDTO;
import com.mall.coupon.domain.dto.CouponUseDTO;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.domain.entity.UserCoupon;
import com.mall.coupon.domain.enums.CouponEnums;
import com.mall.coupon.domain.vo.CouponQuoteItemVO;
import com.mall.coupon.feign.OrderFeignClient;
import com.mall.coupon.mapper.UserCouponMapper;
import com.mall.coupon.service.CouponService;
import com.mall.coupon.service.CouponTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    private final CouponTemplateService templateService;
    private final UserCouponMapper userCouponMapper;
    private final LuaScriptExecutor luaExecutor;
    private final StringRedisTemplate stringRedisTemplate;
    private final IdGenerator idGenerator;
    private final OrderFeignClient orderFeignClient;
    private final BusinessMetrics metrics;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final UserCouponCacheService userCouponCacheService;

    /**
     * 领券：Redis Lua 原子预扣 + Kafka 异步落库。
     *
     * <p>请求线程只做「读缓存 → Lua 预扣 → 发消息」，不再同步访问 MySQL；
     * DB 查重与 incrReceived 全部下移到 {@code CouponClaimConsumer} 批量处理。
     * Kafka 发送失败立即回滚 Redis，保证「有预扣必有消息」。</p>
     */
    @Override
    @SentinelResource(value = "couponClaim", blockHandler = "claimBlockHandler")
    public void claim(Long templateId) {
        long userId = UserContext.getRequired();
        CouponTemplate template = templateService.getDetail(templateId);
        if (template == null) {
            throw new BizException(ResultCode.COUPON_TEMPLATE_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(template.getStartTime())) {
            throw new BizException(ResultCode.COUPON_NOT_STARTED);
        }
        if (now.isAfter(template.getEndTime())) {
            throw new BizException(ResultCode.COUPON_ENDED);
        }

        // KEYS: 库存 / 用户已领 Hash / 活动状态；Lua 内原子校验限领与库存并 DECR
        List<String> keys = List.of(
                RedisKey.couponStock(templateId),
                RedisKey.couponClaimHash(templateId),
                RedisKey.couponStatus(templateId));
        long[] res = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_COUPON_CLAIM, keys,
                String.valueOf(userId), template.getPerUserLimit()));
        switch ((int) res[0]) {
            case 0 -> { /* 预扣成功 */ }
            case -1 -> throw new BizException(ResultCode.COUPON_NOT_STARTED);
            case -2 -> throw new BizException(ResultCode.COUPON_LIMIT_EXCEEDED);
            case -3 -> throw new BizException(ResultCode.COUPON_SOLD_OUT);
            default -> throw new BizException(ResultCode.SYSTEM_ERROR);
        }

        long userCouponId = idGenerator.nextId();
        CouponClaimMessage message = new CouponClaimMessage();
        message.setId(userCouponId);
        message.setUserId(userId);
        message.setTemplateId(templateId);
        message.setCouponName(template.getName());
        message.setType(template.getType());
        message.setDiscountValue(template.getDiscountValue());
        message.setMaxDiscountAmount(template.getMaxDiscountAmount());
        message.setThreshold(template.getThreshold());
        message.setTarget(template.getTarget() == null ? Integer.valueOf(CouponEnums.TARGET_ALL) : template.getTarget());
        message.setProductCode(template.getProductCode());
        message.setStatus(CouponEnums.COUPON_UNUSED);
        message.setCreateTime(now);
        message.setUpdateTime(now);
        LocalDateTime validUntil = now.plusDays(template.getValidDays());
        message.setExpireTime(validUntil.isAfter(template.getEndTime()) ? template.getEndTime() : validUntil);

        String tokenKey = RedisKey.couponClaimToken(templateId, userCouponId);
        boolean tokenWritten = false;
        try {
            // 写入落库凭证：消费端 CAS 认领，防止发送超时回滚后延迟消息仍落库超发
            stringRedisTemplate.opsForValue().set(tokenKey, String.valueOf(userCouponId),
                    Duration.ofSeconds(RedisKey.TOKEN_TTL_SECONDS));
            tokenWritten = true;

            // 以 userCouponId 作为分区键：同一张券顺序一致，且天然打散到各分区
            SendResult<String, Object> result = kafkaTemplate.send(
                    CommonConstant.MQ_TOPIC_COUPON_CLAIM,
                    String.valueOf(userCouponId), message).get(3, TimeUnit.SECONDS);
            var metadata = result.getRecordMetadata();
            log.debug("领券消息已投递 topic={} partition={} offset={} userId={} couponId={}",
                    metadata.topic(), metadata.partition(), metadata.offset(), userId, userCouponId);
        } catch (Exception e) {
            long rollbackCode = rollbackClaim(templateId, userId, userCouponId, tokenWritten);
            if (rollbackCode == 2L) {
                log.warn("领券消息确认超时但消费端已认领，保留扣减 templateId={} userId={} couponId={}",
                        templateId, userId, userCouponId);
            } else {
                log.error("领券消息发送失败，已回滚预扣 templateId={} userId={} couponId={}",
                        templateId, userId, userCouponId, e);
                throw new BizException(ResultCode.MQ_SEND_FAILED);
            }
        }

        metrics.recordCouponClaim();
        log.info("领券预扣成功 userId={} templateId={} userCouponId={}", userId, templateId, userCouponId);
    }

    /** Sentinel 限流降级 */
    public void claimBlockHandler(Long templateId, BlockException e) {
        throw new BizException(ResultCode.TOO_MANY_REQUESTS);
    }

    private long rollbackClaim(Long templateId, long userId, long userCouponId, boolean tokenWritten) {
        try {
            if (tokenWritten) {
                long[] res = luaExecutor.parse(luaExecutor.execute(
                        LuaScriptExecutor.SCRIPT_COUPON_ROLLBACK,
                        List.of(
                                RedisKey.couponStock(templateId),
                                RedisKey.couponClaimHash(templateId),
                                RedisKey.couponClaimToken(templateId, userCouponId),
                                RedisKey.couponRollbackGuard(templateId, userCouponId)),
                        String.valueOf(userId),
                        String.valueOf(userCouponId),
                        RedisKey.ROLLBACK_GUARD_TTL_SECONDS));
                return res[0];
            }
            long[] res = luaExecutor.parse(luaExecutor.execute(
                    LuaScriptExecutor.SCRIPT_COUPON_ROLLBACK,
                    List.of(RedisKey.couponStock(templateId), RedisKey.couponClaimHash(templateId)),
                    String.valueOf(userId)));
            return res[0];
        } catch (Exception ex) {
            // 补偿失败必须告警，人工介入对账
            log.error("领券补偿失败 templateId={} userId={} couponId={}", templateId, userId, userCouponId, ex);
            return -99L;
        }
    }

    @Override
    public List<UserCoupon> listMyCoupons(Integer status) {
        long userId = UserContext.getRequired();
        if (Integer.valueOf(CouponEnums.COUPON_UNUSED).equals(status)) {
            List<UserCoupon> cached = new ArrayList<>(
                    userCouponCacheService.listUnusedCoupons(userId, LocalDateTime.now()));
            cached.sort(Comparator.comparing(
                    UserCoupon::getCreateTime,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            return cached;
        }
        LambdaQueryWrapper<UserCoupon> query = new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .orderByDesc(UserCoupon::getCreateTime);
        if (status != null) {
            query.eq(UserCoupon::getStatus, status);
        }
        return userCouponMapper.selectList(query);
    }

    @Override
    public PageResult<UserCoupon> pageMyCoupons(long pageNum, long pageSize, Integer status) {
        long userId = UserContext.getRequired();
        long safeNum = Math.max(1L, pageNum);
        long safeSize = Math.min(100L, Math.max(1L, pageSize));
        LambdaQueryWrapper<UserCoupon> query = new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .eq(status != null, UserCoupon::getStatus, status)
                .gt(Integer.valueOf(CouponEnums.COUPON_UNUSED).equals(status),
                        UserCoupon::getExpireTime, LocalDateTime.now())
                .orderByDesc(UserCoupon::getCreateTime);
        Page<UserCoupon> page = userCouponMapper.selectPage(new Page<>(safeNum, safeSize), query);
        if (page == null) {
            return PageResult.of(safeNum, safeSize, 0L, List.of());
        }
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    @Override
    public CouponQuoteResponseDTO quoteCoupons(CouponQuoteRequestDTO dto) {
        long userId = UserContext.getRequired();
        if (dto == null || dto.getOrderAmount() == null || dto.getOrderAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "订单金额必须大于 0");
        }
        LocalDateTime now = LocalDateTime.now();
        List<UserCoupon> coupons = userCouponCacheService.listUnusedCoupons(userId, now);
        List<CouponQuoteItemVO> available = new ArrayList<>();
        List<CouponQuoteItemVO> unavailable = new ArrayList<>();
        if (coupons != null) {
            for (UserCoupon coupon : coupons) {
                evaluateCouponQuote(coupon, dto, available, unavailable);
            }
        }
        Comparator<LocalDateTime> expireOrder = Comparator.nullsLast(Comparator.naturalOrder());
        available.sort(Comparator.comparing(CouponQuoteItemVO::getDiscountAmount).reversed()
                .thenComparing(CouponQuoteItemVO::getExpireTime, expireOrder)
                .thenComparing(CouponQuoteItemVO::getUserCouponId));
        unavailable.sort(Comparator.comparing(CouponQuoteItemVO::getExpireTime, expireOrder)
                .thenComparing(CouponQuoteItemVO::getUserCouponId));
        return new CouponQuoteResponseDTO(available, unavailable);
    }

    private void evaluateCouponQuote(
            UserCoupon coupon,
            CouponQuoteRequestDTO dto,
            List<CouponQuoteItemVO> available,
            List<CouponQuoteItemVO> unavailable) {
        int target = coupon.getTarget() == null ? CouponEnums.TARGET_ALL : coupon.getTarget();
        CouponPricingRule rule = new CouponPricingRule(
                coupon.getType() == null ? 0 : coupon.getType(),
                coupon.getDiscountValue(),
                coupon.getMaxDiscountAmount(),
                coupon.getThreshold(),
                target,
                coupon.getProductCode());
        CouponQuoteItemVO.CouponQuoteItemVOBuilder item = CouponQuoteItemVO.builder()
                .userCouponId(coupon.getId())
                .templateId(coupon.getTemplateId())
                .couponName(coupon.getCouponName())
                .type(coupon.getType())
                .discountValue(coupon.getDiscountValue())
                .maxDiscountAmount(coupon.getMaxDiscountAmount())
                .threshold(coupon.getThreshold())
                .target(target)
                .productCode(coupon.getProductCode())
                .expireTime(coupon.getExpireTime());
        try {
            CouponPricingResult priced = CouponPricing.calculate(rule, dto.getOrderAmount(), dto.getProductCode());
            available.add(item
                    .discountAmount(priced.discountAmount())
                    .payAmount(priced.payAmount())
                    .available(true)
                    .build());
        } catch (BizException ex) {
            unavailable.add(item
                    .available(false)
                    .unavailableReason(ex.getMessage())
                    .build());
        }
    }

    @Override
    public void useCoupon(CouponUseDTO dto) {
        long userId = UserContext.getRequired();
        R<Void> response;
        try {
            response = orderFeignClient.applyCoupon(dto.getOrderId(), userId, dto.getUserCouponId());
        } catch (Exception e) {
            log.error("转发订单绑券失败 orderId={} userId={} couponId={} cause={}",
                    dto.getOrderId(), userId, dto.getUserCouponId(), e.getMessage());
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单服务暂不可用，请稍后重试");
        }
        if (response == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单服务暂不可用，请稍后重试");
        }
        if (!response.isSuccess()) {
            throw new BizException(response.getCode(), response.getMessage());
        }
    }

    @Override
    public int expireBatch() {
        return userCouponMapper.expireBatch(LocalDateTime.now());
    }
}
