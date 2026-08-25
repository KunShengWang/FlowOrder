package com.javaup.resource.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ReservationProcessingModeEnum {

    ASYNC_V8(0),

    INSTANT(1);

    private final Integer mode;
}
