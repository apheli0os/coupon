if redis.call('HGET', KEYS[1], 'state') ~= 'PROCESSING' then
  return {0, 0}
end
redis.call('HSET', KEYS[1], 'state', 'COMMITTED')
redis.call('HDEL', KEYS[1], 'message', 'user', 'bucket')
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
redis.call('ZREM', KEYS[2], ARGV[1])
return {1, 0}
