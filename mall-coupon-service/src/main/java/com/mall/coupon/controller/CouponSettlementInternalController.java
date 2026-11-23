package com.mall.coupon.controller;

import com.mall.common.R;
import com.mall.common.message.CouponLockRequest;
import com.mall.common.message.CouponLockResult;
import com.mall.common.message.CouponUnlockRequest;
import com.mall.coupon.service.CouponSettlementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/coupon/internal/settlement")
@RequiredArgsConstructor
public class CouponSettlementInternalController {

    private final CouponSettlementService settlementService;

    @PostMapping("/lock")
    public R<CouponLockResult> lock(@Valid @RequestBody CouponLockRequest request) {
        return R.ok(settlementService.lockCoupon(request));
    }

    @PostMapping("/unlock")
    public R<Void> unlock(@Valid @RequestBody CouponUnlockRequest request) {
        settlementService.unlockCoupon(request);
        return R.ok();
    }
}
