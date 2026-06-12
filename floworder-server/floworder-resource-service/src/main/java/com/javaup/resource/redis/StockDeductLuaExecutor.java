package com.javaup.resource.redis;

import jakarta.annotation.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Component
public class StockDeductLuaExecutor {

    private static final DefaultRedisScript<Long> STOCK_DEDUCT_SCRIPT;

    private static final DefaultRedisScript<Long> STOCK_INCREMENT_SCRIPT;

    static {
        STOCK_DEDUCT_SCRIPT = new DefaultRedisScript<>();
        // 设置返回结果类型
        STOCK_DEDUCT_SCRIPT.setResultType(Long.class);
        // 设置lua脚本位置
        STOCK_DEDUCT_SCRIPT.setLocation(new ClassPathResource("lua/stockDeductV2.lua"));
    }

    static {
        STOCK_INCREMENT_SCRIPT = new DefaultRedisScript<>();
        // 设置返回结果类型
        STOCK_INCREMENT_SCRIPT.setResultType(Long.class);
        // 设置lua脚本位置
        STOCK_INCREMENT_SCRIPT.setLocation(new ClassPathResource("lua/stockIncrementV2.lua"));
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * lua脚本扣减库存
     */
    public Long deduct(String stockKey,Integer quantity){
        return stringRedisTemplate.execute(
                STOCK_DEDUCT_SCRIPT,
                Collections.singletonList(stockKey),
                String.valueOf(quantity)
        );
    }

    /**
     * lua脚本增加库存
     */
    public Long increment(String stockKey,Integer quantity) {
        return stringRedisTemplate.execute(
                STOCK_INCREMENT_SCRIPT,
                Collections.singletonList(stockKey),
                String.valueOf(quantity)
        );
    }
}
