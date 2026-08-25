package com.javaup.resource.enums;

import lombok.Getter;

@Getter
public enum ReservationRequestStatusEnum {

    PENDING(0),
    PROCESSING(10),
    ACCEPTED(20),
    RETRY(30),
    FAILED(40),
    MANUAL_REVIEW(50);

    private final Integer status;

    ReservationRequestStatusEnum(Integer status){
        this.status = status;
    }
}
