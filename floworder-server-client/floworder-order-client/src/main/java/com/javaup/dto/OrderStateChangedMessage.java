package com.javaup.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrderStateChangedMessage {

    private String messageId;
    private String traceId;
    private String eventType;

    private String requestId;
    private String orderNo;
    private String deductNo;
    private Long stockItemId;
    private Integer quantity;

    private Integer fromStatus;
    private Integer toStatus;
    private LocalDateTime occurredAt;
}