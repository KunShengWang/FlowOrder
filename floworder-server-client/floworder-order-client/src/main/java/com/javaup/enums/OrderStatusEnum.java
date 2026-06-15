package com.javaup.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OrderStatusEnum {

    INIT(0, "初始化"),
    RESERVED(10, "已预约"),
    CONFIRMED(20, "已确认"),
    CANCELLED(30, "已取消"),
    TIMEOUT(40, "已超时"),
    FAILED(50, "创建失败");

    private final Integer code;
    private final String description;
}
