local key = KEYS[1]
local quantity = tonumber(ARGV[1])

if not quantity or quantity <= 0 then
    return -3
end

--判断库存key是否存在
if redis.call('EXISTS',key) == 0 then
    return -5;
end

-- 增加库存,返回值是增加成功后的值
return redis.call('INCRBY',key,quantity)