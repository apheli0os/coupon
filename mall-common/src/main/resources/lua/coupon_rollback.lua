-- 领取后 MQ 发送失败时的幂等补偿：
-- 若传入 KEYS[3]=tokenKey 且 ARGV[2]=userCouponId，仅当 tokenKey 仍等于 userCouponId 时才回滚，
-- 防止消费端已认领落库后生产端超时误回滚导致超发；
-- 且仅当用户存在已领计数（claimed > 0）时才扣减计数并 INCR 库存，防止重复补偿虚增库存。
-- KEYS[1] 库存  KEYS[2] 用户已领 Hash  [KEYS[3] tokenKey]
-- ARGV[1] userId  [ARGV[2] expectedCouponId]
-- 返回 {code, 当前库存}  code: 0=归还成功 1=已归还过(幂等跳过) 2=已被消费端认领(禁止回滚)
if KEYS[3] and ARGV[2] then
  local current = redis.call('GET', KEYS[3])
  if current ~= ARGV[2] then
    local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
    return {2, stock}
  end
  redis.call('DEL', KEYS[3])
end

local claimed = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
if claimed > 0 then
  if claimed == 1 then
    redis.call('HDEL', KEYS[2], ARGV[1])
  else
    redis.call('HINCRBY', KEYS[2], ARGV[1], -1)
  end
  local stock = redis.call('INCR', KEYS[1])
  return {0, stock}
end
local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
return {1, stock}
