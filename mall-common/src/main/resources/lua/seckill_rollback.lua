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

local removed = redis.call('SREM', KEYS[2], ARGV[1])
if removed == 1 then
  local stock = redis.call('INCR', KEYS[1])
  return {0, stock}
end
local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
return {1, stock}
