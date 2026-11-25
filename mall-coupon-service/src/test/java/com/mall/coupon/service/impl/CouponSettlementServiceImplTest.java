package com.mall.coupon.service.impl;

import com.mall.common.R;
import com.mall.common.ResultCode;
import com.mall.common.message.CouponLockRequest;
import com.mall.common.message.CouponLockResult;
import com.mall.common.message.CouponUnlockRequest;
import com.mall.common.message.OrderQueryResult;
import com.mall.common.exception.BizException;
import com.mall.common.util.IdGenerator;
import com.mall.coupon.cache.UserCouponCacheService;
import com.mall.coupon.domain.entity.CouponSettlement;
import com.mall.coupon.domain.entity.UserCoupon;
import com.mall.coupon.domain.enums.CouponEnums;
import com.mall.coupon.feign.OrderFeignClient;
import com.mall.coupon.mapper.CouponSettlementMapper;
import com.mall.coupon.mapper.UserCouponMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponSettlementServiceImplTest {

    private CouponSettlementMapper settlementMapper;
    private UserCouponMapper userCouponMapper;
    private IdGenerator idGenerator;
    private TransactionTemplate transactionTemplate;
    private OrderFeignClient orderFeignClient;
    private UserCouponCacheService userCouponCacheService;
    private CouponSettlementServiceImpl service;

    @BeforeEach
    void setUp() {
        settlementMapper = mock(CouponSettlementMapper.class);
        userCouponMapper = mock(UserCouponMapper.class);
        idGenerator = mock(IdGenerator.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
        doAnswer(inv -> {
            Consumer<TransactionStatus> cb = inv.getArgument(0);
            cb.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        orderFeignClient = mock(OrderFeignClient.class);
        userCouponCacheService = mock(UserCouponCacheService.class);
        service = new CouponSettlementServiceImpl(
                settlementMapper, userCouponMapper, idGenerator,
                transactionTemplate, orderFeignClient, userCouponCacheService);
    }

    @Test
    void lockCoupon_shouldInsertSettlementAndLockCouponWithCappedDiscount() {
        UserCoupon coupon = unusedDiscountCoupon(501L, 1001L);
        when(settlementMapper.selectByOrderAndCoupon(1001L, "ORD-1", 501L)).thenReturn(null);
        when(userCouponMapper.selectByIdAndUser(501L, 1001L)).thenReturn(coupon);
        when(idGenerator.nextId()).thenReturn(7001L);
        when(userCouponMapper.lockCoupon(501L, 1001L, "ORD-1")).thenReturn(1);

        CouponLockRequest req = lockRequest(1001L, "ORD-1", 501L, new BigDecimal("5999.00"));
        CouponLockResult result = service.lockCoupon(req);

        assertEquals(new BigDecimal("200.00"), result.getDiscountAmount());
        assertEquals(new BigDecimal("5799.00"), result.getPayAmount());

        ArgumentCaptor<CouponSettlement> captor = ArgumentCaptor.forClass(CouponSettlement.class);
        verify(settlementMapper).insert(captor.capture());
        assertEquals(7001L, captor.getValue().getId());
        assertEquals(CouponEnums.SETTLEMENT_LOCKED, captor.getValue().getStatus());
        assertEquals(new BigDecimal("200.00"), captor.getValue().getDiscountAmount());
    }

    @Test
    void lockCoupon_shouldReplayWhenAlreadyLockedAndHandleDuplicateKey() {
        UserCoupon coupon = unusedDiscountCoupon(502L, 1001L);
        CouponSettlement locked = settlement(7002L, 1001L, "ORD-2", 502L, CouponEnums.SETTLEMENT_LOCKED);
        when(settlementMapper.selectByOrderAndCoupon(1001L, "ORD-2", 502L)).thenReturn(locked);
        when(userCouponMapper.selectByIdAndUser(502L, 1001L)).thenReturn(coupon);

        CouponLockResult replayed = service.lockCoupon(lockRequest(1001L, "ORD-2", 502L, new BigDecimal("5999.00")));
        assertEquals(new BigDecimal("200.00"), replayed.getDiscountAmount());
        verify(settlementMapper, never()).insert(any(CouponSettlement.class));

        CouponSettlement cancelled = settlement(7003L, 1001L, "ORD-3", 503L, CouponEnums.SETTLEMENT_CANCELLED);
        when(settlementMapper.selectByOrderAndCoupon(1001L, "ORD-3", 503L)).thenReturn(cancelled);
        BizException conflict = assertThrows(BizException.class,
                () -> service.lockCoupon(lockRequest(1001L, "ORD-3", 503L, new BigDecimal("5999.00"))));
        assertEquals(ResultCode.COUPON_SETTLEMENT_CONFLICT.getCode(), conflict.getCode());

        UserCoupon coupon4 = unusedDiscountCoupon(504L, 1001L);
        CouponSettlement raceWinner = settlement(7004L, 1001L, "ORD-4", 504L, CouponEnums.SETTLEMENT_LOCKED);
        when(settlementMapper.selectByOrderAndCoupon(1001L, "ORD-4", 504L)).thenReturn(null, raceWinner);
        when(userCouponMapper.selectByIdAndUser(504L, 1001L)).thenReturn(coupon4);
        when(idGenerator.nextId()).thenReturn(7004L);
        when(settlementMapper.insert(any(CouponSettlement.class))).thenThrow(new DuplicateKeyException("dup"));

        CouponLockResult raced = service.lockCoupon(lockRequest(1001L, "ORD-4", 504L, new BigDecimal("5999.00")));
        assertEquals(new BigDecimal("200.00"), raced.getDiscountAmount());
    }

    @Test
    void lockCoupon_shouldRejectNotOwnedExpiredOrLockedCoupon() {
        when(userCouponMapper.selectByIdAndUser(601L, 1001L)).thenReturn(null);
        UserCoupon other = unusedDiscountCoupon(601L, 2002L);
        when(userCouponMapper.selectById(601L)).thenReturn(other);
        BizException notOwned = assertThrows(BizException.class,
                () -> service.lockCoupon(lockRequest(1001L, "ORD-X", 601L, new BigDecimal("100.00"))));
        assertEquals(ResultCode.COUPON_NOT_OWNED.getCode(), notOwned.getCode());

        UserCoupon expired = unusedDiscountCoupon(602L, 1001L);
        expired.setExpireTime(LocalDateTime.now().minusMinutes(1));
        when(userCouponMapper.selectByIdAndUser(602L, 1001L)).thenReturn(expired);
        BizException expiredEx = assertThrows(BizException.class,
                () -> service.lockCoupon(lockRequest(1001L, "ORD-Y", 602L, new BigDecimal("100.00"))));
        assertEquals(ResultCode.COUPON_EXPIRED.getCode(), expiredEx.getCode());

        UserCoupon locked = unusedDiscountCoupon(603L, 1001L);
        locked.setStatus(CouponEnums.COUPON_LOCKED);
        when(userCouponMapper.selectByIdAndUser(603L, 1001L)).thenReturn(locked);
        BizException lockedEx = assertThrows(BizException.class,
                () -> service.lockCoupon(lockRequest(1001L, "ORD-Z", 603L, new BigDecimal("100.00"))));
        assertEquals(ResultCode.COUPON_LOCKED.getCode(), lockedEx.getCode());
    }

    @Test
    void settleByOrder_shouldMatchExactCouponIdAndHandleConcurrentRepeatableReadIdempotently() {
        CouponSettlement c2 = settlement(7102L, 1001L, "ORD-SET", 510L, CouponEnums.SETTLEMENT_LOCKED);
        when(settlementMapper.selectByOrderAndCoupon(1001L, "ORD-SET", 510L)).thenReturn(c2);
        when(settlementMapper.advanceToSettled(7102L, 1001L)).thenReturn(1);
        when(userCouponMapper.settleLockedCoupon(510L, 1001L, "ORD-SET")).thenReturn(1);

        service.settleByOrder("ORD-SET", 1001L, null);
        verify(settlementMapper, never()).advanceToSettled(any(), any());

        service.settleByOrder("ORD-SET", 1001L, 510L);
        verify(settlementMapper).advanceToSettled(7102L, 1001L);
        verify(userCouponMapper).settleLockedCoupon(510L, 1001L, "ORD-SET");

        CouponSettlement raceLocked = settlement(7103L, 1001L, "ORD-RACE", 511L, CouponEnums.SETTLEMENT_LOCKED);
        CouponSettlement raceSettled = settlement(7103L, 1001L, "ORD-RACE", 511L, CouponEnums.SETTLEMENT_SETTLED);
        when(settlementMapper.selectByOrderAndCoupon(1001L, "ORD-RACE", 511L))
                .thenReturn(raceLocked, raceSettled);
        when(settlementMapper.advanceToSettled(7103L, 1001L)).thenReturn(0);

        service.settleByOrder("ORD-RACE", 1001L, 511L);
        verify(userCouponMapper, never()).settleLockedCoupon(511L, 1001L, "ORD-RACE");
    }

    @Test
    void unlockCouponAndUnlockOrRefundByOrder_shouldUnlockAllLockedRowsAvoidBroadcastAndHandleConcurrentCancel() {
        CouponSettlement s = settlement(7200L, 1001L, "ORD-UNL", 520L, CouponEnums.SETTLEMENT_LOCKED);
        CouponSettlement alreadyCancelled = settlement(
                7200L, 1001L, "ORD-UNL", 520L, CouponEnums.SETTLEMENT_CANCELLED);
        when(settlementMapper.selectByOrderAndCoupon(1001L, "ORD-UNL", 520L))
                .thenReturn(s, alreadyCancelled);
        when(settlementMapper.advanceToCancelled(7200L, 1001L)).thenReturn(0);

        CouponUnlockRequest unlockReq = new CouponUnlockRequest();
        unlockReq.setUserId(1001L);
        unlockReq.setOrderId("ORD-UNL");
        unlockReq.setUserCouponId(520L);
        service.unlockCoupon(unlockReq);
        verify(userCouponMapper, never()).unlockLockedCoupon(520L, 1001L, "ORD-UNL");

        CouponSettlement locked1 = settlement(7201L, 1001L, "ORD-MULTI", 521L, CouponEnums.SETTLEMENT_LOCKED);
        CouponSettlement locked2 = settlement(7202L, 1001L, "ORD-MULTI", 522L, CouponEnums.SETTLEMENT_LOCKED);
        when(settlementMapper.selectLockedByOrderAndUser(1001L, "ORD-MULTI")).thenReturn(List.of(locked1, locked2));
        when(settlementMapper.selectByOrderAndCoupon(1001L, "ORD-MULTI", 521L)).thenReturn(locked1);
        when(settlementMapper.selectByOrderAndCoupon(1001L, "ORD-MULTI", 522L)).thenReturn(locked2);
        when(settlementMapper.advanceToCancelled(7201L, 1001L)).thenReturn(1);
        when(settlementMapper.advanceToCancelled(7202L, 1001L)).thenReturn(1);
        when(userCouponMapper.unlockLockedCoupon(521L, 1001L, "ORD-MULTI")).thenReturn(1);
        when(userCouponMapper.unlockLockedCoupon(522L, 1001L, "ORD-MULTI")).thenReturn(1);

        service.unlockOrRefundByOrder("ORD-MULTI", 1001L);
        verify(userCouponMapper).unlockLockedCoupon(521L, 1001L, "ORD-MULTI");
        verify(userCouponMapper).unlockLockedCoupon(522L, 1001L, "ORD-MULTI");

        when(settlementMapper.selectLockedByOrderAndUser(1001L, "ORD-LEGACY")).thenReturn(List.of());
        when(userCouponMapper.refundByOrderAndUser(1001L, "ORD-LEGACY")).thenReturn(1);
        service.unlockOrRefundByOrder("ORD-LEGACY", 1001L);
        verify(userCouponMapper).refundByOrderAndUser(1001L, "ORD-LEGACY");
        verify(settlementMapper, never()).selectLockedByOrderId(any());
        verify(userCouponMapper, never()).refundByOrderId(any());
    }

    @Test
    void sweepExpiredLocks_shouldReconcileAllOrderStatesSafely() {
        CouponSettlement paidBound = settlement(1L, 1001L, "ORD-P1", 11L, CouponEnums.SETTLEMENT_LOCKED);
        CouponSettlement paidOrphan = settlement(2L, 1001L, "ORD-P2", 12L, CouponEnums.SETTLEMENT_LOCKED);
        CouponSettlement cancelled = settlement(3L, 1001L, "ORD-C1", 13L, CouponEnums.SETTLEMENT_LOCKED);
        CouponSettlement unpaidOther = settlement(4L, 1001L, "ORD-U1", 14L, CouponEnums.SETTLEMENT_LOCKED);
        CouponSettlement unpaidSame = settlement(5L, 1001L, "ORD-U2", 15L, CouponEnums.SETTLEMENT_LOCKED);
        CouponSettlement unpaidNull = settlement(6L, 1001L, "ORD-U3", 16L, CouponEnums.SETTLEMENT_LOCKED);
        CouponSettlement feignDown = settlement(7L, 1001L, "ORD-ERR", 17L, CouponEnums.SETTLEMENT_LOCKED);

        when(settlementMapper.findExpiredLocked(any(), anyInt()))
                .thenReturn(List.of(paidBound, paidOrphan, cancelled, unpaidOther, unpaidSame, unpaidNull, feignDown));

        when(orderFeignClient.getOrder("ORD-P1")).thenReturn(R.ok(orderResult("ORD-P1", 1, 11L)));
        when(orderFeignClient.getOrder("ORD-P2")).thenReturn(R.ok(orderResult("ORD-P2", 1, null)));
        when(orderFeignClient.getOrder("ORD-C1")).thenReturn(R.ok(orderResult("ORD-C1", 2, null)));
        when(orderFeignClient.getOrder("ORD-U1")).thenReturn(R.ok(orderResult("ORD-U1", 0, 99L)));
        when(orderFeignClient.getOrder("ORD-U2")).thenReturn(R.ok(orderResult("ORD-U2", 0, 15L)));
        when(orderFeignClient.getOrder("ORD-U3")).thenReturn(R.ok(orderResult("ORD-U3", 0, null)));
        when(orderFeignClient.getOrder("ORD-ERR")).thenThrow(new RuntimeException("timeout"));

        when(settlementMapper.selectByOrderAndCoupon(1001L, "ORD-P1", 11L)).thenReturn(paidBound);
        when(settlementMapper.advanceToSettled(1L, 1001L)).thenReturn(1);
        when(userCouponMapper.settleLockedCoupon(11L, 1001L, "ORD-P1")).thenReturn(1);

        when(settlementMapper.selectByOrderAndCoupon(1001L, "ORD-P2", 12L)).thenReturn(paidOrphan);
        when(settlementMapper.selectByOrderAndCoupon(1001L, "ORD-C1", 13L)).thenReturn(cancelled);
        when(settlementMapper.selectByOrderAndCoupon(1001L, "ORD-U1", 14L)).thenReturn(unpaidOther);
        when(settlementMapper.advanceToCancelled(any(), any())).thenReturn(1);
        when(userCouponMapper.unlockLockedCoupon(any(), any(), any())).thenReturn(1);

        int handled = service.sweepExpiredLocks();
        assertEquals(4, handled);
        verify(settlementMapper).advanceToSettled(1L, 1001L);
        verify(settlementMapper).advanceToCancelled(2L, 1001L);
        verify(settlementMapper).advanceToCancelled(3L, 1001L);
        verify(settlementMapper).advanceToCancelled(4L, 1001L);
        verify(settlementMapper, never()).advanceToCancelled(5L, 1001L);
        verify(settlementMapper, never()).advanceToCancelled(6L, 1001L);
        verify(settlementMapper, never()).advanceToCancelled(7L, 1001L);
    }

    @Test
    void settlementLifecycle_shouldUpdateUserCouponCacheIncludingNullUserIdPaths() {
        UserCoupon coupon = unusedDiscountCoupon(801L, 1001L);
        when(settlementMapper.selectByOrderAndCoupon(1001L, "ORD-CACHE-1", 801L)).thenReturn(null);
        when(userCouponMapper.selectByIdAndUser(801L, 1001L)).thenReturn(coupon);
        when(idGenerator.nextId()).thenReturn(8101L);
        when(userCouponMapper.lockCoupon(801L, 1001L, "ORD-CACHE-1")).thenReturn(1);

        service.lockCoupon(lockRequest(1001L, "ORD-CACHE-1", 801L, new BigDecimal("1000.00")));
        verify(userCouponCacheService).removeUnusedCoupon(1001L, 801L);

        CouponSettlement lockedForSettle = settlement(8101L, 1001L, "ORD-CACHE-1", 801L, CouponEnums.SETTLEMENT_LOCKED);
        when(settlementMapper.selectByOrderAndCouponWithoutUser("ORD-CACHE-1", 801L)).thenReturn(lockedForSettle);
        when(settlementMapper.advanceToSettled(8101L, 1001L)).thenReturn(1);
        when(userCouponMapper.settleLockedCoupon(801L, 1001L, "ORD-CACHE-1")).thenReturn(1);
        service.settleByOrder("ORD-CACHE-1", null, 801L);
        verify(userCouponCacheService, org.mockito.Mockito.times(2)).removeUnusedCoupon(1001L, 801L);

        CouponSettlement lockedForUnlock = settlement(8102L, 1001L, "ORD-CACHE-2", 802L, CouponEnums.SETTLEMENT_LOCKED);
        when(settlementMapper.selectByOrderAndCoupon(1001L, "ORD-CACHE-2", 802L)).thenReturn(lockedForUnlock);
        when(settlementMapper.advanceToCancelled(8102L, 1001L)).thenReturn(1);
        when(userCouponMapper.unlockLockedCoupon(802L, 1001L, "ORD-CACHE-2")).thenReturn(1);
        CouponUnlockRequest unlockReq = new CouponUnlockRequest();
        unlockReq.setUserId(1001L);
        unlockReq.setOrderId("ORD-CACHE-2");
        unlockReq.setUserCouponId(802L);
        service.unlockCoupon(unlockReq);
        verify(userCouponCacheService).evictUnusedCoupons(1001L);

        when(settlementMapper.selectLockedByOrderId("ORD-NULL-USER")).thenReturn(List.of());
        when(userCouponMapper.selectUsedUserIdsByOrderId("ORD-NULL-USER")).thenReturn(List.of(1001L, 1002L));
        when(userCouponMapper.refundByOrderId("ORD-NULL-USER")).thenReturn(1);
        service.unlockOrRefundByOrder("ORD-NULL-USER", null);
        verify(userCouponCacheService, org.mockito.Mockito.times(2)).evictUnusedCoupons(1001L);
        verify(userCouponCacheService).evictUnusedCoupons(1002L);
    }

    private OrderQueryResult orderResult(String orderId, int status, Long couponId) {
        OrderQueryResult res = new OrderQueryResult();
        res.setOrderId(orderId);
        res.setUserId(1001L);
        res.setStatus(status);
        res.setCouponId(couponId);
        return res;
    }

    private CouponLockRequest lockRequest(long userId, String orderId, long couponId, BigDecimal amount) {
        CouponLockRequest req = new CouponLockRequest();
        req.setUserId(userId);
        req.setOrderId(orderId);
        req.setUserCouponId(couponId);
        req.setOrderAmount(amount);
        return req;
    }

    private CouponSettlement settlement(long id, long userId, String orderId, long couponId, int status) {
        CouponSettlement s = new CouponSettlement();
        s.setId(id);
        s.setUserId(userId);
        s.setOrderId(orderId);
        s.setUserCouponId(couponId);
        s.setTemplateId(1002L);
        s.setStatus(status);
        s.setDiscountAmount(new BigDecimal("200.00"));
        s.setPayAmount(new BigDecimal("5799.00"));
        return s;
    }

    private UserCoupon unusedDiscountCoupon(long id, long userId) {
        UserCoupon c = new UserCoupon();
        c.setId(id);
        c.setUserId(userId);
        c.setTemplateId(1002L);
        c.setType(CouponEnums.TYPE_DISCOUNT);
        c.setDiscountValue(new BigDecimal("0.90"));
        c.setMaxDiscountAmount(new BigDecimal("200.00"));
        c.setThreshold(BigDecimal.ZERO);
        c.setTarget(CouponEnums.TARGET_ALL);
        c.setStatus(CouponEnums.COUPON_UNUSED);
        c.setExpireTime(LocalDateTime.now().plusDays(1));
        return c;
    }
}
