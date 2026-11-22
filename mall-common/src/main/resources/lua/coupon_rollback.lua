if KEYS[3] and ARGV[2] then
  local current = redis.call('GET', KEYS[3])
  if current ~= ARGV[2] then
    local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
    return {2, stock}
  end
  redis.call('DEL', KEYS[3])
end

if KEYS[4] and ARGV[3] then
  if redis.call('SET', KEYS[4], '1', 'EX', tonumber(ARGV[3]), 'NX') == false then
    local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
    return {1, stock}
  end
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
