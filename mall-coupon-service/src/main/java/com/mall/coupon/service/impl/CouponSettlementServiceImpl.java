package com.mall.coupon.service.impl;

import com.mall.common.R;
import com.mall.common.ResultCode;
import com.mall.common.exception.BizException;
import com.mall.common.message.CouponLockRequest;
import com.mall.common.message.CouponLockResult;
import com.mall.common.message.CouponUnlockRequest;
import com.mall.common.message.OrderQueryResult;
import com.mall.common.pricing.CouponPricing;
import com.mall.common.pricing.CouponPricingResult;
import com.mall.common.pricing.CouponPricingRule;
import com.mall.common.util.IdGenerator;
import com.mall.coupon.cache.UserCouponCacheService;
import com.mall.coupon.domain.entity.CouponSettlement;
import com.mall.coupon.domain.entity.UserCoupon;
import com.mall.coupon.domain.enums.CouponEnums;
import com.mall.coupon.feign.OrderFeignClient;
import com.mall.coupon.mapper.CouponSettlementMapper;
import com.mall.coupon.mapper.UserCouponMapper;
import com.mall.coupon.service.CouponSettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponSettlementServiceImpl implements CouponSettlementService {

    private static final long LOCK_TIMEOUT_MINUTES = 20L;
    private static final int SWEEP_BATCH_SIZE = 100;
    private static final int SWEEP_MAX_BATCHES = 20;

    private final CouponSettlementMapper settlementMapper;
    private final UserCouponMapper userCouponMapper;
    private final IdGenerator idGenerator;
    private final TransactionTemplate transactionTemplate;
    private final OrderFeignClient orderFeignClient;
    private final UserCouponCacheService userCouponCacheService;

    @Override
    public CouponLockResult lockCoupon(CouponLockRequest request) {
        if (request == null
                || request.getUserId() == null
                || !StringUtils.hasText(request.getOrderId())
                || request.getUserCouponId() == null
                || request.getOrderAmount() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "锁券请求参数不完整");
        }
        String orderId = request.getOrderId().trim();
        CouponSettlement existed = settlementMapper.selectByOrderAndCoupon(
                request.getUserId(), orderId, request.getUserCouponId());
        if (existed != null) {
            return replayLock(existed, request.getUserId(), request.getUserCouponId());
        }

        UserCoupon coupon = loadOwnedCoupon(request.getUserCouponId(), request.getUserId());
        LocalDateTime now = LocalDateTime.now();
        if (coupon.getExpireTime() != null && coupon.getExpireTime().isBefore(now)) {
            throw new BizException(ResultCode.COUPON_EXPIRED);
        }
        int couponStatus = coupon.getStatus() == null ? -1 : coupon.getStatus();
        if (couponStatus == CouponEnums.COUPON_LOCKED) {
            throw new BizException(ResultCode.COUPON_LOCKED);
        }
        if (couponStatus != CouponEnums.COUPON_UNUSED) {
            throw new BizException(ResultCode.COUPON_ALREADY_USED);
        }

        CouponPricingResult priced = CouponPricing.calculate(
                ruleOf(coupon), request.getOrderAmount(), request.getProductCode());

        CouponSettlement settlement = new CouponSettlement();
        settlement.setId(idGenerator.nextId());
        settlement.setUserId(request.getUserId());
        settlement.setOrderId(orderId);
        settlement.setUserCouponId(coupon.getId());
        settlement.setTemplateId(coupon.getTemplateId());
        settlement.setStatus(CouponEnums.SETTLEMENT_LOCKED);
        settlement.setOrderAmount(request.getOrderAmount().setScale(2, RoundingMode.HALF_UP));
        settlement.setDiscountAmount(priced.discountAmount());
        settlement.setPayAmount(priced.payAmount());
        settlement.setLockTime(now);
        settlement.setCreateTime(now);
        settlement.setUpdateTime(now);

        try {
            transactionTemplate.executeWithoutResult(tx -> {
                settlementMapper.insert(settlement);
                int locked = userCouponMapper.lockCoupon(coupon.getId(), request.getUserId(), orderId);
                if (locked == 0) {
                    throw new BizException(ResultCode.COUPON_LOCKED);
                }
            });
        } catch (DuplicateKeyException dup) {
            CouponSettlement concurrent = settlementMapper.selectByOrderAndCoupon(
                    request.getUserId(), orderId, request.getUserCouponId());
            return replayLock(concurrent, request.getUserId(), request.getUserCouponId());
        }

        log.info("优惠券锁券成功 userId={} couponId={} orderId={} discount={} pay={}",
                request.getUserId(), coupon.getId(), orderId,
                priced.discountAmount(), priced.payAmount());
        userCouponCacheService.removeUnusedCoupon(request.getUserId(), coupon.getId());
        return buildLockResult(coupon, priced.discountAmount(), priced.payAmount());
    }

    @Override
    public void settleByOrder(String orderId, Long userId, Long couponId) {
        if (!StringUtils.hasText(orderId) || couponId == null) {
            return;
        }
        transitionLockedSettlement(userId, orderId.trim(), couponId, CouponEnums.SETTLEMENT_SETTLED, true);
    }

    @Override
    public void unlockCoupon(CouponUnlockRequest request) {
        if (request == null
                || request.getUserId() == null
                || !StringUtils.hasText(request.getOrderId())
                || request.getUserCouponId() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "解锁请求参数不完整");
        }
        releaseSettlementByOrderAndCoupon(
                request.getUserId(), request.getOrderId().trim(), request.getUserCouponId());
    }

    @Override
    public void unlockOrRefundByOrder(String orderId, Long userId) {
        if (!StringUtils.hasText(orderId)) {
            return;
        }
        String normalizedOrderId = orderId.trim();
        List<CouponSettlement> locked = userId != null
                ? settlementMapper.selectLockedByOrderAndUser(userId, normalizedOrderId)
                : settlementMapper.selectLockedByOrderId(normalizedOrderId);
        if (locked != null && !locked.isEmpty()) {
            for (CouponSettlement settlement : locked) {
                releaseSettlementByOrderAndCoupon(
                        settlement.getUserId(), normalizedOrderId, settlement.getUserCouponId());
            }
            return;
        }
        List<Long> affectedUsers = userId != null
                ? List.of(userId)
                : userCouponMapper.selectUsedUserIdsByOrderId(normalizedOrderId);
        int refunded = userId != null
                ? userCouponMapper.refundByOrderAndUser(userId, normalizedOrderId)
                : userCouponMapper.refundByOrderId(normalizedOrderId);
        if (refunded > 0) {
            if (affectedUsers != null) {
                for (Long affectedUserId : affectedUsers) {
                    userCouponCacheService.evictUnusedCoupons(affectedUserId);
                }
            }
            log.info("订单取消自动退回已核销优惠券 orderId={} userId={} refunded={}",
                    normalizedOrderId, userId, refunded);
        }
    }

    @Override
    public int sweepExpiredLocks() {
        int handled = 0;
        Set<Long> visitedIds = new HashSet<>();
        for (int batch = 0; batch < SWEEP_MAX_BATCHES; batch++) {
            LocalDateTime deadline = LocalDateTime.now().minusMinutes(LOCK_TIMEOUT_MINUTES);
            List<CouponSettlement> expired = settlementMapper.findExpiredLocked(deadline, SWEEP_BATCH_SIZE);
            if (expired == null || expired.isEmpty()) {
                break;
            }
            boolean batchProgressed = false;
            for (CouponSettlement settlement : expired) {
                if (!visitedIds.add(settlement.getId())) {
                    continue;
                }
                try {
                    if (reconcileSingleExpiredLock(settlement)) {
                        handled++;
                        batchProgressed = true;
                    }
                } catch (Exception e) {
                    log.error("巡检超时锁券结算单异常 settlementId={} orderId={}",
                            settlement.getId(), settlement.getOrderId(), e);
                }
            }
            if (expired.size() < SWEEP_BATCH_SIZE || !batchProgressed) {
                break;
            }
        }
        return handled;
    }

    private boolean reconcileSingleExpiredLock(CouponSettlement settlement) {
        R<OrderQueryResult> orderResp;
        try {
            orderResp = orderFeignClient.getOrder(settlement.getOrderId());
        } catch (Exception e) {
            log.warn("巡检超时锁券查询订单失败，跳过本轮 orderId={} cause={}",
                    settlement.getOrderId(), e.getMessage());
            return false;
        }
        if (orderResp == null || !orderResp.isSuccess()) {
            return false;
        }
        OrderQueryResult order = orderResp.getData();
        if (order != null
                && Integer.valueOf(1).equals(order.getStatus())
                && settlement.getUserCouponId().equals(order.getCouponId())) {
            settleByOrder(settlement.getOrderId(), settlement.getUserId(), order.getCouponId());
            return true;
        }
        if (shouldReleaseLockedSettlement(order, settlement.getUserCouponId())) {
            releaseSettlementByOrderAndCoupon(
                    settlement.getUserId(), settlement.getOrderId(), settlement.getUserCouponId());
            return true;
        }
        return false;
    }

    private boolean shouldReleaseLockedSettlement(OrderQueryResult order, Long userCouponId) {
        if (order == null
                || Integer.valueOf(1).equals(order.getStatus())
                || Integer.valueOf(2).equals(order.getStatus())) {
            return true;
        }
        return order.getCouponId() != null && !userCouponId.equals(order.getCouponId());
    }

    private void releaseSettlementByOrderAndCoupon(Long userId, String orderId, Long userCouponId) {
        transitionLockedSettlement(userId, orderId, userCouponId, CouponEnums.SETTLEMENT_CANCELLED, false);
    }

    private void transitionLockedSettlement(
            Long userId,
            String orderId,
            Long userCouponId,
            int targetStatus,
            boolean settle) {
        AtomicReference<Long> affectedUserId = new AtomicReference<>(userId);
        Boolean completed = transactionTemplate.execute(tx -> {
            CouponSettlement settlement = findSettlementByOrderAndCoupon(userId, orderId, userCouponId);
            if (settlement != null && settlement.getUserId() != null) {
                affectedUserId.set(settlement.getUserId());
            }
            if (settlement == null || Integer.valueOf(targetStatus).equals(settlement.getStatus())) {
                return Boolean.TRUE;
            }
            if (!Integer.valueOf(CouponEnums.SETTLEMENT_LOCKED).equals(settlement.getStatus())) {
                log.warn("结算单处于非锁定态，跳过变更 orderId={} couponId={} status={} targetStatus={}",
                        orderId, userCouponId, settlement.getStatus(), targetStatus);
                return Boolean.TRUE;
            }
            int updated = settle
                    ? settlementMapper.advanceToSettled(settlement.getId(), settlement.getUserId())
                    : settlementMapper.advanceToCancelled(settlement.getId(), settlement.getUserId());
            if (updated == 0) {
                return Boolean.FALSE;
            }
            int couponUpdated = settle
                    ? userCouponMapper.settleLockedCoupon(
                            settlement.getUserCouponId(), settlement.getUserId(), orderId)
                    : userCouponMapper.unlockLockedCoupon(
                            settlement.getUserCouponId(), settlement.getUserId(), orderId);
            if (couponUpdated == 0) {
                throw new IllegalStateException(
                        "优惠券状态与结算单不一致 orderId=" + orderId + " couponId=" + userCouponId);
            }
            log.info(settle ? "优惠券正式核销成功 orderId={} userId={} couponId={}"
                            : "优惠券解锁释放成功 orderId={} userId={} couponId={}",
                    orderId, settlement.getUserId(), userCouponId);
            return Boolean.TRUE;
        });
        if (!Boolean.FALSE.equals(completed)) {
            Long targetUserId = affectedUserId.get();
            if (targetUserId != null) {
                if (settle) {
                    userCouponCacheService.removeUnusedCoupon(targetUserId, userCouponId);
                } else {
                    userCouponCacheService.evictUnusedCoupons(targetUserId);
                }
            }
            return;
        }
        CouponSettlement latest = findSettlementByOrderAndCoupon(userId, orderId, userCouponId);
        if (latest == null || Integer.valueOf(targetStatus).equals(latest.getStatus())) {
            return;
        }
        if (!Integer.valueOf(CouponEnums.SETTLEMENT_LOCKED).equals(latest.getStatus())) {
            log.warn("结算单并发推进为其他终态，幂等跳过 orderId={} couponId={} status={} targetStatus={}",
                    orderId, userCouponId, latest.getStatus(), targetStatus);
            return;
        }
        throw new IllegalStateException(
                "推进结算单状态失败 orderId=" + orderId + " couponId=" + userCouponId + " target=" + targetStatus);
    }

    private CouponSettlement findSettlementByOrderAndCoupon(Long userId, String orderId, Long userCouponId) {
        if (userId != null) {
            return settlementMapper.selectByOrderAndCoupon(userId, orderId, userCouponId);
        }
        return settlementMapper.selectByOrderAndCouponWithoutUser(orderId, userCouponId);
    }

    private UserCoupon loadOwnedCoupon(Long userCouponId, Long userId) {
        UserCoupon coupon = userCouponMapper.selectByIdAndUser(userCouponId, userId);
        if (coupon != null) {
            return coupon;
        }
        UserCoupon existing = userCouponMapper.selectById(userCouponId);
        if (existing != null && !userId.equals(existing.getUserId())) {
            throw new BizException(ResultCode.COUPON_NOT_OWNED);
        }
        throw new BizException(ResultCode.NOT_FOUND);
    }

    private CouponLockResult replayLock(CouponSettlement existed, Long userId, Long userCouponId) {
        if (existed == null) {
            throw new BizException(ResultCode.COUPON_SETTLEMENT_CONFLICT);
        }
        if (Integer.valueOf(CouponEnums.SETTLEMENT_LOCKED).equals(existed.getStatus())
                || Integer.valueOf(CouponEnums.SETTLEMENT_SETTLED).equals(existed.getStatus())) {
            UserCoupon coupon = userCouponMapper.selectByIdAndUser(userCouponId, userId);
            if (coupon == null) {
                throw new BizException(ResultCode.COUPON_SETTLEMENT_CONFLICT);
            }
            return buildLockResult(coupon, existed.getDiscountAmount(), existed.getPayAmount());
        }
        throw new BizException(ResultCode.COUPON_SETTLEMENT_CONFLICT);
    }

    private CouponPricingRule ruleOf(UserCoupon coupon) {
        int target = coupon.getTarget() == null ? CouponEnums.TARGET_ALL : coupon.getTarget();
        return new CouponPricingRule(
                coupon.getType() == null ? 0 : coupon.getType(),
                coupon.getDiscountValue(),
                coupon.getMaxDiscountAmount(),
                coupon.getThreshold(),
                target,
                coupon.getProductCode());
    }

    private CouponLockResult buildLockResult(
            UserCoupon coupon,
            BigDecimal discountAmount,
            BigDecimal payAmount) {
        CouponLockResult result = new CouponLockResult();
        result.setUserCouponId(coupon.getId());
        result.setDiscountAmount(discountAmount);
        result.setPayAmount(payAmount);
        result.setType(coupon.getType());
        result.setDiscountValue(coupon.getDiscountValue());
        result.setMaxDiscountAmount(coupon.getMaxDiscountAmount());
        result.setThreshold(coupon.getThreshold());
        result.setTarget(coupon.getTarget() == null ? Integer.valueOf(CouponEnums.TARGET_ALL) : coupon.getTarget());
        result.setProductCode(coupon.getProductCode());
        return result;
    }
}
