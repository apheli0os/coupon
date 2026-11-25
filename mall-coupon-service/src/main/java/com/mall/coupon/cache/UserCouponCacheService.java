package com.mall.coupon.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.constants.RedisKey;
import com.mall.coupon.domain.entity.UserCoupon;
import com.mall.coupon.domain.enums.CouponEnums;
import com.mall.coupon.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserCouponCacheService {

    private static final String EMPTY_SENTINEL = "-1";
    private static final String MISS_MARKER = "__MISS__";
    private static final String SENTINEL_SCORE = "9999999999999";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration EMPTY_TTL = Duration.ofSeconds(60);
    private static final long EPOCH_TTL_SECONDS = 300L;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> READ_ZSET_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[1]) == 0 then return {'" + MISS_MARKER + "'} end "
                    + "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1]) "
                    + "local items = redis.call('ZRANGE', KEYS[1], 0, -1) "
                    + "if #items == 0 then "
                    + "redis.call('ZADD', KEYS[1], ARGV[2], '" + EMPTY_SENTINEL + "') "
                    + "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3])) "
                    + "return {'" + EMPTY_SENTINEL + "'} "
                    + "end "
                    + "return items",
            List.class);

    private static final DefaultRedisScript<Long> EVICT_ZSET_SCRIPT = new DefaultRedisScript<>(
            "redis.call('DEL', KEYS[1]) "
                    + "redis.call('INCR', KEYS[2]) "
                    + "redis.call('EXPIRE', KEYS[2], tonumber(ARGV[1])) "
                    + "return 1",
            Long.class);

    private static final DefaultRedisScript<Long> POPULATE_ZSET_SCRIPT = new DefaultRedisScript<>(
            "local curEpoch = redis.call('GET', KEYS[2]) or '0' "
                    + "if curEpoch ~= ARGV[1] then return 0 end "
                    + "redis.call('DEL', KEYS[1]) "
                    + "for i = 3, #ARGV - 1, 2 do "
                    + "redis.call('ZADD', KEYS[1], ARGV[i + 1], ARGV[i]) "
                    + "end "
                    + "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2])) "
                    + "return 1",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final UserCouponMapper userCouponMapper;

    public List<UserCoupon> listUnusedCoupons(Long userId, LocalDateTime now) {
        if (userId == null) {
            return List.of();
        }
        String key = RedisKey.userUnusedCouponZSet(userId);
        String epochKey = RedisKey.userUnusedCouponEpoch(userId);
        long nowEpoch = now.atZone(ZoneId.systemDefault()).toEpochSecond();
        try {
            List<?> rawMembers = stringRedisTemplate.execute(
                    READ_ZSET_SCRIPT,
                    List.of(key),
                    String.valueOf(nowEpoch),
                    SENTINEL_SCORE,
                    String.valueOf(EMPTY_TTL.toSeconds()));
            if (rawMembers != null && !rawMembers.isEmpty() && !MISS_MARKER.equals(String.valueOf(rawMembers.get(0)))) {
                List<Long> ids = parseCouponIds(rawMembers);
                if (ids.isEmpty()) {
                    return List.of();
                }
                List<UserCoupon> hits = userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                        .eq(UserCoupon::getUserId, userId)
                        .in(UserCoupon::getId, ids)
                        .eq(UserCoupon::getStatus, CouponEnums.COUPON_UNUSED)
                        .gt(UserCoupon::getExpireTime, now)
                        .orderByAsc(UserCoupon::getExpireTime));
                return hits == null ? List.of() : hits;
            }
            String observedEpoch = currentEpoch(epochKey);
            List<UserCoupon> fresh = queryUnusedFromDb(userId, now);
            writeCacheQuietly(key, epochKey, observedEpoch, fresh);
            return fresh;
        } catch (Exception e) {
            log.warn("读取用户可用券 ZSet 缓存失败，降级查询 DB userId={}", userId, e);
            return queryUnusedFromDb(userId, now);
        }
    }

    public void removeUnusedCoupon(Long userId, Long userCouponId) {
        if (userId == null || userCouponId == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForZSet().remove(
                    RedisKey.userUnusedCouponZSet(userId), String.valueOf(userCouponId));
        } catch (Exception e) {
            log.warn("定向移除用户可用券缓存失败 userId={} couponId={}", userId, userCouponId, e);
        }
    }

    public void evictUnusedCoupons(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            stringRedisTemplate.execute(
                    EVICT_ZSET_SCRIPT,
                    List.of(RedisKey.userUnusedCouponZSet(userId), RedisKey.userUnusedCouponEpoch(userId)),
                    String.valueOf(EPOCH_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("清理用户可用券缓存失败 userId={}", userId, e);
        }
    }

    private String currentEpoch(String epochKey) {
        String raw = stringRedisTemplate.opsForValue().get(epochKey);
        return raw == null || raw.isBlank() ? "0" : raw;
    }

    private List<UserCoupon> queryUnusedFromDb(Long userId, LocalDateTime now) {
        List<UserCoupon> list = userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getStatus, CouponEnums.COUPON_UNUSED)
                .gt(UserCoupon::getExpireTime, now)
                .orderByAsc(UserCoupon::getExpireTime));
        return list == null ? List.of() : list;
    }

    private void writeCacheQuietly(String key, String epochKey, String expectedEpoch, List<UserCoupon> coupons) {
        try {
            List<String> args = new ArrayList<>();
            args.add(expectedEpoch);
            if (coupons.isEmpty()) {
                args.add(String.valueOf(EMPTY_TTL.toSeconds()));
                args.add(EMPTY_SENTINEL);
                args.add(SENTINEL_SCORE);
            } else {
                args.add(String.valueOf(CACHE_TTL.toSeconds()));
                ZoneId zone = ZoneId.systemDefault();
                for (UserCoupon coupon : coupons) {
                    args.add(String.valueOf(coupon.getId()));
                    long score = coupon.getExpireTime() == null
                            ? Long.MAX_VALUE
                            : coupon.getExpireTime().atZone(zone).toEpochSecond();
                    args.add(String.valueOf(score));
                }
            }
            stringRedisTemplate.execute(POPULATE_ZSET_SCRIPT, List.of(key, epochKey), args.toArray());
        } catch (Exception e) {
            log.warn("回填用户可用券 ZSet 缓存失败 key={}", key, e);
        }
    }

    private List<Long> parseCouponIds(List<?> members) {
        List<Long> ids = new ArrayList<>(members.size());
        for (Object rawMember : members) {
            String member = String.valueOf(rawMember);
            if (EMPTY_SENTINEL.equals(member)) {
                continue;
            }
            try {
                ids.add(Long.valueOf(member));
            } catch (NumberFormatException ignored) {
                log.warn("忽略异常 ZSet 成员 member={}", member);
            }
        }
        return ids;
    }
}
