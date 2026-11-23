local stockKey = KEYS[1]
local delta = tonumber(ARGV[1])
if redis.call('EXISTS', stockKey) == 1 then
    local nextStock = redis.call('INCRBY', stockKey, delta)
    return {1, nextStock}
end
return {0, 0}
