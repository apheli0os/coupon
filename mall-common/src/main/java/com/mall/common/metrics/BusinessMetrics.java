package com.mall.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 秒杀/领券核心业务指标，Prometheus 抓取后可直接做告警 */
@Component
public class BusinessMetrics {

    private static final String PREFIX = "mall.seckill.";

    private final Counter seckillSuccess;
    private final Counter seckillSoldOut;
    private final Counter seckillRejected;
    private final Counter couponClaimSuccess;
    private final Counter couponReconcileAutoFix;
    private final Counter couponReconcileAlert;
    private final Counter couponBloomRejected;
    private final Counter duplicateSubmitRejected;
    private final Timer seckillLatency;

    public BusinessMetrics(MeterRegistry registry) {
        this.seckillSuccess = Counter.builder(PREFIX + "success.total")
                .description("秒杀抢购成功数").register(registry);
        this.seckillSoldOut = Counter.builder(PREFIX + "soldout.total")
                .description("秒杀库存不足次数").register(registry);
        this.seckillRejected = Counter.builder(PREFIX + "rejected.total")
                .description("被限流/重复购买拒绝的次数").register(registry);
        this.couponClaimSuccess = Counter.builder("mall.coupon.claim.success.total")
                .description("领券成功数").register(registry);
        this.couponReconcileAutoFix = Counter.builder("mall.coupon.reconcile.autofix.total")
                .description("优惠券对账自动修复次数").register(registry);
        this.couponReconcileAlert = Counter.builder("mall.coupon.reconcile.alert.total")
                .description("优惠券对账超阈值告警次数").register(registry);
        this.couponBloomRejected = Counter.builder("mall.coupon.bloom.rejected.total")
                .description("优惠券模板布隆过滤器拦截次数").register(registry);
        this.duplicateSubmitRejected = Counter.builder("mall.duplicate.submit.rejected.total")
                .description("防重切面拦截重复提交次数").register(registry);
        this.seckillLatency = Timer.builder(PREFIX + "latency")
                .description("秒杀请求耗时")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void recordSuccess() { seckillSuccess.increment(); }
    public void recordSoldOut() { seckillSoldOut.increment(); }
    public void recordRejected() { seckillRejected.increment(); }
    public void recordCouponClaim() { couponClaimSuccess.increment(); }
    public void recordCouponReconcileAutoFix() { couponReconcileAutoFix.increment(); }
    public void recordCouponReconcileAlert() { couponReconcileAlert.increment(); }
    public void recordCouponBloomRejected() { couponBloomRejected.increment(); }
    public void recordDuplicateSubmitRejected() { duplicateSubmitRejected.increment(); }
    public void recordLatency(Duration duration) { seckillLatency.record(duration); }
}
