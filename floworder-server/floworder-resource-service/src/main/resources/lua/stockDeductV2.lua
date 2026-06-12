local quantity = tonumber(ARGV[1])
if not quantity or quantity <= 0 then
    return -3
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

return redis.call('DECRBY', KEYS[1], quantity)

--脚本职责:
--校验 quantity
--读取库存
--判断库存是否充足
--扣减库存
--返回结果




--返回值：
-->= 0：成功，值为剩余库存
---1：库存不足
---2：库存缓存不存在
---3：quantity 非法
---4：库存值不是合法数字