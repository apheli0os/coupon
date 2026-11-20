-- 预扣库存后 MQ 发送失败时的幂等补偿：
-- 若传入 KEYS[3]=tokenKey 且 ARGV[2]=orderId，仅当 tokenKey 仍等于 orderId 时才删除凭证并归还库存，
-- 防止消费端已认领落单后生产端超时误回滚库存导致超卖；
-- 且仅在 SREM 返回 1 时才 INCR 库存，防止重复补偿导致库存虚增。
-- KEYS[1] 被扣减的库存桶  KEYS[2] 已购用户 Set  [KEYS[3] tokenKey]
-- ARGV[1] userId  [ARGV[2] expectedOrderId]
-- 返回 {code, 当前桶库存}  code: 0=归还成功 1=已归还过(幂等跳过) 2=已被消费端认领(禁止回滚)
if KEYS[3] and ARGV[2] then
  local current = redis.call('GET', KEYS[3])
  if current ~= ARGV[2] then
    local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
    return {2, stock}
  end
  redis.call('DEL', KEYS[3])
end

local removed = redis.call('SREM', KEYS[2], ARGV[1])
if removed == 1 then
  local stock = redis.call('INCR', KEYS[1])
  return {0, stock}
end
local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
return {1, stock}
