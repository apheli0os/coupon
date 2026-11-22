if redis.call('EXISTS', KEYS[3]) == 1 then
  local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
  return {2, stock}
end

if redis.call('SET', KEYS[4], '1', 'EX', tonumber(ARGV[2]), 'NX') == false then
  local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
  return {1, stock}
end

local removed = redis.call('SREM', KEYS[2], ARGV[1])
if removed == 1 then
  local stock = redis.call('INCR', KEYS[1])
  return {0, stock}
end
local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
return {1, stock}
