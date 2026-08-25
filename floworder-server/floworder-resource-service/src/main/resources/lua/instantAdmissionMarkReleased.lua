if redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('HSET', KEYS[1], 'deducted', '0')
    redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
redis.call('ZREM', KEYS[2], ARGV[1])
return 1
