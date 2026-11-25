package com.mall.common.monitoring;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrometheusAlertRulesTest {

    @Test
    void prometheusAlertFiles_shouldParseCleanlyAndIncludeCriticalIncidentRules() throws Exception {
        for (Path path : List.of(
                resolveRepoPath("monitoring/prometheus/rules/mall-alerts.yml"),
                resolveRepoPath("k8s/alerts.yml"))) {
            assertTrue(Files.exists(path), "Missing alerts file: " + path);
            Map<?, ?> root;
            try (InputStream in = Files.newInputStream(path)) {
                root = new Yaml().load(in);
            }
            assertNotNull(root);
            List<?> groups = (List<?>) root.get("groups");
            assertNotNull(groups);
            assertFalse(groups.isEmpty());

            Set<String> alerts = new HashSet<>();
            String soldOutSeverity = null;
            for (Object gObj : groups) {
                Map<?, ?> group = (Map<?, ?>) gObj;
                List<?> rules = (List<?>) group.get("rules");
                assertNotNull(rules);
                for (Object rObj : rules) {
                    Map<?, ?> rule = (Map<?, ?>) rObj;
                    String alertName = String.valueOf(rule.get("alert"));
                    String expr = String.valueOf(rule.get("expr"));
                    String forDuration = String.valueOf(rule.get("for"));
                    Map<?, ?> labels = (Map<?, ?>) rule.get("labels");
                    assertFalse(alertName.isBlank());
                    assertBalancedDelimiters(expr, path + " -> " + alertName);
                    assertTrue(forDuration.matches("\\d+[smh]"), "Invalid for duration: " + forDuration);
                    assertNotNull(labels);
                    alerts.add(alertName);
                    if ("SeckillSoldOutSpike".equals(alertName)) {
                        soldOutSeverity = String.valueOf(labels.get("severity"));
                    }
                }
            }
            assertTrue(alerts.contains("CouponStockReconcileDriftAlert"));
            assertTrue(alerts.contains("CouponStockReconcileFrequentAutoFix"));
            assertTrue(alerts.contains("GatewayCircuitBreakerOpen"));
            assertEquals("info", soldOutSeverity);
        }
    }

    private Path resolveRepoPath(String relative) {
        Path direct = Path.of(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        return Path.of("..").resolve(relative);
    }

    private void assertBalancedDelimiters(String expr, String context) {
        int parens = 0;
        int braces = 0;
        int brackets = 0;
        for (char c : expr.toCharArray()) {
            switch (c) {
                case '(' -> parens++;
                case ')' -> parens--;
                case '{' -> braces++;
                case '}' -> braces--;
                case '[' -> brackets++;
                case ']' -> brackets--;
                default -> {
                }
            }
            assertTrue(parens >= 0 && braces >= 0 && brackets >= 0, context);
        }
        assertEquals(0, parens, context);
        assertEquals(0, braces, context);
        assertEquals(0, brackets, context);
    }
}
