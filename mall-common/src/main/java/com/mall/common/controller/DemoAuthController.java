package com.mall.common.controller;

import com.mall.common.R;
import com.mall.common.constants.CommonConstant;
import com.mall.common.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 演示用登录接口：仅在 dev / local profile 下启用，防止生产环境暴露免密签发任意 userId Token 的漏洞。
 * 生产环境应替换为真实的账号密码/短信/网关鉴权。
 */
@Profile({"dev", "local"})
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class DemoAuthController {

    private final JwtUtil jwtUtil;

    @GetMapping("/token")
    public R<String> token(@RequestParam(defaultValue = "1001") Long userId,
                           @RequestParam(defaultValue = CommonConstant.ROLE_USER) String role) {
        return R.ok(jwtUtil.generateToken(userId, "demo-user-" + userId, role));
    }
}
