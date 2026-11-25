local orderId = ARGV[1]
local bucketCount = tonumber(ARGV[2])
local retryDelay = tonumber(ARGV[3])
local usersKey = KEYS[bucketCount + 1]
local ownersKey = KEYS[bucketCount + 2]
local reservationKey = KEYS[bucketCount + 3]
local dueKey = KEYS[bucketCount + 4]

local state = redis.call('HGET', reservationKey, 'state')
if not state then
  redis.call('ZREM', dueKey, orderId)
  return {1, 0}
end
if state == 'COMMITTED' then
  redis.call('ZREM', dueKey, orderId)
  return {2, 0}
end
if retryDelay == 0 and state == 'PROCESSING' then
  return {2, 0}
end
if retryDelay > 0 then
  local time = redis.call('TIME')
  local now = time[1] * 1000 + math.floor(time[2] / 1000)
  local deadline = tonumber(redis.call('ZSCORE', dueKey, orderId))
  if not deadline or deadline > now then
    return {1, 0}
  end
  if state == 'PROCESSING' then
    redis.call('ZADD', dueKey, now + retryDelay, orderId)
    return {3, tonumber(redis.call('HGET', reservationKey, 'bucket'))}
  end
end
if state ~= 'PENDING' then
  return {1, 0}
end

local userId = redis.call('HGET', reservationKey, 'user')
local bucket = tonumber(redis.call('HGET', reservationKey, 'bucket'))
local stockKey = KEYS[bucket + 1]
if redis.call('HGET', ownersKey, userId) == orderId then
  if redis.call('EXISTS', stockKey) == 1 then
    redis.call('INCR', stockKey)
  end
  redis.call('SREM', usersKey, userId)
  redis.call('HDEL', ownersKey, userId)
end
redis.call('ZREM', dueKey, orderId)
redis.call('DEL', reservationKey)
return {0, bucket}
