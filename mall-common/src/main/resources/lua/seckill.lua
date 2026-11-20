-- 秒杀核心（热点分桶版）：原子校验活动时间/重复购买，从 userId 对应桶开始轮询扣减
-- KEYS[1..N]     N 个库存桶
-- KEYS[N+1]      已购用户 Set
-- KEYS[N+2]      活动状态
-- ARGV[1]        userId
-- ARGV[2]        起始桶索引（0-based）
-- ARGV[3]        桶总数 N
-- 返回 {code, 扣减桶索引}  code: 0成功 -1未开始/结束 -2重复购买 -3抢光
local userId = ARGV[1]
local startBucket = tonumber(ARGV[2])
local bucketCount = tonumber(ARGV[3])

local status = redis.call('GET', KEYS[bucketCount + 2])
if not status or status ~= '1' then
  return {-1, -1}
end

if redis.call('SISMEMBER', KEYS[bucketCount + 1], userId) == 1 then
  return {-2, -1}
end

-- 从起始桶轮询一圈，命中第一个有库存的桶，避免某桶售罄导致少卖
for i = 0, bucketCount - 1 do
  local idx = (startBucket + i) % bucketCount
  local stockKey = KEYS[idx + 1]
  local stock = tonumber(redis.call('GET', stockKey) or '0')
  if stock > 0 then
    redis.call('DECR', stockKey)
    redis.call('SADD', KEYS[bucketCount + 1], userId)
    if redis.call('TTL', KEYS[bucketCount + 1]) == -1 then
      local ttl = redis.call('TTL', KEYS[bucketCount + 2])
      if ttl > 0 then
        redis.call('EXPIRE', KEYS[bucketCount + 1], ttl)
      end
    end
    return {0, idx}
  end
end

return {-3, -1}
