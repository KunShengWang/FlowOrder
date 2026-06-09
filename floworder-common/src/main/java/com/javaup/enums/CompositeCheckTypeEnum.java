package com.javaup.enums;

import lombok.Getter;

@Getter
public enum CompositeCheckTypeEnum {

    /**
     * 订单创建
     * */
    PROGRAM_ORDER_CREATE_CHECK(1,"program_order_create_check","订单创建");

    private final Integer code;

    private final String value;

    private final String msg;

    CompositeCheckTypeEnum(Integer code,String value,String msg){
        this.code = code;
        this.value = value;
        this.msg = msg;
    }
}
