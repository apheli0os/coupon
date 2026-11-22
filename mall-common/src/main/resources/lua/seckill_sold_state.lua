local state = redis.call('GET', KEYS[1])
local event = ARGV[1]
local ttl = tonumber(ARGV[2])

if event == 'SOLD' then
  if not state then
    redis.call('SET', KEYS[1], 'SOLD', 'EX', ttl)
    return {1, 0}
  end
  return {0, 0}
elseif event == 'CANCEL' then
  if state == 'SOLD' then
    redis.call('SET', KEYS[1], 'CANCELLED', 'EX', ttl)
    return {-1, 0}
  elseif not state then
    redis.call('SET', KEYS[1], 'CANCELLED', 'EX', ttl)
    return {0, 0}
  end
  return {0, 0}
end

return {0, 0}
