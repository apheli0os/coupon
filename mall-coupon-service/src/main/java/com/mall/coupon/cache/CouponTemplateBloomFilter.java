package com.mall.coupon.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.constants.RedisKey;
import com.mall.common.metrics.BusinessMetrics;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.mapper.CouponTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponTemplateBloomFilter {

    private static final long EXPECTED_INSERTIONS = 100_000L;
    private static final double FALSE_PROBABILITY = 0.001D;

    private final RedissonClient redissonClient;
    private final CouponTemplateMapper templateMapper;
    private final BusinessMetrics businessMetrics;
    private volatile boolean ready;

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        try {
            RBloomFilter<Long> bloom = redissonClient.getBloomFilter(RedisKey.couponTemplateBloomFilter());
            bloom.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);
            List<CouponTemplate> rows = templateMapper.selectList(
                    new LambdaQueryWrapper<CouponTemplate>()
                            .select(CouponTemplate::getId)
                            .last("LIMIT " + (EXPECTED_INSERTIONS + 1)));
            if (rows != null && rows.size() > EXPECTED_INSERTIONS) {
                this.ready = false;
                log.warn("券模板数量超过布隆过滤器容量上限 {}，保持直通回源", EXPECTED_INSERTIONS);
                return;
            }
            if (rows != null) {
                for (CouponTemplate row : rows) {
                    if (row != null && row.getId() != null) {
                        bloom.add(row.getId());
                    }
                }
            }
            this.ready = true;
        } catch (Exception e) {
            this.ready = false;
            log.warn("初始化券模板布隆过滤器失败，降级直通回源", e);
        }
    }

    public void add(Long templateId) {
        if (templateId == null || !ready) {
            return;
        }
        try {
            RBloomFilter<Long> bloom = redissonClient.getBloomFilter(RedisKey.couponTemplateBloomFilter());
            if (!bloom.isExists()) {
                this.ready = false;
                return;
            }
            bloom.add(templateId);
        } catch (Exception e) {
            this.ready = false;
            log.warn("写入券模板布隆过滤器失败，降级直通回源 templateId={}", templateId, e);
        }
    }

    public boolean mightContain(Long templateId) {
        if (templateId == null) {
            return false;
        }
        if (!ready) {
            return true;
        }
        try {
            RBloomFilter<Long> bloom = redissonClient.getBloomFilter(RedisKey.couponTemplateBloomFilter());
            if (!bloom.isExists()) {
                this.ready = false;
                return true;
            }
            boolean present = bloom.contains(templateId);
            if (!present) {
                businessMetrics.recordCouponBloomRejected();
            }
            return present;
        } catch (Exception e) {
            this.ready = false;
            log.warn("查询券模板布隆过滤器失败，降级放行 templateId={}", templateId, e);
            return true;
        }
    }
}
