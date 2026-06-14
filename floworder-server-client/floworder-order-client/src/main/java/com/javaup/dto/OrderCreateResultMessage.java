package com.javaup.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrderCreateResultMessage {

    private String messageId;
    private String eventType;
    private LocalDateTime occurredAt;

    private String requestId;
    private String deductNo;
    private String orderNo;

    private Boolean success;
    private String errorMessage;
}
