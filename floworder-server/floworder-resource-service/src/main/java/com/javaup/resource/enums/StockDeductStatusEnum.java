package com.javaup.resource.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum StockDeductStatusEnum {

    PRE_DEDUCTED(10, "已预扣"),
    ORDER_CREATED(20, "订单已创建"),
    RELEASED(30, "库存已释放"),
    FAILED(40, "处理失败"),
    MANUAL_REVIEW(50, "人工确认"),
    SOLD(60, "库存已确认成交");

    private final Integer code;
    private final String description;
}