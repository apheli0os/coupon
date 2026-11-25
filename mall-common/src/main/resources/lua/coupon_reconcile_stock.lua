local cur = redis.call('GET', KEYS[1])
if ARGV[1] == '__MISSING__' then
    if cur ~= false then
        return {0, 0}
    end
else
    if cur ~= ARGV[1] then
        return {0, 0}
    end
end
redis.call('DEL', KEYS[3])
local ttl = tonumber(ARGV[3])
redis.call('SET', KEYS[1], ARGV[2], 'EX', ttl)
redis.call('DEL', KEYS[2])
if #ARGV >= 5 then
    for i = 4, #ARGV - 1, 2 do
        redis.call('HSET', KEYS[2], ARGV[i], ARGV[i + 1])
    end
    redis.call('EXPIRE', KEYS[2], ttl)
end
return {1, tonumber(ARGV[2])}
