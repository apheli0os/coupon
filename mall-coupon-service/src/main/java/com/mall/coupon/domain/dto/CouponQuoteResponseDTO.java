package com.mall.coupon.domain.dto;

import com.mall.coupon.domain.vo.CouponQuoteItemVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponQuoteResponseDTO {

    private List<CouponQuoteItemVO> availableCoupons;
    private List<CouponQuoteItemVO> unavailableCoupons;
}
