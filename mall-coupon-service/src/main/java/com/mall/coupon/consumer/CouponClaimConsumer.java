package com.mall.coupon.consumer;

import com.mall.common.constants.CommonConstant;
import com.mall.common.constants.RedisKey;
import com.mall.common.message.CouponClaimMessage;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.coupon.domain.entity.UserCoupon;
import com.mall.coupon.mapper.CouponTemplateMapper;
import com.mall.coupon.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 领券异步落库消费者（批量 + 事务保护 + Token CAS 防超发）。
 *
 * <p>1) 每条消息先经 Redis token_claim.lua 原子认领，防止生产端超时回滚库存后延迟消息仍落库超发；
 * 2) 整批落库包在 {@code @Transactional} 内，确保 batchInsertIgnore 与 incrReceivedBy 原子成功/回滚，
 *    避免部分失败重投时 received 永久少计；
 * 3) 重复消费时主键冲突被 INSERT IGNORE 跳过，received 按实际新增行数累加。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponClaimConsumer {

    private final UserCouponMapper userCouponMapper;
    private final CouponTemplateMapper templateMapper;
    private final LuaScriptExecutor luaExecutor;
    private final StringRedisTemplate stringRedisTemplate;

    @KafkaListener(topics = CommonConstant.MQ_TOPIC_COUPON_CLAIM,
            groupId = CommonConstant.MQ_GROUP_COUPON_CLAIM)
    @Transactional(rollbackFor = Exception.class)
    public void onMessage(List<CouponClaimMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        List<CouponClaimMessage> validMessages = new ArrayList<>(messages.size());
        for (CouponClaimMessage msg : messages) {
            String tokenKey = RedisKey.couponClaimToken(msg.getTemplateId(), msg.getId());
            long[] claimRes = luaExecutor.parse(luaExecutor.execute(
                    LuaScriptExecutor.SCRIPT_TOKEN_CLAIM,
                    List.of(tokenKey),
                    String.valueOf(msg.getId()),
                    RedisKey.TOKEN_TTL_SECONDS));
            if (claimRes[0] != 1L) {
                if (userCouponMapper.selectById(msg.getId()) != null) {
                    log.info("领券记录已落库，忽略凭证已过期的重复投递 templateId={} userId={} couponId={}",
                            msg.getTemplateId(), msg.getUserId(), msg.getId());
                } else {
                    compensateExpiredTokenQuietly(msg, tokenKey);
                    log.warn("领券凭证缺失、已被生产端回滚或已过期，拒绝落库并已触发幂等补偿校验 templateId={} userId={} couponId={}",
                            msg.getTemplateId(), msg.getUserId(), msg.getId());
                }
                continue;
            }
            validMessages.add(msg);
        }
        if (validMessages.isEmpty()) {
            return;
        }

        Map<Long, List<CouponClaimMessage>> grouped = validMessages.stream()
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
                log.error("[ALERT] 模板已领计数触及库存上限，回滚事务以防静默超发 templateId={} inserted={}",
                        entry.getKey(), inserted);
                throw new IllegalStateException("模板已领计数触及库存上限 templateId=" + entry.getKey()
                        + " inserted=" + inserted);
            }
        }
        scheduleMarkTokensConsumed(validMessages);
        log.info("领券批量落库完成 received={} valid={} inserted={}",
                messages.size(), validMessages.size(), totalInserted);
    }

    private void compensateExpiredTokenQuietly(CouponClaimMessage msg, String tokenKey) {
        try {
            long[] res = luaExecutor.parse(luaExecutor.execute(
                    LuaScriptExecutor.SCRIPT_COUPON_EXPIRE_COMPENSATE,
                    List.of(
                            RedisKey.couponStock(msg.getTemplateId()),
                            RedisKey.couponClaimHash(msg.getTemplateId()),
                            tokenKey,
                            RedisKey.couponRollbackGuard(msg.getTemplateId(), msg.getId())),
                    String.valueOf(msg.getUserId()),
                    RedisKey.ROLLBACK_GUARD_TTL_SECONDS));
            if (res[0] == 0L) {
                log.info("领券凭证 TTL 过期，消费端已幂等归还预扣库存 templateId={} userId={} couponId={}",
                        msg.getTemplateId(), msg.getUserId(), msg.getId());
            }
        } catch (Exception e) {
            log.error("领券凭证过期补偿归还 Redis 库存失败 templateId={} userId={} couponId={}",
                    msg.getTemplateId(), msg.getUserId(), msg.getId(), e);
        }
    }

    private void scheduleMarkTokensConsumed(List<CouponClaimMessage> messages) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    markTokensConsumed(messages);
                }
            });
        } else {
            markTokensConsumed(messages);
        }
    }

    private void markTokensConsumed(List<CouponClaimMessage> messages) {
        Duration ttl = Duration.ofSeconds(RedisKey.CONSUMED_TOKEN_TTL_SECONDS);
        for (CouponClaimMessage msg : messages) {
            try {
                String tokenKey = RedisKey.couponClaimToken(msg.getTemplateId(), msg.getId());
                stringRedisTemplate.opsForValue().set(tokenKey, "CONSUMED:" + msg.getId(), ttl);
            } catch (Exception e) {
                log.warn("回写领券已消费凭证失败（不影响 DB 主键幂等） couponId={}", msg.getId(), e);
            }
        }
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
