package com.javaup.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MqDeadLetterAdminDto {

    private Long id;
    private String messageId;
    private String deadQueue;
    private String producerService;
    private String messageType;
    private String bizKey;
    private String exchangeName;
    private String routingKey;
    private String content;
    private String deathReason;
    private Integer status;
    private Integer replayCount;
    private String lastError;
    private LocalDateTime replayedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String handledBy;
    private String resolutionNote;
}
