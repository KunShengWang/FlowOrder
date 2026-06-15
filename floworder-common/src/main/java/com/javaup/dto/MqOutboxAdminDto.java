package com.javaup.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MqOutboxAdminDto {

    private String messageId;

    private String producerService;

    private String bizKey;

    private String messageType;

    private Integer status;

    private Integer retryCount;

    private String lastError;

    private LocalDateTime nextRetryTime;

    private LocalDateTime createdAt;
}