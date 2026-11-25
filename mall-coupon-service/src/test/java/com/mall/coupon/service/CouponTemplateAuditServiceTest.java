package com.mall.coupon.service;

import com.mall.common.constants.CommonConstant;
import com.mall.common.context.UserContext;
import com.mall.common.util.IdGenerator;
import com.mall.coupon.domain.entity.CouponTemplateAuditLog;
import com.mall.coupon.mapper.CouponTemplateAuditLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponTemplateAuditServiceTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
        MDC.clear();
    }

    @Test
    void recordAndList_shouldCaptureOperatorTraceAndAction() {
        CouponTemplateAuditLogMapper mapper = mock(CouponTemplateAuditLogMapper.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        when(idGenerator.nextId()).thenReturn(8001L);

        UserContext.set(1001L, CommonConstant.ROLE_ADMIN);
        MDC.put(CommonConstant.MDC_TRACE_ID, "trace-audit-1");
        CouponTemplateAuditService service = new CouponTemplateAuditService(mapper, idGenerator);
        service.record(100L, "CREATE", "total=100");

        ArgumentCaptor<CouponTemplateAuditLog> captor = ArgumentCaptor.forClass(CouponTemplateAuditLog.class);
        verify(mapper).insert(captor.capture());
        assertEquals(8001L, captor.getValue().getId());
        assertEquals(100L, captor.getValue().getTemplateId());
        assertEquals(1001L, captor.getValue().getOperatorId());
        assertEquals("CREATE", captor.getValue().getAction());
        assertEquals("trace-audit-1", captor.getValue().getTraceId());

        when(mapper.selectList(any())).thenReturn(List.of(captor.getValue()));
        assertEquals(1, service.listByTemplate(100L).size());
    }
}
