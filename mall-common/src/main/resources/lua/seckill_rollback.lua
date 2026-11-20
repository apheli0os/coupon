-- 预扣库存后 MQ 发送失败时的补偿：归还指定桶库存并移除购买标记
-- KEYS[1] 被扣减的库存桶  KEYS[2] 已购用户 Set
-- ARGV[1] userId
redis.call('SREM', KEYS[2], ARGV[1])
local stock = redis.call('INCR', KEYS[1])
return {0, stock}
