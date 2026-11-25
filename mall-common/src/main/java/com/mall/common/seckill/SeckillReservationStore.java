package com.mall.common.seckill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.constants.RedisKey;
import com.mall.common.message.SeckillOrderMessage;
import com.mall.common.util.LuaScriptExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillReservationStore {

    private static final long PENDING_MILLIS = Duration.ofSeconds(RedisKey.TOKEN_TTL_SECONDS).toMillis();
    private static final long PROCESSING_RETRY_MILLIS = Duration.ofSeconds(30).toMillis();

    private final LuaScriptExecutor luaExecutor;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public long[] reserve(SeckillOrderMessage message) {
        Long activityId = message.getActivityId();
        String orderId = message.getOrderId();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化秒杀预占消息", e);
        }
        List<String> keys = stockKeys(activityId);
        keys.add(RedisKey.seckillUsers(activityId));
        keys.add(RedisKey.seckillStatus(activityId));
        keys.add(RedisKey.seckillReservationOwners(activityId));
        keys.add(RedisKey.seckillReservation(activityId, orderId));
        keys.add(RedisKey.seckillReservationsDue(activityId));
        return luaExecutor.parse(luaExecutor.execute(LuaScriptExecutor.SCRIPT_SECKILL, keys,
                message.getUserId(), RedisKey.bucketOf(message.getUserId()), RedisKey.SECKILL_STOCK_BUCKETS,
                orderId, PENDING_MILLIS, payload));
    }

    public boolean claim(SeckillOrderMessage message) {
        List<String> keys = reservationKeys(message.getActivityId(), message.getOrderId());
        long[] result = luaExecutor.parse(luaExecutor.execute(LuaScriptExecutor.SCRIPT_SECKILL_RESERVATION_CLAIM,
                keys, message.getOrderId(), message.getUserId(), message.getStockBucket(), PROCESSING_RETRY_MILLIS));
        return result[0] == 1;
    }

    public void complete(Long activityId, String orderId) {
        luaExecutor.execute(LuaScriptExecutor.SCRIPT_SECKILL_RESERVATION_COMPLETE,
                reservationKeys(activityId, orderId), orderId, RedisKey.CONSUMED_TOKEN_TTL_SECONDS);
    }

    public boolean confirmClaimOrRelease(Long activityId, String orderId) {
        return resolve(activityId, orderId, 0)[0] == 2;
    }

    public Set<String> dueOrderIds(Long activityId, int limit) {
        Set<String> due = redis.opsForZSet().rangeByScore(
                RedisKey.seckillReservationsDue(activityId), 0, Instant.now().toEpochMilli(), 0, limit);
        return due == null ? Set.of() : due;
    }

    public Optional<SeckillOrderMessage> recover(Long activityId, String orderId) {
        long[] result = resolve(activityId, orderId, PROCESSING_RETRY_MILLIS);
        if (result[0] == 0) {
            log.info("超时秒杀预占已释放 activityId={} orderId={}", activityId, orderId);
        }
        if (result[0] != 3) {
            return Optional.empty();
        }
        String payload = redis.<String, String>opsForHash()
                .get(RedisKey.seckillReservation(activityId, orderId), "message");
        if (payload == null) {
            return Optional.empty();
        }
        try {
            SeckillOrderMessage message = objectMapper.readValue(payload, SeckillOrderMessage.class);
            message.setStockBucket((int) result[1]);
            return Optional.of(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法反序列化秒杀预占消息 orderId=" + orderId, e);
        }
    }

    private long[] resolve(Long activityId, String orderId, long retryDelayMillis) {
        List<String> keys = stockKeys(activityId);
        keys.add(RedisKey.seckillUsers(activityId));
        keys.add(RedisKey.seckillReservationOwners(activityId));
        keys.addAll(reservationKeys(activityId, orderId));
        return luaExecutor.parse(luaExecutor.execute(LuaScriptExecutor.SCRIPT_SECKILL_RESERVATION_RESOLVE,
                keys, orderId, RedisKey.SECKILL_STOCK_BUCKETS, retryDelayMillis));
    }

    private List<String> stockKeys(Long activityId) {
        List<String> keys = new ArrayList<>(RedisKey.SECKILL_STOCK_BUCKETS + 5);
        for (int bucket = 0; bucket < RedisKey.SECKILL_STOCK_BUCKETS; bucket++) {
            keys.add(RedisKey.seckillStockBucket(activityId, bucket));
        }
        return keys;
    }

    private List<String> reservationKeys(Long activityId, String orderId) {
        return List.of(RedisKey.seckillReservation(activityId, orderId), RedisKey.seckillReservationsDue(activityId));
    }
}
