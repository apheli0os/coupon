package com.mall.seckill.controller;

import com.mall.common.R;
import com.mall.seckill.domain.vo.SeckillResultVO;
import com.mall.seckill.service.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "秒杀抢购")
@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;

    @Operation(summary = "抢购（返回订单号，异步落单）")
    @PostMapping("/{activityId}/buy")
    public R<String> buy(@PathVariable Long activityId) {
        return R.ok(seckillService.seckill(activityId));
    }

    @Operation(summary = "轮询抢购结果")
    @GetMapping("/result")
    public R<SeckillResultVO> result(@RequestParam Long activityId) {
        return R.ok(seckillService.result(activityId));
    }
}
