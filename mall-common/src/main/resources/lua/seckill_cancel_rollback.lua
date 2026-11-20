-- 关单库存归还（按 orderId 严格幂等）：
-- 即使应用在归还 Redis 后、更新 DB stock_restored=1 前宕机，且用户随即重新下单，
-- 后台补偿重试也会命中 rollbackGuard Key 直接返回幂等成功，绝不会误撤新订单或重复加库存。
-- KEYS[1] 库存桶  KEYS[2] 已购用户 Set  KEYS[3] 关单幂等键  KEYS[4] tokenKey
-- ARGV[1] userId  ARGV[2] orderId  ARGV[3] guardTtlSeconds
-- 返回 {code, 当前桶库存}  code: 0=本次归还成功 1=已归还过(幂等跳过)
if redis.call('SET', KEYS[3], '1', 'EX', tonumber(ARGV[3]), 'NX') == false then
  local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
  return {1, stock}
end

local curTok = redis.call('GET', KEYS[4])
if curTok == ARGV[2] or curTok == ('PROCESSING:' .. ARGV[2]) or curTok == ('CONSUMED:' .. ARGV[2]) then
  redis.call('DEL', KEYS[4])
end

local removed = redis.call('SREM', KEYS[2], ARGV[1])
if removed == 1 then
  local stock = redis.call('INCR', KEYS[1])
  return {0, stock}
end
local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
return {1, stock}
