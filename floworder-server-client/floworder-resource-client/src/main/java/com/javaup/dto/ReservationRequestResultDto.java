package com.javaup.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReservationRequestResultDto {

    private String requestId;

    private Integer status;

    private String orderNo;

    private Integer orderStatus;

    private String latestOrderEventType;

    private LocalDateTime latestOrderEventTime;

    private Integer orderEventVersion;

    private Integer retryCount;

    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
