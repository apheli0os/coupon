package com.mall.common.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BusinessMetricsTest {

    @Test
    void countersAndTimers_shouldRecordAllTier2Metrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);

        metrics.recordSuccess();
        metrics.recordSoldOut();
        metrics.recordRejected();
        metrics.recordCouponClaim();
        metrics.recordCouponReconcileAutoFix();
        metrics.recordCouponReconcileAlert();
        metrics.recordCouponBloomRejected();
        metrics.recordDuplicateSubmitRejected();
        metrics.recordLatency(Duration.ofMillis(25));

        assertEquals(1.0, registry.get("mall.seckill.success.total").counter().count());
        assertEquals(1.0, registry.get("mall.seckill.soldout.total").counter().count());
        assertEquals(1.0, registry.get("mall.seckill.rejected.total").counter().count());
        assertEquals(1.0, registry.get("mall.coupon.claim.success.total").counter().count());
        assertEquals(1.0, registry.get("mall.coupon.reconcile.autofix.total").counter().count());
        assertEquals(1.0, registry.get("mall.coupon.reconcile.alert.total").counter().count());
        assertEquals(1.0, registry.get("mall.coupon.bloom.rejected.total").counter().count());
        assertEquals(1.0, registry.get("mall.duplicate.submit.rejected.total").counter().count());
        assertEquals(1L, registry.get("mall.seckill.latency").timer().count());
    }
}
