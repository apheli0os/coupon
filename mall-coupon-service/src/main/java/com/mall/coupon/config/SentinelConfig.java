package com.mall.coupon.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 领券接口限流规则（本地兜底）。
 * 生产环境置 sentinel.nacos.enabled=true，规则由 Nacos 动态下发（见 SentinelRuleDataSource），本类不生效。
 */
@Configuration
@ConditionalOnProperty(name = "sentinel.nacos.enabled", havingValue = "false", matchIfMissing = true)
public class SentinelConfig {

    @Value("${mall.sentinel.coupon-claim-qps:200}")
    private int claimQps;

    @PostConstruct
    public void initFlowRules() {
        FlowRule rule = new FlowRule("couponClaim");
        rule.setCount(claimQps);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setLimitApp("default");
        FlowRuleManager.loadRules(List.of(rule));
    }
}
