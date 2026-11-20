package com.mall.coupon.controller;

import com.mall.common.R;
import com.mall.coupon.domain.dto.CouponUseDTO;
import com.mall.coupon.domain.entity.UserCoupon;
import com.mall.coupon.service.CouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "用户优惠券")
@RestController
@RequestMapping("/api/coupon")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    @Operation(summary = "领取优惠券")
    @PostMapping("/claim/{templateId}")
    public R<Void> claim(@PathVariable Long templateId) {
        couponService.claim(templateId);
        return R.ok();
    }

    @Operation(summary = "我的优惠券")
    @GetMapping("/my")
    public R<List<UserCoupon>> myCoupons(@RequestParam(required = false) Integer status) {
        return R.ok(couponService.listMyCoupons(status));
    }

    @Operation(summary = "核销优惠券")
    @PostMapping("/use")
    public R<Void> use(@Valid @RequestBody CouponUseDTO dto) {
        couponService.useCoupon(dto);
        return R.ok();
    }
}
