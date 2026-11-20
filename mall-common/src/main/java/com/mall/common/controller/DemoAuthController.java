package com.mall.common.controller;

import com.mall.common.R;
import com.mall.common.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 演示用登录接口：签发 JWT，便于本地联调。
 * 生产环境应替换为真实的账号密码/短信/网关鉴权。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class DemoAuthController {

    private final JwtUtil jwtUtil;

    @GetMapping("/token")
    public R<String> token(@RequestParam(defaultValue = "1001") Long userId) {
        return R.ok(jwtUtil.generateToken(userId, "demo-user-" + userId));
    }
}
