package com.javaup.enums;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OrderOperatorTypeEnum {

    USER("USER", "用户操作"),
    SYSTEM("SYSTEM", "系统操作"),
    MQ("MQ", "消息驱动");

    private final String code;
    private final String description;
}