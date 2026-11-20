-- 消费端原子认领凭证：防止生产端超时回滚库存后延迟消息仍被消费导致超卖
-- KEYS[1] tokenKey
-- ARGV[1] expectedTokenValue（orderId 或 userCouponId）
-- ARGV[2] ttlSeconds
-- 返回 {code, 0}  code: 1=允许落库（刚认领或同消息重试） 0=拒绝（已被回滚/已过期/属于其他订单/已消费）
local current = redis.call('GET', KEYS[1])
local expected = ARGV[1]
local processing = 'PROCESSING:' .. expected
if current == expected then
  redis.call('SET', KEYS[1], processing, 'EX', tonumber(ARGV[2]))
  return {1, 0}
end
if current == processing then
  return {1, 0}
end
return {0, 0}
