package com.javaup.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum StockLuaResultCodeEnum {

    INSUFFICIENT_STOCK(-1, "库存不足",-1L),

    STOCK_CACHE_MISSING(-2, "库存缓存不存在",-2L),

    INVALID_QUANTITY(-3, "quantity 非法",-3L),

    INVALID_STOCK_VALUE(-4, "库存值不是合法数字",-4L),

    STOCK_KEY_MISSING(-5, "库存key不存在",-5L);

    private final Integer code;

    private final String message;

    private final Long value;

    public static StockLuaResultCodeEnum of(Long value){
        for (StockLuaResultCodeEnum resultCode : StockLuaResultCodeEnum.values()) {
            if(resultCode.value.equals(value)){
                return resultCode;
            }
        }
        return null;
    }
}
