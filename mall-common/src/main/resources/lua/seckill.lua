local userId = ARGV[1]
local startBucket = tonumber(ARGV[2])
local bucketCount = tonumber(ARGV[3])
local orderId = ARGV[4]
local usersKey = KEYS[bucketCount + 1]
local statusKey = KEYS[bucketCount + 2]
local ownersKey = KEYS[bucketCount + 3]
local reservationKey = KEYS[bucketCount + 4]
local dueKey = KEYS[bucketCount + 5]

if redis.call('GET', statusKey) ~= '1' then
  return {-1, -1}
end
if redis.call('SISMEMBER', usersKey, userId) == 1 or redis.call('HEXISTS', ownersKey, userId) == 1 then
  return {-2, -1}
end
if redis.call('EXISTS', reservationKey) == 1 or redis.call('ZSCORE', dueKey, orderId) then
  return {-4, -1}
end

local ttl = redis.call('PTTL', statusKey)
local time = redis.call('TIME')
local deadline = time[1] * 1000 + math.floor(time[2] / 1000) + tonumber(ARGV[5])
for i = 0, bucketCount - 1 do
  local idx = (startBucket + i) % bucketCount
  local stockKey = KEYS[idx + 1]
  local stock = tonumber(redis.call('GET', stockKey) or '0')
  if stock > 0 then
    redis.call('DECR', stockKey)
    redis.call('SADD', usersKey, userId)
    redis.call('HSET', ownersKey, userId, orderId)
    redis.call('HSET', reservationKey, 'state', 'PENDING', 'user', userId,
      'bucket', idx, 'message', ARGV[6])
    redis.call('ZADD', dueKey, deadline, orderId)
    if ttl > 0 then
      if redis.call('PTTL', usersKey) == -1 then
        redis.call('PEXPIRE', usersKey, ttl)
      end
      redis.call('PEXPIRE', ownersKey, ttl)
    end
    return {0, idx}
  end
end

return {-3, -1}
