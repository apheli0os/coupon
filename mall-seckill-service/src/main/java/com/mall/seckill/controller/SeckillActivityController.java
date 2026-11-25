package com.mall.seckill.controller;

import com.mall.common.R;
import com.mall.common.context.UserContext;
import com.mall.common.idempotent.NoDuplicateSubmit;
import com.mall.seckill.domain.dto.SeckillActivityCreateDTO;
import com.mall.seckill.domain.entity.SeckillActivity;
import com.mall.seckill.service.SeckillActivityService;
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

@Tag(name = "秒杀活动管理")
@RestController
@RequestMapping("/api/seckill/activities")
@RequiredArgsConstructor
public class SeckillActivityController {

    private final SeckillActivityService activityService;

    @Operation(summary = "创建秒杀活动（管理员）")
    @PostMapping
    @NoDuplicateSubmit(lockSeconds = 3)
    public R<Long> create(@Valid @RequestBody SeckillActivityCreateDTO dto) {
        UserContext.requireAdmin();
        return R.ok(activityService.create(dto));
    }

    @Operation(summary = "发布活动（库存预热，管理员）")
    @PostMapping("/{id}/publish")
    @NoDuplicateSubmit(unlockOnFinish = true)
    public R<Void> publish(@PathVariable Long id) {
        UserContext.requireAdmin();
        activityService.publish(id);
        return R.ok();
    }

    @Operation(summary = "活动详情")
    @GetMapping("/{id}")
    public R<SeckillActivity> detail(@PathVariable Long id) {
        return R.ok(activityService.getDetail(id));
    }

    @Operation(summary = "活动列表")
    @GetMapping
    public R<List<SeckillActivity>> list() {
        return R.ok(activityService.listAll());
    }
}
