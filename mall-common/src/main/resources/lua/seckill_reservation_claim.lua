local state = redis.call('HGET', KEYS[1], 'state')
if state ~= 'PENDING' and state ~= 'PROCESSING' then
  return {0, 0}
end
if redis.call('HGET', KEYS[1], 'user') ~= ARGV[2]
    or redis.call('HGET', KEYS[1], 'bucket') ~= ARGV[3] then
  return {0, 0}
end
if state == 'PROCESSING' then
  return {1, 0}
end

local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
local deadline = tonumber(redis.call('ZSCORE', KEYS[2], ARGV[1]))
if not deadline or deadline <= now then
  return {0, 0}
end
redis.call('HSET', KEYS[1], 'state', 'PROCESSING')
redis.call('ZADD', KEYS[2], now + tonumber(ARGV[4]), ARGV[1])
return {1, 0}
