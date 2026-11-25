package com.mall.coupon.service;

import com.mall.common.PageResult;
import com.mall.coupon.domain.dto.CouponTemplateCreateDTO;
import com.mall.coupon.domain.dto.CouponTemplatePageQueryDTO;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.domain.entity.CouponTemplateAuditLog;

import java.util.List;

public interface CouponTemplateService {

    Long create(CouponTemplateCreateDTO dto);

    /** 发布活动：加锁 + 状态切换 + 库存预热到 Redis */
    void publish(Long templateId);

    /** 缓存优先读取详情 */
    CouponTemplate getDetail(Long templateId);

    List<CouponTemplate> listAll();

    void increaseStock(Long templateId, int delta);

    void terminate(Long templateId);

    PageResult<CouponTemplate> pageQuery(CouponTemplatePageQueryDTO query);

    List<CouponTemplateAuditLog> listAudits(Long templateId);
}
