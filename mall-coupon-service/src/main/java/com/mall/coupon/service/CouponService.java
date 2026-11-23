package com.mall.coupon.service;

import com.mall.coupon.domain.dto.CouponQuoteRequestDTO;
import com.mall.coupon.domain.dto.CouponQuoteResponseDTO;
import com.mall.coupon.domain.dto.CouponUseDTO;
import com.mall.coupon.domain.entity.UserCoupon;

import java.util.List;

public interface CouponService {

    /** 领券：Lua 原子预扣 + DB 落库 + 失败补偿 */
    void claim(Long templateId);

    List<UserCoupon> listMyCoupons(Integer status);

    CouponQuoteResponseDTO quoteCoupons(CouponQuoteRequestDTO dto);

    /** 核销券，条件更新保证幂等 */
    void useCoupon(CouponUseDTO dto);

    /** 过期扫描任务 */
    int expireBatch();
}
