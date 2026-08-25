package com.javaup.resource.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

@Getter
@AllArgsConstructor
public enum InstantAdmissionResultEnum {

    ADMITTED_NEW(0L),// 准入成功（新）
    ADMITTED_DUPLICATE(1L),// 准入成功（重复）
    DUPLICATE_RELEASED(2L),// 重复请求但已释放
    SOLD_OUT(-1L),// Redis 库存不足
    CACHE_MISSING(-2L),// 缓存缺失
    INVALID_QUANTITY(-3L),// 数量无效
    INVALID_STOCK_VALUE(-4L),// 无效的库存数量
    IDEMPOTENT_CONFLICT(-10L);// requestId 已存在且参数不一致

    private final Long code;

    public static InstantAdmissionResultEnum of(Long code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未知Instant准入结果：" + code));
    }
}
