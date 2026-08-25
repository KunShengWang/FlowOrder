package com.javaup.dto;

import lombok.Data;

@Data
public class InstantReservationResultDto {

    private String requestId;

    private String orderNo;

    private String resultStatus;

    private String reasonCode;

    private String message;

    private boolean queryRequired;
}
