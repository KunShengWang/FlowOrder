package com.javaup.resource.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RecoveryExecuteResult {

    private String actionRequestId;

    private String actionType;

    private String targetType;

    private String targetKey;

    /**
     * EXECUTING / SUCCEEDED / FAILED / IDEMPOTENT_SUCCEEDED。
     */
    private String status;

    private String message;

    private LocalDateTime executedAt;
}
