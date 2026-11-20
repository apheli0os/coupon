package com.mall.coupon.controller;

import com.mall.common.R;
import com.mall.coupon.domain.dto.CouponTemplateCreateDTO;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.service.CouponTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "优惠券模板管理")
@RestController
@RequestMapping("/api/coupon/templates")
@RequiredArgsConstructor
public class CouponTemplateController {

    private final CouponTemplateService templateService;

    @Operation(summary = "创建券模板")
    @PostMapping
    public R<Long> create(@Valid @RequestBody CouponTemplateCreateDTO dto) {
        return R.ok(templateService.create(dto));
    }

    @Operation(summary = "发布活动（预热库存）")
    @PostMapping("/{id}/publish")
    public R<Void> publish(@PathVariable Long id) {
        templateService.publish(id);
        return R.ok();
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
