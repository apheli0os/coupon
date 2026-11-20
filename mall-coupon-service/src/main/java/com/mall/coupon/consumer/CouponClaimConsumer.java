package com.mall.coupon.consumer;

import com.mall.common.constants.CommonConstant;
import com.mall.common.message.CouponClaimMessage;
import com.mall.coupon.domain.entity.UserCoupon;
import com.mall.coupon.mapper.CouponTemplateMapper;
import com.mall.coupon.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 领券异步落库消费者（批量）。
 *
 * <p>按模板分组后一次 INSERT IGNORE 批量写入，再用「实际新增行数」累加模板 received。
 * 重复消费时主键冲突被忽略、received 不重复累加，实现精确一次效果。
 * ack-mode=batch：只有本批全部处理成功才提交 offset，失败由 Kafka 重投。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponClaimConsumer {

    private final UserCouponMapper userCouponMapper;
    private final CouponTemplateMapper templateMapper;

    @KafkaListener(topics = CommonConstant.MQ_TOPIC_COUPON_CLAIM,
            groupId = CommonConstant.MQ_GROUP_COUPON_CLAIM)
    public void onMessage(List<CouponClaimMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Map<Long, List<CouponClaimMessage>> grouped = messages.stream()
                .collect(Collectors.groupingBy(CouponClaimMessage::getTemplateId));

        int totalInserted = 0;
        for (Map.Entry<Long, List<CouponClaimMessage>> entry : grouped.entrySet()) {
            List<UserCoupon> entities = entry.getValue().stream()
                    .map(this::toEntity)
                    .toList();
            int inserted = userCouponMapper.batchInsertIgnore(entities);
            totalInserted += inserted;
            if (inserted == 0) {
                continue;
            }
            int updated = templateMapper.incrReceivedBy(entry.getKey(), inserted);
            if (updated == 0) {
                log.error("[ALERT] 模板已领计数触及库存上限，需人工对账 templateId={} inserted={}",
                        entry.getKey(), inserted);
            }
        }
        log.info("领券批量落库完成 received={} inserted={}", messages.size(), totalInserted);
    }

    private UserCoupon toEntity(CouponClaimMessage m) {
        UserCoupon coupon = new UserCoupon();
        coupon.setId(m.getId());
        coupon.setUserId(m.getUserId());
        coupon.setTemplateId(m.getTemplateId());
        coupon.setCouponName(m.getCouponName());
        coupon.setType(m.getType());
        coupon.setDiscountValue(m.getDiscountValue());
        coupon.setThreshold(m.getThreshold());
        coupon.setStatus(m.getStatus());
        coupon.setExpireTime(m.getExpireTime());
        coupon.setCreateTime(m.getCreateTime());
        coupon.setUpdateTime(m.getUpdateTime());
        return coupon;
    }
}
