local quantity = tonumber(ARGV[4])
if not quantity or quantity <= 0 then
    return -3
end

local storedDigest = redis.call('HGET', KEYS[2], 'digest')
if storedDigest then
    if storedDigest ~= ARGV[2] then
        return -10
    end
    local deducted = redis.call('HGET', KEYS[2], 'deducted')
    if deducted == '1' then
        return 1
    end
    return 2
end

local stockValue = redis.call('GET', KEYS[1])
if not stockValue then
    return -2
end

local stock = tonumber(stockValue)
if not stock then
    return -4
end
if stock < quantity then
    return -1
end

redis.call('DECRBY', KEYS[1], quantity)
redis.call('HSET', KEYS[2],
        'digest', ARGV[2],
        'stockItemId', ARGV[3],
        'quantity', ARGV[4],
        'deducted', '1')
redis.call('ZADD', KEYS[3], ARGV[5], ARGV[1])
return 0
