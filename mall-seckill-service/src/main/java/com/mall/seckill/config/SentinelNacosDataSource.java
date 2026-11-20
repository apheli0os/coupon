package com.mall.seckill.config;

import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Sentinel 流控规则持久化到 Nacos（生产开启：sentinel.nacos.enabled=true）。
 * 规则变更在 Nacos 控制台修改即可实时生效，无需重启应用。
 * 关闭时由 SentinelConfig 的本地规则兜底。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "sentinel.nacos.enabled", havingValue = "true")
public class SentinelNacosDataSource {

    @Value("${sentinel.nacos.server-addr:127.0.0.1:8848}")
    private String serverAddr;

    @Value("${sentinel.nacos.group-id:MALL_SENTINEL_GROUP}")
    private String groupId;

    @Value("${sentinel.nacos.data-id:seckill-sentinel-flow.json}")
    private String dataId;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        Converter<String, List<FlowRule>> converter = source -> {
            try {
                return objectMapper.readValue(source, new TypeReference<List<FlowRule>>() {});
            } catch (Exception e) {
                throw new IllegalArgumentException("解析 Sentinel 流控规则失败: " + source, e);
            }
        };
        NacosDataSource<List<FlowRule>> ds =
                new NacosDataSource<>(serverAddr, groupId, dataId, converter);
        FlowRuleManager.register2Property(ds.getProperty());
        log.info("Sentinel 流控规则已接入 Nacos: {}/{}/{}", serverAddr, groupId, dataId);
    }
}
