package com.javaup.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrderCreateMessage {

    private String messageId;

    private String eventType;

    private LocalDateTime occurredAt;

    private CreateOrderDto data;
}