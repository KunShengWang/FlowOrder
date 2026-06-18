package com.javaup.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OrderEventEnum {

    CREATE("CREATE", "创建订单"),
    CONFIRM("CONFIRM", "确认订单"),
    CANCEL("CANCEL", "取消订单"),
    TIMEOUT("TIMEOUT", "订单超时"),
    FAIL("FAIL", "创建失败");

    private final String code;
    private final String description;
}