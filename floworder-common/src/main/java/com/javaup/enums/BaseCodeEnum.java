package com.javaup.enums;

import lombok.Getter;

@Getter
public enum BaseCodeEnum {

    SYSTEM_ERROR(-1,"系统异常，请稍后重试"),

    COMPOSITE_NOT_EXIST(40012,"通用验证不存在"),

    PROGRAM_ORDER_STRATEGY_NOT_EXIST(50011,"创建订单策略不存在");

    private final Integer code;

    private final String message;

    BaseCodeEnum(Integer code,String message){
        this.code = code;
        this.message = message;
    }
}
