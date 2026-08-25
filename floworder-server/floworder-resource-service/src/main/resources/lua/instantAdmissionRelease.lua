local storedDigest = redis.call('HGET', KEYS[2], 'digest')
if not storedDigest then
    redis.call('ZREM', KEYS[3], ARGV[1])
    return 0
end
if storedDigest ~= ARGV[2] then
    return -10
end

local deducted = redis.call('HGET', KEYS[2], 'deducted')
if deducted ~= '1' then
    redis.call('ZREM', KEYS[3], ARGV[1])
    return 0
end

if ARGV[4] == '1' then
    redis.call('DEL', KEYS[1])
else
    if redis.call('EXISTS', KEYS[1]) == 1 then
        redis.call('INCRBY', KEYS[1], ARGV[3])
    end
end
redis.call('HSET', KEYS[2], 'deducted', '0')
redis.call('PEXPIRE', KEYS[2], ARGV[5])
redis.call('ZREM', KEYS[3], ARGV[1])
return 1
