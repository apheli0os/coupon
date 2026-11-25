package com.mall.coupon.controller;

import com.mall.common.PageResult;
import com.mall.common.R;
import com.mall.common.context.UserContext;
import com.mall.common.idempotent.NoDuplicateSubmit;
import com.mall.coupon.domain.dto.CouponTemplateCreateDTO;
import com.mall.coupon.domain.dto.CouponTemplateIncreaseStockDTO;
import com.mall.coupon.domain.dto.CouponTemplatePageQueryDTO;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.domain.entity.CouponTemplateAuditLog;
import com.mall.coupon.service.CouponTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "优惠券模板管理")
@RestController
@RequestMapping("/api/coupon/templates")
@RequiredArgsConstructor
public class CouponTemplateController {

    private final CouponTemplateService templateService;

    @Operation(summary = "创建券模板（管理员）")
    @PostMapping
    @NoDuplicateSubmit(lockSeconds = 3)
    public R<Long> create(@Valid @RequestBody CouponTemplateCreateDTO dto) {
        UserContext.requireAdmin();
        return R.ok(templateService.create(dto));
    }

    @Operation(summary = "发布活动（预热库存，管理员）")
    @PostMapping("/{id}/publish")
    @NoDuplicateSubmit(unlockOnFinish = true)
    public R<Void> publish(@PathVariable Long id) {
        UserContext.requireAdmin();
        templateService.publish(id);
        return R.ok();
    }

    @PostMapping("/{id}/increase-stock")
    @NoDuplicateSubmit(unlockOnFinish = true)
    public R<Void> increaseStock(
            @PathVariable Long id,
            @Valid @RequestBody CouponTemplateIncreaseStockDTO dto) {
        UserContext.requireAdmin();
        templateService.increaseStock(id, dto.getDelta());
        return R.ok();
    }

    @PostMapping("/{id}/terminate")
    @NoDuplicateSubmit(unlockOnFinish = true)
    public R<Void> terminate(@PathVariable Long id) {
        UserContext.requireAdmin();
        templateService.terminate(id);
        return R.ok();
    }

    @GetMapping("/{id}/audits")
    public R<List<CouponTemplateAuditLog>> audits(@PathVariable Long id) {
        UserContext.requireAdmin();
        return R.ok(templateService.listAudits(id));
    }

    @GetMapping("/page")
    public R<PageResult<CouponTemplate>> page(CouponTemplatePageQueryDTO query) {
        return R.ok(templateService.pageQuery(query));
    }

    @Operation(summary = "查询模板详情")
    @GetMapping("/{id}")
    public R<CouponTemplate> detail(@PathVariable Long id) {
        return R.ok(templateService.getDetail(id));
    }

    @Operation(summary = "模板列表")
    @GetMapping
    public R<List<CouponTemplate>> list() {
        return R.ok(templateService.listAll());
    }
}
