package com.mall.coupon.service.impl;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.ResultCode;
import com.mall.common.constants.CommonConstant;
import com.mall.common.constants.RedisKey;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BizException;
import com.mall.common.message.CouponClaimMessage;
import com.mall.common.metrics.BusinessMetrics;
import com.mall.common.util.IdGenerator;
import com.mall.common.util.LuaScriptExecutor;
import com.mall.coupon.domain.dto.CouponUseDTO;
import com.mall.coupon.domain.entity.CouponTemplate;
import com.mall.coupon.domain.entity.UserCoupon;
import com.mall.coupon.domain.enums.CouponEnums;
import com.mall.coupon.feign.OrderFeignClient;
import com.mall.coupon.mapper.UserCouponMapper;
import com.mall.coupon.service.CouponService;
import com.mall.coupon.service.CouponTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    private final CouponTemplateService templateService;
    private final UserCouponMapper userCouponMapper;
    private final LuaScriptExecutor luaExecutor;
    private final StringRedisTemplate stringRedisTemplate;
    private final IdGenerator idGenerator;
    private final OrderFeignClient orderFeignClient;
    private final BusinessMetrics metrics;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 领券：Redis Lua 原子预扣 + Kafka 异步落库。
     *
     * <p>请求线程只做「读缓存 → Lua 预扣 → 发消息」，不再同步访问 MySQL；
     * DB 查重与 incrReceived 全部下移到 {@code CouponClaimConsumer} 批量处理。
     * Kafka 发送失败立即回滚 Redis，保证「有预扣必有消息」。</p>
     */
    @Override
    @SentinelResource(value = "couponClaim", blockHandler = "claimBlockHandler")
    public void claim(Long templateId) {
        long userId = UserContext.getRequired();
        CouponTemplate template = templateService.getDetail(templateId);
        if (template == null) {
            throw new BizException(ResultCode.COUPON_TEMPLATE_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(template.getStartTime())) {
            throw new BizException(ResultCode.COUPON_NOT_STARTED);
        }
        if (now.isAfter(template.getEndTime())) {
            throw new BizException(ResultCode.COUPON_ENDED);
        }

        // KEYS: 库存 / 用户已领 Hash / 活动状态；Lua 内原子校验限领与库存并 DECR
        List<String> keys = List.of(
                RedisKey.couponStock(templateId),
                RedisKey.couponClaimHash(templateId),
                RedisKey.couponStatus(templateId));
        long[] res = luaExecutor.parse(luaExecutor.execute(
                LuaScriptExecutor.SCRIPT_COUPON_CLAIM, keys,
                String.valueOf(userId), template.getPerUserLimit()));
        switch ((int) res[0]) {
            case 0 -> { /* 预扣成功 */ }
            case -1 -> throw new BizException(ResultCode.COUPON_NOT_STARTED);
            case -2 -> throw new BizException(ResultCode.COUPON_LIMIT_EXCEEDED);
            case -3 -> throw new BizException(ResultCode.COUPON_SOLD_OUT);
            default -> throw new BizException(ResultCode.SYSTEM_ERROR);
        }

        long userCouponId = idGenerator.nextId();
        CouponClaimMessage message = new CouponClaimMessage();
        message.setId(userCouponId);
        message.setUserId(userId);
        message.setTemplateId(templateId);
        message.setCouponName(template.getName());
        message.setType(template.getType());
        message.setDiscountValue(template.getDiscountValue());
        message.setThreshold(template.getThreshold());
        message.setStatus(CouponEnums.COUPON_UNUSED);
        message.setCreateTime(now);
        message.setUpdateTime(now);
        LocalDateTime validUntil = now.plusDays(template.getValidDays());
        message.setExpireTime(validUntil.isAfter(template.getEndTime()) ? template.getEndTime() : validUntil);

        try {
            // 以 userCouponId 作为分区键：同一张券顺序一致，且天然打散到各分区
            SendResult<String, Object> result = kafkaTemplate.send(
                    CommonConstant.MQ_TOPIC_COUPON_CLAIM,
                    String.valueOf(userCouponId), message).get(3, TimeUnit.SECONDS);
            var metadata = result.getRecordMetadata();
            log.debug("领券消息已投递 topic={} partition={} offset={} userId={} couponId={}",
                    metadata.topic(), metadata.partition(), metadata.offset(), userId, userCouponId);
        } catch (Exception e) {
            // 消息没落地，必须归还 Redis 预扣，否则出现「扣了库存却没有券」
            rollbackClaim(templateId, userId);
            log.error("领券消息发送失败，已回滚预扣 templateId={} userId={}", templateId, userId, e);
            throw new BizException(ResultCode.MQ_SEND_FAILED);
        }

        metrics.recordCouponClaim();
        log.info("领券预扣成功 userId={} templateId={} userCouponId={}", userId, templateId, userCouponId);
    }

    /** Sentinel 限流降级 */
    public void claimBlockHandler(Long templateId, BlockException e) {
        throw new BizException(ResultCode.TOO_MANY_REQUESTS);
    }

    private void rollbackClaim(Long templateId, long userId) {
        try {
            luaExecutor.execute(LuaScriptExecutor.SCRIPT_COUPON_ROLLBACK,
                    List.of(RedisKey.couponStock(templateId), RedisKey.couponClaimHash(templateId)),
                    String.valueOf(userId));
        } catch (Exception ex) {
            // 补偿失败必须告警，人工介入对账
            log.error("领券补偿失败 templateId={} userId={}", templateId, userId, ex);
        }
    }

    @Override
    public List<UserCoupon> listMyCoupons(Integer status) {
        long userId = UserContext.getRequired();
        LambdaQueryWrapper<UserCoupon> query = new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .orderByDesc(UserCoupon::getCreateTime);
        if (status != null) {
            query.eq(UserCoupon::getStatus, status);
        }
        return userCouponMapper.selectList(query);
    }

    @Override
    public void useCoupon(CouponUseDTO dto) {
        long userId = UserContext.getRequired();
        UserCoupon coupon = userCouponMapper.selectById(dto.getUserCouponId());
        if (coupon == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        if (!coupon.getUserId().equals(userId)) {
            throw new BizException(ResultCode.COUPON_NOT_OWNED);
        }
        if (coupon.getStatus() != CouponEnums.COUPON_UNUSED) {
            throw new BizException(ResultCode.COUPON_ALREADY_USED);
        }
        if (coupon.getExpireTime() != null && coupon.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BizException(ResultCode.COUPON_EXPIRED);
        }
        BigDecimal threshold = coupon.getThreshold() == null ? BigDecimal.ZERO : coupon.getThreshold();
        if (dto.getOrderAmount().compareTo(threshold) < 0) {
            throw new BizException(ResultCode.COUPON_THRESHOLD_NOT_MET);
        }

        // 订单存在性校验；订单服务不可用时走降级，不影响主流程
        try {
            var exists = orderFeignClient.exists(dto.getOrderId());
            if (exists != null && exists.getData() != null && !exists.getData()) {
                throw new BizException(ResultCode.ORDER_NOT_FOUND);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("订单校验跳过 orderId={} cause={}", dto.getOrderId(), e.getMessage());
        }

        int rows = userCouponMapper.useCoupon(dto.getUserCouponId(), userId, dto.getOrderId());
        if (rows == 0) {
            throw new BizException(ResultCode.COUPON_ALREADY_USED);
        }
        log.info("优惠券核销成功 userId={} couponId={} orderId={}", userId, dto.getUserCouponId(), dto.getOrderId());
    }

    @Override
    public int expireBatch() {
        return userCouponMapper.expireBatch(LocalDateTime.now());
    }
}
