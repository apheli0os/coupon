package com.mall.seckill.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 秒杀入口限流（本地兜底）：超过阈值直接快速失败，保护 Redis 与下游订单服务。
 * 生产环境置 sentinel.nacos.enabled=true，规则由 Nacos 动态下发（见 SentinelRuleDataSource），本类不生效。
 */
@Configuration
@ConditionalOnProperty(name = "sentinel.nacos.enabled", havingValue = "false", matchIfMissing = true)
public class SentinelConfig {

    @Value("${mall.sentinel.seckill-qps:100}")
    private int seckillQps;

    @PostConstruct
    public void initFlowRules() {
        FlowRule rule = new FlowRule("seckill");
        rule.setCount(seckillQps);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setLimitApp("default");
        FlowRuleManager.loadRules(List.of(rule));
    }
}
