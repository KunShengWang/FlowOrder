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
     * EXECUTING / SUBMITTED / FAILED / IDEMPOTENT_SUBMITTED。
     * SUBMITTED 只代表恢复命令已提交，不代表业务已经收敛。
     */
    private String status;

    private String message;

    private LocalDateTime executedAt;
}
