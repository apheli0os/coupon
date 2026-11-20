-- 优惠券领取：原子校验活动状态/限领/库存并预扣库存
-- KEYS[1] 库存  KEYS[2] 用户已领 Hash  KEYS[3] 活动状态
-- ARGV[1] userId  ARGV[2] 每人限领数
-- 返回 {code, 剩余库存}  code: 0成功 -1未开始/结束 -2超限 -3抢光
local status = redis.call('GET', KEYS[3])
if not status or status ~= '1' then
  return {-1, 0}
end

local claimed = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
if claimed >= tonumber(ARGV[2]) then
  return {-2, 0}
end

local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
if stock <= 0 then
  return {-3, 0}
end

redis.call('DECR', KEYS[1])
redis.call('HINCRBY', KEYS[2], ARGV[1], 1)
if redis.call('TTL', KEYS[2]) == -1 then
  local ttl = redis.call('TTL', KEYS[3])
  if ttl > 0 then
    redis.call('EXPIRE', KEYS[2], ttl)
  end
end
return {0, stock - 1}
