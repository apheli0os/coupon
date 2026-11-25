package com.mall.coupon.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.constants.CommonConstant;
import com.mall.common.context.UserContext;
import com.mall.common.util.IdGenerator;
import com.mall.coupon.domain.entity.CouponTemplateAuditLog;
import com.mall.coupon.mapper.CouponTemplateAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponTemplateAuditService {

    private static final int MAX_DETAIL_LENGTH = 500;

    private final CouponTemplateAuditLogMapper auditLogMapper;
    private final IdGenerator idGenerator;

    public void record(Long templateId, String action, String detail) {
        if (templateId == null || action == null) {
            return;
        }
        try {
            CouponTemplateAuditLog entry = new CouponTemplateAuditLog();
            entry.setId(idGenerator.nextId());
            entry.setTemplateId(templateId);
            entry.setOperatorId(UserContext.get());
            entry.setAction(action);
            entry.setDetail(truncate(detail));
            entry.setTraceId(MDC.get(CommonConstant.MDC_TRACE_ID));
            entry.setCreateTime(LocalDateTime.now());
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("写入券模板审计日志失败 templateId={} action={}", templateId, action, e);
        }
    }

    public List<CouponTemplateAuditLog> listByTemplate(Long templateId) {
        if (templateId == null) {
            return List.of();
        }
        return auditLogMapper.selectList(new LambdaQueryWrapper<CouponTemplateAuditLog>()
                .eq(CouponTemplateAuditLog::getTemplateId, templateId)
                .orderByDesc(CouponTemplateAuditLog::getCreateTime));
    }

    private String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= MAX_DETAIL_LENGTH ? detail : detail.substring(0, MAX_DETAIL_LENGTH);
    }
}
