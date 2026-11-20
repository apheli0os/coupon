-- 领取后 DB 落库失败时的 Redis 补偿：回滚库存与用户已领计数
-- KEYS[1] 库存  KEYS[2] 用户已领 Hash
-- ARGV[1] userId
local claimed = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
if claimed > 0 then
  redis.call('HINCRBY', KEYS[2], ARGV[1], -1)
end
local stock = redis.call('INCR', KEYS[1])
return {0, stock}
