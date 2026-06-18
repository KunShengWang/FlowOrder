package com.javaup.enums;

import lombok.Getter;

@Getter
public enum BaseCodeEnum {

    SYSTEM_ERROR(-1,"系统异常，请稍后重试"),

    BUSINESS_ERROR(40000,"业务处理失败"),

    COMPOSITE_NOT_EXIST(40012,"通用验证不存在"),

    StockItem_NOT_EXIST(40013,"库存项不存在"),

    StockItem_NOT_OPEN(40014,"库存项未启用"),

    ORDER_CREATE_PARAM_EMPTY(41001,"创建订单参数不能为空"),

    ORDER_NO_EMPTY(41002,"订单号不能为空"),

    ORDER_USER_ID_EMPTY(41003,"用户ID不能为空"),

    ORDER_RESOURCE_ID_EMPTY(41004,"资源ID不能为空"),

    ORDER_STOCK_ITEM_ID_EMPTY(41005,"库存项ID不能为空"),

    ORDER_QUANTITY_INVALID(41006,"预约数量非法"),

    ORDER_REQUEST_ID_EMPTY(41007,"requestId不能为空"),

    PROGRAM_ORDER_STRATEGY_NOT_EXIST(50011,"创建订单策略不存在"),

    ORDER_DEDUCT_NO_EMPTY(41008, "库存预扣流水号不能为空"),

    ORDER_EXPIRE_TIME_INVALID(41009, "订单过期时间必须晚于当前时间"),

    ORDER_IDEMPOTENT_CONFLICT(41010, "requestId已存在，但订单参数不一致");

    private final Integer code;

    private final String message;

    BaseCodeEnum(Integer code,String message){
        this.code = code;
        this.message = message;
    }
}